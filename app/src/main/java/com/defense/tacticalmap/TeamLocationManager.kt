package com.defense.tacticalmap

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * TeamLocationManager — tracks all team members received from the mesh.
 *
 * - Updates member positions, marks stale after 30s, removes after 5min
 * - Notifies via [Listener] when team state changes so MainActivity can
 *   refresh the map overlay
 */
class TeamLocationManager {

    private val tag = "TeamLocationMgr"
    private val members = mutableMapOf<String, TeamMember>() // id → member
    private val handler = Handler(Looper.getMainLooper())
    private var listener: Listener? = null

    interface Listener {
        fun onTeamUpdated(members: List<TeamMember>)
    }

    fun setListener(l: Listener) { listener = l }

    // ── Public API ────────────────────────────────────────────────────────────

    fun updateMember(
        id: String,
        callsign: String,
        lat: Double,
        lon: Double,
        accuracy: Float
    ) {
        val existing = members[id]
        if (existing != null) {
            existing.callsign   // immutable, ignore incoming (first seen wins for callsign)
            members[id] = existing.copy(
                lat           = lat,
                lon           = lon,
                accuracyMeters = accuracy,
                lastSeenMs    = System.currentTimeMillis(),
                isStale       = false
            )
        } else {
            members[id] = TeamMember(id, callsign, lat, lon, accuracy)
            Log.i(tag, "New team member joined: $callsign ($id)")
        }
        notifyListener()
        scheduleStaleCheck()
    }

    fun removeMember(id: String) {
        members.remove(id)
        notifyListener()
    }

    fun clearAll() {
        members.clear()
        notifyListener()
    }

    fun getMemberCount(): Int = members.size

    fun getAll(): List<TeamMember> = members.values.toList()

    fun getMember(id: String): TeamMember? = members[id]

    // ── Stale Detection ───────────────────────────────────────────────────────

    private fun scheduleStaleCheck() {
        handler.postDelayed({
            var changed = false
            val now = System.currentTimeMillis()
            val toRemove = mutableListOf<String>()

            members.forEach { (id, member) ->
                val age = now - member.lastSeenMs
                when {
                    age > TeamMember.REMOVE_THRESHOLD_MS -> {
                        toRemove.add(id)
                        changed = true
                        Log.i(tag, "Removing timed-out member: ${member.callsign}")
                    }
                    age > TeamMember.STALE_THRESHOLD_MS && !member.isStale -> {
                        members[id] = member.copy(isStale = true)
                        changed = true
                    }
                }
            }
            toRemove.forEach { members.remove(it) }
            if (changed) notifyListener()
        }, TeamMember.STALE_THRESHOLD_MS)
    }

    private fun notifyListener() {
        handler.post { listener?.onTeamUpdated(members.values.toList()) }
    }
}
