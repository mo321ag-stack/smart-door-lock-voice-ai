package com.smartdoorlock.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bluetooth Classic SPP client for the HC-05 (RFCOMM socket, no BLE).
 *
 * Connection levels are kept separate:
 *   1. paired (bonded)                -> DeviceInfo.bonded
 *   2. RFCOMM socket open             -> ConnectionState.Connected
 *   3. ATmega32 answers the protocol  -> ConnectionState.Connected.mcuVerified
 *
 * One instance should live for the whole app process (see SmartDoorLockApp).
 */
class SppConnectionManager(context: Context, private val scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Last status token received from the ATmega32; null when unknown (not connected). */
    private val _lastStatus = MutableStateFlow<McuStatus?>(null)
    val lastStatus: StateFlow<McuStatus?> = _lastStatus.asStateFlow()

    private val _lastStatusTime = MutableStateFlow<Long?>(null)
    val lastStatusTime: StateFlow<Long?> = _lastStatusTime.asStateFlow()

    /** Every status token as it arrives (no replay). */
    private val _statusEvents = MutableSharedFlow<McuStatus>(extraBufferCapacity = 32)
    val statusEvents: SharedFlow<McuStatus> = _statusEvents.asSharedFlow()

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    /** Reconnect automatically after an unexpected link loss. */
    @Volatile
    var autoReconnect: Boolean = true

    private val lock = Any()
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var pendingSocket: BluetoothSocket? = null
    @Volatile private var currentDevice: DeviceInfo? = null
    @Volatile private var userInitiatedClose = false
    private var connectJob: Job? = null
    private var readerJob: Job? = null
    private val writeMutex = Mutex()

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (st == BluetoothAdapter.STATE_TURNING_OFF || st == BluetoothAdapter.STATE_OFF) {
                        onAdapterOff()
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val dev = IntentCompat.getParcelableExtra(
                        intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java,
                    )
                    val s = socket
                    if (dev != null && s != null && dev.address == currentDevice?.address) {
                        onLinkLost(s, BtError.SOCKET_CLOSED, "Bluetooth ACL link disconnected")
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(appContext, systemReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    val isSupported: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true
    val isConnected: Boolean get() = _state.value is ConnectionState.Connected

    // ------------------------------------------------------------------
    // Connect / disconnect
    // ------------------------------------------------------------------

    /** Opens an RFCOMM connection to a paired device. Progress is reported via [state]. */
    fun connect(address: String) {
        connectJob?.cancel()
        closeCurrentSocket()
        userInitiatedClose = false
        connectJob = scope.launch { runConnect(address, quiet = false) }
    }

    /** User-requested disconnect. Never triggers auto-reconnect. */
    fun disconnect() {
        userInitiatedClose = true
        connectJob?.cancel()
        connectJob = null
        pendingSocket?.let { runCatching { it.close() } }
        closeCurrentSocket()
        _lastStatus.value = null
        _state.value = ConnectionState.Disconnected
        log(LogEntry.Kind.INFO, "Disconnected by user")
    }

    /**
     * @param quiet true during auto-reconnect: failures are logged but the
     *              Reconnecting state is left in place for the retry loop.
     * @return true if the RFCOMM socket was opened.
     */
    @SuppressLint("MissingPermission")
    private suspend fun runConnect(address: String, quiet: Boolean): Boolean {
        val bt = adapter ?: return fail(BtError.NOT_SUPPORTED, null, null, quiet)
        if (!BluetoothPermissions.hasConnect(appContext)) return fail(BtError.PERMISSION_DENIED, null, null, quiet)
        if (!bt.isEnabled) return fail(BtError.DISABLED, null, null, quiet)
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            return fail(BtError.DEVICE_UNAVAILABLE, "Invalid address $address", null, quiet)
        }

        val device = try {
            bt.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            return fail(BtError.DEVICE_UNAVAILABLE, e.message, null, quiet)
        }
        val info = device.toInfo()
        if (!info.bonded) return fail(BtError.NOT_PAIRED, null, info, quiet)

        currentDevice = info
        if (!quiet) _state.value = ConnectionState.Connecting(info)
        log(LogEntry.Kind.INFO, "Opening RFCOMM to ${info.name} (${info.address})")

        val s = try {
            openSocket(device)
        } catch (e: ConnectTimeoutException) {
            return fail(BtError.TIMEOUT, "No answer within ${SppConstants.CONNECT_TIMEOUT_MS / 1000} s", info, quiet)
        } catch (e: SecurityException) {
            return fail(BtError.PERMISSION_DENIED, e.message, info, quiet)
        } catch (e: IOException) {
            return fail(BtError.CONNECTION_FAILED, e.message, info, quiet)
        }

        if (userInitiatedClose) {
            runCatching { s.close() }
            return false
        }

        synchronized(lock) { socket = s }
        _state.value = ConnectionState.Connected(info, mcuVerified = false)
        startReader(s)

        // Handshake: prove that the ATmega32 (not just the HC-05) is answering.
        val reply = sendAndAwait(McuCommand.STATUS, SppConstants.HANDSHAKE_TIMEOUT_MS)
        if (reply == null && socket === s) {
            _state.value = ConnectionState.Connected(info, mcuVerified = false, warning = BtError.NO_MCU_RESPONSE)
            log(LogEntry.Kind.ERROR, BtError.NO_MCU_RESPONSE.message)
        }
        return true
    }

    private fun fail(error: BtError, detail: String?, device: DeviceInfo?, quiet: Boolean): Boolean {
        log(LogEntry.Kind.ERROR, error.message + (detail?.let { " ($it)" } ?: ""))
        if (!quiet) _state.value = ConnectionState.Failed(error, detail, device)
        return false
    }

    private class ConnectTimeoutException : IOException("RFCOMM connect timeout")

    /**
     * Blocking RFCOMM connect on an IO thread, with a watchdog that closes the
     * socket after CONNECT_TIMEOUT_MS (BluetoothSocket.connect() has no timeout
     * parameter). Tries the secure SPP socket first, then the insecure one,
     * which some HC-05 firmware versions need.
     */
    @SuppressLint("MissingPermission")
    private suspend fun openSocket(device: BluetoothDevice): BluetoothSocket = withContext(Dispatchers.IO) {
        runCatching { adapter?.cancelDiscovery() } // discovery slows down / breaks RFCOMM connect

        val factories: List<Pair<String, () -> BluetoothSocket>> = listOf(
            "secure" to { device.createRfcommSocketToServiceRecord(SppConstants.SPP_UUID) },
            "insecure" to { device.createInsecureRfcommSocketToServiceRecord(SppConstants.SPP_UUID) },
        )
        var lastError: IOException? = null

        for ((label, factory) in factories) {
            ensureActive()
            val s = factory()
            pendingSocket = s
            val timedOut = AtomicBoolean(false)
            val watchdog = launch {
                delay(SppConstants.CONNECT_TIMEOUT_MS)
                timedOut.set(true)
                runCatching { s.close() }
            }
            try {
                s.connect()
                watchdog.cancel()
                pendingSocket = null
                log(LogEntry.Kind.INFO, "RFCOMM socket open ($label)")
                return@withContext s
            } catch (e: IOException) {
                watchdog.cancel()
                pendingSocket = null
                runCatching { s.close() }
                if (timedOut.get()) throw ConnectTimeoutException()
                log(LogEntry.Kind.INFO, "RFCOMM $label attempt failed: ${e.message}")
                lastError = e
            }
        }
        throw lastError ?: IOException("RFCOMM connect failed")
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    private fun startReader(s: BluetoothSocket) {
        readerJob?.cancel()
        readerJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(256)
            val assembler = LineAssembler()
            try {
                val input = s.inputStream
                while (isActive) {
                    val n = input.read(buf)
                    if (n < 0) throw IOException("End of stream")
                    assembler.feed(buf, n).forEach { handleLine(it) }
                }
            } catch (e: IOException) {
                onLinkLost(s, BtError.SOCKET_CLOSED, e.message)
            }
        }
    }

    private fun handleLine(line: String) {
        log(LogEntry.Kind.RX, line)
        val status = McuStatus.parse(line)
        if (status == null) {
            log(LogEntry.Kind.INFO, "Ignored unknown line from MCU")
            return
        }
        _lastStatus.value = status
        _lastStatusTime.value = System.currentTimeMillis()
        _state.update { st ->
            if (st is ConnectionState.Connected && !st.mcuVerified) st.copy(mcuVerified = true, warning = null) else st
        }
        _statusEvents.tryEmit(status)
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /** Sends one command followed by '\n'. Returns false if not connected or the write failed. */
    suspend fun send(command: McuCommand): Boolean {
        val s = socket
        if (s == null) {
            log(LogEntry.Kind.ERROR, "Cannot send ${command.wire}: not connected")
            return false
        }
        return try {
            withContext(Dispatchers.IO) {
                writeMutex.withLock {
                    s.outputStream.write(command.toBytes())
                    s.outputStream.flush()
                }
            }
            log(LogEntry.Kind.TX, command.wire)
            true
        } catch (e: IOException) {
            onLinkLost(s, BtError.WRITE_FAILED, e.message)
            false
        }
    }

    /**
     * Sends [command] and waits for the next status token from the MCU.
     * Returns null if sending failed or no answer came within [timeoutMs].
     */
    suspend fun sendAndAwait(
        command: McuCommand,
        timeoutMs: Long = SppConstants.RESPONSE_TIMEOUT_MS,
    ): McuStatus? = coroutineScope {
        // Subscribe BEFORE sending so a fast reply cannot be missed.
        val reply = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(timeoutMs) { statusEvents.first() }
        }
        if (!send(command)) {
            reply.cancel()
            null
        } else {
            reply.await()
        }
    }

    // ------------------------------------------------------------------
    // Link loss and reconnection
    // ------------------------------------------------------------------

    private fun onLinkLost(s: BluetoothSocket, error: BtError, detail: String?) {
        synchronized(lock) {
            if (socket !== s) return // already handled, or replaced by a newer socket
            socket = null
        }
        runCatching { s.close() }
        _lastStatus.value = null // door state is unknown without a link
        if (userInitiatedClose) return

        log(LogEntry.Kind.ERROR, error.message + (detail?.let { " ($it)" } ?: ""))
        val dev = currentDevice
        _state.value = ConnectionState.Failed(error, detail, dev)
        if (autoReconnect && dev != null && error != BtError.DISABLED) {
            startReconnect(dev.address)
        }
    }

    private fun startReconnect(address: String) {
        connectJob?.cancel()
        connectJob = scope.launch {
            for (attempt in 1..SppConstants.MAX_RECONNECT_ATTEMPTS) {
                _state.value = ConnectionState.Reconnecting(currentDevice, attempt, SppConstants.MAX_RECONNECT_ATTEMPTS)
                delay(SppConstants.RECONNECT_BASE_DELAY_MS * attempt)
                if (userInitiatedClose) return@launch
                log(LogEntry.Kind.INFO, "Reconnect attempt $attempt/${SppConstants.MAX_RECONNECT_ATTEMPTS}")
                if (runConnect(address, quiet = true)) return@launch
            }
            _state.value = ConnectionState.Failed(
                BtError.SOCKET_CLOSED,
                "Reconnect failed after ${SppConstants.MAX_RECONNECT_ATTEMPTS} attempts",
                currentDevice,
            )
        }
    }

    private fun onAdapterOff() {
        val s = socket
        if (s != null) {
            onLinkLost(s, BtError.DISABLED, null)
        } else if (_state.value is ConnectionState.Connecting || _state.value is ConnectionState.Reconnecting) {
            connectJob?.cancel()
            pendingSocket?.let { runCatching { it.close() } }
            _state.value = ConnectionState.Failed(BtError.DISABLED, null, currentDevice)
        }
    }

    private fun closeCurrentSocket() {
        val s = synchronized(lock) { socket.also { socket = null } }
        runCatching { s?.close() }
        readerJob?.cancel()
        readerJob = null
    }

    // ------------------------------------------------------------------

    fun clearLog() {
        _log.value = emptyList()
    }

    private fun log(kind: LogEntry.Kind, text: String) {
        val entry = LogEntry(System.currentTimeMillis(), kind, text)
        _log.update { (it + entry).takeLast(SppConstants.LOG_CAPACITY) }
    }
}
