package com.defense.tacticalmap

/**
 * Represents a single team member visible on the mesh network.
 */
data class TeamMember(
    val id: String,                    // Unique device ID (UUID stored in SharedPreferences)
    val callsign: String,              // e.g. "BRAVO-2"
    var lat: Double,
    var lon: Double,
    var accuracyMeters: Float = 0f,
    var lastSeenMs: Long = System.currentTimeMillis(),
    var isStale: Boolean = false       // true if >30s since last update
) {
    companion object {
        const val STALE_THRESHOLD_MS = 30_000L   // 30 seconds
        const val REMOVE_THRESHOLD_MS = 300_000L // 5 minutes
    }
}
