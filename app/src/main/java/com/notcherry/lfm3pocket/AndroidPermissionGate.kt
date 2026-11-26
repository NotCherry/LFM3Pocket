package com.notcherry.lfm3pocket

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import java.net.URI
import java.util.Locale

/**
 * Centralized, SDK-versioned Android 12-17 permission and compatibility policy (ADR 0026 / ADR 0027).
 *
 * Pure policy methods accept explicit {@code sdkInt} parameters so JVM unit tests can prove behavior
 * across Android 8-17 (API 26-37) without an emulator.
 */
object AndroidPermissionGate {
    const val API_NOTIFICATIONS_RUNTIME = 33
    const val API_FGS_TYPES_REQUIRED = 34
    const val API_EDGE_TO_EDGE_ENFORCED = 35
    const val API_PREDICTIVE_BACK_DEFAULT = 36
    const val API_LOCAL_NETWORK_PERMISSION = 37

    const val MEMORY_LIMITER_MARKER = "MemoryLimiter:AnonSwap"
    const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    @JvmStatic
    fun requiresRuntimeNotificationPermission(sdkInt: Int): Boolean =
        sdkInt >= API_NOTIFICATIONS_RUNTIME

    @JvmStatic
    fun requiresDynamicReceiverExportFlag(sdkInt: Int): Boolean =
        sdkInt >= API_NOTIFICATIONS_RUNTIME

    @JvmStatic
    fun requiresExplicitForegroundServiceType(sdkInt: Int): Boolean =
        sdkInt >= API_FGS_TYPES_REQUIRED

    @JvmStatic
    fun prefersUserInitiatedDownloadJob(sdkInt: Int): Boolean =
        sdkInt >= API_FGS_TYPES_REQUIRED

    /** Android 13+ locks sideloaded apps out of NotificationListenerService until Restricted Settings are allowed. */
    @JvmStatic
    fun hasRestrictedSideloadNotificationSettings(sdkInt: Int): Boolean =
        sdkInt >= API_NOTIFICATIONS_RUNTIME

    /** Android 15 (API 35) deprecated and disabled setStatusBarColor/setNavigationBarColor under edge-to-edge. */
    @JvmStatic
    fun usesLegacySystemBarColorApis(sdkInt: Int): Boolean =
        sdkInt < API_EDGE_TO_EDGE_ENFORCED

    /** Android 17 (API 37) enforces ACCESS_LOCAL_NETWORK for apps targeting API 37+. */
    @JvmStatic
    fun requiresLocalNetworkPermission(sdkInt: Int, targetSdk: Int): Boolean =
        sdkInt >= API_LOCAL_NETWORK_PERMISSION && targetSdk >= API_LOCAL_NETWORK_PERMISSION

    /**
     * Computes the foreground service type mask for {@link ModelRuntimeService}.
     * On API 34+, {@code FOREGROUND_SERVICE_TYPE_MICROPHONE} is only included when {@code RECORD_AUDIO}
     * is granted, preventing Android 14+ {@link SecurityException} crashes.
     */
    @JvmStatic
    fun computeRuntimeFgsTypeMask(
        sdkInt: Int,
        mediaPlayback: Boolean,
        microphoneCapture: Boolean,
        recordAudioGranted: Boolean,
    ): Int {
        if (!requiresExplicitForegroundServiceType(sdkInt)) return 0
        var mask = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (mediaPlayback) {
            mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        if (microphoneCapture && recordAudioGranted) {
            mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return mask
    }

    /** Detects Android 17 per-app memory limiter exits in {@code ApplicationExitInfo.getDescription()}. */
    @JvmStatic
    fun isAndroid17MemoryLimiterExit(description: String?): Boolean =
        description != null && description.contains(MEMORY_LIMITER_MARKER)

    /**
     * Returns true when {@code rawHost} points at a Local Area Network address subject to Android 17
     * {@code ACCESS_LOCAL_NETWORK} protections (RFC1918, link-local, CGNAT, IPv6 ULA/link-local, or
     * {@code .local} mDNS names). Same-device loopback ({@code 127.0.0.1}, {@code localhost}, {@code ::1})
     * returns false because Android 17 exempts same-profile loopback.
     */
    @JvmStatic
    fun isLanHost(rawHost: String?): Boolean {
        if (rawHost.isNullOrBlank()) return false
        val host = rawHost.trim().trimStart('[').trimEnd(']').lowercase(Locale.ROOT)
        if (host.isEmpty()) return false
        if (host == "localhost" || host == "::1" || host.startsWith("127.")) return false
        if (host.endsWith(".local")) return true

        val ipv4 = parseIpv4(host)
        if (ipv4 != null) {
            val a = ipv4[0]
            val b = ipv4[1]
            // 10.0.0.0/8 (RFC1918)
            if (a == 10) return true
            // 172.16.0.0/12 (RFC1918)
            if (a == 172 && b in 16..31) return true
            // 192.168.0.0/16 (RFC1918)
            if (a == 192 && b == 168) return true
            // 169.254.0.0/16 (Link-local)
            if (a == 169 && b == 254) return true
            // 100.64.0.0/10 (CGNAT)
            if (a == 100 && b in 64..127) return true
            // 224.0.0.0/4 (Multicast) and 255.255.255.255 (Broadcast)
            if (a in 224..239 || (a == 255 && b == 255 && ipv4[2] == 255 && ipv4[3] == 255)) return true
            return false
        }

        // IPv6 link-local (fe80::/10), ULA (fc00::/7), multicast (ff00::/8)
        if (host.contains(':')) {
            val firstGroup = host.substringBefore(':').padStart(4, '0')
            val prefix = firstGroup.toIntOrNull(16) ?: return false
            if ((prefix and 0xFFC0) == 0xFE80) return true // fe80::/10
            if ((prefix and 0xFE00) == 0xFC00) return true // fc00::/7
            if ((prefix and 0xFF00) == 0xFF00) return true // ff00::/8
        }
        return false
    }

    @JvmStatic
    fun isLanEndpointUrl(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        return try {
            isLanHost(URI(rawUrl.trim()).host)
        } catch (_: Exception) {
            false
        }
    }

    @JvmStatic
    fun hasPermission(context: Context?, permission: String): Boolean {
        if (context == null) return false
        return try {
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        } catch (_: RuntimeException) {
            false
        }
    }

    @JvmStatic
    fun hasContactsPermission(context: Context?): Boolean =
        hasPermission(context, Manifest.permission.READ_CONTACTS)

    @JvmStatic
    fun hasRecordAudioPermission(context: Context?): Boolean =
        hasPermission(context, Manifest.permission.RECORD_AUDIO)

    @JvmStatic
    fun hasCameraPermission(context: Context?): Boolean =
        hasPermission(context, Manifest.permission.CAMERA)

    @JvmStatic
    fun hasCalendarPermissions(context: Context?): Boolean =
        hasPermission(context, Manifest.permission.READ_CALENDAR) &&
            hasPermission(context, Manifest.permission.WRITE_CALENDAR)

    @JvmStatic
    fun hasNotificationPermission(context: Context?): Boolean {
        if (!requiresRuntimeNotificationPermission(Build.VERSION.SDK_INT)) return true
        return hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (i in 0..3) {
            val v = parts[i].toIntOrNull() ?: return null
            if (v !in 0..255) return null
            out[i] = v
        }
        return out
    }
}
