package io.github.xhzair.refresholdphone.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat

class AndroidSystemStatusSource(context: Context) : SystemStatusSource {
    private val applicationContext = context.applicationContext
    private val connectivityManager =
        applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // All lifecycle/status state is guarded by this object's monitor. Framework callbacks
    // may reenter on this thread or arrive concurrently on a binder/handler thread.
    private var statusCallback: ((SystemStatus) -> Unit)? = null
    private var started = false
    private var generation = 0L
    private var registering = false
    private var batteryReceiverRegistered = false
    private var networkCallbackRegistered = false
    private var connectivityReceiverRegistered = false

    private var batteryPercent = 0
    private var charging = false
    private var networkLabel = OFFLINE_LABEL

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                updateBattery(intent)
            }
        }
    }

    private val connectivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ConnectivityManager.CONNECTIVITY_ACTION) {
                refreshNetworkStatus()
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refreshNetworkStatus()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            updateNetworkStatus(networkLabel(capabilities))
        }

        override fun onLost(network: Network) {
            refreshNetworkStatus()
        }

        override fun onUnavailable() {
            updateNetworkStatus(OFFLINE_LABEL)
        }
    }

    @Synchronized
    override fun start(onStatus: (SystemStatus) -> Unit) {
        statusCallback = onStatus
        if (started) {
            emitCurrentStatus()
            return
        }

        started = true
        val startupGeneration = ++generation
        runSystemStatusStartup(
            isActive = { started && generation == startupGeneration },
            ::registerBatteryReceiver,
            ::registerConnectivityObserver,
            ::refreshNetworkStatus,
            ::emitCurrentStatus,
        )
    }

    @Synchronized
    override fun stop() {
        statusCallback = null
        if (!started) return
        started = false
        generation++

        if (batteryReceiverRegistered) {
            try {
                applicationContext.unregisterReceiver(batteryReceiver)
            } catch (_: IllegalArgumentException) {
                // The framework may already have discarded the registration.
            } catch (_: SecurityException) {
                // Registration can become inaccessible after a permission or user-state change.
            } finally {
                batteryReceiverRegistered = false
            }
        }

        if (networkCallbackRegistered) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            } catch (_: IllegalArgumentException) {
                // The framework may already have discarded the registration.
            } catch (_: SecurityException) {
                // ACCESS_NETWORK_STATE can be revoked while the source is active.
            } finally {
                networkCallbackRegistered = false
            }
        }

        if (connectivityReceiverRegistered) {
            try {
                applicationContext.unregisterReceiver(connectivityReceiver)
            } catch (_: IllegalArgumentException) {
                // The framework may already have discarded the registration.
            } catch (_: SecurityException) {
                // Registration can become inaccessible after a permission or user-state change.
            } finally {
                connectivityReceiverRegistered = false
            }
        }
    }

    private fun registerBatteryReceiver() {
        registering = true
        val stickyBattery = try {
            ContextCompat.registerReceiver(
                applicationContext,
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } catch (_: SecurityException) {
            return
        } finally {
            registering = false
        }
        batteryReceiverRegistered = true
        // Deliver only after registration ownership is published, so a client may stop.
        stickyBattery?.let(::updateBattery)
    }

    private fun registerConnectivityObserver() {
        // Synchronous framework callbacks can update state, but must not invoke the
        // client until the corresponding registration has returned and is owned.
        registering = true
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(networkCallback)
                networkCallbackRegistered = true
            } else {
                @Suppress("DEPRECATION")
                ContextCompat.registerReceiver(
                    applicationContext,
                    connectivityReceiver,
                    IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                connectivityReceiverRegistered = true
            }
        } catch (_: SecurityException) {
            // Failed registrations never acquire ownership; battery updates remain available.
        } finally {
            registering = false
        }
    }

    @Synchronized
    private fun updateBattery(intent: Intent) {
        if (!started) return

        val nextPercent = calculateBatteryPercent(
            level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
        )
        val nextCharging = isChargingStatus(
            intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
        )
        if (nextPercent == batteryPercent && nextCharging == charging) return

        batteryPercent = nextPercent
        charging = nextCharging
        emitCurrentStatus()
    }

    @Synchronized
    private fun refreshNetworkStatus() {
        if (!started) return

        val nextLabel = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val activeNetwork = connectivityManager.activeNetwork
                networkLabel(connectivityManager.getNetworkCapabilities(activeNetwork))
            } else {
                @Suppress("DEPRECATION")
                legacyNetworkLabel(connectivityManager.activeNetworkInfo)
            }
        } catch (_: SecurityException) {
            OFFLINE_LABEL
        }
        updateNetworkStatus(nextLabel)
    }

    @Synchronized
    private fun updateNetworkStatus(nextLabel: String) {
        if (!started || nextLabel == networkLabel) return

        networkLabel = nextLabel
        emitCurrentStatus()
    }

    @Synchronized
    private fun emitCurrentStatus() {
        if (!started || registering) return
        statusCallback?.invoke(
            SystemStatus(
                batteryPercent = batteryPercent,
                charging = charging,
                networkLabel = networkLabel,
            ),
        )
    }

    @Suppress("DEPRECATION")
    private fun legacyNetworkLabel(networkInfo: NetworkInfo?): String {
        if (networkInfo?.isConnected != true) return OFFLINE_LABEL
        return when (networkInfo.type) {
            ConnectivityManager.TYPE_WIFI -> WIFI_LABEL
            ConnectivityManager.TYPE_MOBILE -> MOBILE_LABEL
            ConnectivityManager.TYPE_ETHERNET -> ETHERNET_LABEL
            else -> OFFLINE_LABEL
        }
    }
}

internal fun calculateBatteryPercent(level: Int, scale: Int): Int {
    if (level < 0 || scale <= 0) return 0
    return ((level.toLong() * 100L) / scale.toLong()).coerceIn(0L, 100L).toInt()
}

internal fun isChargingStatus(status: Int): Boolean =
    status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

internal fun networkLabel(
    hasWifi: Boolean,
    hasCellular: Boolean,
    hasEthernet: Boolean,
): String = when {
    hasWifi -> WIFI_LABEL
    hasCellular -> MOBILE_LABEL
    hasEthernet -> ETHERNET_LABEL
    else -> OFFLINE_LABEL
}

private fun networkLabel(capabilities: NetworkCapabilities?): String = networkLabel(
    hasWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
    hasCellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
    hasEthernet = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true,
)

private const val WIFI_LABEL = "Wi‑Fi"
private const val MOBILE_LABEL = "移动网络"
private const val ETHERNET_LABEL = "以太网"
private const val OFFLINE_LABEL = "离线"
