package dev.joseo.casa;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;

/** Small drawing helpers shared by the views. */
final class Ui {
    private Ui() {
    }

    static int dp(Context c, float v) {
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        return Math.round(v * m.density);
    }

    /** A translucent rounded "glass" fill with a hairline edge. */
    static GradientDrawable glass(Context c, float radiusDp, int fillAlpha, int strokeAlpha) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.argb(fillAlpha, 255, 255, 255));
        g.setCornerRadius(dp(c, radiusDp));
        g.setStroke(Math.max(1, dp(c, 1)), Color.argb(strokeAlpha, 255, 255, 255));
        return g;
    }

    static GradientDrawable solid(Context c, float radiusDp, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    /** The focus ring drawn over a tile. */
    static GradientDrawable ring(Context c, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setCornerRadius(dp(c, radiusDp));
        g.setStroke(dp(c, 3), Color.WHITE);
        return g;
    }
}
