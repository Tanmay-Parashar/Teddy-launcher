package app.monolith.launcher

import android.app.Notification
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

class TeddyNotificationListener : NotificationListenerService() {

    private var msm: MediaSessionManager? = null
    private var controller: MediaController? = null

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateMedia()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateMedia()
        }
    }

    private val sessionListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> bind(list) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        msm = getSystemService(MediaSessionManager::class.java)
        val cn = ComponentName(this, TeddyNotificationListener::class.java)
        runCatching {
            msm?.addOnActiveSessionsChangedListener(sessionListener, cn)
            bind(msm?.getActiveSessions(cn))
        }
        refreshNotifications()
    }

    override fun onListenerDisconnected() {
        runCatching { msm?.removeOnActiveSessionsChangedListener(sessionListener) }
        controller?.unregisterCallback(callback)
        controller = null
        LockState.media = null
        LockState.notifications.clear()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        refreshNotifications()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        refreshNotifications()
    }

    // ---------------------------------------------------------------- media

    private fun bind(list: List<MediaController>?) {
        val next = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list?.firstOrNull()
        if (next?.sessionToken == controller?.sessionToken) {
            updateMedia()
            return
        }
        controller?.unregisterCallback(callback)
        controller = next
        controller?.registerCallback(callback)
        updateMedia()
    }

    private fun updateMedia() {
        val c = controller
        val md = c?.metadata
        val state = c?.playbackState?.state
        if (c == null || md == null || state == null ||
            state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_NONE
        ) {
            LockState.media = null
            return
        }
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        if (title.isBlank()) {
            LockState.media = null
            return
        }
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
        LockState.media = MediaInfo(
            title = title,
            artist = artist,
            art = bmp?.asImageBitmap(),
            playing = state == PlaybackState.STATE_PLAYING,
            controller = c,
        )
    }

    // ---------------------------------------------------------------- notifications

    private fun refreshNotifications() {
        val all = runCatching { activeNotifications }.getOrNull() ?: return
        val items = all
            .filter { sbn ->
                val n = sbn.notification
                sbn.packageName != packageName &&
                    (n.flags and Notification.FLAG_ONGOING_EVENT) == 0 &&
                    (n.flags and Notification.FLAG_GROUP_SUMMARY) == 0 &&
                    n.category != Notification.CATEGORY_TRANSPORT
            }
            .sortedByDescending { it.postTime }
            .mapNotNull { sbn ->
                val n = sbn.notification
                val extras = n.extras
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty()
                if (title.isBlank() && text.isBlank()) return@mapNotNull null
                val label = runCatching {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(sbn.packageName, 0)
                    ).toString()
                }.getOrDefault(sbn.packageName)
                val icon = runCatching {
                    n.smallIcon?.loadDrawable(this)?.toBitmap(64, 64)?.asImageBitmap()
                }.getOrNull()
                NotifItem(sbn.key, label, title, text, icon, n.contentIntent)
            }
            .take(6)
        LockState.notifications.clear()
        LockState.notifications.addAll(items)
    }
}
