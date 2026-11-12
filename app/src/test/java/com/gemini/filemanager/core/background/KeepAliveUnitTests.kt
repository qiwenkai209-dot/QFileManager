package com.gemini.filemanager.core.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAliveUnitTests {

    @Test
    fun `evaluatePolicy holds power locks whenever leases active or strict mode enabled`() {
        val disabledIdle = AppBackgroundKeepAliveManager.evaluatePolicy(
            keepAliveEnabled = false,
            strictWakeLockEnabled = false,
            activeLeaseCount = 0
        )
        assertFalse(disabledIdle.shouldRunForegroundService)
        assertFalse(disabledIdle.shouldHoldPowerLocks)

        val residentIdle = AppBackgroundKeepAliveManager.evaluatePolicy(
            keepAliveEnabled = true,
            strictWakeLockEnabled = false,
            activeLeaseCount = 0
        )
        assertTrue(residentIdle.shouldRunForegroundService)
        assertFalse(residentIdle.shouldHoldPowerLocks)

        val strictResident = AppBackgroundKeepAliveManager.evaluatePolicy(
            keepAliveEnabled = true,
            strictWakeLockEnabled = true,
            activeLeaseCount = 0
        )
        assertTrue(strictResident.shouldRunForegroundService)
        assertTrue(strictResident.shouldHoldPowerLocks)

        // 即使关闭常驻开关，只要存在活跃传输/转码/扫盘租约，也必须激活前台保护与唤醒锁
        val activeTaskWithResidentOff = AppBackgroundKeepAliveManager.evaluatePolicy(
            keepAliveEnabled = false,
            strictWakeLockEnabled = false,
            activeLeaseCount = 2
        )
        assertTrue(activeTaskWithResidentOff.shouldRunForegroundService)
        assertTrue(activeTaskWithResidentOff.shouldHoldPowerLocks)
    }

    @Test
    fun `formatActiveReasonsSummary deduplicates and truncates long task lists`() {
        assertEquals("", AppBackgroundKeepAliveManager.formatActiveReasonsSummary(emptyList()))
        assertEquals("", AppBackgroundKeepAliveManager.formatActiveReasonsSummary(listOf("", "  ")))

        assertEquals(
            "SMB 文件传输",
            AppBackgroundKeepAliveManager.formatActiveReasonsSummary(listOf("SMB 文件传输", "SMB 文件传输"))
        )

        assertEquals(
            "SMB 文件传输 · M3U8 后台转码",
            AppBackgroundKeepAliveManager.formatActiveReasonsSummary(listOf("SMB 文件传输", "M3U8 后台转码"))
        )

        assertEquals(
            "SMB 文件传输 · M3U8 后台转码 等 3 项",
            AppBackgroundKeepAliveManager.formatActiveReasonsSummary(
                listOf("SMB 文件传输", "M3U8 后台转码", "LocalSend 发送文件")
            )
        )
    }
}
