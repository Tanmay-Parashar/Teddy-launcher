package app.monolith.launcher

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppItem(
    val label: String,
    val pkg: String,
    val cls: String,
    val icon: ImageBitmap,
    val mono: ImageBitmap? = null,
    val glow: Int = 0xFF9FB4FF.toInt(),
)

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val pm: PackageManager = application.packageManager
    private val prefs = application.getSharedPreferences("teddy", Context.MODE_PRIVATE)

    var apps by mutableStateOf<List<AppItem>>(emptyList())
        private set

    var dockPkgs by mutableStateOf(
        prefs.getString("dock", null)?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    )
        private set

    /** 0 = white, 1 = black, 2 = red */
    var homePkgs by mutableStateOf(
        prefs.getString("home", null)?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    )
        private set

    var clockColor by mutableIntStateOf(prefs.getInt("clock_color", 0))
        private set

    /** index into the dim levels used by the UI (0 = none) */
    var dimLevel by mutableIntStateOf(prefs.getInt("dim", 0))
        private set

    var iconMono by mutableStateOf(prefs.getBoolean("icon_mono", false))
        private set

    var onboarded by mutableStateOf(prefs.getBoolean("onboarded", false))
        private set

    var showSettings by mutableStateOf(!prefs.getBoolean("onboarded", false))
        private set

    var resumeTick by mutableIntStateOf(0)
        private set

    var accent by mutableIntStateOf(0xFFFFFFFF.toInt())
        private set

    fun onResume() {
        resumeTick++
        refreshAccent()
    }

    fun refreshAccent() {
        if (Build.VERSION.SDK_INT < 27) return
        runCatching {
            val wm = android.app.WallpaperManager.getInstance(getApplication())
            val c = wm.getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM) ?: return
            val base = (c.primaryColor.toArgb())
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(base, hsv)
            hsv[1] = hsv[1].coerceIn(0.35f, 0.85f)
            hsv[2] = 1f
            accent = android.graphics.Color.HSVToColor(hsv)
        }
    }

    fun openSettings() {
        showSettings = true
    }

    fun closeSettings() {
        showSettings = false
        if (!onboarded) {
            onboarded = true
            prefs.edit().putBoolean("onboarded", true).apply()
        }
    }

    fun updateIconMono(v: Boolean) {
        iconMono = v
        prefs.edit().putBoolean("icon_mono", v).apply()
    }

    fun updateClockColor(i: Int) {
        clockColor = i
        prefs.edit().putInt("clock_color", i).apply()
    }

    fun updateDim(i: Int) {
        dimLevel = i
        prefs.edit().putInt("dim", i).apply()
    }

    fun cycleDim() {
        dimLevel = (dimLevel + 1) % 4
        prefs.edit().putInt("dim", dimLevel).apply()
    }

    var homeSignal by mutableIntStateOf(0)
        private set

    fun onHomePressed() {
        homeSignal++
    }

    fun cycleClockColor() {
        clockColor = (clockColor + 1) % 3
        prefs.edit().putInt("clock_color", clockColor).apply()
    }

    fun pin(pkg: String) {
        dockPkgs = (dockPkgs.filter { it != pkg } + pkg).takeLast(5)
        homePkgs = homePkgs.filter { it != pkg }
        saveDock()
    }

    fun addHome(pkg: String) {
        homePkgs = (homePkgs.filter { it != pkg } + pkg).takeLast(12)
        dockPkgs = dockPkgs.filter { it != pkg }
        saveDock()
    }

    fun unpin(pkg: String) {
        dockPkgs = dockPkgs.filter { it != pkg }
        homePkgs = homePkgs.filter { it != pkg }
        saveDock()
    }

    private fun saveDock() {
        prefs.edit()
            .putString("dock", dockPkgs.joinToString(","))
            .putString("home", homePkgs.joinToString(","))
            .apply()
    }

    fun refresh() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.Default) { loadApps() }
            apps = list
            if (prefs.getString("dock", null) == null && dockPkgs.isEmpty()) {
                val defaults = defaultDock().filter { p -> list.any { it.pkg == p } }
                if (defaults.isNotEmpty()) dockPkgs = defaults
            }
            if (prefs.getInt("dock_v", 0) < 2) {
                var d = dockPkgs
                if (d.size < 4) {
                    val cands = listOfNotNull(
                        resolvePkg(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)),
                        resolvePkg(Intent(MediaStore.ACTION_IMAGE_CAPTURE)),
                    ) + listOf(
                        "com.oplus.camera", "com.android.camera",
                        "com.google.android.apps.photos", "com.coloros.gallery3d",
                        "com.google.android.deskclock", "com.oplus.alarmclock",
                        "com.android.settings",
                    )
                    for (c in cands.distinct()) {
                        if (d.size >= 4) break
                        if (c !in d && list.any { it.pkg == c }) d = d + c
                    }
                    if (d != dockPkgs) {
                        dockPkgs = d
                        saveDock()
                    }
                }
                prefs.edit().putInt("dock_v", 2).apply()
            }
            if (prefs.getString("home", null) == null && homePkgs.isEmpty()) {
                val wanted = listOf(
                    "com.whatsapp", "com.google.android.youtube", "com.instagram.android",
                    "com.spotify.music", "com.google.android.gm", "com.google.android.apps.maps",
                    "com.google.android.apps.photos", "com.google.android.calendar",
                )
                val picks = wanted.filter { p -> list.any { it.pkg == p } && p !in dockPkgs }
                if (picks.isNotEmpty()) {
                    homePkgs = picks
                    saveDock()
                }
            }
        }
    }

    private fun loadApps(): List<AppItem> {
        val ownPkg = getApplication<Application>().packageName
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != ownPkg }
            .map { ri ->
                val d = ri.loadIcon(pm)
                val bmp = d.toBitmap(96, 96)
                AppItem(
                    label = ri.loadLabel(pm).toString(),
                    pkg = ri.activityInfo.packageName,
                    cls = ri.activityInfo.name,
                    icon = bmp.asImageBitmap(),
                    mono = monoIcon(d),
                    glow = dominantColor(bmp),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /** Saturation-weighted average colour; soft blue-white for greyscale icons. */
    private fun dominantColor(b: android.graphics.Bitmap): Int {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val hsv = FloatArray(3)
        var r = 0.0; var g = 0.0; var bl = 0.0; var wt = 0.0
        for (c in px) {
            if (android.graphics.Color.alpha(c) < 200) continue
            android.graphics.Color.colorToHSV(c, hsv)
            val weight = (hsv[1] * hsv[2]).toDouble()
            if (weight < 0.12) continue
            r += android.graphics.Color.red(c) * weight
            g += android.graphics.Color.green(c) * weight
            bl += android.graphics.Color.blue(c) * weight
            wt += weight
        }
        if (wt < 8.0) return 0xFF9FB4FF.toInt()
        val out = android.graphics.Color.rgb((r / wt).toInt(), (g / wt).toInt(), (bl / wt).toInt())
        android.graphics.Color.colorToHSV(out, hsv)
        hsv[1] = hsv[1].coerceAtLeast(0.55f)
        hsv[2] = hsv[2].coerceAtLeast(0.9f)
        return android.graphics.Color.HSVToColor(hsv)
    }

    private fun monoIcon(d: Drawable): ImageBitmap? {
        if (Build.VERSION.SDK_INT >= 33 && d is AdaptiveIconDrawable) {
            val m = d.monochrome ?: return null
            return m.toBitmap(128, 128).asImageBitmap()
        }
        return null
    }

    private fun resolvePkg(intent: Intent): String? {
        val ri = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return null
        val p = ri.activityInfo.packageName
        return if (p == "android") null else p
    }

    private fun defaultDock(): List<String> = listOfNotNull(
        resolvePkg(Intent(Intent.ACTION_DIAL)),
        resolvePkg(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)),
        resolvePkg(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))),
        resolvePkg(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)),
    ).distinct()
}

fun launchApp(ctx: Context, app: AppItem) {
    val i = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setClassName(app.pkg, app.cls)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    runCatching { ctx.startActivity(i) }
}

fun isDefaultHome(ctx: Context): Boolean {
    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val ri = ctx.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
    return ri?.activityInfo?.packageName == ctx.packageName
}
