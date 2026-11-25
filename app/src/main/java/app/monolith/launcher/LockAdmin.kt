package app.monolith.launcher

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

class LockAdmin : DeviceAdminReceiver()

fun lockScreenNow(ctx: Context) {
    val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val cn = ComponentName(ctx, LockAdmin::class.java)
    if (dpm.isAdminActive(cn)) {
        dpm.lockNow()
    } else {
        val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, cn)
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Lets Teddy's Launcher lock the screen when you double-tap home.")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
    }
}
