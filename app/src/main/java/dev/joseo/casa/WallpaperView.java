package dev.joseo.casa;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.view.View;

import java.io.File;

/**
 * The background. A picture at files/wallpaper.jpg wins; otherwise a soft aurora is painted once into a small bitmap
 * and scaled up, so the TV never redraws gradients. A dark scrim keeps the clock and the dock readable.
 */
final class WallpaperView extends View {

    private Bitmap bitmap;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect src = new Rect();
    private final Rect dst = new Rect();
    private final Paint scrim = new Paint();
    private int scrimH = -1;
    private File current;

    WallpaperView(Context c) {
        super(c);
        reload();
    }

    /** Chooses the picture: the one pinned in the web panel, else one per hour from the wallpapers folder. */
    void reload() {
        reload(false);
    }

    void reload(boolean force) {
        File dir = WebServer.wallpaperDir(getContext());
        File[] set = dir == null ? null : dir.listFiles(
                (d, n) -> !n.startsWith(".") && !n.endsWith(".tmp") && new File(d, n).isFile());
        File f = null;
        if (set != null && set.length > 0) {
            java.util.Arrays.sort(set);
            String pinned = getContext().getSharedPreferences("casa", Context.MODE_PRIVATE).getString("wp", "");
            for (File c : set) if (c.getName().equals(pinned)) f = c;
            if (f == null) f = set[java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) % set.length];
        }
        if (!force && bitmap != null && (f == null ? current == null : f.equals(current))) return;
        current = f;
        Bitmap b = null;
        if (f != null) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 1920) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            b = BitmapFactory.decodeFile(f.getPath(), o);
        }
        if (b == null) b = aurora();
        bitmap = b;
        invalidate();
    }

    private static Bitmap aurora() {
        int w = 640, h = 360;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, Color.rgb(8, 12, 26), Color.rgb(3, 4, 9), Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, p);
        glow(cv, w * 0.78f, h * 0.18f, w * 0.55f, Color.argb(150, 70, 90, 230));
        glow(cv, w * 0.30f, h * 0.92f, w * 0.60f, Color.argb(120, 20, 170, 160));
        glow(cv, w * 0.95f, h * 0.80f, w * 0.40f, Color.argb(90, 170, 60, 190));
        return b;
    }

    private static void glow(Canvas cv, float cx, float cy, float r, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(cx, cy, r, color, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, r, p);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (bitmap == null || w == 0 || h == 0) return;
        // center-crop the picture to the screen
        float sr = (float) bitmap.getWidth() / bitmap.getHeight();
        float dr = (float) w / h;
        if (sr > dr) {
            int cw = Math.round(bitmap.getHeight() * dr);
            int x = (bitmap.getWidth() - cw) / 2;
            src.set(x, 0, x + cw, bitmap.getHeight());
        } else {
            int ch = Math.round(bitmap.getWidth() / dr);
            int y = (bitmap.getHeight() - ch) / 2;
            src.set(0, y, bitmap.getWidth(), y + ch);
        }
        dst.set(0, 0, w, h);
        canvas.drawBitmap(bitmap, src, dst, paint);
        if (scrimH != h) {
            scrim.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{Color.argb(110, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(190, 0, 0, 0)},
                    new float[]{0f, 0.30f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            scrimH = h;
        }
        canvas.drawRect(0, 0, w, h, scrim);
    }
}
