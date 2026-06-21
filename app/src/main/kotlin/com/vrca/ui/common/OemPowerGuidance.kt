package com.vrca.ui.common

import android.os.Build

/**
 * Manufacturer-specific background/power-management guidance.
 *
 * The four foreground services + wakelock + WorkManager watchdog keep the
 * process alive on stock Android, but several OEMs ship proprietary "battery
 * savers" that kill background apps regardless of the standard battery-
 * optimization exemption. On those phones the ONLY reliable survival fix is
 * the user adding VRC-A to the OEM's allow-list (Autostart / Protected apps /
 * Never sleeping apps). No app code can override these killers — so we detect
 * the manufacturer and tell the user exactly where to look.
 *
 * Returns null on manufacturers without a known aggressive killer (stock
 * Android, Google Pixel, Motorola, etc.) so the UI shows nothing extra.
 */
object OemPowerGuidance {

    data class Guidance(
        /** Short brand label, e.g. "Samsung", "Xiaomi (MIUI)". */
        val brand: String,
        /** Step text shown to the user. No em dashes (matches onboarding copy rule). */
        val steps: String
    )

    fun forThisDevice(): Guidance? {
        val mfr = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return when {
            mfr.contains("samsung") -> Guidance(
                "Samsung",
                "Samsung's \"Sleeping apps\" feature pauses background apps even with the " +
                "battery exemption granted. To keep VRC-A running:\n" +
                "1. Settings > Battery > Background usage limits.\n" +
                "2. Add VRC-A to \"Never sleeping apps\".\n" +
                "3. Make sure VRC-A is NOT in \"Sleeping apps\" or \"Deep sleeping apps\".\n" +
                "4. Settings > Battery > turn OFF \"Put unused apps to sleep\" (optional, " +
                "strongest)."
            )
            mfr.contains("xiaomi") || mfr.contains("redmi") || mfr.contains("poco") ||
                brand.contains("redmi") || brand.contains("poco") -> Guidance(
                "Xiaomi (MIUI / HyperOS)",
                "MIUI kills background apps unless you grant Autostart and remove battery " +
                "restrictions:\n" +
                "1. Security app > Permissions > Autostart > enable VRC-A.\n" +
                "2. Settings > Apps > VRC-A > Battery saver > set to \"No restrictions\".\n" +
                "3. In Recents, pull down on the VRC-A card and tap the lock icon so it " +
                "isn't cleared.\n" +
                "4. Settings > Battery > turn off \"MIUI optimization\" if notifications " +
                "still stop."
            )
            mfr.contains("huawei") || mfr.contains("honor") || brand.contains("honor") -> Guidance(
                "Huawei / Honor",
                "Huawei's power manager force-stops apps unless you protect them:\n" +
                "1. Settings > Battery > App launch.\n" +
                "2. Find VRC-A, turn OFF \"Manage automatically\".\n" +
                "3. Enable all three: Auto-launch, Secondary launch, Run in background.\n" +
                "4. Settings > Battery > turn off \"Close apps after screen lock\" for VRC-A."
            )
            mfr.contains("oppo") || mfr.contains("realme") || brand.contains("realme") -> Guidance(
                "Oppo / Realme (ColorOS)",
                "ColorOS sleeps background apps aggressively:\n" +
                "1. Settings > Battery > VRC-A > Allow background activity / Allow auto-launch.\n" +
                "2. Settings > Apps > Startup manager > enable VRC-A.\n" +
                "3. In Recents, lock the VRC-A card so it isn't cleared."
            )
            mfr.contains("vivo") || mfr.contains("iqoo") || brand.contains("iqoo") -> Guidance(
                "Vivo / iQOO (Funtouch OS)",
                "Funtouch OS restricts background apps:\n" +
                "1. Settings > Battery > Background power consumption > allow VRC-A.\n" +
                "2. Settings > Apps > Autostart > enable VRC-A.\n" +
                "3. In Recents, lock the VRC-A card."
            )
            mfr.contains("oneplus") || brand.contains("oneplus") -> Guidance(
                "OnePlus (OxygenOS)",
                "OxygenOS has \"Advanced optimization\" that kills background apps:\n" +
                "1. Settings > Battery > VRC-A > set to \"Don't optimize\" / \"No restrictions\".\n" +
                "2. Settings > Battery > Battery optimization > Advanced optimization > turn it " +
                "off if music or notifications still stop.\n" +
                "3. In Recents, lock the VRC-A card so it isn't cleared."
            )
            else -> null
        }
    }
}
