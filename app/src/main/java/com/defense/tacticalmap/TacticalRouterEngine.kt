package com.defense.tacticalmap

import android.content.Context
import android.util.Log
import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.GraphHopperConfig
import com.graphhopper.config.CHProfile
import com.graphhopper.config.Profile
import java.io.File
import java.util.Locale

class TacticalRouterEngine(context: Context, private val graphCacheDir: String) {
    private val tag = "TacticalRouter"
    private var hopper: GraphHopper? = null

    init {
        // Use context to avoid unused parameter warning
        Log.d(tag, "Initializing routing engine with context: $context")
        initializeGraphHopper()
    }

    private fun initializeGraphHopper() {
        Log.i(tag, "Initializing GraphHopper offline engine at $graphCacheDir")
        try {
            // Check if we need to append the subdirectory name from the zip
            val actualGraphPath = if (File(graphCacheDir, "eastern-zone-gh").exists()) {
                File(graphCacheDir, "eastern-zone-gh").absolutePath
            } else {
                graphCacheDir
            }
            Log.i(tag, "Using actual graph path: $actualGraphPath")

            // Use explicit GraphHopper configuration for version 8.0 on Android
            val gh = GraphHopper()
            gh.isAllowWrites = false // READ-ONLY to prevent hash reconciliation attempts on Android
            gh.setGraphHopperLocation(actualGraphPath)
            
            // Define the profile exactly to match the pre-built graph
            val carProfile = Profile("car")
                .setVehicle("car")
                .setWeighting("fastest")
                .setTurnCosts(false)
            
            // Set profiles directly on the hopper instance to ensure they are registered
            gh.setProfiles(listOf(carProfile))
            gh.setCHProfiles(listOf(CHProfile("car")))
            
            Log.d(tag, "Initializaing GraphHopper with profile: ${carProfile.name}")

            try {
                if (!gh.load()) {
                    Log.e(tag, "Failed to load existing graph from $actualGraphPath")
                    hopper = null
                    return
                }
            } catch (e: Exception) {
                Log.e(tag, "Exception during GraphHopper load: ${e.message}")
                if (e.message?.contains("Profiles do not match") == true) {
                    Log.w(tag, "Profile mismatch detected in catch. Deleting cache at $actualGraphPath to force re-unpack.")
                    val cacheDirFile = File(actualGraphPath)
                    if (cacheDirFile.exists()) {
                        cacheDirFile.deleteRecursively()
                        Log.i(tag, "Cache directory deleted. App should re-unpack on next launch.")
                    }
                }
                hopper = null
                return
            }

            hopper = gh
            Log.i(tag, "GraphHopper initialized successfully.")
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize offline routing engine", e)
            hopper = null
        }
    }

    /**
     * Calculates the fastest route entirely offline using Contraction Hierarchies.
     * Returns a GeoJSON-compatible LineString coordinate set.
     */
    fun calculateRoute(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): List<DoubleArray>? {
        val currentHopper = hopper
        if (currentHopper == null) {
            Log.e(tag, "Cannot calculate route: GraphHopper not initialized or failed to load.")
            return null
        }

        try {
            val req = GHRequest(fromLat, fromLon, toLat, toLon).setProfile("car").setLocale(Locale.US)
            val rsp = currentHopper.route(req)

            if (rsp.hasErrors()) {
                Log.e(tag, "Routing errors: ${rsp.errors}")
                return null
            } else {
                val path = rsp.best
                val pointList = path.points
                val coordinateList = mutableListOf<DoubleArray>()

                for (i in 0 until pointList.size()) {
                    coordinateList.add(doubleArrayOf(pointList.getLon(i), pointList.getLat(i)))
                }
                
                Log.i(tag, "Calculated route successfully with distance: ${path.distance}m")
                return coordinateList
            }
        } catch (e: Exception) {
            Log.e(tag, "Exception during routing", e)
            return null
        }
    }
}
