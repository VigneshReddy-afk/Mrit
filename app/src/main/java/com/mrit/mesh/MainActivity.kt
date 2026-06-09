package com.mrit.mesh

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.mrit.mesh.core.MeshID
import com.mrit.mesh.databinding.ActivityMainBinding
import com.mrit.mesh.mesh.MeshNode
import com.mrit.mesh.service.MeshService
import com.mrit.mesh.ui.MessageAdapter
import com.mrit.mesh.ui.PeerAdapter
import kotlinx.coroutines.launch

/**
 * MainActivity — Phase 2
 *
 * What's new:
 *   - Live peer list (horizontal RecyclerView, updates via StateFlow)
 *   - Message log (vertical RecyclerView, auto-scrolls to newest)
 *   - Broadcast text message to all connected peers
 *   - Status bar shows peer count
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val peerAdapter    = PeerAdapter()
    private val messageAdapter = MessageAdapter()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startMeshService()
        } else {
            Toast.makeText(this, "Permissions required to discover nearby devices", Toast.LENGTH_LONG).show()
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerViews()
        setupSendButton()
        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        // Attach observers once the service (and its node) are running
        MeshService.node?.let { attachToNode(it) }
    }

    // ── UI setup ───────────────────────────────────────────────────────────────

    private fun setupRecyclerViews() {
        // Peers — horizontal chip list
        binding.rvPeers.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvPeers.adapter = peerAdapter

        // Messages — vertical, newest at bottom
        binding.rvMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.rvMessages.adapter = messageAdapter
    }

    private fun setupSendButton() {
        binding.btnSend.setOnClickListener { sendMessage() }

        // Also allow "Send" from keyboard
        binding.etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }
    }

    private fun sendMessage() {
        val node = MeshService.node ?: return
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty()) return

        node.sendMessage(to = MeshID.BROADCAST, text = text)

        // Show our own message in the log
        messageAdapter.add(
            MeshNode.IncomingMessage(
                from = node.ourId,
                text = "▶ $text"      // "▶" prefix = sent by us
            )
        )
        binding.rvMessages.scrollToPosition(messageAdapter.itemCount - 1)
        binding.etMessage.setText("")
    }

    // ── Observing MeshNode ────────────────────────────────────────────────────

    private fun attachToNode(node: MeshNode) {
        // Display our short node ID
        binding.tvNodeId.text = "NODE: ${node.ourId.shortId()}"

        // Observe connected peers
        lifecycleScope.launch {
            node.peers.collect { peerList ->
                peerAdapter.submitList(peerList)
                val count = peerList.size
                binding.tvStatus.text = when (count) {
                    0 -> "● Scanning for peers…"
                    1 -> "● 1 peer connected"
                    else -> "● $count peers connected"
                }
            }
        }

        // Observe incoming messages
        lifecycleScope.launch {
            node.incomingMessages.collect { msg ->
                messageAdapter.add(msg)
                binding.rvMessages.scrollToPosition(messageAdapter.itemCount - 1)
            }
        }
    }

    // ── Permissions ────────────────────────────────────────────────────────────

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
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
        if (missing.isEmpty()) startMeshService() else permissionLauncher.launch(missing.toTypedArray())
    }

    // ── Service ────────────────────────────────────────────────────────────────

    private fun startMeshService() {
        val intent = MeshService.startIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        // Node may not be ready yet — onResume will attach when it is
    }
}
