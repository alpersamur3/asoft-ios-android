package com.asoft.ios;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The logs of the system that runs, for the Logs screen at any time, not only after a failure: the guest's
 * serial console, QEMU's stderr (the native-EL0 engine's own lines), QEMU's guest-error log, the command line,
 * the boot before this one, and the setup's kept slices. Logs reads them while they are still being written;
 * a file longer than {@link #VIEW_BYTES} is shown from its end, which is the part that names a stall.
 */
final class RunLogs {
    /** What the viewer shows of one file: its end (the whole log is never cut). */
    static final int VIEW_BYTES = 300_000;
    /** What Share as a file sends: more than the viewer, for a chat's or a paste site's limit. */
    static final int SHARE_BYTES = 2_000_000;

    static final class Item {
        final String key, title, meta;
        final File file;
        final Charset charset;
        final boolean live;

        Item(String key, String title, String meta, File file, Charset charset, boolean live) {
            this.key = key;
            this.title = title;
            this.meta = meta;
            this.file = file;
            this.charset = charset;
            this.live = live;
        }

        long length() {
            return file.length();
        }
    }

    private static final Charset LATIN1 = StandardCharsets.ISO_8859_1;

    private RunLogs() {}

    /** Every log that is there now, the running system's first; empty files are left out. */
    static List<Item> all(Context c) {
        File run = Guest.runDir(c), log = AppLog.dir(c);
        List<Item> out = new ArrayList<>();
        item(out, "console", "iOS console", "The guest's serial console: launchd, migrators, AMFI, panics",
                new File(run, "serial.txt"), LATIN1, true);
        item(out, "stderr", "QEMU stderr", "The engine: el-sample stats, native-EL0 TLB invalidations, stub waits",
                new File(run, "qemu-stderr.txt"), LATIN1, true);
        item(out, "qemu", "QEMU guest errors", "-d guest_errors,unimp: what TCG could not do",
                new File(run, "qemu.log"), LATIN1, true);
        item(out, "cmdline", "QEMU command line", "This run's QEMU arguments", new File(run, "cmdline.txt"),
                StandardCharsets.UTF_8, true);
        item(out, "engine", "Engine test log", "What the engine test measured (el0bench.log)",
                new File(c.getExternalFilesDir(null), EngineService.LOG), StandardCharsets.UTF_8, false);
        item(out, "last-console", "Previous run: console", "The end of the boot before this one",
                new File(log, "last-serial.txt"), LATIN1, false);
        item(out, "last-stderr", "Previous run: QEMU stderr", "The end of the boot before this one",
                new File(log, "last-qemu-stderr.txt"), LATIN1, false);
        // the setup's kept evidence: the console and QEMU's stderr around the first no-answer and pid jump,
        // and the lasts of a stall (PhoneSetup26.stalled, SetupTick)
        File[] kept = new File(PhoneSetup26.work(c), "phone/logs").listFiles();
        if (kept != null) {
            Arrays.sort(kept, Comparator.comparingLong(File::lastModified).reversed()
                    .thenComparing(File::getName));
            for (File f : kept) {
                if (f.isFile() && f.getName().endsWith(".txt")) {
                    item(out, "setup:" + f.getName(), "Setup: " + f.getName(), "Kept around a stall or a pid jump", f, LATIN1, false);
                }
            }
        }
        return out;
    }

    private static void item(List<Item> out, String key, String title, String meta, File f, Charset cs, boolean live) {
        if (f.isFile() && f.length() > 0) {
            out.add(new Item(key, title, meta, f, cs, live));
        }
    }

    /** The item with @key, or null: a key comes from this list, never from a path. */
    static Item find(Context c, String key) {
        for (Item i : all(c)) {
            if (i.key.equals(key)) {
                return i;
            }
        }
        return null;
    }

    /**
     * The last @maxBytes of @f in @cs, its first partial line dropped; a file that fits is all there. On a file
     * being written the reader can catch a half line at the end: the next Refresh has the rest.
     */
    static String tail(File f, int maxBytes, Charset cs) {
        try (RandomAccessFile in = new RandomAccessFile(f, "r")) {
            long len = in.length(), from = Math.max(0, len - maxBytes);
            byte[] b = new byte[(int) (len - from)];
            in.seek(from);
            in.readFully(b);
            String s = new String(b, cs);
            int nl = from > 0 ? s.indexOf('\n') : -1;
            return nl >= 0 ? s.substring(nl + 1) : s;
        } catch (IOException e) {
            return "(" + f.getName() + " could not be read: " + e.getMessage() + ")";
        }
    }

    static String size(long b) {
        return b >= 1_000_000 ? String.format(Locale.ROOT, "%.1f MB", b / 1e6)
                : String.format(Locale.ROOT, "%d KB", Math.max(1, b / 1000));
    }
}
