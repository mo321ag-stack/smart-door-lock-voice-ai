package com.smartdoorlock.app

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import com.smartdoorlock.bluetooth.ClassicDiscovery
import com.smartdoorlock.bluetooth.SppConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Holds the single Bluetooth connection for the whole process, so the link
 * survives screen changes and configuration changes.
 */
class SmartDoorLockApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val spp: SppConnectionManager by lazy { SppConnectionManager(this, appScope) }
    val discovery: ClassicDiscovery by lazy { ClassicDiscovery(this) }

    /** Remembers the last device address (not sensitive: it is broadcast by the HC-05). */
    var lastDeviceAddress: String?
        get() = prefs().getString(KEY_LAST_DEVICE, null)
        set(value) = prefs().edit { putString(KEY_LAST_DEVICE, value) }

    private fun prefs() = getSharedPreferences("connection", Context.MODE_PRIVATE)

    private companion object {
        const val KEY_LAST_DEVICE = "last_device_address"
    }
}
