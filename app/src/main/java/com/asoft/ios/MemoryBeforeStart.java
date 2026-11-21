package com.asoft.ios;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * The memory sheet before a setup or a start on a phone short of memory (LowMemory.askBeforeStart decides): what
 * is free of the phone's RAM, what the start takes, the apps that hold the memory now, a one-tap am kill-all, and
 * Start. The phone's own adb shell does the kill (the Wireless debugging pairing); Android 13 and older also have
 * the app's killBackgroundProcesses.
 */
final class MemoryBeforeStart {
    private MemoryBeforeStart() {}

    static String gb(long b) {
        return String.format(Locale.ROOT, "%.1f GB", b / 1e9);
    }

    static String statsText(String what, long total, long avail, long need) {
        String s = "Free " + gb(avail) + " of " + gb(total) + "  ·  " + what + " takes about " + gb(need) + ".";
        return s + (avail < need
                ? "\nMemory is short: Android may close the app, and work that was not saved is lost."
                : "\nEnough for the start.");
    }

    /** Shows the sheet; @go runs when Start is pressed (Battery.askOnce and the service's start follow it). */
    static void show(Activity a, String what, long need, Runnable go) {
        long total = Helper.phoneRam(a), avail = Helper.available(a);
        AppLog.note(a, String.format(Locale.ROOT, "memory before %s: %.1f GB free of %.1f GB, takes about %.1f GB",
                what, avail / 1e9, total / 1e9, need / 1e9));
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(a, 20);
        box.setPadding(p, Ui.dp(a, 6), p, 0);
        TextView stats = Ui.text(a, box, statsText(what, total, avail, need), 15, Ui.TEXT);
        TextView summary = Ui.text(a, box, "", 12.5f, Ui.FAINT);
        summary.setPadding(0, Ui.dp(a, 8), 0, 0);
        TextView top = Ui.text(a, box, "Reading what holds the memory…", 12.5f, Ui.DIM);
        top.setPadding(0, Ui.dp(a, 12), 0, 0);
        TextView result = Ui.text(a, box, "", 13, Ui.DIM);
        result.setPadding(0, Ui.dp(a, 10), 0, 0);
        LinearLayout actions = new LinearLayout(a);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, Ui.dp(a, 14), 0, 0);
        TextView kill = Ui.capsule(a, "Kill background", true, null);
        actions.addView(kill);
        box.addView(actions);
        TextView note = Ui.text(a, box, "Kill background ends the other apps' cached processes; their notifications stay. "
                + "Android 14 and later do it through the Wireless debugging pairing; without one, Android 13 and "
                + "older still let this app end its own background processes.", 11.5f, Ui.FAINT);
        note.setPadding(0, Ui.dp(a, 10), 0, 0);
        AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle("Memory before " + what)
                .setView(box)
                .setPositiveButton("Start", (d, w) -> go.run())
                .setNegativeButton("Cancel", null)
                .create();
        kill.setOnClickListener(v -> free(a, what, need, total, stats, summary, top, result, kill));
        dlg.show();
        readInfo(a, top, summary);
    }

    private static void readInfo(Activity a, TextView top, TextView summary) {
        new Thread(() -> {
            MemoryCleaner.Info i = MemoryCleaner.info(a, 6, 30_000);
            a.runOnUiThread(() -> {
                if (!a.isDestroyed()) {
                    top.setText(i.top.isEmpty() ? "No process list came back." : i.top);
                    summary.setText(i.summary);
                }
            });
        }, "memory-top").start();
    }

    private static void free(Activity a, String what, long need, long total, TextView stats, TextView summary,
                             TextView top, TextView result, TextView kill) {
        kill.setEnabled(false);
        result.setTextColor(Ui.DIM);
        result.setText("Killing the other apps' background processes…");
        new Thread(() -> {
            try {
                long[] r = MemoryCleaner.freeNow(a, 30_000);
                long now = Helper.available(a);
                MemoryCleaner.Info i = MemoryCleaner.info(a, 6, 30_000);
                a.runOnUiThread(() -> {
                    if (a.isDestroyed()) {
                        return;
                    }
                    stats.setText(statsText(what, total, now, need));
                    result.setTextColor(r[1] > r[0] ? Ui.GREEN : Ui.ORANGE);
                    result.setText(String.format(Locale.ROOT, "Kill background: %d MB -> %d MB (freed %d MB)",
                            r[0], r[1], r[1] - r[0]));
                    top.setText(i.top.isEmpty() ? "No process list came back." : i.top);
                    summary.setText(i.summary);
                    kill.setEnabled(true);
                });
            } catch (java.io.IOException e) {
                a.runOnUiThread(() -> {
                    if (a.isDestroyed()) {
                        return;
                    }
                    result.setTextColor(Ui.RED);
                    result.setText("Could not free memory: " + e.getMessage());
                    kill.setEnabled(true);
                });
            }
        }, "memory-free").start();
    }
}
