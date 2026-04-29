package com.defense.tacticalmap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * TeamMeshService — foreground service that broadcasts this device's GPS position
 * via UDP every 3 seconds and listens for peer position + hazard zone packets
 * on the same local Wi-Fi network (or mobile hotspot).
 *
 * No internet required. Runs on port 5005 (UDP).
 *
 * Broadcasts LocalBroadcast intents to MainActivity:
 *   ACTION_PEER_POSITION  — a team member's position was received
 *   ACTION_HAZARD_ADD     — a hazard zone was received from a peer
 *   ACTION_HAZARD_CLEAR   — a zone was cleared by a peer
 *   ACTION_HAZARD_CLEAR_ALL
 *   ACTION_STATE_REQUEST  — a new peer wants full hazard state dump
 */
class TeamMeshService : Service() {

    companion object {
        const val TAG = "TeamMeshService"
        const val UDP_PORT = 5005
        const val BROADCAST_INTERVAL_SEC = 3L
        const val NOTIFICATION_ID = 9001
        const val CHANNEL_ID = "veer_rakshak_mesh"

        // Intent actions (sent via LocalBroadcastManager)
        const val ACTION_PEER_POSITION   = "com.defense.tacticalmap.PEER_POSITION"
        const val ACTION_HAZARD_ADD      = "com.defense.tacticalmap.HAZARD_ADD"
        const val ACTION_HAZARD_CLEAR    = "com.defense.tacticalmap.HAZARD_CLEAR"
        const val ACTION_HAZARD_CLEAR_ALL = "com.defense.tacticalmap.HAZARD_CLEAR_ALL"
        const val ACTION_STATE_REQUEST   = "com.defense.tacticalmap.STATE_REQUEST"

        // Intent extras keys
        const val EXTRA_MEMBER_ID        = "memberId"
        const val EXTRA_CALLSIGN         = "callsign"
        const val EXTRA_LAT              = "lat"
        const val EXTRA_LON              = "lon"
        const val EXTRA_ACCURACY         = "accuracy"
        const val EXTRA_ZONE_JSON        = "zoneJson"
        const val EXTRA_ZONE_ID          = "zoneId"
        const val EXTRA_SENDER_ID        = "senderId"

        // Called by MainActivity to send packets outward
        const val ACTION_SEND_POSITION   = "com.defense.tacticalmap.SEND_POSITION"
        const val ACTION_SEND_HAZARD_ADD    = "com.defense.tacticalmap.SEND_HAZARD_ADD"
        const val ACTION_SEND_HAZARD_CLEAR  = "com.defense.tacticalmap.SEND_HAZARD_CLEAR"
        const val ACTION_SEND_HAZARD_CLEAR_ALL = "com.defense.tacticalmap.SEND_HAZARD_CLEAR_ALL"
        const val ACTION_SEND_STATE_DUMP = "com.defense.tacticalmap.SEND_STATE_DUMP"
        const val ACTION_SEND_STATE_REQUEST = "com.defense.tacticalmap.SEND_STATE_REQUEST"
    }

    // Current operator data (set by MainActivity via startService Intent extras)
    @Volatile private var myId: String = ""
    @Volatile private var myCallsign: String = "ALPHA-1"
    @Volatile private var myLat: Double = 0.0
    @Volatile private var myLon: Double = 0.0
    @Volatile private var myAccuracy: Float = 0f

    private val executor = Executors.newScheduledThreadPool(2)
    private var sendFuture: ScheduledFuture<*>? = null
    private var receiveSocket: DatagramSocket? = null

    private lateinit var lbm: LocalBroadcastManager

