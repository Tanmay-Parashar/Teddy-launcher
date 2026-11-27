package app.monolith.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

private const val CHANNEL = "teddy_lock"

/** Tiny breadcrumbs so the setup screen can show what the lock screen is doing. */
object LockDiag {
    fun mark(ctx: Context, key: String) {
        ctx.getSharedPreferences("teddy", Context.MODE_PRIVATE)
            .edit().putLong("diag_$key", System.currentTimeMillis()).apply()
    }

    fun read(ctx: Context, key: String): String {
        val t = ctx.getSharedPreferences("teddy", Context.MODE_PRIVATE).getLong("diag_$key", 0L)
        if (t == 0L) return "never"
        return DateFormat.format(if (DateFormat.is24HourFormat(ctx)) "HH:mm:ss" else "hh:mm:ss a", t).toString()
    }
}

fun startLockService(ctx: Context) {
    runCatching {
        ContextCompat.startForegroundService(ctx, Intent(ctx, LockService::class.java))
    }
}

/**
 * Keeps a screen-off receiver alive and pre-loads [LockActivity] so it is already on top of the
 * system keyguard when the screen turns back on.
 */
class LockService : Service() {

    companion object {
        @Volatile
        var running = false
    }

    private var overlay: View? = null
    private var lastWake = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_OFF) {
                LockDiag.mark(this@LockService, "off")
                showLock()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Lock screen service", NotificationManager.IMPORTANCE_MIN)
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Teddy's lock screen is on")
            .setSmallIcon(R.drawable.ic_stat)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, 1, n, type)

        running = true
        addOverlay()
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        runCatching { overlay?.let { getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    /**
     * A 1px invisible overlay window counts as "app has a visible window", which Android needs to
     * allow starting an activity from the background.
     */
    private fun addOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        val lp = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.START or Gravity.TOP }
        runCatching {
            val v = View(this)
            getSystemService(WindowManager::class.java).addView(v, lp)
            overlay = v
        }
    }

    private fun isCharging(): Boolean {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    private fun showLock() {
        // On a call (phone, WhatsApp, etc.) the screen goes dark at your ear. Stay out of the way so
        // the in-call screen, not the clock, is what's there when you pull the phone away.
        val am = getSystemService(AudioManager::class.java)
        if (am.mode != AudioManager.MODE_NORMAL) {
            LockDiag.mark(this, "skipcall")
            return
        }
        LockDiag.mark(this, "try")
        val keepOn = getSharedPreferences("teddy", Context.MODE_PRIVATE).getBoolean("lock_keepon", true)
        val now = System.currentTimeMillis()
        // While charging, wake the screen straight back up so the clock stays always-on.
        // Screen turning off again within 3s of our wake means a deliberate double press: let it sleep.
        val wake = keepOn && isCharging() && now - lastWake > 3_000L
        if (wake) lastWake = now
        val i = Intent(this, LockActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        ).putExtra("wake", wake)
        runCatching { startActivity(i) }
    }
}
