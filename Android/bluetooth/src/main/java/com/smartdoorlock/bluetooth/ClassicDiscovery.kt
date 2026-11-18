package com.smartdoorlock.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Bluetooth Classic discovery (BluetoothAdapter.startDiscovery + ACTION_FOUND),
 * paired-device list and pairing (createBond). No BLE scanning is used.
 */
class ClassicDiscovery(context: Context) {

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private val _paired = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val paired: StateFlow<List<DeviceInfo>> = _paired.asStateFlow()

    private val _found = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val found: StateFlow<List<DeviceInfo>> = _found.asStateFlow()

    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()

    private val _bluetoothOn = MutableStateFlow(adapter?.isEnabled == true)
    val bluetoothOn: StateFlow<Boolean> = _bluetoothOn.asStateFlow()

    val isSupported: Boolean get() = adapter != null

    /** Last pairing outcome, for a one-line message in the UI. */
    private val _pairingMessage = MutableStateFlow<String?>(null)
    val pairingMessage: StateFlow<String?> = _pairingMessage.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.device() ?: return
                    val info = device.toInfo()
                    if (!info.isClassic) return // HC-05 is Classic; skip LE-only devices
                    _found.update { list -> list.filterNot { it.address == info.address } + info }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> _discovering.value = true
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _discovering.value = false
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.device() ?: return
                    val newState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    val prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                    val info = device.toInfo()
                    when {
                        newState == BluetoothDevice.BOND_BONDED ->
                            _pairingMessage.value = "Paired with ${info.name}"
                        newState == BluetoothDevice.BOND_NONE && prevState == BluetoothDevice.BOND_BONDING ->
                            _pairingMessage.value = BtError.PAIRING_FAILED.message
                    }
                    _found.update { list -> list.map { if (it.address == info.address) info else it } }
                    refreshPaired()
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    _bluetoothOn.value = st == BluetoothAdapter.STATE_ON
                    if (st != BluetoothAdapter.STATE_ON) _discovering.value = false
                    refreshPaired()
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Reloads the bonded-device list. HC-05 entries are listed first. */
    @SuppressLint("MissingPermission")
    fun refreshPaired(): BtError? {
        val bt = adapter ?: return BtError.NOT_SUPPORTED
        if (!BluetoothPermissions.hasConnect(appContext)) {
            _paired.value = emptyList()
            return BtError.PERMISSION_DENIED
        }
        if (!bt.isEnabled) {
            _paired.value = emptyList()
            return BtError.DISABLED
        }
        return try {
            _paired.value = bt.bondedDevices.orEmpty()
                .map { it.toInfo() }
                .filter { it.isClassic }
                .sortedWith(compareByDescending<DeviceInfo> { it.looksLikeHc05 }.thenBy { it.name })
            null
        } catch (e: SecurityException) {
            BtError.PERMISSION_DENIED
        }
    }

    /** Starts Classic discovery (~12 s). Returns an error, or null when started. */
    @SuppressLint("MissingPermission")
    fun start(): BtError? {
        val bt = adapter ?: return BtError.NOT_SUPPORTED
        if (!bt.isEnabled) return BtError.DISABLED
        if (!BluetoothPermissions.hasScan(appContext)) return BtError.PERMISSION_DENIED
        return try {
            if (bt.isDiscovering) bt.cancelDiscovery()
            _found.value = emptyList()
            if (bt.startDiscovery()) null else BtError.DISCOVERY_FAILED
        } catch (e: SecurityException) {
            BtError.PERMISSION_DENIED
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        runCatching { if (BluetoothPermissions.hasScan(appContext)) adapter?.cancelDiscovery() }
        _discovering.value = false
    }

    /** Starts pairing. Android shows its own PIN dialog (HC-05 default is often 1234 or 0000). */
    @SuppressLint("MissingPermission")
    fun pair(address: String): BtError? {
        val bt = adapter ?: return BtError.NOT_SUPPORTED
        if (!BluetoothPermissions.hasConnect(appContext)) return BtError.PERMISSION_DENIED
        return try {
            stop()
            _pairingMessage.value = null
            val device = bt.getRemoteDevice(address)
            if (device.bondState == BluetoothDevice.BOND_BONDED) return null
            if (device.createBond()) null else BtError.PAIRING_FAILED
        } catch (e: SecurityException) {
            BtError.PERMISSION_DENIED
        } catch (e: IllegalArgumentException) {
            BtError.DEVICE_UNAVAILABLE
        }
    }

    fun clearPairingMessage() {
        _pairingMessage.value = null
    }

    private fun Intent.device(): BluetoothDevice? =
        IntentCompat.getParcelableExtra(this, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
}
