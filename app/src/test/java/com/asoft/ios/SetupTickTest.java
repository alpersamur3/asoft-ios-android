package com.asoft.ios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SetupTickTest {
    private File dir;

    @Before
    public void dir() throws IOException {
        dir = Files.createTempDirectory("setuptick").toFile();
    }

    @After
    public void clean() {
        File[] f = dir.listFiles();
        if (f != null) {
            for (File x : f) {
                x.delete();
            }
        }
        dir.delete();
    }

    private static String amfi(int pid) {
        return amfi(pid, "/bin/bash");
    }

    private static String amfi(int pid, String exe) {
        return "AMFI: Launch Constraint Violation (not enforcing), error info: c[6]p[2]m[3]e[255], (Process was launched as a system"
                + " service unexpectedly) launching proc[vc: 1 pid: " + pid + "]: " + exe + ", launch type 1, failure proc [vc: 1 pid: 1]\n";
    }

    private static final String ENGINE = "native-el0: stub 18 did not apply a TLB invalidation in 5 s\n"
            + "native-el0: vCPU 2 waits 10 s on stub 18 (pid 4242 tid 4243): cmd=1 status=3 kick=0 last sig=7 code=1 addr=0x0"
            + " pc=0x1 fill va=0x2 len=0x3 enters=4\n"
            + "native-el0: stub 18 died while running vCPU 2; slots: [v0 phase=1 status=3 cmd=1 sig=7 pc=0x1 sp=0x2 fill=0x3+0x4]\n";

    private void append(File f, String s) throws IOException {
        Files.write(f.toPath(), s.getBytes(StandardCharsets.ISO_8859_1), java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    @Test
    public void theConsoleScanCountsWhatK18Asks() {
        String t = amfi(65342) + "\"AppleSEPKeyStore\":pid:61,sel:142:2979: SEP is not booted\n"
                + "IOMFB: default_fb_surface: No Default FB Surface found. Return error\n" + amfi(65471)
                + "Trying to restart GPU (Undefined)...\n";
        SetupTick.Console c = SetupTick.scanConsole(t, 1200);
        assertEquals(65471, c.maxPid);
        assertEquals(2, c.bash);
        assertEquals(1, c.displayStarts);
        assertEquals(1, c.gpuRestarts);
        assertEquals(t.indexOf("pid: 65342"), c.jumpAt);
    }

    @Test
    public void theScanNamesWhatIsLaunchedOverAndOver() {
        String t = amfi(65342) + amfi(65343, "/usr/libexec/mobileassetd") + amfi(65344)
                + amfi(65345, "/usr/libexec/mobileassetd") + amfi(65346, "/usr/libexec/mobileassetd");
        SetupTick.Console c = SetupTick.scanConsole(t, 0);
        assertEquals(5, c.launches);
        assertEquals(2, c.bash);
        assertEquals("/usr/libexec/mobileassetd", c.topLaunch);
        assertEquals(3, c.topLaunchCount);
    }

    @Test
    public void theTickReportsLaunchesAndTheEnginesOwnStalls() throws IOException {
        File serial = new File(dir, "serial.txt"), err = new File(dir, "qemu-stderr.txt");
        append(serial, amfi(46, "/usr/libexec/mobileassetd") + amfi(47, "/usr/libexec/mobileassetd"));
        append(err, ENGINE);
        SetupTick t = new SetupTick(serial, err, new File(dir, "logs"), "wallpaper", 0);
        String line = t.tick(5, "5 min", 0, 0, 300);
        assertTrue(line, line.contains("launch procs 2 (top /usr/libexec/mobileassetd x2)"));
        assertTrue(line, line.contains("engine tlb-timeouts 1, stub-waits 1, stub-deaths 1"));
        assertEquals("engine tlb-timeouts 1, stub-waits 1, stub-deaths 1", SetupTick.engineText(err));
    }

    @Test
    public void theThermalStatusHasWords() {
        assertEquals("none", SetupTick.thermalName(0));
        assertEquals("moderate", SetupTick.thermalName(2));
        assertEquals("severe", SetupTick.thermalName(3));
        assertEquals("?", SetupTick.thermalName(9));
    }

    @Test
    public void aHealthyBootHasNoJump() {
        SetupTick.Console c = SetupTick.scanConsole(amfi(46) + "\"AppleSEPKeyStore\":pid:0,:2979: x\n" + amfi(1246), 0);
        assertEquals(1246, c.maxPid);
        assertEquals(-1, c.jumpAt);
    }

    @Test
    public void aStepOfExactlyTheJumpIsNoJump() {
        assertEquals(-1, SetupTick.scanConsole(amfi(3200), 1200).jumpAt);
        assertTrue(SetupTick.scanConsole(amfi(3201), 1200).jumpAt >= 0);
    }

    @Test
    public void stderrCountsAndTheSamplersClock() {
        String e = "reims[warn] x: task 2: exec faulted: resource 48 not found\n"
                + "reims[warn] reims_vgpu_core::device::waits: waiter 4 waited 2s for stamp 1 to reach 0x1fd1000\n"
                + "el-sample t=11130s waits: qemu\nel-sample t=11633s cpu0\n";
        assertEquals(1, SetupTick.count(java.util.regex.Pattern.compile("exec faulted"), e));
        assertEquals(11633, SetupTick.samplerT(e, -1));
        assertEquals(77, SetupTick.samplerT("nothing", 77));
    }

    @Test
    public void aTickLineReportsTheNewTextOnly() throws IOException {
        File serial = new File(dir, "serial.txt"), err = new File(dir, "qemu-stderr.txt");
        append(serial, amfi(46));
        append(err, "el-sample t=30s a\n");
        SetupTick t = new SetupTick(serial, err, new File(dir, "logs"), "wallpaper", 5);
        String first = t.tick(0, "5 min", 9, 0, 31);
        assertTrue(first, first.contains("console pid max 46,"));
        assertTrue(first, first.contains("bash launches 1,"));
        assertTrue(first, first.contains("frames +4,"));
        assertTrue(first, first.contains("sampler t=30 s of 31 s"));
        append(serial, amfi(47) + amfi(48));
        String second = t.tick(5, "5 min", 9, 12, 331);
        assertTrue(second, second.contains("bash launches 2,"));
        assertTrue(second, second.contains("frames +0,"));
        assertTrue(second, second.contains("phone asleep 12 s"));
        assertTrue(second, second.contains("sampler t=30 s of 331 s"));     // no new sample: the last one stays
        assertFalse(new File(dir, "logs").exists());
    }

    @Test
    public void theFirstPidJumpKeeps64KbBeforeAndAfter() throws IOException {
        File serial = new File(dir, "serial.txt"), err = new File(dir, "qemu-stderr.txt");
        File logs = new File(dir, "logs");
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            pad.append("hfs: set hardlink bit on vol=iOSRoot cnid=450708 inoid=").append(i).append('\n');
        }
        append(serial, amfi(46));
        SetupTick t = new SetupTick(serial, err, logs, "wallpaper", 0);
        t.tick(0, "5 min", 0, 0, 0);
        append(serial, pad.toString());                         // ~190 KB before the jump
        append(serial, amfi(65342) + pad);                       // and the same after it
        t.tick(5, "5 min", 0, 0, 300);
        File before = new File(logs, "wallpaper-serial-pidjump-before.txt");
        File after = new File(logs, "wallpaper-serial-pidjump-after.txt");
        assertEquals(SetupTick.SLICE, before.length());
        assertEquals(SetupTick.SLICE, after.length());
        assertTrue(new String(Files.readAllBytes(after.toPath()), StandardCharsets.ISO_8859_1).startsWith("pid: 65342"));
        // a second jump keeps nothing new
        before.delete();
        append(serial, amfi(130000));
        t.tick(10, "5 min", 0, 0, 600);
        assertFalse(before.exists());
    }

    @Test
    public void aJumpWithLittleAfterItIsCompletedAtTheNextTick() throws IOException {
        File serial = new File(dir, "serial.txt"), err = new File(dir, "qemu-stderr.txt");
        File logs = new File(dir, "logs");
        SetupTick t = new SetupTick(serial, err, logs, "wallpaper", 0);
        append(serial, "early line\n" + amfi(70000) + "after one\n");
        t.tick(5, "5 min", 0, 0, 300);
        File after = new File(logs, "wallpaper-serial-pidjump-after.txt");
        assertFalse(after.exists());
        append(serial, "after two\n");
        t.tick(10, "5 min", 0, 0, 600);
        String s = new String(Files.readAllBytes(after.toPath()), StandardCharsets.ISO_8859_1);
        assertTrue(s.startsWith("pid: 70000"));
        assertTrue(s.endsWith("after one\nafter two\n"));
        assertTrue(new String(Files.readAllBytes(new File(logs, "wallpaper-serial-pidjump-before.txt").toPath()),
                StandardCharsets.ISO_8859_1).startsWith("early line\n"));
    }

    @Test
    public void theFirstNoAnswerKeepsTheConsoleBeforeItAndWhatFollowsAtTheNextTick() throws IOException {
        File serial = new File(dir, "serial.txt"), err = new File(dir, "qemu-stderr.txt");
        File logs = new File(dir, "logs");
        SetupTick t = new SetupTick(serial, err, logs, "wallpaper", 0);
        append(serial, "before one\n");
        t.silent();
        t.silent();                                             // only the first counts
        append(serial, "after one\n");
        t.tick(5, "5 min", 0, 0, 300);
        assertEquals("before one\n", new String(Files.readAllBytes(new File(logs, "wallpaper-serial-noanswer-before.txt").toPath()),
                StandardCharsets.ISO_8859_1));
        assertEquals("after one\n", new String(Files.readAllBytes(new File(logs, "wallpaper-serial-noanswer-after.txt").toPath()),
                StandardCharsets.ISO_8859_1));
    }

    @Test
    public void aMissingFileIsNoFailure() {
        SetupTick t = new SetupTick(new File(dir, "none"), new File(dir, "none2"), new File(dir, "logs"), "wallpaper", 0);
        assertTrue(t.tick(0, "5 min", 0, 0, 0).contains("console pid max 0,"));
    }
}
