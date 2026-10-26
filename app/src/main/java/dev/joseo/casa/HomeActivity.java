package dev.joseo.casa;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Casa. Page one is the picture, the clock and a row of apps along the bottom; Down slides page two up with
 * every other app. Sizes are in dp: this TV renders a 1280x720 UI (960x540 dp) on a 42" panel viewed from the sofa,
 * so touch targets are generous and text never goes below 13 sp. Plain Views, no libraries.
 */
public class HomeActivity extends Activity {

    private static final int DOCK_TILE_DP = 76;
    private static final int GRID_W_DP = 150;
    private static final int GRID_H_DP = 84;
    private static final int GAP_DP = 14;
    private static final int COLUMNS = 5;
    private static final int SIDE_DP = 64;

    private WallpaperView wallpaper;
    private TextView greeting;
    private TextView gridTitle;
    private TextView panelInfo;
    private NowBar nowBar;
    private QrView qr;
    private LinearLayout dockRow;
    private LinearLayout grid;
    private PagedScroll scroll;
    private FrameLayout page1;
    private int pageH;
    private String shownSignature = "";

    /** A ScrollView that only moves when we say so: Android's "scroll to the focused child" would fight the pages. */
    static final class PagedScroll extends ScrollView {
        PagedScroll(android.content.Context c) {
            super(c);
            setVerticalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setFillViewport(true);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rect) {
            return 0;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pageH = getResources().getDisplayMetrics().heightPixels;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        wallpaper = new WallpaperView(this);
        root.addView(wallpaper, new FrameLayout.LayoutParams(-1, -1));

        // Darkens the picture as page two slides up, so the tiles stay readable over a busy photo.
        final View dim = new View(this);
        dim.setBackgroundColor(Color.BLACK);
        dim.setAlpha(0f);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        scroll = new PagedScroll(this);
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> dim.setAlpha(Math.min(1f, y / (float) pageH) * 0.6f));
        LinearLayout pages = new LinearLayout(this);
        pages.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(pages, new FrameLayout.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        page1 = new FrameLayout(this);
        page1.setClipChildren(false);
        pages.addView(page1, new LinearLayout.LayoutParams(-1, pageH));
        buildHeader(page1);
        buildPills(page1);
        nowBar = new NowBar(this);
        // Right-aligned beside the three round buttons (3 x 46 dp + gaps + the side margin) so it never covers the date.
        FrameLayout.LayoutParams nb = new FrameLayout.LayoutParams(Ui.dp(this, 224), Ui.dp(this, 48),
                Gravity.TOP | Gravity.END);
        nb.topMargin = Ui.dp(this, 33);
        nb.rightMargin = Ui.dp(this, SIDE_DP + 3 * 46 + 3 * 12 + 14);
        page1.addView(nowBar, nb);
        buildDock(page1);

        LinearLayout page2 = new LinearLayout(this);
        page2.setOrientation(LinearLayout.VERTICAL);
        page2.setClipChildren(false);
        page2.setMinimumHeight(pageH);
        int side = Ui.dp(this, SIDE_DP);
        page2.setPadding(side, Ui.dp(this, 36), side, Ui.dp(this, 70));
        gridTitle = new TextView(this);
        gridTitle.setText("TODAS LAS APPS");
        gridTitle.setTextColor(Color.argb(190, 255, 255, 255));
        gridTitle.setTextSize(13);
        gridTitle.setLetterSpacing(0.2f);
        gridTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        gridTitle.setPadding(Ui.dp(this, 6), 0, 0, Ui.dp(this, 14));
        page2.addView(gridTitle);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        page2.addView(grid);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(Ui.dp(this, 6), Ui.dp(this, 26), 0, 0);
        qr = new QrView(this);
        panel.addView(qr, new LinearLayout.LayoutParams(Ui.dp(this, 120), Ui.dp(this, 120)));
        panelInfo = new TextView(this);
        panelInfo.setTextColor(Color.argb(210, 255, 255, 255));
        panelInfo.setTextSize(14);
        panelInfo.setLineSpacing(0, 1.25f);
        panelInfo.setPadding(Ui.dp(this, 18), 0, 0, 0);
        panel.addView(panelInfo);
        page2.addView(panel);
        pages.addView(page2, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        A11y.ensure(this);
        WebServer.start(this);
        Bus.listen(() -> {
            wallpaper.reload(true);
            populate();
        });
        wallpaper.reload();
        refreshGreeting();
        populate();
        refreshPanelInfo();
        nowBar.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Bus.listen(null);
        nowBar.stop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already here: back to the first page and the first app.
        scroll.smoothScrollTo(0, 0);
        if (dockRow.getChildCount() > 0) dockRow.getChildAt(0).requestFocus();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (scroll.getScrollY() > 0 && dockRow.getChildCount() > 0) dockRow.getChildAt(0).requestFocus();
            return true;   // Back never leaves Home
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- header ------------------------------------------------------------------------------------------------------

    private void buildHeader(FrameLayout page) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
        lp.leftMargin = Ui.dp(this, SIDE_DP);
        lp.topMargin = Ui.dp(this, 30);
        page.addView(box, lp);

        greeting = new TextView(this);
        greeting.setTextColor(Color.argb(200, 255, 255, 255));
        greeting.setTextSize(13);
        greeting.setLetterSpacing(0.2f);
        greeting.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        greeting.setPadding(Ui.dp(this, 4), 0, 0, 0);
        box.addView(greeting);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(row);

        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("H:mm");
        time.setTextColor(Color.WHITE);
        time.setTextSize(54);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setIncludeFontPadding(false);
        time.setShadowLayer(Ui.dp(this, 6), 0, Ui.dp(this, 2), Color.argb(120, 0, 0, 0));
        row.addView(time);

        TextClock ampm = new TextClock(this);
        ampm.setFormat12Hour("a");
        ampm.setFormat24Hour("");
        ampm.setAllCaps(true);
        ampm.setTextColor(Color.argb(230, 255, 255, 255));
        ampm.setTextSize(16);
        ampm.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
        ap.leftMargin = Ui.dp(this, 8);
        row.addView(ampm, ap);

        View bar = new View(this);
        bar.setBackgroundColor(Color.argb(110, 255, 255, 255));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(Ui.dp(this, 1), Ui.dp(this, 30));
        bp.leftMargin = Ui.dp(this, 16);
        bp.rightMargin = Ui.dp(this, 16);
        row.addView(bar, bp);

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE  d  MMMM");
        date.setFormat24Hour("EEEE  d  MMMM");
        date.setAllCaps(true);
        date.setTextColor(Color.argb(220, 255, 255, 255));
        date.setTextSize(16);
        date.setLetterSpacing(0.12f);
        row.addView(date);
    }

    private void refreshPanelInfo() {
        String ip = WebServer.address();
        if (ip == null) {
            panelInfo.setText("Panel web: la TV no tiene red.");
            qr.setText("");
            return;
        }
        String base = "http://" + ip + ":" + WebServer.PORT;
        qr.setText(base + "/?pin=" + WebServer.pin(this));
        panelInfo.setText("Controla la TV desde tu celular\n"
                + "Escanea el código con la cámara.\n"
                + base + "   ·   PIN " + WebServer.pin(this));
    }

    private void refreshGreeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 6 ? "BUENAS NOCHES" : h < 12 ? "BUENOS DÍAS" : h < 20 ? "BUENAS TARDES" : "BUENAS NOCHES");
    }

