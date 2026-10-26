package dev.joseo.casa;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The apps Casa shows, and which of them sit in the dock. */
final class AppRepo {

    static final class Entry {
        final String pkg;
        final String label;
        final Intent launch;
        final Drawable banner;   // 16:9 artwork, or null
        final Drawable icon;

        Entry(String pkg, String label, Intent launch, Drawable banner, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.launch = launch;
            this.banner = banner;
            this.icon = icon;
        }
    }

    private static final String PREFS = "casa";
    private static final String KEY_DOCK = "dock";

    static final List<String> DEFAULT_DOCK = Arrays.asList(
            "com.netflix.ninja",
            "com.google.android.youtube.tv",
            "com.disney.disneyplus",
            "com.spotify.tv.android",
            "com.android.mgstv");

    /** Never shown as tiles: ourselves, the stock home, settings and the TV's own plumbing. */
    private static final Set<String> HIDDEN = new HashSet<>(Arrays.asList(
            "dev.joseo.casa",
            "com.gathin.tvhome",
            "com.mediatek.androidbox",   // Miracast receiver: no use here, and its tile is a bare Wi-Fi glyph
            "com.google.android.apps.tv.launcherx",
            "com.google.android.tungsten.setupwraith",
            "com.android.tv.settings",
            "com.mediatek.wwtv.tvcenter",
            "com.mediatek.wwtv.tvcenter.plus",
            "com.google.android.tv.remote.service"));

    private AppRepo() {
    }

    static List<String> dock(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = p.getString(KEY_DOCK, null);
        if (saved == null || saved.isEmpty()) return DEFAULT_DOCK;
        return Arrays.asList(saved.split(","));
    }

    static void saveDock(Context c, List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String s : packages) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DOCK, sb.toString()).apply();
    }

    /** Every launchable app, alphabetical. */
    static List<Entry> load(Context c) {
        PackageManager pm = c.getPackageManager();
        List<ResolveInfo> found = new ArrayList<>();
        found.addAll(pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        // Apps with no leanback entry (rare on a TV) still get a tile when nothing else claims the package.
        Set<String> have = new HashSet<>();
        for (ResolveInfo ri : found) have.add(ri.activityInfo.packageName);
        for (ResolveInfo ri : pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            if (have.add(ri.activityInfo.packageName)) found.add(ri);
        }
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (HIDDEN.contains(pkg) || !seen.add(pkg)) continue;
            Intent launch = new Intent(Intent.ACTION_MAIN)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            Drawable banner = ri.activityInfo.loadBanner(pm);
            out.add(new Entry(pkg, String.valueOf(ri.loadLabel(pm)), launch, banner, ri.loadIcon(pm)));
        }
        final Collator coll = Collator.getInstance(Locale.getDefault());
        Collections.sort(out, (a, b) -> coll.compare(a.label, b.label));
        return out;
    }
}
