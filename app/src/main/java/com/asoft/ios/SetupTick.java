package com.asoft.ios;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The record of a setup boot's DataMigration that needs no answer from the guest (K18: a second boot whose
 * shell goes silent at minute 50, see notes/handover-2026-11-13/k18-stall.md, section 6): one line every 5 min and at
 * the first no-answer, from what the console and QEMU's stderr say, and 64 KB of the console before and after
 * the first jump of the pids it prints or the first no-answer, kept in the setup's log folder.
 *
 * Also on every line (k18-log): what the shell poll saw of its own command (Poll), how late the loop's sleeps ran
 * (LoopLate) and the phone's state (screen, doze, power saving, charger, heat), so a silent shell can be told
 * from a frozen or dozing app. Not recorded, for want of a cheap source: the guest's real pid counter (the console
 * prints a pid only in some lines).
 */
final class SetupTick {
    /** A pid this much higher than the last tick's is a jump (a healthy boot ends near 1,300; a churning one passes 65,000). */
    static final int JUMP = 2000;
    static final int SLICE = 64 << 10;
    /** At most this much of a file is read per tick (the newest part, when more came). */
    static final int READ_CAP = 4 << 20;

    private static final Pattern PID = Pattern.compile("pid:\\s?(\\d+)");
    private static final Pattern BASH = Pattern.compile("launching proc\\[[^\\]]*\\]: /bin/bash");
    /** Every program launch AMFI prints (the executable's path), not only /bin/bash. */
    private static final Pattern LAUNCH = Pattern.compile("launching proc\\[[^\\]]*\\]:\\s*([^,\\s]+)");
    private static final Pattern STAMP = Pattern.compile("waited \\d+s for stamp");
    private static final Pattern SAMPLER = Pattern.compile("el-sample t=(\\d+)s");
    /** The engine's own stall lines in QEMU's stderr (native-el0.c): a stub that did not apply a TLB invalidation, a vCPU that waited on a stub, a stub that died. */
    private static final Pattern TLB_TIMEOUT = Pattern.compile("native-el0: stub \\d+ did not apply a TLB invalidation in 5 s");
    private static final Pattern STUB_WAIT = Pattern.compile("native-el0: vCPU \\d+ waits 10 s on stub");
    private static final Pattern STUB_DEATH = Pattern.compile("native-el0: stub \\d+ died");

    /** How far a shell command got, as the console's text shows it (GuestConsole.run). */
    enum Poll {
        /** No connection to the console, or nothing sent. */
        NOT_SENT,
        /** The command line was sent; its start marker never came back (the shell did not take it). */
        SENT,
        /** The start marker came back and the end marker did not (the command began and has not finished). */
        STARTED,
        /** Both markers came back. */
        DONE
    }

    /** The poll's state in words, with how long the shell has not answered. */
    static String pollText(Poll p, boolean answered, long silentMin) {
        if (answered) {
            return "shell answers";
        }
        String what;
        switch (p == null ? Poll.NOT_SENT : p) {
            case SENT:
                what = "command sent, never started";
                break;
            case STARTED:
                what = "command started, no end";
                break;
            case DONE:
                what = "command finished";
                break;
            default:
                what = "no console connection";
        }
        return "shell silent " + silentMin + " min (" + what + ")";
    }

    private static String yn(int v) {
        return v < 0 ? "?" : v == 0 ? "no" : "yes";
    }

    /**
     * The phone's state in words. @lateMs: the worst lateness of the loop's sleeps since the last line; the flags are
     * 1 yes, 0 no, -1 not known; @thermal: Android's status (-1 not known).
     */
    static String hostText(long lateMs, int interactive, int idle, int powerSave, int whitelisted, int charging, int thermal) {
        return String.format(Locale.ROOT, "loop late max %d s, screen %s, idle %s, power-save %s, battery whitelist %s, charging %s, thermal %s",
                (Math.max(0, lateMs) + 500) / 1000, interactive < 0 ? "?" : interactive == 0 ? "off" : "on", yn(idle), yn(powerSave),
                yn(whitelisted), yn(charging), thermal < 0 ? "?" : thermal + " (" + thermalName(thermal) + ")");
    }

    /** PowerManager's THERMAL_STATUS_* in words: the number alone says little (2 = moderate is common on a cool phone). */
    static String thermalName(int t) {
        switch (t) {
            case 0: return "none";
            case 1: return "light";
            case 2: return "moderate";
            case 3: return "severe";
            case 4: return "critical";
            case 5: return "emergency";
            case 6: return "shutdown";
            default: return "?";
        }
    }

