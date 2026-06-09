package com.mrit.mesh.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mrit.mesh.R
import com.mrit.mesh.core.PeerInfo

/**
 * PeerAdapter — displays the live list of connected mesh peers.
 *
 * Laid out horizontally in a RecyclerView.
 * Each item shows the peer's short MeshID and their current IP address.
 */
class PeerAdapter : ListAdapter<PeerInfo, PeerAdapter.PeerViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_peer, parent, false)
        return PeerViewHolder(view)
    }

    override fun onBindViewHolder(holder: PeerViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class PeerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvPeerId: TextView = itemView.findViewById(R.id.tvPeerId)
        private val tvPeerIp: TextView = itemView.findViewById(R.id.tvPeerIp)

        fun bind(peer: PeerInfo) {
            tvPeerId.text = peer.meshId.shortId()
            tvPeerIp.text = peer.ipAddress
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<PeerInfo>() {
            override fun areItemsTheSame(a: PeerInfo, b: PeerInfo) = a.meshId == b.meshId
            override fun areContentsTheSame(a: PeerInfo, b: PeerInfo) = a == b
        }
    }
}
