package dev.joseo.casa;

import android.os.Handler;
import android.os.Looper;

/** Tells the open Home screen that the web panel changed something (same process, so a plain callback does). */
final class Bus {
    private static volatile Runnable listener;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Bus() {
    }

    static void listen(Runnable r) {
        listener = r;
    }

    static void changed() {
        final Runnable r = listener;
        if (r != null) MAIN.post(r);
    }
}
