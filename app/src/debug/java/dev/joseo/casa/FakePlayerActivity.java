package dev.joseo.casa;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.SystemClock;

public class FakePlayerActivity extends Activity {
    static MediaSession session;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (session == null) session = new MediaSession(getApplicationContext(), "fake");
        Bitmap art = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, 300, 300, Color.rgb(255, 90, 120), Color.rgb(70, 60, 220), Shader.TileMode.CLAMP));
        new Canvas(art).drawRect(0, 0, 300, 300, p);
        boolean pause = "pause".equals(getIntent().getStringExtra("state"));
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Canción de prueba muy larga para ver el recorte")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Artista de prueba")
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 200000).build());
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE)
                .setState(pause ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_PLAYING, 50000, 1f, SystemClock.elapsedRealtime())
                .build());
        session.setActive(true);
        finish();
    }
}
