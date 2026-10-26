package dev.joseo.casa;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** The three round-button glyphs, drawn with strokes so they stay sharp at any size and need no image files. */
final class IconView extends View {

    static final int INPUT = 0, WIFI = 1, GEAR = 2;

    private final int kind;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    IconView(Context c, int kind) {
        super(c);
        this.kind = kind;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(Color.WHITE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setColor(Color.WHITE);
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight()) / 24f;   // everything below is drawn on a 24 x 24 grid
        c.save();
        c.translate((getWidth() - 24 * s) / 2f, (getHeight() - 24 * s) / 2f);
        c.scale(s, s);
        stroke.setStrokeWidth(2f);
        switch (kind) {
            case INPUT:
                // a box with an arrow going in
                rect.set(10f, 4.5f, 21f, 19.5f);
                c.drawRoundRect(rect, 2.5f, 2.5f, stroke);
                path.reset();
                path.moveTo(3f, 12f);
                path.lineTo(15f, 12f);
                path.moveTo(11.5f, 8.5f);
                path.lineTo(15f, 12f);
                path.lineTo(11.5f, 15.5f);
                c.drawPath(path, stroke);
                break;
            case WIFI:
                for (float r : new float[]{14f, 9f, 4.2f}) {
                    rect.set(12f - r, 19f - r, 12f + r, 19f + r);
                    c.drawArc(rect, 225f, 90f, false, stroke);
                }
                c.drawCircle(12f, 19f, 1.7f, fill);
                break;
            default:
                c.drawCircle(12f, 12f, 6.2f, stroke);
                c.drawCircle(12f, 12f, 2.4f, stroke);
                stroke.setStrokeWidth(3.2f);
                for (int i = 0; i < 8; i++) {
                    double a = Math.toRadians(i * 45);
                    float cx = (float) Math.cos(a), cy = (float) Math.sin(a);
                    c.drawLine(12f + cx * 7.4f, 12f + cy * 7.4f, 12f + cx * 9.6f, 12f + cy * 9.6f, stroke);
                }
        }
        c.restore();
    }
}
