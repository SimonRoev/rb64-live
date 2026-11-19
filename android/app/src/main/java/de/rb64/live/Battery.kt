package de.rb64.live

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings

/**
 * Akku-Optimierung: Einige Hersteller (vor allem Samsung) legen Apps schlafen, die man ein paar Tage nicht oeffnet.
 * Dann kommen Push-Daten verspaetet, das Widget haengt, der Mitfahr-Vorschlag bleibt aus und Aufzeichnungen
 * koennen enden. Hier: pruefen, passende Einstellung oeffnen, Herstellerhinweis.
 */
object Battery {
    enum class Vendor { SAMSUNG, XIAOMI, HUAWEI, ONEPLUS, VIVO, OTHER }

    val vendor: Vendor = Build.MANUFACTURER.lowercase().let { m ->
        when {
            "samsung" in m -> Vendor.SAMSUNG
            "xiaomi" in m || "redmi" in m || "poco" in m -> Vendor.XIAOMI
            "huawei" in m || "honor" in m -> Vendor.HUAWEI
            "oneplus" in m || "oppo" in m || "realme" in m -> Vendor.ONEPLUS
            "vivo" in m || "iqoo" in m -> Vendor.VIVO
            else -> Vendor.OTHER
        }
    }

    /** Hersteller, die Hintergrund-Apps bekanntermassen hart einschraenken. */
    val aggressiveVendor get() = vendor != Vendor.OTHER

    /** Nimmt Android die App von der Akku-Optimierung aus? */
    fun unrestricted(ctx: Context): Boolean {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val am = ctx.getSystemService(ActivityManager::class.java)
        return pm.isIgnoringBatteryOptimizations(ctx.packageName) && !am.isBackgroundRestricted
    }

    /** Hat der Nutzer die App auf "Eingeschraenkt" gestellt? (Bei Pixel & Co. ist "Optimiert" normal und reicht.) */
    fun backgroundRestricted(ctx: Context) = ctx.getSystemService(ActivityManager::class.java).isBackgroundRestricted

    /** Karte/Eintrag zeigen? Bei auffaelligen Herstellern immer, sonst nur wenn wirklich eingeschraenkt. */
    fun relevant(ctx: Context) = aggressiveVendor || backgroundRestricted(ctx)

    /** Android-Dialog "Im Hintergrund ausfuehren lassen?", sonst Liste der Akku-Optimierung, sonst App-Info (der Reihe nach probieren). */
    fun requestIntents(ctx: Context): List<Intent> = listOf(
        Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
        Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        appInfo(ctx),
    )

    fun appInfo(ctx: Context) = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))

    /** Herstellerseite fuer den zweiten Schritt (z. B. Samsung "nie im Standby"); faellt auf die App-Info zurueck. */
    fun openVendorSettings(ctx: Context) {
        val tries = when (vendor) {
            Vendor.SAMSUNG -> listOf(
                Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")),
                Intent().setComponent(ComponentName("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity")),
            )
            Vendor.XIAOMI -> listOf(
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            )
            Vendor.HUAWEI -> listOf(
                Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
            )
            else -> emptyList()
        } + appInfo(ctx)
        for (i in tries) {
            if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

    /** Zweiter Schritt je Hersteller (null = keiner noetig). Pfade koennen je nach Version leicht abweichen. */
    fun vendorHint(): String? = when (vendor) {
        Vendor.SAMSUNG -> "Samsung: zusätzlich unter Einstellungen → Akku → Grenzwerte für Hintergrundnutzung " +
            "RB64 Live zu den Apps hinzufügen, die nie in den Standby gehen."
        Vendor.XIAOMI -> "Xiaomi: zusätzlich „Autostart“ erlauben und in der App-Info unter Akku „Keine Einschränkungen“ wählen."
        Vendor.HUAWEI -> "Huawei/Honor: unter Akku → App-Start RB64 Live auf „Manuell verwalten“ stellen und alle drei Schalter anlassen."
        Vendor.ONEPLUS -> "OnePlus/Oppo/Realme: in der App-Info unter Akku „Hintergrundaktivität zulassen“ einschalten."
        Vendor.VIVO -> "vivo: in der App-Info unter Akku „Hohe Hintergrundnutzung zulassen“ einschalten."
        Vendor.OTHER -> null
    }

    val vendorName: String get() = when (vendor) {
        Vendor.SAMSUNG -> "Samsung"; Vendor.XIAOMI -> "Xiaomi"; Vendor.HUAWEI -> "Huawei/Honor"
        Vendor.ONEPLUS -> "OnePlus/Oppo"; Vendor.VIVO -> "vivo"; Vendor.OTHER -> "Android"
    }
}
