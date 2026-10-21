package com.ramphoenix.launcher;

import android.service.notification.NotificationListenerService;

/**
 * Empty notification listener. Its only job is to exist and be enabled, which
 * is what MediaSessionManager requires before it will expose other apps'
 * media sessions to the dashboard's now-playing widget.
 *
 *   adb shell cmd notification allow_listener com.ramphoenix.launcher/.MediaListenerService
 */
public class MediaListenerService extends NotificationListenerService {
}
