package com.mrit.mesh.transport

import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.mrit.mesh.core.MeshID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.UUID

/**
 * BLETransport — Bluetooth Low Energy node discovery for the MRIT mesh.
 *
 * Role: Always-on, low-power neighbor discovery ONLY.
 *       Bulk data moves over WiFi Direct (WifiDirectTransport).
 *
 * How it works:
 *   ADVERTISE → broadcasts our MRIT service UUID so nearby nodes can detect us
 *   SCAN      → listens for other MRIT nodes via their service UUID
 *
 * When a nearby MRIT node is discovered, its Bluetooth address is emitted via
 * [discoveredNodes] so the WifiDirectTransport can establish a data connection.
 *
 * Range: ~100 m (BLE), low power drain (ADVERTISE_MODE_LOW_POWER).
 */
class BLETransport(
    private val context: Context,
    private val ourId: MeshID
) {

    companion object {
        private const val TAG = "BLETransport"

        /**
         * Service UUID that identifies ALL MRIT mesh nodes.
         * "4d524954" = ASCII hex for "MRIT"
         */
        val MRIT_SERVICE_UUID: UUID =
            UUID.fromString("4d524954-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothAdapter by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null

    /** Emits every newly discovered nearby MRIT node */
    private val _discoveredNodes = MutableSharedFlow<DiscoveredNode>(extraBufferCapacity = 32)
    val discoveredNodes: SharedFlow<DiscoveredNode> = _discoveredNodes

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is not enabled — BLE discovery unavailable")
            return
        }
        startAdvertising()
        startScanning()
        Log.d(TAG, "BLETransport started — our MeshID: ${ourId.shortId()}")
    }

    fun stop() {
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        Log.d(TAG, "BLETransport stopped")
    }

    // ── Advertising ───────────────────────────────────────────────────────────

    private fun startAdvertising() {
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.w(TAG, "This device does not support BLE peripheral mode")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setConnectable(true)
            .setTimeout(0)                               // Advertise indefinitely
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(MRIT_SERVICE_UUID))
            .setIncludeDeviceName(false)                 // Saves space in ad packet
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "BLE advertising active — visible as MRIT node ${ourId.shortId()}")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE advertising failed, errorCode=$errorCode")
        }
    }

    // ── Scanning ──────────────────────────────────────────────────────────────

    private fun startScanning() {
        scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "BLE scanner not available")
            return
        }

        // Only surface devices that advertise the MRIT service UUID
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MRIT_SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()

        scanner?.startScan(listOf(filter), settings, scanCallback)
        Log.d(TAG, "BLE scan started — watching for MRIT nodes")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val node = DiscoveredNode(
                bluetoothAddress = result.device.address,
                rssi = result.rssi
            )
            Log.d(TAG, "MRIT node found: addr=${node.bluetoothAddress} rssi=${node.rssi} dBm")
            _discoveredNodes.tryEmit(node)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed, errorCode=$errorCode")
        }
    }

    // ── Data model ────────────────────────────────────────────────────────────

    /**
     * A nearby MRIT node detected via BLE.
     *
     * @param bluetoothAddress MAC address — used to initiate WiFi Direct connection
     * @param rssi             Signal strength in dBm — used to estimate proximity
     */
    data class DiscoveredNode(
        val bluetoothAddress: String,
        val rssi: Int
    ) {
        /** Rough distance estimate based on RSSI (not precise — indicative only) */
        fun isClose(): Boolean = rssi > -70   // stronger than -70 dBm ≈ within ~30 m
    }
}
