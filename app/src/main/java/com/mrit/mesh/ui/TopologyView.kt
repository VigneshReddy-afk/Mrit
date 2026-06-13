package com.mrit.mesh.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.mrit.mesh.core.PeerInfo
import com.mrit.mesh.routing.AODVRouter
import kotlin.math.cos
import kotlin.math.sin

/**
 * TopologyView — draws a live radial snapshot of the mesh.
 *
 * Our node sits at the center. Direct (1-hop) peers are placed on an inner ring,
 * each connected to the center with a solid line. Multi-hop destinations
 * (known via AODV routes where the next hop isn't the destination itself) are
 * placed on an outer ring, connected with a dashed line to the direct peer that
 * relays for them.
 */
class TopologyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var ourShortId: String = "--------"
    private var peers: List<PeerInfo> = emptyList()
    private var routes: List<AODVRouter.RouteSnapshot> = emptyList()

    private val selfPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.FILL
    }
    private val directPeerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF88")
        style = Paint.Style.FILL
    }
    private val remotePeerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3388FF")
        style = Paint.Style.FILL
    }
    private val directLinkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val routeLinkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#222222")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAAAAA")
        textSize = 22f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }

    /** Update the topology snapshot and request a redraw. */
    fun setData(ourShortId: String, peers: List<PeerInfo>, routes: List<AODVRouter.RouteSnapshot>) {
        this.ourShortId = ourShortId
        this.peers = peers
        this.routes = routes
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val centerX = w / 2f
        val centerY = h / 2f
        val nodeRadius = 22f

        if (peers.isEmpty()) {
            canvas.drawCircle(centerX, centerY, nodeRadius, selfPaint)
            canvas.drawText(ourShortId, centerX, centerY + nodeRadius + 28f, labelPaint)
            return
        }

        val directRadius = minOf(w, h) * 0.28f
        val remoteRadius = minOf(w, h) * 0.46f

        // Place direct peers on the inner ring, remembering each one's angle so
        // multi-hop destinations can be anchored to the peer that relays for them.
        val directAngles = HashMap<String, Float>()
        val n = peers.size
        peers.forEachIndexed { i, peer ->
            val angle = (2.0 * Math.PI * i / n).toFloat()
            val px = centerX + directRadius * cos(angle)
            val py = centerY + directRadius * sin(angle)
            val shortId = peer.meshId.shortId()
            directAngles[shortId] = angle

            canvas.drawLine(centerX, centerY, px, py, directLinkPaint)
            canvas.drawCircle(px, py, nodeRadius, directPeerPaint)
            canvas.drawText(shortId, px, py + nodeRadius + 28f, labelPaint)
        }

        // Multi-hop destinations: anchor to their next-hop's spoke and fan out
        // along the outer ring so several remote nodes via the same hop don't overlap.
        val multiHop = routes.filter { it.destination != it.nextHop }
        val byNextHop = multiHop.groupBy { it.nextHop.shortId() }
        byNextHop.forEach { (nextHopShortId, destRoutes) ->
            val baseAngle = directAngles[nextHopShortId] ?: return@forEach
            val hopX = centerX + directRadius * cos(baseAngle)
            val hopY = centerY + directRadius * sin(baseAngle)

            destRoutes.forEachIndexed { j, route ->
                val spread = 0.35f
                val angle = baseAngle + (j - (destRoutes.size - 1) / 2f) * spread
                val rx = centerX + remoteRadius * cos(angle)
                val ry = centerY + remoteRadius * sin(angle)

                canvas.drawLine(hopX, hopY, rx, ry, routeLinkPaint)
                canvas.drawCircle(rx, ry, nodeRadius * 0.8f, remotePeerPaint)
                canvas.drawText(route.destination.shortId(), rx, ry + nodeRadius + 26f, labelPaint)
            }
        }

        // Our node — drawn last so it sits on top of any overlapping links.
        canvas.drawCircle(centerX, centerY, nodeRadius, selfPaint)
        canvas.drawText(ourShortId, centerX, centerY + nodeRadius + 28f, labelPaint)
    }
}