    // ── Service Lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        lbm = LocalBroadcastManager.getInstance(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.i(TAG, "TeamMeshService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { handleCommand(it) }
        if (receiveSocket == null) startReceiving()
        if (sendFuture == null) startBroadcasting()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        sendFuture?.cancel(true)
        receiveSocket?.close()
        executor.shutdownNow()
        Log.i(TAG, "TeamMeshService stopped")
    }

    // ── Command Handling (from MainActivity) ──────────────────────────────────

    private fun handleCommand(intent: Intent) {
        when {
            intent.action == ACTION_SEND_STATE_REQUEST -> {
                sendPacket(JSONObject().apply {
                    put("type", "state_request")
                    put("senderId", myId)
                }.toString())
            }
            intent.hasExtra(EXTRA_MEMBER_ID) -> {
                // Position update from MainActivity
                myId       = intent.getStringExtra(EXTRA_MEMBER_ID) ?: myId
                myCallsign = intent.getStringExtra(EXTRA_CALLSIGN) ?: myCallsign
                myLat      = intent.getDoubleExtra(EXTRA_LAT, myLat)
                myLon      = intent.getDoubleExtra(EXTRA_LON, myLon)
                myAccuracy = intent.getFloatExtra(EXTRA_ACCURACY, myAccuracy)
            }
            intent.action == ACTION_SEND_HAZARD_ADD -> {
                val json = intent.getStringExtra(EXTRA_ZONE_JSON) ?: return
                sendPacket(json)
            }
            intent.action == ACTION_SEND_HAZARD_CLEAR -> {
                val zoneId = intent.getStringExtra(EXTRA_ZONE_ID) ?: return
                sendPacket(JSONObject().apply {
                    put("type", "hazard_clear")
                    put("senderId", myId)
                    put("zoneId", zoneId)
                }.toString())
            }
            intent.action == ACTION_SEND_HAZARD_CLEAR_ALL -> {
                sendPacket(JSONObject().apply {
                    put("type", "hazard_clear_all")
                    put("senderId", myId)
                }.toString())
            }
            intent.action == ACTION_SEND_STATE_DUMP -> {
                val zonesJson = intent.getStringExtra(EXTRA_ZONE_JSON) ?: return
                sendPacket(JSONObject().apply {
                    put("type", "hazard_state_dump")
                    put("senderId", myId)
                    put("zones", JSONArray(zonesJson))
                }.toString())
            }
        }
    }

    // ── UDP Broadcasting ──────────────────────────────────────────────────────

    private fun startBroadcasting() {
        sendFuture = executor.scheduleAtFixedRate({
            if (myId.isBlank() || (myLat == 0.0 && myLon == 0.0)) return@scheduleAtFixedRate
            val packet = JSONObject().apply {
                put("type",     "position")
                put("id",       myId)
                put("callsign", myCallsign)
                put("lat",      myLat)
                put("lon",      myLon)
                put("accuracy", myAccuracy)
                put("ts",       System.currentTimeMillis())
            }.toString()
            sendPacket(packet)
        }, 1L, BROADCAST_INTERVAL_SEC, TimeUnit.SECONDS)
    }

    private fun sendPacket(json: String) {
        executor.execute {
            try {
                val data = json.toByteArray(Charsets.UTF_8)
                val socket = DatagramSocket()
                socket.broadcast = true
                socket.send(
                    DatagramPacket(
                        data, data.size,
                        InetAddress.getByName("255.255.255.255"),
                        UDP_PORT
                    )
                )
                socket.close()
            } catch (e: Exception) {
                Log.w(TAG, "Send failed: ${e.message}")
            }
        }
    }

    // ── UDP Receiving ─────────────────────────────────────────────────────────

    private fun startReceiving() {
        executor.execute {
            try {
                val socket = DatagramSocket(UDP_PORT).also { receiveSocket = it }
                socket.broadcast = true
                val buf = ByteArray(8192)
                Log.i(TAG, "Listening on UDP port $UDP_PORT")

                while (!socket.isClosed) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                        val json = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        handleIncoming(json)
                    } catch (e: Exception) {
                        if (!socket.isClosed) Log.w(TAG, "Receive error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not open receive socket: ${e.message}")
            }
        }
    }

    private fun handleIncoming(json: String) {
        try {
            val obj = JSONObject(json)
            val type = obj.optString("type")
            val senderId = obj.optString("id").ifBlank { obj.optString("senderId") }

            // Ignore our own packets
            if (senderId == myId) return

            when (type) {
                "position" -> {
                    lbm.sendBroadcast(Intent(ACTION_PEER_POSITION).apply {
                        putExtra(EXTRA_MEMBER_ID, senderId)
                        putExtra(EXTRA_CALLSIGN,  obj.optString("callsign", "UNKNOWN"))
                        putExtra(EXTRA_LAT,       obj.optDouble("lat", 0.0))
                        putExtra(EXTRA_LON,       obj.optDouble("lon", 0.0))
                        putExtra(EXTRA_ACCURACY,  obj.optDouble("accuracy", 0.0).toFloat())
                    })
                }
                "hazard_add" -> {
                    lbm.sendBroadcast(Intent(ACTION_HAZARD_ADD).apply {
                        putExtra(EXTRA_SENDER_ID, senderId)
                        putExtra(EXTRA_ZONE_JSON, obj.optJSONObject("zone")?.toString() ?: "")
                    })
                }
                "hazard_clear" -> {
                    lbm.sendBroadcast(Intent(ACTION_HAZARD_CLEAR).apply {
                        putExtra(EXTRA_SENDER_ID, senderId)
                        putExtra(EXTRA_ZONE_ID, obj.optString("zoneId"))
                    })
                }
                "hazard_clear_all" -> {
                    lbm.sendBroadcast(Intent(ACTION_HAZARD_CLEAR_ALL).apply {
                        putExtra(EXTRA_SENDER_ID, senderId)
                    })
                }
                "hazard_state_dump" -> {
                    // Full state from an existing peer — apply all zones
                    val zonesArr = obj.optJSONArray("zones") ?: return
                    lbm.sendBroadcast(Intent(ACTION_HAZARD_ADD).apply {
                        putExtra(EXTRA_SENDER_ID, senderId)
                        putExtra(EXTRA_ZONE_JSON, zonesArr.toString())
                    })
                }
                "state_request" -> {
                    // A new peer wants our full hazard state
                    lbm.sendBroadcast(Intent(ACTION_STATE_REQUEST).apply {
                        putExtra(EXTRA_SENDER_ID, senderId)
                    })
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse incoming packet: ${e.message}")
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Veer Rakshak Mesh",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Team position sharing — offline local mesh"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Veer Rakshak — Mesh Active")
                .setContentText("Broadcasting position to team")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Veer Rakshak — Mesh Active")
                .setContentText("Broadcasting position to team")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .build()
        }
    }
}
