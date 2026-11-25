package app.monolith.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class LockSetupActivity : ComponentActivity() {

    private var tick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SetupScreen(tick)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        tick++
    }
}

private val Bg = Color(0xFF0A0D12)

@Composable
private fun SetupScreen(tick: Int) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("teddy", Context.MODE_PRIVATE) }
    val overlayOk = remember(tick) { Settings.canDrawOverlays(ctx) }
    val batteryOk = remember(tick) {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(ctx.packageName)
    }
    var enabled by remember { mutableStateOf(prefs.getBoolean("lock_enabled", false)) }
    val pkgUri = Uri.parse("package:${ctx.packageName}")

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "LOCK SCREEN",
            style = TextStyle(color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light, letterSpacing = 3.sp),
        )
        Text(
            "Shows the clock over your normal lock screen. " +
                "Your PIN or fingerprint still protects the phone.",
            style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp),
        )
        Spacer(Modifier.height(16.dp))

        StepRow(
            title = "1. Display over other apps",
            desc = "Needed to open the lock screen when you wake the phone.",
            done = overlayOk,
            action = "GRANT",
        ) { ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)) }

        StepRow(
            title = "2. Battery: unrestricted",
            desc = "OnePlus kills background apps. Also set Battery to Unrestricted and enable Auto-launch in App info.",
            done = batteryOk,
            action = "ALLOW",
        ) { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)) }

        Spacer(Modifier.height(12.dp))

        SwitchRow("Use custom lock screen", enabled) { v ->
            enabled = v
            prefs.edit().putBoolean("lock_enabled", v).apply()
            if (v) startLockService(ctx) else ctx.stopService(Intent(ctx, LockService::class.java))
        }

        Spacer(Modifier.height(12.dp))
        val dim = TextStyle(color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
        Text("STATUS", style = TextStyle(color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, letterSpacing = 2.sp))
        val running = remember(tick) { LockService.running }
        Text("Service: " + if (running) "running" else "not running", style = dim)
        Text("Screen-off last seen: " + remember(tick) { LockDiag.read(ctx, "off") }, style = dim)
        Text("Launch attempted: " + remember(tick) { LockDiag.read(ctx, "try") }, style = dim)
        Text("Lock screen last shown: " + remember(tick) { LockDiag.read(ctx, "shown") }, style = dim)

        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { ctx.startActivity(Intent(ctx, LockActivity::class.java)) }) {
            Text("PREVIEW LOCK SCREEN", color = Color.White, letterSpacing = 2.sp, fontSize = 12.sp)
        }
    }
}

@Composable
private fun StepRow(title: String, desc: String, done: Boolean, action: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium),
            )
            Text(desc, style = TextStyle(color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp))
        }
        if (done) {
            Text("DONE", style = TextStyle(color = Color(0xFF7BD88F), fontSize = 12.sp, letterSpacing = 2.sp))
        } else {
            TextButton(onClick = onClick) {
                Text(action, color = Color.White, letterSpacing = 2.sp, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = TextStyle(color = Color.White, fontSize = 15.sp),
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = Color.White,
                uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                uncheckedTrackColor = Color(0xFF2A2F38),
            ),
        )
    }
}
