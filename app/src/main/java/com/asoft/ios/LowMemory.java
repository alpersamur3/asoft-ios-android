package com.asoft.ios;

import android.app.Activity;

import java.util.Locale;

/**
 * Memory sums for K5 (Android ends the app for low memory): the guest's default RAM by the phone's size, and the
 * question before a guest or a setup helper starts on a phone that has too little memory free. Pure numbers apart
 * from the dialog, so they are unit-tested (LowMemoryTest).
 */
final class LowMemory {
    /** Below this a phone (MemTotal, bytes) always starts iOS 26 with 3 GiB (as in 0.1.5). */
    static final long BIG_PHONE = 10_000_000_000L;
    /**
     * The undecided K5 change: 3 GiB instead of 4 GiB on phones from BIG_PHONE up to this size (12 GB phones report
     * ~11.7 GB, 16 GB ones ~15.5). Set to BIG_PHONE = the 0.1.5 behaviour (4 GiB from 10 GB); the user has not decided,
     * 14_000_000_000L is the K5 plan's value.
     */
    static final long IOS26_3G_UP_TO = BIG_PHONE;
    /** Below this the watch starts with 2 GiB (unchanged since 0.1.0). */
    private static final long WATCH_BIG_PHONE = 10_000_000_000L;

    private LowMemory() {
    }

    /**
     * The RAM (MiB) a guest gets when the user has not chosen one. Only the phone's size decides, never what is free
     * now: a default that moved from one start to the next would make every quick save "other hardware"
     * (Guest.otherHardware). 4 GiB on a 12 GB phone was closed by Android after 16 min with 1.0-1.7 GB free
     * (A#8), so the K5 plan wants 3 GiB under 14 GB (IOS26_3G_UP_TO; not measured over a long session, so left at 10 GB).
     */
    static int defaultGuestMiB(long memTotal, int forOs) {
        if (forOs == Guest.WATCH) {
            return memTotal < WATCH_BIG_PHONE ? 2048 : 3072;
        }
        return memTotal < IOS26_3G_UP_TO ? 3072 : 4096;
    }

    /**
     * What a guest takes from the phone while it runs, in bytes. iOS 16 asks QEMU for 7 GB but touches ~2.7 GB (RSS
     * 2.68 GB, peak 3.41 GB, ANDROID-S25.md); iOS 26 and the watch touch most of their RAM over a long session
     * (RSS 2.0-3.8 GB at 4 GiB): three quarters of it, plus 512 MiB for QEMU, its translation cache and the GPU's
     * mappings. Derived from those runs, not a limit Android states.
     */
    static long guestNeedBytes(int forOs, int ramMiB) {
        long mib = forOs == Guest.IOS16 ? 3072 : ramMiB * 3L / 4 + 512;
        return mib << 20;
    }

    /** True when @avail (MemAvailable) is under @need, both in bytes; an unread @avail (zero or less) is not "short". */
    static boolean isShort(long avail, long need) {
        return avail > 0 && avail < need;
    }

    /** The question's words; @what is "iOS 26", "the iOS 26 setup". */
    static String message(String what, long avail, long need) {
        return String.format(Locale.ROOT, "Only %.1f GB of this phone's memory is free, and %s takes about %.1f GB. "
                + "Android may close the app, and whatever was not saved is lost. Close other apps first, "
                + "and keep ASoftware on the screen while it runs.", avail / 1e9, what, need / 1e9);
    }

    /**
     * On a phone short of memory, runs @go behind the memory sheet (MemoryBeforeStart): free of the phone's RAM,
     * what the start takes, the apps that hold it, a one-tap kill-all, then Start. With enough free, @go runs
     * at once: the sheet is not one more tap before every quick resume.
     */
    static void askBeforeStart(Activity a, String what, long need, Runnable go) {
        if (!isShort(Helper.available(a), need)) {
            go.run();
            return;
        }
        MemoryBeforeStart.show(a, what, need, go);
    }
}
