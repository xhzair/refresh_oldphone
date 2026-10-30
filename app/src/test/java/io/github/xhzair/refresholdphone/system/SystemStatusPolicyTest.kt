package io.github.xhzair.refresholdphone.system

import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemStatusPolicyTest {
    @Test
    fun `battery percent handles invalid values and clamps valid calculations`() {
        assertEquals(0, calculateBatteryPercent(level = -1, scale = 100))
        assertEquals(0, calculateBatteryPercent(level = 50, scale = 0))
        assertEquals(50, calculateBatteryPercent(level = 1, scale = 2))
        assertEquals(100, calculateBatteryPercent(level = 150, scale = 100))
    }

    @Test
    fun `charging includes charging and full battery states only`() {
        assertTrue(isChargingStatus(BatteryManager.BATTERY_STATUS_CHARGING))
        assertTrue(isChargingStatus(BatteryManager.BATTERY_STATUS_FULL))
        assertFalse(isChargingStatus(BatteryManager.BATTERY_STATUS_DISCHARGING))
        assertFalse(isChargingStatus(BatteryManager.BATTERY_STATUS_UNKNOWN))
    }

    @Test
    fun `network label has stable transport precedence and offline fallback`() {
        assertEquals("Wi‑Fi", networkLabel(hasWifi = true, hasCellular = true, hasEthernet = true))
        assertEquals("移动网络", networkLabel(hasWifi = false, hasCellular = true, hasEthernet = true))
        assertEquals("以太网", networkLabel(hasWifi = false, hasCellular = false, hasEthernet = true))
        assertEquals("离线", networkLabel(hasWifi = false, hasCellular = false, hasEthernet = false))
    }
}
