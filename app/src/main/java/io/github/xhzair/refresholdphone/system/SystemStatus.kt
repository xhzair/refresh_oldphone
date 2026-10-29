package io.github.xhzair.refresholdphone.system

data class SystemStatus(
    val batteryPercent: Int = 0,
    val charging: Boolean = false,
    val networkLabel: String = "离线",
)

interface SystemStatusSource {
    fun start(onStatus: (SystemStatus) -> Unit)

    fun stop()
}
