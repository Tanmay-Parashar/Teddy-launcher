package app.monolith.launcher

import android.app.PendingIntent
import android.media.session.MediaController
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap

data class NotifItem(
    val key: String,
    val app: String,
    val title: String,
    val text: String,
    val icon: ImageBitmap?,
    val intent: PendingIntent?,
)

data class MediaInfo(
    val title: String,
    val artist: String,
    val art: ImageBitmap?,
    val playing: Boolean,
    val controller: MediaController,
)

/** Shared state fed by [TeddyNotificationListener] and read by the lock screen UI. */
object LockState {
    val notifications = mutableStateListOf<NotifItem>()
    var media by mutableStateOf<MediaInfo?>(null)
}
