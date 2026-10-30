package net.nuclearlighters.ventriloquist

import android.service.notification.NotificationListenerService

/**
 * Exists only so that the app may list active media sessions
 * (`MediaSessionManager.getActiveSessions` needs an enabled notification listener). It reads no
 * notifications. Enable it once over adb:
 * `cmd notification allow_listener <package>/net.nuclearlighters.ventriloquist.NotificationListener`.
 */
class NotificationListener : NotificationListenerService()
