package dev.joseo.casa;

import android.service.notification.NotificationListenerService;

/**
 * Does nothing itself. Android only lets an app read other apps' media sessions (what is playing, the cover) when it
 * holds an enabled notification listener, so NowBar uses this component as its key. It is allowed once over adb:
 * adb shell cmd notification allow_listener dev.joseo.casa/dev.joseo.casa.NowPlayingListener
 */
public class NowPlayingListener extends NotificationListenerService {
}