    // ---- round buttons, top right ---------------------------------------------------------------------------------

    private void buildPills(FrameLayout page) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        lp.topMargin = Ui.dp(this, 34);
        lp.rightMargin = Ui.dp(this, SIDE_DP);
        page.addView(row, lp);
        row.addView(roundButton(IconView.INPUT, v -> openInputs()));
        row.addView(roundButton(IconView.WIFI, v -> start(new Intent(Settings.ACTION_WIFI_SETTINGS))));
        row.addView(roundButton(IconView.GEAR, v -> start(new Intent(Settings.ACTION_SETTINGS))));
    }

    private View roundButton(int kind, View.OnClickListener click) {
        int size = Ui.dp(this, 46);
        FrameLayout f = new FrameLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.leftMargin = Ui.dp(this, 12);
        f.setLayoutParams(lp);
        f.setFocusable(true);
        f.setClickable(true);
        f.setBackground(Ui.glass(this, 23, 55, 70));
        int glyph = Ui.dp(this, 22);
        f.addView(new IconView(this, kind), new FrameLayout.LayoutParams(glyph, glyph, Gravity.CENTER));
        f.setOnClickListener(click);
        f.setOnFocusChangeListener((v, focus) -> {
            v.animate().scaleX(focus ? 1.15f : 1f).scaleY(focus ? 1.15f : 1f).setDuration(140).start();
            v.setBackground(focus ? Ui.glass(this, 23, 130, 230) : Ui.glass(this, 23, 55, 70));
            if (focus) scroll.smoothScrollTo(0, 0);
        });
        return f;
    }

    private void openInputs() {
        // The Live TV app's own input menu is the way to the HDMI ports on this TV.
        Intent i = getPackageManager().getLaunchIntentForPackage("com.mediatek.wwtv.tvcenter");
        if (i == null) i = new Intent(Settings.ACTION_SETTINGS);
        start(i);
    }

    private void start(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "No se pudo abrir", Toast.LENGTH_SHORT).show();
        }
    }

    // ---- the dock along the bottom -----------------------------------------------------------------------------

    private void buildDock(FrameLayout page) {
        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        dockRow.setClipChildren(false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.bottomMargin = Ui.dp(this, 44);
        page.addView(dockRow, lp);
    }

    private void populate() {
        List<AppRepo.Entry> all = AppRepo.load(this);
        List<String> dockPkgs = AppRepo.dock(this);
        StringBuilder sig = new StringBuilder();
        for (AppRepo.Entry e : all) sig.append(e.pkg).append(',');
        sig.append('|').append(dockPkgs);
        if (sig.toString().equals(shownSignature)) return;
        shownSignature = sig.toString();

        List<AppRepo.Entry> dockApps = new ArrayList<>();
        for (String pkg : dockPkgs) {
            for (AppRepo.Entry e : all) {
                if (e.pkg.equals(pkg)) {
                    dockApps.add(e);
                    break;
                }
            }
        }

        dockRow.removeAllViews();
        for (AppRepo.Entry e : dockApps) dockRow.addView(dockItem(e));

        grid.removeAllViews();
        LinearLayout row = null;
        int n = 0;
        for (AppRepo.Entry e : all) {
            if (dockApps.contains(e)) continue;
            if (n % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
                rp.bottomMargin = Ui.dp(this, GAP_DP);
                grid.addView(row, rp);
            }
            row.addView(gridTile(e, n % COLUMNS));
            n++;
        }
        if (dockRow.getChildCount() > 0) dockRow.getChildAt(0).requestFocus();
    }

    /** One dock app: its name floats above when focused, the icon grows, a dot sits beneath. */
    private View dockItem(AppRepo.Entry e) {
        int tile = Ui.dp(this, DOCK_TILE_DP);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);
        col.setFocusable(true);
        col.setClickable(true);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(Ui.dp(this, DOCK_TILE_DP + 28), -2);
        col.setLayoutParams(cp);

        TextView label = new TextView(this);
        label.setText(e.label);
        label.setTextColor(Color.WHITE);
        label.setTextSize(13);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setSingleLine(true);
        label.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 5));
        label.setBackground(Ui.glass(this, 14, 70, 60));
        label.setAlpha(0f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.bottomMargin = Ui.dp(this, 10);
        col.addView(label, lp);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(e.icon);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        final float radius = Ui.dp(this, 18);
        icon.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        icon.setClipToOutline(true);
        icon.setElevation(Ui.dp(this, 4));
        col.addView(icon, new LinearLayout.LayoutParams(tile, tile));

        View dot = new View(this);
        dot.setBackground(Ui.solid(this, 3, Color.WHITE));
        dot.setAlpha(0f);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(Ui.dp(this, 6), Ui.dp(this, 6));
        dp.topMargin = Ui.dp(this, 12);
        col.addView(dot, dp);

        col.setOnClickListener(v -> start(new Intent(e.launch)));
        col.setOnFocusChangeListener((v, focus) -> {
            float s = focus ? 1.22f : 1f;
            icon.animate().scaleX(s).scaleY(s).setDuration(160).start();
            label.animate().alpha(focus ? 1f : 0f).translationY(focus ? -Ui.dp(this, 6) : 0).setDuration(160).start();
            dot.animate().alpha(focus ? 1f : 0f).setDuration(160).start();
            if (focus) scroll.smoothScrollTo(0, 0);
        });
        return col;
    }

    private View gridTile(AppRepo.Entry e, int column) {
        int w = Ui.dp(this, GRID_W_DP), h = Ui.dp(this, GRID_H_DP);
        FrameLayout f = new FrameLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        if (column > 0) lp.leftMargin = Ui.dp(this, GAP_DP);
        f.setLayoutParams(lp);
        f.setFocusable(true);
        f.setClickable(true);
        f.setBackground(Ui.solid(this, 14, Color.argb(235, 24, 27, 38)));
        final float radius = Ui.dp(this, 14);
        f.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        f.setClipToOutline(true);

        ImageView art = new ImageView(this);
        Drawable banner = e.banner;
        if (banner != null) {
            art.setImageDrawable(banner);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            f.addView(art, new FrameLayout.LayoutParams(-1, -1));
        } else {
            art.setImageDrawable(e.icon);
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int s = Ui.dp(this, 40);
            FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(s, s, Gravity.CENTER_HORIZONTAL);
            ip.topMargin = Ui.dp(this, 10);
            f.addView(art, ip);
            TextView label = new TextView(this);
            label.setText(e.label);
            label.setTextColor(Color.WHITE);
            label.setTextSize(13);
            label.setSingleLine(true);
            label.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            tp.bottomMargin = Ui.dp(this, 7);
            f.addView(label, tp);
        }
        f.setOnClickListener(v -> start(new Intent(e.launch)));
        final Drawable ring = Ui.ring(this, 14);
        f.setOnFocusChangeListener((v, focus) -> {
            v.animate().scaleX(focus ? 1.08f : 1f).scaleY(focus ? 1.08f : 1f).setDuration(150).start();
            ((FrameLayout) v).setForeground(focus ? ring : null);
            v.setElevation(focus ? Ui.dp(this, 8) : 0);
            if (focus) {
                gridTitle.setText(e.label.toUpperCase());
                // first page stays put; deeper rows slide up so the focused row is always on screen
                View row = (View) v.getParent();
                int rowTop = row.getTop();
                scroll.smoothScrollTo(0, pageH + Math.max(0, rowTop - Ui.dp(this, 20)));
            }
        });
        return f;
    }
}
