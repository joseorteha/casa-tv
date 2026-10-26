package dev.joseo.casa;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Draws a QR code in black on white with the quiet zone the scanners need. */
final class QrView extends View {

    private boolean[][] modules;
    private final Paint paint = new Paint();
    private final RectF rect = new RectF();

    QrView(Context c) {
        super(c);
    }

    void setText(String text) {
        try {
            modules = QrCode.encode(text);
        } catch (IllegalArgumentException e) {
            modules = null;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        paint.setAntiAlias(true);
        paint.setColor(Color.WHITE);
        rect.set(0, 0, w, getHeight());
        c.drawRoundRect(rect, w * 0.06f, w * 0.06f, paint);
        if (modules == null) return;
        int n = modules.length;
        float quiet = 4;   // modules of white on every side
        float cell = (float) Math.floor(w / (n + 2 * quiet) * 4) / 4f;   // quarter-pixel steps keep the edges crisp
        float origin = (w - cell * n) / 2f;
        paint.setAntiAlias(false);
        paint.setColor(Color.BLACK);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (!modules[y][x]) continue;
                c.drawRect(origin + x * cell, origin + y * cell, origin + (x + 1) * cell, origin + (y + 1) * cell, paint);
            }
        }
    }
}
