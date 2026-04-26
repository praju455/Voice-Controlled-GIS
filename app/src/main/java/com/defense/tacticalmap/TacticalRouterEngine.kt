package com.defense.tacticalmap

import android.content.Context
import android.util.Log
import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.config.Profile
import com.graphhopper.routing.DefaultWeightingFactory
import com.graphhopper.routing.WeightingFactory
import com.graphhopper.routing.ev.VehicleAccess
import com.graphhopper.routing.ev.RoadAccess
import com.graphhopper.routing.ev.VehicleSpeed
import com.graphhopper.routing.weighting.FastestWeighting
import com.graphhopper.util.CustomModel
import java.io.File
import java.util.Locale

class TacticalRouterEngine(context: Context, private val graphCacheDir: String) {
    private val tag = "TacticalRouter"
    private var hopper: GraphHopper? = null
    private var lastError: String? = null

    init {
        // Use context to avoid unused parameter warning
        Log.d(tag, "Initializing routing engine with context: $context")
        initializeGraphHopper()
    }

    private fun initializeGraphHopper() {
        Log.i(tag, "Initializing GraphHopper offline engine at $graphCacheDir")
        lastError = null
        try {
            val actualGraphPath = resolveGraphDirectory()
            Log.i(tag, "Using actual graph path: $actualGraphPath")

            val graphDir = File(actualGraphPath)
            if (!graphDir.exists()) {
                lastError = "Graph cache directory missing at $actualGraphPath"
                Log.e(tag, lastError!!)
                hopper = null
                return
            }

            val requiredFiles = listOf("nodes", "edges", "geometry", "location_index", "properties")
            val missingFiles = requiredFiles.filterNot { File(graphDir, it).exists() }
            if (missingFiles.isNotEmpty()) {
                lastError = "Graph cache incomplete. Missing: ${missingFiles.joinToString()}"
                Log.e(tag, lastError!!)
                hopper = null
                return
            }

            // Recreate the exact profile structure used during desktop import. For GraphHopper 8
            // the profile version hash includes hint insertion order, so we must mirror the
            // config.yml import path: custom_model_files first, then the resolved empty custom model.
            val gh = object : GraphHopper() {
                override fun createWeightingFactory(): WeightingFactory {
                    val defaultFactory = DefaultWeightingFactory(baseGraph.baseGraph, encodingManager)
                    return WeightingFactory { profile, requestHints, disableTurnCosts ->
                        if (profile.weighting.equals("custom", ignoreCase = true) && profile.vehicle == "car") {
                            val accessEnc = encodingManager.getBooleanEncodedValue(VehicleAccess.key(profile.vehicle))
                            val speedEnc = encodingManager.getDecimalEncodedValue(VehicleSpeed.key(profile.vehicle))
                            val roadAccessEnc = encodingManager.getEnumEncodedValue(RoadAccess.KEY, RoadAccess::class.java)
                            FastestWeighting(accessEnc, speedEnc, roadAccessEnc, requestHints, com.graphhopper.routing.weighting.TurnCostProvider.NO_TURN_COST_PROVIDER)
                        } else {
                            defaultFactory.createWeighting(profile, requestHints, disableTurnCosts)
                        }
                    }
                }
            }
            gh.isAllowWrites = false // READ-ONLY to prevent hash reconciliation attempts on Android
            gh.setGraphHopperLocation(actualGraphPath)
            val carProfile = Profile("car")
                .setVehicle("car")
                .setWeighting("custom")
                .setTurnCosts(false)
            carProfile.hints.remove(CustomModel.KEY)
            carProfile.putHint("custom_model_files", emptyList<String>())
            carProfile.setCustomModel(CustomModel())
            gh.setProfiles(listOf(carProfile))
            Log.d(tag, "Initializing GraphHopper with reproduced profile hash: ${carProfile.name}")

            try {
                if (!gh.load()) {
                    lastError = "GraphHopper could not load graph cache from $actualGraphPath"
                    Log.e(tag, "Failed to load existing graph from $actualGraphPath")
                    hopper = null
                    return
                }
            } catch (e: Exception) {
                lastError = e.message ?: "Unknown GraphHopper load error"
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
            lastError = e.message ?: "Unknown GraphHopper initialization error"
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
            lastError = lastError ?: "GraphHopper not initialized or failed to load"
            Log.e(tag, "Cannot calculate route: $lastError")
            return null
        }

        try {
            val req = GHRequest(fromLat, fromLon, toLat, toLon)
                .setProfile("car")
                .setLocale(Locale.US)
            req.putHint("ch.disable", true)
            val rsp = currentHopper.route(req)

            if (rsp.hasErrors()) {
                lastError = rsp.errors.joinToString(separator = " | ") { it.message ?: it.toString() }
                Log.e(tag, "Routing errors: ${rsp.errors}")
                return null
            } else {
                val path = rsp.best
                val pointList = path.points
                val coordinateList = mutableListOf<DoubleArray>()

                for (i in 0 until pointList.size()) {
                    coordinateList.add(doubleArrayOf(pointList.getLon(i), pointList.getLat(i)))
                }
                
                lastError = null
                Log.i(tag, "Calculated route successfully with distance: ${path.distance}m")
                return coordinateList
            }
        } catch (e: Exception) {
            lastError = e.message ?: "Unknown routing exception"
            Log.e(tag, "Exception during routing", e)
            return null
        }
    }

    fun getLastError(): String? = lastError

    private fun resolveGraphDirectory(): String {
        val cacheRoot = File(graphCacheDir)
        val directGraphCandidates = listOf(
            File(cacheRoot, "bmsit-zone-gh"),
            File(cacheRoot, "eastern-zone-gh")
        )

        directGraphCandidates.firstOrNull { it.exists() }?.let { return it.absolutePath }

        cacheRoot.listFiles()
            ?.firstOrNull { it.isDirectory && it.name.endsWith("-gh") }
            ?.let { return it.absolutePath }

        return graphCacheDir
    }
}
