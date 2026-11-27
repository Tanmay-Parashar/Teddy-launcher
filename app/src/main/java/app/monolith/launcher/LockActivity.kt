package app.monolith.launcher

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.content.pm.ActivityInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class LockActivity : ComponentActivity() {

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            closeNow()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var seenLocked = false
    private var closing = false
    private var userLandscape by mutableStateOf(false)

    private fun setLandscape(l: Boolean) {
        val want = if (l) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (requestedOrientation != want) requestedOrientation = want
    }

    private fun chargingLandscape(): Boolean {
        val keepOn = getSharedPreferences("teddy", Context.MODE_PRIVATE).getBoolean("lock_keepon", true)
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return keepOn && (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    private val poll = object : Runnable {
        override fun run() {
            if (closing) return
            val km = getSystemService(KeyguardManager::class.java)
            if (km.isKeyguardLocked) seenLocked = true
            else if (seenLocked) { closeNow(); return }
            handler.postDelayed(this, 30L)
        }
    }

    private fun closeNow() {
        if (closing) return
        closing = true
        handler.removeCallbacksAndMessages(null)
        finishAndRemoveTask()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun hideBars() {
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    override fun onResume() {
        super.onResume()
        hideBars()
        seenLocked = false
        closing = false
        handler.removeCallbacksAndMessages(null)
        handler.post(poll)
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    private fun applyWake(i: Intent?) {
        val wake = i?.getBooleanExtra("wake", false) == true
        if (Build.VERSION.SDK_INT >= 27) {
            setTurnScreenOn(wake)
        } else if (wake) {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LockDiag.mark(this, "shown")
        applyWake(intent)
        setLandscape(chargingLandscape())
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        ContextCompat.registerReceiver(
            this,
            unlockReceiver,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                BackHandler { /* stay on the lock screen */ }
                LockScreen(
                    userLandscape = userLandscape,
                    onUserLandscape = { userLandscape = it },
                    onOrientation = { setLandscape(it) },
                    onUnlock = { unlock(null) },
                    onOpen = { pi -> unlock(pi) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyWake(intent)
        userLandscape = false
        setLandscape(chargingLandscape())
        LockDiag.mark(this, "shown")
    }

    private fun unlock(after: PendingIntent?) {
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) {
            runCatching { after?.send() }
            closeNow()
            return
        }
        km.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    runCatching { after?.send() }
                    closeNow()
                }
            },
        )
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(unlockReceiver) }
        super.onDestroy()
    }
}
