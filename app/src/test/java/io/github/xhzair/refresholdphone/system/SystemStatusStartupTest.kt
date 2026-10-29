package io.github.xhzair.refresholdphone.system

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemStatusStartupTest {
    @Test
    fun `stopping during battery startup skips network refresh and emission`() {
        var active = true
        val calls = mutableListOf<String>()

        runSystemStatusStartup(
            isActive = { active },
            { calls += "battery"; active = false },
            { calls += "network" },
            { calls += "refresh" },
            { calls += "emit" },
        )

        assertEquals(listOf("battery"), calls)
    }

    @Test
    fun `active startup runs all steps in order`() {
        val calls = mutableListOf<String>()
        runSystemStatusStartup(
            isActive = { true },
            { calls += "battery" },
            { calls += "network" },
            { calls += "refresh" },
            { calls += "emit" },
        )
        assertEquals(listOf("battery", "network", "refresh", "emit"), calls)
    }

    @Test
    fun `inactive startup skips every step`() {
        val calls = mutableListOf<String>()
        runSystemStatusStartup(isActive = { false }, { calls += "battery" })
        assertEquals(emptyList<String>(), calls)
    }
}