    /** The loop's saved stall files to send: the files of the @stalls highest numbers among @names ("wallpaper-serial-stall-3.txt"). */
    static java.util.List<String> newestStallFiles(String[] names, int stalls) {
        Pattern p = Pattern.compile("wallpaper-(?:serial|stderr)-stall-(\\d+)\\.txt");
        java.util.TreeSet<Integer> ns = new java.util.TreeSet<>();
        for (String n : names) {
            Matcher m = p.matcher(n);
            if (m.matches()) {
                try {
                    ns.add(Integer.parseInt(m.group(1)));
                } catch (NumberFormatException ignored) {
                    // not a number we wrote
                }
            }
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        int kept = 0;
        for (Integer n : ns.descendingSet()) {
            if (kept++ >= stalls) {
                break;
            }
            for (String kind : new String[] {"serial", "stderr"}) {
                String f = "wallpaper-" + kind + "-stall-" + n + ".txt";
                for (String x : names) {
                    if (x.equals(f)) {
                        out.add(f);
                    }
                }
            }
        }
        return out;
    }

    /** What one tick's new console text holds. */
    static final class Console {
        int maxPid, bash, launches, displayStarts, gpuRestarts;
        /** The executable AMFI launched most often since the last tick, and how many times; "" when none. */
        String topLaunch = "";
        int topLaunchCount;
        /** Offset in the text of the first pid more than JUMP above @prevMax, -1 when none. */
        int jumpAt = -1;
    }

    static Console scanConsole(String text, int prevMaxPid) {
        Console c = new Console();
        Matcher m = PID.matcher(text);
        while (m.find()) {
            int pid;
            try {
                pid = Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (pid > c.maxPid) {
                c.maxPid = pid;
            }
            if (c.jumpAt < 0 && pid > prevMaxPid + JUMP) {
                c.jumpAt = m.start();
            }
        }
        c.bash = count(BASH, text);
        // What iOS starts over and over names the crash loop (2026-11-17: a churning second boot's console
        // named only bash because only /bin/bash was counted)
        Map<String, Integer> byExe = new HashMap<>();
        Matcher lm = LAUNCH.matcher(text);
        while (lm.find()) {
            c.launches++;
            byExe.merge(lm.group(1), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : byExe.entrySet()) {
            if (e.getValue() > c.topLaunchCount) {
                c.topLaunch = e.getKey();
                c.topLaunchCount = e.getValue();
            }
        }
        c.displayStarts = count(Pattern.compile("No Default FB Surface found"), text);
        c.gpuRestarts = count(Pattern.compile("Trying to restart GPU"), text);
        return c;
    }

    static int count(Pattern p, String text) {
        int n = 0;
        Matcher m = p.matcher(text);
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** The last "el-sample t=Ns" of QEMU's stderr text: N, or @was when it has none. */
    static long samplerT(String stderr, long was) {
        Matcher m = SAMPLER.matcher(stderr);
        long t = was;
        while (m.find()) {
            t = Long.parseLong(m.group(1));
        }
        return t;
    }

    // -- the files ----------------------------------------------------------------------------------------

    private final File serial, stderr, logs;
    private final String name;
    private long serialAt, stderrAt, samplerT = -1;
    private int maxPid, framesAt;
    private boolean jumpKept, silentKept;
    private File afterFile;
    private long afterFrom;

    /** @name: the prefix of the slices' files ("wallpaper"); @logs: the setup's log folder. */
    SetupTick(File serial, File stderr, File logs, String name, int framesNow) {
        this.serial = serial;
        this.stderr = stderr;
        this.logs = logs;
        this.name = name;
        this.framesAt = framesNow;
    }

    /** The text of @f after @from (at most READ_CAP of it, the newest part); @from[0] becomes the file's length. */
    private static String readFrom(File f, long[] from) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long len = r.length();
            long at = from[0] > len ? 0 : from[0];
            long n = len - at;
            if (n > READ_CAP) {
                at = len - READ_CAP;
                n = READ_CAP;
            }
            byte[] b = new byte[(int) n];
            r.seek(at);
            r.readFully(b);
            from[0] = len;
            return new String(b, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * The tick's line for minute @min of the DataMigration loop: what the console and stderr gained since the last
     * tick. @frames: iOS's frames shown so far; @suspendS: seconds the phone slept since the boot began; @wallS:
     * seconds since QEMU started (the sampler's own clock minus this one grows when QEMU is frozen).
     */
    String tick(int min, String why, int frames, long suspendS, long wallS) {
        if (afterFile != null) {
            // what followed the cut since it was made (the cut's own tick set it: this is the next one)
            slice(serial, afterFrom, afterFile);
            afterFile = null;
        }
        long[] sa = {serialAt};
        long before = serialAt;
        String console = readFrom(serial, sa);
        long consoleBytes = sa[0] - before;
        long start = sa[0] - console.length();       // the text's first byte in the file
        serialAt = sa[0];
        Console c = scanConsole(console, maxPid);
        if (c.jumpAt >= 0 && !jumpKept) {
            jumpKept = true;
            keep("pidjump", start + c.jumpAt);
        }
        maxPid = Math.max(maxPid, c.maxPid);
        long[] ea = {stderrAt};
        String err = readFrom(stderr, ea);
        stderrAt = ea[0];
        int faulted = count(Pattern.compile("exec faulted"), err);
        int stamps = count(STAMP, err);
        int tlbTimeouts = count(TLB_TIMEOUT, err);
        int stubWaits = count(STUB_WAIT, err);
        int stubDeaths = count(STUB_DEATH, err);
        samplerT = samplerT(err, samplerT);
        int newFrames = frames - framesAt;
        framesAt = frames;
        String top = c.topLaunch.isEmpty() ? "" : " (top " + c.topLaunch + " x" + c.topLaunchCount + ")";
        return String.format(Locale.ROOT, "tick %d min (%s): console pid max %d, launch procs %d%s, bash launches %d, display starts %d, \"restart GPU\" %d,"
                        + " console +%d bytes, frames +%d, reims exec-faulted %d, engine tlb-timeouts %d, stub-waits %d, stub-deaths %d,"
                        + " stamp-waits %d, sampler t=%s s of %d s since QEMU started, phone asleep %d s",
                min, why, maxPid, c.launches, top, c.bash, c.displayStarts, c.gpuRestarts, consoleBytes, newFrames, faulted,
                tlbTimeouts, stubWaits, stubDeaths, stamps,
                samplerT < 0 ? "?" : String.valueOf(samplerT), wallS, suspendS);
    }

    /**
     * The engine's stall lines in the newest {@link #SLICE} bytes of @stderr, for the log line a stall leaves:
     * a stub that did not apply a TLB invalidation, a vCPU parked on a stub, a stub that died. These name a
     * failure of the native EL0 engine when the guest's own console only shows the churn.
     */
    static String engineText(File stderr) {
        // the whole tail of READ_CAP, not SLICE: one warning early in a five-hour stall would be out of 64 KB
        String s = new String(tail(stderr, READ_CAP), StandardCharsets.ISO_8859_1);
        return "engine tlb-timeouts " + count(TLB_TIMEOUT, s) + ", stub-waits " + count(STUB_WAIT, s)
                + ", stub-deaths " + count(STUB_DEATH, s);
    }

    /** The newest @max bytes of @f; empty when it cannot be read. */
    private static byte[] tail(File f, int max) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long len = r.length();
            long from = Math.max(0, len - max);
            byte[] b = new byte[(int) (len - from)];
            r.seek(from);
            r.readFully(b);
            return b;
        } catch (IOException e) {
            return new byte[0];
        }
    }

    /** The shell has just not answered: the console up to now is kept, what follows it at the next tick. */
    void silent() {
        if (silentKept) {
            return;
        }
        silentKept = true;
        long cut = serial.length();
        keep("noanswer", cut);
    }

    /** The 64 KB before @cut now; the 64 KB from @cut when they are there (a cut in the past), else at the next tick. */
    private void keep(String why, long cut) {
        logs.mkdirs();
        long from = Math.max(0, cut - SLICE);
        slice(serial, from, cut - from, new File(logs, name + "-serial-" + why + "-before.txt"));
        File after = new File(logs, name + "-serial-" + why + "-after.txt");
        if (serial.length() - cut >= SLICE) {
            slice(serial, cut, SLICE, after);
        } else {
            afterFile = after;
            afterFrom = cut;
        }
    }

    private static void slice(File src, long from, File dst) {
        slice(src, from, SLICE, dst);
    }

    /** @len bytes of @src from @from (fewer at its end) into @dst. */
    static void slice(File src, long from, long len, File dst) {
        try (RandomAccessFile r = new RandomAccessFile(src, "r")) {
            long n = Math.max(0, Math.min(len, r.length() - from));
            byte[] b = new byte[(int) n];
            r.seek(from);
            r.readFully(b);
            java.nio.file.Files.write(dst.toPath(), b);
        } catch (IOException ignored) {
            // the slice is a help for a later question: no file is no harm
        }
    }
}
