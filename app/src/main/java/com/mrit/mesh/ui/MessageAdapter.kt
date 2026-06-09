package com.mrit.mesh.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mrit.mesh.R
import com.mrit.mesh.mesh.MeshNode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MessageAdapter — displays the log of received mesh messages.
 * Newest messages appear at the bottom.
 */
class MessageAdapter : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private val messages = mutableListOf<MeshNode.IncomingMessage>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun add(message: MeshNode.IncomingMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount(): Int = messages.size

    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSenderId: TextView  = itemView.findViewById(R.id.tvSenderId)
        private val tvTime: TextView      = itemView.findViewById(R.id.tvTime)
        private val tvMessageText: TextView = itemView.findViewById(R.id.tvMessageText)

        fun bind(msg: MeshNode.IncomingMessage) {
            tvSenderId.text  = msg.from.shortId()
            tvTime.text      = timeFormat.format(Date(msg.timestampMs))
            tvMessageText.text = msg.text

            // Highlight SOS messages in red
            val textColor = if (msg.isSOS) 0xFFFF4444.toInt() else 0xFFDDDDDD.toInt()
            tvMessageText.setTextColor(textColor)
        }
    }
}
