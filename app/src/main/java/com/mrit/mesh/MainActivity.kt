package com.mrit.mesh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mrit.mesh.core.MeshID
import com.mrit.mesh.databinding.ActivityMainBinding
import com.mrit.mesh.service.MeshService

/**
 * MainActivity — entry point for the MRIT mesh app.
 *
 * Responsibilities:
 *   1. Request all required runtime permissions
 *   2. Load (or generate) this device's MeshID
 *   3. Start the background MeshService
 *   4. Display basic node status to the user
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG       = "MainActivity"
        private const val PREFS_NAME = "mrit_prefs"
        private const val KEY_MESH_ID = "mesh_id_hex"
    }

    private lateinit var binding: ActivityMainBinding

    // ── Permission request launcher ────────────────────────────────────────────

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            Log.d(TAG, "All permissions granted — starting mesh service")
            startMeshService()
        } else {
            val denied = results.entries.filter { !it.value }.map { it.key }
            Log.w(TAG, "Denied: $denied")
            Toast.makeText(
                this,
                "MRIT needs these permissions to find nearby devices",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val meshId = loadOrCreateMeshId()
        displayNodeInfo(meshId)

        requestPermissionsIfNeeded()
    }

    // ── UI ─────────────────────────────────────────────────────────────────────

    private fun displayNodeInfo(meshId: MeshID) {
        binding.tvNodeId.text     = "Node ID: ${meshId.shortId()}"
        binding.tvNodeIdFull.text = meshId.toString()
        binding.tvStatus.text     = "Status: Starting…"
    }

    // ── Permissions ────────────────────────────────────────────────────────────

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_ADVERTISE
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        return perms.toTypedArray()
    }

    private fun requestPermissionsIfNeeded() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startMeshService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // ── Service ────────────────────────────────────────────────────────────────

    private fun startMeshService() {
        val intent = MeshService.startIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        binding.tvStatus.text = "Status: Mesh Active — scanning for peers"
        Log.d(TAG, "MeshService started")
    }

    // ── MeshID ─────────────────────────────────────────────────────────────────

    private fun loadOrCreateMeshId(): MeshID {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_MESH_ID, null)
        return if (stored != null) {
            MeshID.fromHex(stored)
        } else {
            MeshID.generate().also {
                prefs.edit().putString(KEY_MESH_ID, it.toString()).apply()
            }
        }
    }
}
