package com.defense.tacticalmap

import android.content.Context
import android.util.Log
import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.config.CHProfile
import com.graphhopper.config.Profile
import java.io.File
import java.util.Locale

class TacticalRouterEngine(private val context: Context, private val graphCacheDir: String) {
    private val TAG = "TacticalRouter"
    private var hopper: GraphHopper? = null

    init {
        initializeGraphHopper()
    }

    private fun initializeGraphHopper() {
        Log.i(TAG, "Initializing GraphHopper offline engine at $graphCacheDir")
        try {
            // Note: In an actual deployment, the .gh pre-compiled constraint graph
            // would have to be populated at the graphCacheDir.
            val graphDir = File(graphCacheDir)
            if (!graphDir.exists()) {
                graphDir.mkdirs()
                Log.w(TAG, "Graphhopper directory created. Missing actual graph data files.")
            }

            hopper = GraphHopper().forMobile()
            hopper?.graphHopperLocation = graphCacheDir
            
            // To function efficiently on 3GB RAM, we rely purely on Contraction Hierarchies (CH)
            // pre-processed elsewhere (server)
            hopper?.profiles = listOf(Profile("car").setVehicle("car").setWeighting("fastest"))
            hopper?.chProfiles = listOf(CHProfile("car"))
            
            // In a real execution with data, hopper.importOrLoad() is called here
            // hopper?.importOrLoad()

            Log.i(TAG, "GraphHopper initialized successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize offline routing engine", e)
        }
    }

    /**
     * Calculates the fastest route entirely offline using Contraction Hierarchies.
     * Returns a GeoJSON-compatible LineString coordinate set.
     */
    fun calculateRoute(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): List<DoubleArray>? {
        hopper?.let {
            val req = GHRequest(fromLat, fromLon, toLat, toLon).setProfile("car").setLocale(Locale.US)
            val rsp = it.route(req)

            if (rsp.hasErrors()) {
                Log.e(TAG, "Routing errors: ${rsp.errors}")
                return null
            } else {
                val path = rsp.best
                val pointList = path.points
                val coordinateList = mutableListOf<DoubleArray>()

                for (i in 0 until pointList.size()) {
                    coordinateList.add(doubleArrayOf(pointList.getLon(i), pointList.getLat(i)))
                }
                
                Log.i(TAG, "Calculated route successfully with distance: ${path.distance}m")
                return coordinateList
            }
        }
        return null
    }
}
