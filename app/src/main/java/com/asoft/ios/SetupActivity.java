package com.asoft.ios;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Set up: bring iOS 16 in from the PC (pc/Send-ToPhone.cmd makes ASoftware-iOS16.asoft and,
 * with the phone on USB, pushes it to this app's import folder), or from a file picked here.
 */
public class SetupActivity extends Activity {
    private static final int PICK = 1;
    private LinearLayout box;
    private TextView status;
    private ProgressBar bar;
    private volatile boolean busy;
    private SetupScreen fromApple;
    private Setup26Screen from26;
    private View section26;
    /** "iOS 26 is set up" with its one action, home: shown when a setup (or an import) has finished. */
    private LinearLayout doneBanner;
    private TextView doneText;
    private String importDone;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable follow = new Runnable() {
        @Override
        public void run() {
            showDone();
            ui.postDelayed(this, 700);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        DevNight.apply(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.setFitsSystemWindows(true);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 32));
        scroll.addView(box);
        setContentView(scroll);
        show();
        // the home screen's "Set up iOS 26 or watchOS": open at that part, not at iOS 16's. The parts above it
        // change size after the first layout, so it follows that part's place for 3 s, until the first touch
        if (state == null && getIntent().getBooleanExtra(EXTRA_IOS26, false)) {
            long until = android.os.SystemClock.uptimeMillis() + 3000;
            boolean[] touched = {false};
            scroll.setOnTouchListener((v, e) -> {
                touched[0] = true;
                return false;
            });
            scroll.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    if (touched[0] || android.os.SystemClock.uptimeMillis() > until) {
                        scroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        return;
                    }
                    // (the section sits inside Setup26Screen's column: its place in the page is the sum up to the page)
                    int y = 0;
                    for (View v = section26; v != null && v != box && v.getParent() instanceof View; v = (View) v.getParent()) {
                        y += v.getTop();
                    }
                    scroll.scrollTo(0, Math.max(0, y - 24));
                }
            });
        }
    }

    /** Intent extra: open at iOS 26 and watchOS from Apple. */
    static final String EXTRA_IOS26 = "ios26";

    @Override
    protected void onResume() {
        super.onResume();
        if (!busy) {
            show();                     // a file may have arrived over adb meanwhile
        }
        ui.post(follow);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(follow);
        super.onPause();
    }

    /**
     * The home screen, from the arrow, the Back gesture and "Go to home". A running setup goes on in its service. When this
     * page is the first of its task (opened from the setup's notification after the app's task was gone), Back would leave
     * the app: the home screen is started first.
     */
    private void goHome() {
        if (isTaskRoot()) {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        }
        finish();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        goHome();
    }

    /**
     * Left by the Home button or the recents (not by a picker or Logs): with nothing running here, the page closes, so the
     * launcher icon lands on the home screen, which shows a running setup on its device. An import from a file runs in this page.
     */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (!busy) {
            finish();
        }
    }

    /** What the finished setup says, and its way home. Hidden while one runs or when none finished. */
    private void showDone() {
        SetupService.State s = SetupService.state;
        String text = importDone;
        if (text == null && s.finished && !s.running && s.error == null) {
            text = "a26".equals(s.target) ? setUpNames() : "iOS 16 is set up.";
        }
        doneText.setText(text == null ? "" : text + " Start it from the home screen.");
        doneBanner.setVisibility(text == null || busy || s.running ? View.GONE : View.VISIBLE);
    }

    /** "iOS 26 and watchOS are set up." for what the finished run made. */
    private String setUpNames() {
        boolean i = PhoneSetup26.wants(this, PhoneSetup26.IOS26) && PhoneSetup26.installed(this, PhoneSetup26.IOS26);
        boolean w = PhoneSetup26.wants(this, PhoneSetup26.WATCH) && PhoneSetup26.installed(this, PhoneSetup26.WATCH);
        return i && w ? "iOS 26 and watchOS are set up." : w ? "watchOS is set up." : i ? "iOS 26 is set up." : "It is set up.";
    }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(0, 0, 0, 28);
        box.addView(t);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        box.addView(b);
        return b;
    }

    /** The notice of a record not seen yet (RecordNotice), but the one a failed step or import shows under its line. */
    private RecordNotice notice;
    /** The record of the last import that failed, shown under its status line. */
    private volatile String importRecord;

    private void show() {
        box.removeAllViews();
        // the way back to the home screen, Logs on the other side (there on a fresh app too, where a failed first setup
        // has nothing else to show), and the title
        LinearLayout navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Ui.link(this, "‹  Home", Ui.LINK, v -> goHome());
        back.setTextSize(16);
        back.setPadding(0, Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        back.setContentDescription("Back to the home screen");
        navBar.addView(back, new LinearLayout.LayoutParams(0, -2, 1));
        navBar.addView(Ui.link(this, "Logs", Ui.LINK, v -> startActivity(new Intent(this, LogsActivity.class))));
        box.addView(navBar);
        TextView title = new TextView(this);
        title.setText("Set up");
        title.setTextSize(26);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, Ui.dp(this, 6), 0, 28);
        box.addView(title);
        doneBanner = new LinearLayout(this);
        doneBanner.setOrientation(LinearLayout.VERTICAL);
        doneBanner.setVisibility(View.GONE);
        doneText = new TextView(this);
        doneText.setTextSize(16);
        doneText.setTextColor(Ui.GREEN);
        doneBanner.addView(doneText);
        TextView homeBtn = Ui.capsule(this, "Go to home", false, v -> goHome());
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.topMargin = Ui.dp(this, 12);
        hlp.bottomMargin = 28;
        doneBanner.addView(homeBtn, hlp);
        box.addView(doneBanner);
        showDone();
        // an import from a file: its bar and its line (an import runs in this page; setups run in their service)
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setVisibility(View.GONE);
        box.addView(bar);
        status = text("", 15, Color.WHITE);
        status.setGravity(Gravity.START);
        if (notice == null) {
            notice = new RecordNotice(this, () -> {
                SetupService.State s = SetupService.state;
                return s.error != null && s.record != null ? s.record : importRecord;
            });
        }
        View n = notice.view();
        if (n.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) n.getParent()).removeView(n);
        }
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.bottomMargin = 28;
        box.addView(n, nlp);
        // iOS 16, then iOS 26 and watchOS (with the one run's details), each a section: state, action, Details
        if (fromApple == null) {
            fromApple = new SetupScreen(this);
        }
        box.addView(fromApple.view());
        Ui.line(this, box, 22, 22);
        if (from26 == null) {
            from26 = new Setup26Screen(this);
        }
        box.addView(from26.view());     // (view() takes it out of its old parent: call it only here)
        section26 = from26.ios26Section();
        Ui.line(this, box, 22, 22);
        pcSection();
    }

    /** "Bring it from your PC": an import of what the PC installer made, instead of the download. Closed unless a file is waiting. */
    private void pcSection() {
        SetupSection pc = new SetupSection(this, "From your PC", -1, null);
        pc.setState(SetupSection.OFF, "Instead of the download");
        SetupSection.say(pc.info, String.format("%.1f GB free on this phone; iOS 16 needs about 6.5 GB, and 4 GB more for a save.",
                getFilesDir().getUsableSpace() / 1e9), Ui.DIM);
        TextView choose = Ui.capsule(this, "Choose file…", true, v -> {
            if (!busy) {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK);
            }
        });
        pc.actions.addView(choose, new LinearLayout.LayoutParams(-2, -2));
        File[] waiting = Installer.waiting(this);
        for (File f : waiting) {
            TextView b = Ui.capsule(this, String.format(java.util.Locale.ROOT, "Import %s (%.1f GB)", f.getName(), f.length() / 1e9), false, v -> {
                try {
                    run(new FileInputStream(f), f.length(), f);
                } catch (IOException e) {
                    status.setText(e.getMessage());
                }
            });
            pc.main.addView(b, Ui.top(Ui.dp(this, 10)));
            ((LinearLayout.LayoutParams) b.getLayoutParams()).width = -2;
        }
        pc.paragraph("This takes a file made by the PC version of ASoftware iOS: the .asoft file that Send-ToPhone.cmd writes on a "
                + "Windows PC. No other file works. A phone on its own sets iOS up from Apple with the Set up buttons above.", Ui.DIM);
        pc.paragraph("1. Install ASoftware iOS (iOS 16) on a Windows PC.\n"
                + "2. On the PC, run Send-ToPhone.cmd from the ASoftware iOS folder. It packs that iOS into ASoftware-iOS16.asoft (about 6.5 GB).\n"
                + "3. With this phone on USB and USB debugging on, it sends the file here by itself. "
                + "Otherwise copy the file to the phone (for example to Download) and choose it.", Ui.DIM);
        if (Installer.installed(this)) {
            pc.paragraph("iOS 16 is set up on this phone. Importing or installing again replaces it: this iPhone's settings, apps and saves go.",
                    0xffbbbbbb);
        }
        if (waiting.length > 0) {
            pc.open();      // a file is waiting: its button is the point of this part
        }
        box.addView(pc.root);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || res != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri u = data.getData();
        long size = -1;
        try (Cursor c = getContentResolver().query(u, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                size = c.getLong(0);
            }
        }
        try {
            run(getContentResolver().openInputStream(u), size, null);
        } catch (IOException e) {
            status.setText(e.getMessage());
        }
    }

    /** Asks first when it replaces something, then imports in the background; @done is the pushed file to delete after. */
    private void run(InputStream in, long total, File done) {
        if (busy) {
            return;
        }
        if (Guest.running()) {
            status.setText("Close iOS first (side bar > Saves > Save and close).");
            return;
        }
        String n = done == null ? "" : done.getName();
        boolean other = n.contains("iOS26") || n.contains("watchOS");
        if (Installer.hasData(this) || other) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle(n.contains("iOS26") ? "Bring in iOS 26?" : n.contains("watchOS") ? "Bring in watchOS?" : "Replace iOS on this phone?")
                    .setMessage("What the file replaces goes first: that system's disk with its settings, apps and saves (a user "
                            + "disk of iOS 26 or the watch is kept as .old). Then the new one is copied in.")
                    .setPositiveButton("Replace", (d, w) -> importNow(in, total, done))
                    .setNegativeButton("Cancel", (d, w) -> {
                        try {
                            in.close();
                        } catch (IOException ignored) {
                            // nothing read
                        }
                    })
                    .show();
            return;
        }
        importNow(in, total, done);
    }

    private void importNow(InputStream in, long total, File done) {
        busy = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        bar.setVisibility(View.VISIBLE);
        status.setText("Starting… keep this screen open.");
        importRecord = null;
        importDone = null;
        long t0 = System.currentTimeMillis();
        long logAt = new java.io.File(Guest.runDir(this), "app.txt").length();
        AppExit.begin(this, AppExit.IMPORT, "an import from the PC was running");
        new Thread(() -> {
            String result;
            try (InputStream s = in) {
                String os = Installer.installAny(this, new java.io.BufferedInputStream(s, 1 << 20), total, (what, at, all) -> runOnUiThread(() -> {
                    bar.setIndeterminate(all <= 0);
                    if (all > 0) {
                        bar.setProgress((int) (at * 1000 / all));
                    }
                    status.setText(all > 0 ? String.format("%s: %.1f of %.1f GB", what, at / 1e9, all / 1e9) : what + "…");
                }));
                if (done != null) {
                    done.delete();
                }
                String notes = "16".equals(os) ? Installer.kernelNotes(this) : "";
                result = String.format("%s is set up (%d s).%s", "16".equals(os) ? "iOS 16" : "26".equals(os) ? "iOS 26" : "watchOS",
                        (System.currentTimeMillis() - t0) / 1000, notes.isEmpty() ? "" : "\n" + notes);
            } catch (IOException | RuntimeException e) {
                result = OwnAnswer.importText(e.getMessage());
                AppLog.note(this, "import stopped: " + e);
                // an empty or short file is its own answer: the line alone, no log to share or report
                importRecord = OwnAnswer.is(e.getMessage()) ? null : SetupRecord.writeImport(this, e.getMessage(), e, total, logAt, t0);
            } finally {
                AppExit.end(this, AppExit.IMPORT);
            }
            String r = result;
            runOnUiThread(() -> finishRun(r));
        }, "setup-import").start();
    }

    private void finishRun(String result) {
        busy = false;
        if (isDestroyed() || isFinishing()) {
            return;
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        show();
        status.setText(result);
        importDone = result.contains(" is set up") ? result.split("\n")[0] : null;
        showDone();
        String id = importRecord;
        if (id != null) {
            // under the failed import's line: its log, as under a failed setup step
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            Button share = new Button(this), report = new Button(this);
            share.setText("Share log");
            report.setText("Report on GitHub");
            for (Button b : new Button[] {share, report}) {
                b.setAllCaps(false);
                row.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
            }
            share.setOnClickListener(v -> {
                CrashReport.Record rec = CrashReport.find(this, id);
                if (rec != null) {
                    CrashReport.share(this, rec);
                }
            });
            report.setOnClickListener(v -> {
                CrashReport.Record rec = CrashReport.find(this, id);
                if (rec != null) {
                    CrashReport.openIssue(this, rec);
                }
            });
            box.addView(row);
        }
        if (Installer.installed(this) && result.startsWith("iOS 16 is set up")) {
            Guest.select(this, Guest.IOS16);
            button("Start iOS 16 (the first start takes a few minutes)", v -> {
                startActivity(new Intent(this, ScreenActivity.class));
                finish();
            });
        }
    }
}
