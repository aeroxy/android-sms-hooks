package wtf.oops.aero.smshooks

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

/** What the app can find out about the phone it runs on. */
object Device {
    val isXiaomi: Boolean get() = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)
    val isHuawei: Boolean get() = Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true)

    /** Android's own connectivity check: the phone has a network that actually reaches the internet. */
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * The runtime permission and the app-op behind it: MIUI can deny the op while the permission
     * still reads "granted".
     */
    fun smsPermissionOk(context: Context): Boolean {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return false
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return true
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_RECEIVE_SMS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT
    }

    fun batteryOptimizationOff(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** Whether "remove permissions if app is unused" is off; null where Android lacks it (Android 10). */
    fun unusedAppResetOff(context: Context): Boolean? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) context.packageManager.isAutoRevokeWhitelisted else null

    const val MIUI_OP_AUTO_START = 10008
    const val MIUI_OP_READ_NOTIFICATION_SMS = 10018

    /**
     * Reads one of MIUI's own permission switches (自启动 = 10008, 通知类短信 = 10018) through a hidden
     * but callable AppOpsManager method, the same way Telegram does. Null when it can't be read.
     */
    fun miuiOpAllowed(context: Context, op: Int): Boolean? = try {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val check = AppOpsManager::class.java.getMethod(
            "checkOpNoThrow",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
        )
        (check.invoke(ops, op, Process.myUid(), context.packageName) as Int) == AppOpsManager.MODE_ALLOWED
    } catch (e: Throwable) {
        null
    }

    /** Which SIM slot an SMS_RECEIVED broadcast came in on (0-based), plus what was used to tell. */
    data class SimInfo(val slot: Int?, val subId: Int?, val extrasKeys: List<String>)

    fun simInfo(intent: Intent): SimInfo {
        val extras = intent.extras ?: return SimInfo(null, null, emptyList())
        val keys = extras.keySet().sorted()
        val subId = SUB_ID_KEYS.firstNotNullOfOrNull { intExtra(extras, it) }?.takeIf { it >= 0 }
        val slotFromSub = subId?.let { SubscriptionManager.getSlotIndex(it) }?.takeIf { it >= 0 }
        val slot = slotFromSub ?: SLOT_KEYS.firstNotNullOfOrNull { intExtra(extras, it)?.takeIf { s -> s >= 0 } }
        return SimInfo(slot, subId, keys)
    }

    private val SUB_ID_KEYS = listOf("android.telephony.extra.SUBSCRIPTION_INDEX", "subscription")
    private val SLOT_KEYS = listOf("android.telephony.extra.SLOT_INDEX", "slot", "phone", "simId")

    @Suppress("DEPRECATION")
    private fun intExtra(extras: Bundle, key: String): Int? = (extras.get(key) as? Number)?.toInt()
}
