package com.smartdoorlock.bluetooth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Runtime permissions needed for Bluetooth Classic, per Android version. */
object BluetoothPermissions {

    /** Needed for the paired list, pairing and RFCOMM connect. */
    fun connectPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyArray() // BLUETOOTH is an install-time permission before Android 12
        }

    /** Needed for Classic discovery (finding unpaired devices). */
    fun scanPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun allPermissions(): Array<String> = (connectPermissions() + scanPermissions()).distinct().toTypedArray()

    fun hasConnect(context: Context): Boolean = connectPermissions().all { granted(context, it) }

    fun hasScan(context: Context): Boolean = scanPermissions().all { granted(context, it) }

    fun missing(context: Context): List<String> = allPermissions().filterNot { granted(context, it) }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
