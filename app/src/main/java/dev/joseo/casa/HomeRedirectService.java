package dev.joseo.casa;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

/**
 * The stock Google TV launcher and the setup wizard's RecoveryActivity both answer the Home key and cannot be
 * disabled on this TV (the saved Wi-Fi belongs to the wizard). Instead of fighting them, this service watches for
 * their windows and brings Casa back in front. Nothing is disabled, so if the service is off Home still works.
 */
public class HomeRedirectService extends AccessibilityService {

    private static final String STOCK_HOME = "com.google.android.apps.tv.launcherx";
    private static final String SETUP_WIZARD = "com.google.android.tungsten.setupwraith";

    /** The running service, so the web panel can ask it for Home and Back. */
    static volatile HomeRedirectService instance;

    private long lastRedirect;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg == null) return;
        String p = pkg.toString();
        String c = cls == null ? "" : cls.toString();
        boolean stockHome = p.equals(STOCK_HOME) && c.contains("HomeActivity");
        boolean wizard = p.equals(SETUP_WIZARD) && c.contains("RecoveryActivity");
        if (!stockHome && !wizard) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastRedirect < 700) return;   // never loop against a window that reappears at once
        lastRedirect = now;
        Intent home = new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(home);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        WebServer.start(this);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        WebServer.stop();
        return super.onUnbind(intent);
    }

    @Override
    public void onInterrupt() {
    }
}
