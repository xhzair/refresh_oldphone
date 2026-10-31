package io.github.xhzair.refresholdphone.local

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

data class BluetoothOutput(
    val connected: Boolean,
    val deviceName: String?,
)

object BluetoothOutputSource {
    fun current(context: Context): BluetoothOutput {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return BluetoothOutput(false, null)
        val device = try {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        } catch (_: SecurityException) {
            null
        }
        return if (device == null) {
            BluetoothOutput(false, null)
        } else {
            BluetoothOutput(true, device.productName?.toString()?.takeIf(String::isNotBlank))
        }
    }
}
