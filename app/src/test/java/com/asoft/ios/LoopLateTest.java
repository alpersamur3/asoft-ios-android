package com.asoft.ios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class LoopLateTest {
    @Test
    public void aSleepOnTimeOrEarlyIsNotLate() {
        LoopLate l = new LoopLate();
        assertEquals(0, l.sleep(30_000, 30_000));
        assertEquals(0, l.sleep(30_000, 12_000));
        assertEquals(0, l.takeMax());
    }

    @Test
    public void theWorstIsKeptUntilTaken() {
        LoopLate l = new LoopLate();
        assertEquals(2_000, l.sleep(30_000, 32_000));
        assertEquals(400_000, l.sleep(30_000, 430_000));
        assertEquals(500, l.sleep(30_000, 30_500));
        assertEquals(500, l.last());
        assertEquals(400_000, l.takeMax());
        assertEquals(0, l.takeMax());
        l.sleep(30_000, 31_000);
        assertEquals(1_000, l.takeMax());
    }

    @Test
    public void theHostLineReadsLikeAPerson() {
        assertEquals("loop late max 2 s, screen off, idle no, power-save no, battery whitelist yes, charging yes, thermal 2 (moderate)",
                SetupTick.hostText(1_800, 0, 0, 0, 1, 1, 2));
        assertEquals("loop late max 0 s, screen on, idle yes, power-save yes, battery whitelist no, charging no, thermal 0 (none)",
                SetupTick.hostText(-5, 1, 1, 1, 0, 0, 0));
        assertEquals("loop late max 691 s, screen ?, idle ?, power-save ?, battery whitelist ?, charging no, thermal ?",
                SetupTick.hostText(691_000, -1, -1, -1, -1, 0, -1));
    }

    @Test
    public void thePollLineSaysHowFarTheCommandGot() {
        assertEquals("shell answers", SetupTick.pollText(SetupTick.Poll.DONE, true, 0));
        assertEquals("shell silent 6 min (command started, no end)", SetupTick.pollText(SetupTick.Poll.STARTED, false, 6));
        assertEquals("shell silent 3 min (command sent, never started)", SetupTick.pollText(SetupTick.Poll.SENT, false, 3));
        assertEquals("shell silent 1 min (no console connection)", SetupTick.pollText(SetupTick.Poll.NOT_SENT, false, 1));
        assertEquals("shell silent 1 min (no console connection)", SetupTick.pollText(null, false, 1));
        assertTrue(SetupTick.Poll.STARTED.ordinal() > SetupTick.Poll.SENT.ordinal());
    }

    @Test
    public void onlyTheNewestTwoStallsAreSent() {
        String[] names = {"wallpaper-serial-stall-1.txt", "wallpaper-stderr-stall-1.txt", "wallpaper-serial-stall-2.txt",
                "wallpaper-serial-stall-10.txt", "wallpaper-stderr-stall-10.txt", "wallpaper-serial-pidjump-before.txt", "other.txt"};
        List<String> got = SetupTick.newestStallFiles(names, 2);
        assertEquals(Arrays.asList("wallpaper-serial-stall-10.txt", "wallpaper-stderr-stall-10.txt", "wallpaper-serial-stall-2.txt"), got);
        assertTrue(SetupTick.newestStallFiles(new String[0], 2).isEmpty());
    }
}
