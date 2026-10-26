package dev.joseo.casa;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * Android switches an app's accessibility services off whenever the app is force-stopped or reinstalled. With
 * WRITE_SECURE_SETTINGS (granted once over adb) Casa puts its redirect service back by itself.
 */
final class A11y {
    private A11y() {
    }

    static void ensure(Context c) {
        if (c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return;
        String me = new ComponentName(c, HomeRedirectService.class).flattenToString();
        try {
            String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            boolean on = list != null && list.contains(me);
            boolean enabled = Settings.Secure.getInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;
            if (on && enabled) return;
            String next = list == null || list.isEmpty() || list.equals("null") ? me : on ? list : list + ":" + me;
            Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (SecurityException ignored) {
        }
    }
}
