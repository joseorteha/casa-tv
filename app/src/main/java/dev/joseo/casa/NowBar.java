package dev.joseo.casa;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * "Now playing" pill: cover, title, artist and a thin progress line, read from whatever app is playing (Spotify,
 * YouTube Music...) through Android's media sessions. No account, no API key, no network. It is hidden when nothing
 * is playing, and costs nothing while Home is not on screen: it listens only between start() and stop().
 * OK on the pill plays or pauses.
 */
final class NowBar extends FrameLayout {

    private final Context ctx;
    private final ImageView cover;
    private final TextView title;
    private final TextView artist;
    private final Progress progress;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private MediaSessionManager manager;
    private ComponentName listener;
    private final List<MediaController> watched = new ArrayList<>();
    private MediaController current;
    private boolean started;
    /** For when permission is not there yet: the reason is kept for the logs, the bar just stays hidden. */
    private boolean denied;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::watch;
    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            refresh();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            refresh();
        }
    };
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            progress.setFraction(fraction());
            if (started && current != null && isPlaying(current)) handler.postDelayed(this, 1000);
        }
    };

    NowBar(Context c) {
        super(c);
        ctx = c;
        setVisibility(GONE);
        setFocusable(true);
        setClickable(true);
        setBackground(Ui.glass(c, 24, 38, 60));
        setClipChildren(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), Ui.dp(ctx, 24));
            }
        });
        setClipToOutline(true);

        int art = Ui.dp(c, 36);
        cover = new ImageView(c);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(Ui.solid(c, 18, Color.argb(120, 0, 0, 0)));
        final float r = Ui.dp(c, 18);
        cover.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        cover.setClipToOutline(true);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(art, art, Gravity.CENTER_VERTICAL | Gravity.START);
        cp.leftMargin = Ui.dp(c, 6);
        addView(cover, cp);

        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL);
        tp.leftMargin = Ui.dp(c, 52);
        tp.rightMargin = Ui.dp(c, 16);
        addView(text, tp);

        title = new TextView(c);
        title.setTextColor(Color.WHITE);
        title.setTextSize(13);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(title);

        artist = new TextView(c);
        artist.setTextColor(Color.argb(190, 255, 255, 255));
        artist.setTextSize(12);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(artist);

        progress = new Progress(c);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-1, Ui.dp(c, 3), Gravity.BOTTOM);
        addView(progress, pp);

        setOnClickListener(v -> {
            MediaController m = current;
            if (m == null) return;
            if (isPlaying(m)) m.getTransportControls().pause();
            else m.getTransportControls().play();
        });
        setOnFocusChangeListener((v, focus) -> {
            v.animate().scaleX(focus ? 1.07f : 1f).scaleY(focus ? 1.07f : 1f).setDuration(140).start();
            setBackground(focus ? Ui.glass(ctx, 24, 110, 200) : Ui.glass(ctx, 24, 38, 60));
        });
    }

    void start() {
        if (started) return;
        started = true;
        connect();
    }

    private final Runnable retry = () -> {
        if (started && denied) connect();
    };

    /** Android binds a freshly enabled notification listener a moment later, so a first refusal is retried. */
    private void connect() {
        manager = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
        listener = new ComponentName(ctx, NowPlayingListener.class);
        try {
            manager.addOnActiveSessionsChangedListener(sessionsChanged, listener);
            denied = false;
            watch(manager.getActiveSessions(listener));
        } catch (SecurityException e) {
            denied = true;
            setVisibility(GONE);
            handler.postDelayed(retry, 3000);
        }
    }

    void stop() {
        if (!started) return;
        started = false;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(retry);
        try {
            manager.removeOnActiveSessionsChangedListener(sessionsChanged);
        } catch (RuntimeException ignored) {
        }
        for (MediaController c : watched) c.unregisterCallback(callback);
        watched.clear();
        current = null;
    }

    boolean isDenied() {
        return denied;
    }

    private void watch(List<MediaController> controllers) {
        for (MediaController c : watched) c.unregisterCallback(callback);
        watched.clear();
        if (controllers != null) {
            for (MediaController c : controllers) {
                c.registerCallback(callback, handler);
                watched.add(c);
            }
        }
        refresh();
    }

    private void refresh() {
        MediaController pick = null;
        for (MediaController c : watched) {   // a playing session wins; otherwise the first paused one with a title
            if (isPlaying(c)) {
                pick = c;
                break;
            }
            if (pick == null && c.getMetadata() != null && c.getPlaybackState() != null
                    && c.getPlaybackState().getState() == PlaybackState.STATE_PAUSED) pick = c;
        }
        current = pick;
        handler.removeCallbacks(tick);
        if (pick == null || pick.getMetadata() == null) {
            setVisibility(GONE);
            return;
        }
        MediaMetadata md = pick.getMetadata();
        CharSequence t = md.getText(MediaMetadata.METADATA_KEY_TITLE);
        CharSequence a = md.getText(MediaMetadata.METADATA_KEY_ARTIST);
        if (a == null) a = md.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        if (a == null) a = md.getText(MediaMetadata.METADATA_KEY_ALBUM);
        if (TextUtils.isEmpty(t)) {
            setVisibility(GONE);
            return;
        }
        title.setText(t);
        artist.setText(a == null ? "" : a);
        Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (art != null) {
            cover.setImageBitmap(art);
        } else {
            Drawable icon = null;
            try {
                icon = ctx.getPackageManager().getApplicationIcon(pick.getPackageName());
            } catch (Exception ignored) {
            }
            cover.setImageDrawable(icon);
        }
        setVisibility(VISIBLE);
        progress.setFraction(fraction());
        if (isPlaying(pick)) handler.postDelayed(tick, 1000);
    }

    private static boolean isPlaying(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        return s != null && s.getState() == PlaybackState.STATE_PLAYING;
    }

    /** How far through the track, 0..1, or 0 when the app does not say how long it is. */
    private float fraction() {
        MediaController c = current;
        if (c == null || c.getMetadata() == null) return 0f;
        long dur = c.getMetadata().getLong(MediaMetadata.METADATA_KEY_DURATION);
        PlaybackState s = c.getPlaybackState();
        if (dur <= 0 || s == null) return 0f;
        long pos = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        return Math.max(0f, Math.min(1f, pos / (float) dur));
    }

    /** The thin line along the bottom edge of the pill. */
    private static final class Progress extends View {
        private final Paint back = new Paint();
        private final Paint front = new Paint();
        private final RectF r = new RectF();
        private float fraction;

        Progress(Context c) {
            super(c);
            back.setColor(Color.argb(60, 255, 255, 255));
            front.setColor(Color.WHITE);
        }

        void setFraction(float f) {
            fraction = f;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            r.set(0, 0, getWidth(), getHeight());
            c.drawRect(r, back);
            r.right = getWidth() * fraction;
            c.drawRect(r, front);
        }
    }
}
