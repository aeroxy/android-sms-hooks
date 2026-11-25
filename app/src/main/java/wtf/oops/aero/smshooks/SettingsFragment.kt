package wtf.oops.aero.smshooks

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.InputType
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import wtf.oops.aero.smshooks.core.Verdict
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * The main screen. On top, 设置检查 (setup check): one row per thing that can stop forwarding, each
 * re-checked whenever the app comes back and opening the screen that fixes it. Below, the settings.
 */
class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var prefs: Prefs
    private val mainThread = Handler(Looper.getMainLooper())
    private val onPrefsChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshChecks() }

    private val askSmsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) open(miuiPermissionEditor(), appDetails())
            refreshChecks()
        }

    private val exportLog =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) writeLog(uri)
        }

    /** One row of the setup check. [ok] = false counts towards "还有 N 项需要处理". */
    private class Row(val title: String, val summary: String, val ok: Boolean, val onClick: (() -> Unit)?)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        prefs = Prefs(requireContext())
        setUpForwardingPrefs()
        findPreference<Preference>("exportLogs")?.setOnPreferenceClickListener {
            exportLog.launch("smshooks_log.txt")
            true
        }
        findPreference<Preference>("version")?.summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})"
    }

    override fun onResume() {
        super.onResume()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .registerOnSharedPreferenceChangeListener(onPrefsChanged)
        refreshChecks()
    }

    override fun onPause() {
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .unregisterOnSharedPreferenceChangeListener(onPrefsChanged)
        super.onPause()
    }

    // ---- Settings ----

    private fun setUpForwardingPrefs() {
        findPreference<EditTextPreference>(Prefs.KEY_URL)?.apply {
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                p.text?.takeIf { it.isNotBlank() } ?: getString(R.string.not_set)
            }
            setOnPreferenceChangeListener { pref, value ->
                val typed = (value as String).trim()
                val url = normalizeUrl(typed)
                when {
                    typed.isEmpty() -> true
                    url == null -> {
                        toast(R.string.invalid_url)
                        false
                    }
                    url != value -> {
                        (pref as EditTextPreference).text = url
                        false
                    }
                    else -> true
                }
            }
        }
        findPreference<EditTextPreference>(Prefs.KEY_SECRET)?.apply {
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                getString(if (p.text.isNullOrEmpty()) R.string.not_set else R.string.is_set)
            }
        }
        listOf(Prefs.KEY_SIM1, Prefs.KEY_SIM2).forEach { key ->
            findPreference<EditTextPreference>(key)?.summaryProvider =
                Preference.SummaryProvider<EditTextPreference> { p ->
                    p.text?.takeIf { it.isNotBlank() } ?: getString(R.string.optional)
                }
        }
    }

    /** Adds https:// when no scheme was typed; accepts only https URLs with a host. */
    private fun normalizeUrl(typed: String): String? {
        val withScheme = if ("://" in typed) typed else "https://$typed"
        val url = withScheme.toHttpUrlOrNull() ?: return null
        return if (url.isHttps && url.host.isNotEmpty()) withScheme else null
    }

    // ---- Setup check ----

    private fun refreshChecks() {
        val ctx = context ?: return
        val category = findPreference<PreferenceCategory>("checks") ?: return
        val rows = buildRows(ctx)
        val todo = rows.count { !it.ok }

        category.removeAll()
        val summary = if (todo == 0) {
            Row(getString(R.string.summary_all_ok), "", ok = true, onClick = null)
        } else {
            Row(getString(R.string.summary_todo, todo), getString(R.string.check_tap_to_fix), ok = false, onClick = null)
        }
        category.addPreference(preferenceOf(ctx, summary))
        rows.forEach { category.addPreference(preferenceOf(ctx, it)) }
        category.addPreference(preferenceOf(ctx, lastSmsRow()))
    }

    private fun buildRows(ctx: Context): List<Row> = buildList {
        add(serverRow())
        add(
            Row(
                getString(R.string.check_sms),
                if (Device.smsPermissionOk(ctx)) getString(R.string.check_allowed) else getString(R.string.check_sms_todo),
                Device.smsPermissionOk(ctx),
            ) { onSmsPermissionClick(ctx) },
        )
        add(
            Row(
                getString(R.string.check_battery),
                getString(if (Device.batteryOptimizationOff(ctx)) R.string.check_off else R.string.check_battery_todo),
                Device.batteryOptimizationOff(ctx),
            ) { open(batteryExemptionRequest(ctx), Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails()) },
        )
        Device.unusedAppResetOff(ctx)?.let { off ->
            add(
                Row(
                    getString(R.string.check_unused),
                    getString(if (off) R.string.check_off else R.string.check_unused_todo),
                    off,
                ) { open(unusedAppSettings(ctx), appDetails()) },
            )
        }
        if (Device.isXiaomi) {
            add(
                readableRow(
                    R.string.check_autostart,
                    Device.miuiOpAllowed(ctx, Device.MIUI_OP_AUTO_START),
                    R.string.check_on,
                    R.string.check_autostart_todo,
                    confirmKey = "miui_autostart",
                    steps = R.string.steps_miui_autostart,
                    settings = listOf(miuiAutostart(), appDetails()),
                ),
            )
            add(
                readableRow(
                    R.string.check_notification_sms,
                    Device.miuiOpAllowed(ctx, Device.MIUI_OP_READ_NOTIFICATION_SMS),
                    R.string.check_allowed,
                    R.string.check_notification_sms_todo,
                    confirmKey = "miui_notification_sms",
                    steps = R.string.steps_miui_notification_sms,
                    settings = listOf(miuiPermissionEditor(), appDetails()),
                ),
            )
            add(confirmRow(R.string.check_miui_manual, "miui_manual", R.string.steps_miui, listOf(appDetails())))
        }
        if (Device.isHuawei) {
            add(
                confirmRow(
                    R.string.check_huawei_manual,
                    "huawei_manual",
                    R.string.steps_huawei,
                    listOf(huaweiAppLaunch(), appDetails()),
                ),
            )
        }
        add(testRow())
    }

    private fun serverRow(): Row {
        val summary = when {
            !prefs.configured -> getString(R.string.check_server_todo)
            !prefs.enabled -> getString(R.string.check_server_off)
            else -> getString(R.string.check_server_ok)
        }
        return Row(getString(R.string.check_server), summary, prefs.configured && prefs.enabled) { onServerRowClick() }
    }

    private fun onServerRowClick() {
        if (prefs.url.isEmpty()) {
            showDialogFor(Prefs.KEY_URL)
        } else if (prefs.secret.isEmpty()) {
            showDialogFor(Prefs.KEY_SECRET)
        } else if (!prefs.enabled) {
            findPreference<SwitchPreferenceCompat>(Prefs.KEY_ENABLED)?.isChecked = true
        }
    }

    /**
     * A setting the app reads directly. When this phone won't let it be read ([state] null), the row
     * works like a manual one instead: tap for the steps, then confirm with 已完成.
     */
    private fun readableRow(
        @StringRes title: Int,
        state: Boolean?,
        @StringRes okText: Int,
        @StringRes todoText: Int,
        confirmKey: String,
        @StringRes steps: Int,
        settings: List<Intent>,
    ): Row {
        if (state == null) return confirmRow(title, confirmKey, steps, settings, R.string.check_unreadable)
        return Row(getString(title), getString(if (state) okText else todoText), state) {
            open(*settings.toTypedArray())
        }
    }

    /** A setting no app can read: ⚠️ until confirmed in its steps dialog. */
    private fun confirmRow(
        @StringRes title: Int,
        key: String,
        @StringRes steps: Int,
        settings: List<Intent>,
        @StringRes todoText: Int = R.string.check_confirm_todo,
    ): Row {
        val confirmed = prefs.isConfirmed(key)
        return Row(getString(title), getString(if (confirmed) R.string.check_confirmed else todoText), confirmed) {
            showSteps(steps, settings) { prefs.setConfirmed(key) }
        }
    }

    private fun testRow(): Row {
        val title = getString(R.string.check_test)
        // Still pending, so it doesn't count as done.
        if (testInFlight) return Row(title, getString(R.string.check_test_running), ok = false, onClick = null)
        val last = prefs.lastTest() ?: return Row(title, getString(R.string.check_test_todo), ok = false) { runTest() }
        val label = last.verdict?.let { getString(it.labelRes()) }.orEmpty()
        val summary = if (last.verdict == Verdict.OK) {
            getString(R.string.status_line, time(last.time), label)
        } else {
            getString(R.string.status_line_detail, time(last.time), label, last.detail)
        }
        return Row(title, summary, last.verdict == Verdict.OK) { runTest() }
    }

    /** Not counted as a to-do: it can only be fixed by fixing the rows above. */
    private fun lastSmsRow(): Row {
        val title = getString(R.string.check_last_sms)
        val last = prefs.lastSms() ?: return Row(title, getString(R.string.check_last_sms_none), ok = true, onClick = null)
        val result = when {
            last.notSent -> getString(R.string.last_sms_not_sent)
            last.verdict == null -> getString(R.string.last_sms_pending)
            last.retrying -> getString(R.string.retrying_suffix, getString(last.verdict.labelRes()))
            else -> getString(last.verdict.labelRes())
        }
        val summary = getString(R.string.last_sms_line, time(last.time), last.receiver, last.from, result)
        return Row(title, summary, ok = true, onClick = null)
    }

    private fun preferenceOf(ctx: Context, row: Row) = Preference(ctx).apply {
        title = row.title
        summary = row.summary.ifEmpty { null }
        isIconSpaceReserved = false
        isPersistent = false
        isSelectable = row.onClick != null
        onPreferenceClickListener = row.onClick?.let { click ->
            Preference.OnPreferenceClickListener {
                click()
                true
            }
        }
    }

    // ---- Actions ----

    private fun onSmsPermissionClick(ctx: Context) {
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) open(miuiPermissionEditor(), appDetails()) else askSmsPermission.launch(Manifest.permission.RECEIVE_SMS)
    }

    private fun runTest() {
        val ctx = requireContext().applicationContext
        if (!prefs.configured) {
            toast(R.string.check_test_needs_setup)
            return
        }
        if (testInFlight) return
        testInFlight = true
        refreshChecks()
        thread {
            val outcome = Forwarder.send(ctx, Forwarder.testContent(), System.currentTimeMillis(), Forwarder.TEST_TIMEOUT_S)
            Forwarder.log(ctx, "test", outcome)
            mainThread.post {
                testInFlight = false
                // Saving notifies the preference listener, which refreshes whichever screen is showing.
                Prefs(ctx).saveTest(outcome)
            }
        }
    }

    private fun showSteps(@StringRes steps: Int, settings: List<Intent>, confirm: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setMessage(steps)
            .setNeutralButton(R.string.btn_open_settings) { _, _ -> open(*settings.toTypedArray()) }
            .setPositiveButton(R.string.btn_done) { _, _ ->
                confirm()
                refreshChecks()
            }
            .show()
    }

    private fun showDialogFor(key: String) {
        findPreference<EditTextPreference>(key)?.let { preferenceManager.showDialog(it) }
    }

    /** Starts the first of [intents] this phone has; ROM-specific screens fall back to app info. */
    private fun open(vararg intents: Intent) {
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                // Not on this phone, or not allowed: try the next one.
            }
        }
    }

    private fun writeLog(uri: Uri) {
        val ctx = requireContext()
        val log = EventLog.file(ctx)
        if (!log.exists()) {
            toast(R.string.no_log_yet)
            try {
                DocumentsContract.deleteDocument(ctx.contentResolver, uri)
            } catch (e: Exception) {
                // An empty file left behind is harmless.
            }
            return
        }
        val header = "SMS Hooks ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA}) · " +
            "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n"
        try {
            val stream = ctx.contentResolver.openOutputStream(uri) ?: throw IOException("no output stream")
            stream.use { out ->
                out.write(header.toByteArray())
                log.inputStream().use { it.copyTo(out) }
            }
        } catch (e: Exception) {
            toast(R.string.export_failed)
        }
    }

    // ---- Intents ----

    private fun pkg() = requireContext().packageName

    private fun appDetails() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg(), null))

    private fun batteryExemptionRequest(ctx: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))

    private fun unusedAppSettings(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, Uri.fromParts("package", ctx.packageName, null))
        } else {
            appDetails()
        }

    private fun miuiPermissionEditor() = Intent("miui.intent.action.APP_PERM_EDITOR")
        .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
        .putExtra("extra_pkgname", pkg())

    private fun miuiAutostart() = Intent()
        .setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")

    private fun huaweiAppLaunch() = Intent()
        .setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")

    // ---- Helpers ----

    private fun time(millis: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

    private fun toast(@StringRes text: Int) = Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()

    private companion object {
        /**
         * One test at a time, app-wide. It outlives a recreated screen, so a second test can't start
         * and finish out of order. Only touched on the main thread.
         */
        var testInFlight = false
    }
}

@StringRes
fun Verdict.labelRes(): Int = when (this) {
    Verdict.OK -> R.string.result_ok
    Verdict.CHANNEL_FAILED -> R.string.result_channel_failed
    Verdict.BAD_SECRET -> R.string.result_bad_secret
    Verdict.BAD_URL -> R.string.result_bad_url
    Verdict.SERVER_ERROR -> R.string.result_server_error
    Verdict.NETWORK_DOWN -> R.string.result_network_down
    Verdict.PHONE_OFFLINE -> R.string.result_phone_offline
}
