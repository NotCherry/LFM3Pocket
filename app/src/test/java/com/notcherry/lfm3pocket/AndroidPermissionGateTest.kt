package com.notcherry.lfm3pocket

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM unit tests for Android 12-17 permission and compatibility gates (ADR 0026 / ADR 0027). */
class AndroidPermissionGateTest {
    @Test
    fun sdkVersionGatesMatchAndroid12Through17Milestones() {
        // Android 8 (API 26) & Android 12 (API 31)
        for (api in listOf(26, 30, 31, 32)) {
            assertFalse(AndroidPermissionGate.requiresRuntimeNotificationPermission(api))
            assertFalse(AndroidPermissionGate.requiresDynamicReceiverExportFlag(api))
            assertFalse(AndroidPermissionGate.requiresExplicitForegroundServiceType(api))
            assertFalse(AndroidPermissionGate.prefersUserInitiatedDownloadJob(api))
            assertFalse(AndroidPermissionGate.hasRestrictedSideloadNotificationSettings(api))
            assertTrue(AndroidPermissionGate.usesLegacySystemBarColorApis(api))
            assertFalse(AndroidPermissionGate.requiresLocalNetworkPermission(api, 35))
        }

        // Android 13 (API 33)
        assertTrue(AndroidPermissionGate.requiresRuntimeNotificationPermission(33))
        assertTrue(AndroidPermissionGate.requiresDynamicReceiverExportFlag(33))
        assertTrue(AndroidPermissionGate.hasRestrictedSideloadNotificationSettings(33))
        assertFalse(AndroidPermissionGate.requiresExplicitForegroundServiceType(33))
        assertFalse(AndroidPermissionGate.prefersUserInitiatedDownloadJob(33))
        assertTrue(AndroidPermissionGate.usesLegacySystemBarColorApis(33))

        // Android 14 (API 34)
        assertTrue(AndroidPermissionGate.requiresExplicitForegroundServiceType(34))
        assertTrue(AndroidPermissionGate.prefersUserInitiatedDownloadJob(34))
        assertTrue(AndroidPermissionGate.usesLegacySystemBarColorApis(34))

        // Android 15 (API 35) & Android 16 (API 36)
        assertFalse(AndroidPermissionGate.usesLegacySystemBarColorApis(35))
        assertFalse(AndroidPermissionGate.usesLegacySystemBarColorApis(36))
        assertFalse(AndroidPermissionGate.requiresLocalNetworkPermission(36, 36))

        // Android 17 (API 37): enforced only when BOTH device SDK >= 37 and targetSdk >= 37
        assertFalse(AndroidPermissionGate.requiresLocalNetworkPermission(37, 35))
        assertFalse(AndroidPermissionGate.requiresLocalNetworkPermission(37, 36))
        assertFalse(AndroidPermissionGate.requiresLocalNetworkPermission(36, 37))
        assertTrue(AndroidPermissionGate.requiresLocalNetworkPermission(37, 37))
    }

    @Test
    fun runtimeFgsMaskRequiresRecordAudioBeforeIncludingMicrophoneType() {
        // Below API 34: legacy startForeground (mask 0)
        assertEquals(0, AndroidPermissionGate.computeRuntimeFgsTypeMask(33, true, true, true))

        // API 34+: SPECIAL_USE always set; MICROPHONE omitted when RECORD_AUDIO is denied
        val noMicPerm = AndroidPermissionGate.computeRuntimeFgsTypeMask(34, false, true, false)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, noMicPerm)

        // With RECORD_AUDIO granted + media playback active -> all three bits set
        val fullMask = AndroidPermissionGate.computeRuntimeFgsTypeMask(35, true, true, true)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            fullMask,
        )
    }

    @Test
    fun lanHostClassifierSeparatesLanFromLoopbackAndPublicInternet() {
        // Loopback (exempt on Android 17 same-profile)
        assertFalse(AndroidPermissionGate.isLanHost("127.0.0.1"))
        assertFalse(AndroidPermissionGate.isLanHost("127.0.1.1"))
        assertFalse(AndroidPermissionGate.isLanHost("localhost"))
        assertFalse(AndroidPermissionGate.isLanHost("::1"))
        assertFalse(AndroidPermissionGate.isLanEndpointUrl("http://127.0.0.1:8080/mcp"))

        // Public internet hosts
        assertFalse(AndroidPermissionGate.isLanHost("api.open-meteo.com"))
        assertFalse(AndroidPermissionGate.isLanHost("8.8.8.8"))
        assertFalse(AndroidPermissionGate.isLanHost("172.32.0.1"))
        assertFalse(AndroidPermissionGate.isLanEndpointUrl("https://github.com/NotCherry/LFM3Pocket"))

        // LAN RFC1918, link-local, CGNAT, mDNS .local, IPv6 link-local/ULA
        assertTrue(AndroidPermissionGate.isLanHost("192.168.1.42"))
        assertTrue(AndroidPermissionGate.isLanHost("10.0.0.5"))
        assertTrue(AndroidPermissionGate.isLanHost("172.16.0.1"))
        assertTrue(AndroidPermissionGate.isLanHost("172.31.255.254"))
        assertTrue(AndroidPermissionGate.isLanHost("169.254.10.20"))
        assertTrue(AndroidPermissionGate.isLanHost("100.64.1.2"))
        assertTrue(AndroidPermissionGate.isLanHost("searx.local"))
        assertTrue(AndroidPermissionGate.isLanHost("[fe80::1]"))
        assertTrue(AndroidPermissionGate.isLanHost("fd12:3456:789a::1"))
        assertTrue(AndroidPermissionGate.isLanEndpointUrl("http://192.168.0.10:8080/search"))
    }

    @Test
    fun detectsAndroid17MemoryLimiterExitMarker() {
        assertTrue(AndroidPermissionGate.isAndroid17MemoryLimiterExit("MemoryLimiter:AnonSwap limit=4096MB"))
        assertFalse(AndroidPermissionGate.isAndroid17MemoryLimiterExit("empty #31"))
        assertFalse(AndroidPermissionGate.isAndroid17MemoryLimiterExit(null))
    }

    @Test
    fun unattachedApplicationFailsClosedWithoutThrowing() {
        assertFalse(AndroidPermissionGate.hasContactsPermission(null))
        assertFalse(AndroidPermissionGate.hasContactsPermission(Lfm3PocketApplication()))
    }
}
