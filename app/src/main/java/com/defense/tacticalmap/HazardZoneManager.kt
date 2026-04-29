package com.defense.tacticalmap

import android.content.Context
import android.util.Log
import com.mapbox.mapboxsdk.geometry.LatLng
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * HazardZoneManager — manages tactical no-go zones for dynamic hazard-aware routing.
 *
 * Zones are circle-based (centre + radius) for fast field entry.
 * Each zone is converted to a 16-point polygon approximation for GraphHopper's
 * CustomModel area-blocking, and drawn as a GeoJSON fill layer on the map.
 *
 * Zones are persisted to cache as JSON across app restarts.
 */
class HazardZoneManager(private val context: Context) {

    private val tag = "HazardZoneManager"
    private val persistFile = File(context.cacheDir, "hazard_zones.json")
    private val _zones = mutableListOf<HazardZone>()
    val zones: List<HazardZone> get() = _zones.toList()

    // ── Zone Types ────────────────────────────────────────────────────────────

    enum class HazardType(
        val label: String,
        val colorHex: String,
        val fillAlpha: Int   // 0-255
    ) {
        FLOOD_ZONE    ("FLOOD ZONE",     "#2196F3", 80),
        BLAST_RADIUS  ("BLAST RADIUS",   "#E74C3C", 80),
        HOSTILE_AREA  ("HOSTILE AREA",   "#FF6B35", 80),
        BLOCKED_ROAD  ("BLOCKED ROAD",   "#607D8B", 80),
        RESTRICTED    ("RESTRICTED",     "#9C27B0", 80)
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    data class HazardZone(
        val id: String,
        val type: HazardType,
        val center: LatLng,
        val radiusMeters: Double,
        val label: String,
        val timestampMs: Long = System.currentTimeMillis()
    )

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        loadFromCache()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun hasZones(): Boolean = _zones.isNotEmpty()

    fun addZone(
        type: HazardType,
        center: LatLng,
        radiusMeters: Double = 200.0,
        label: String = type.label,
        id: String? = null
    ): HazardZone {
        val zone = HazardZone(
            id = id ?: "hazard_${UUID.randomUUID().toString().replace("-", "").take(8)}",
            type = type,
            center = center,
            radiusMeters = radiusMeters,
            label = label
        )
        _zones.add(zone)
        saveToCache()
        Log.i(tag, "Added hazard zone: ${zone.id} (${zone.type.label}) at ${zone.center}")
        return zone
    }

    fun removeZone(id: String) {
        _zones.removeAll { it.id == id }
        saveToCache()
        Log.i(tag, "Removed hazard zone: $id")
    }

    fun clearAll() {
        _zones.clear()
        saveToCache()
        Log.i(tag, "All hazard zones cleared")
    }

    /**
     * Checks if a point is inside any hazard zone.
     * Uses simple great-circle distance check for circles.
     */
    fun isPointInAnyZone(lat: Double, lon: Double): HazardZone? {
        return _zones.firstOrNull { zone ->
            val dist = distanceMeters(lat, lon, zone.center.latitude, zone.center.longitude)
            dist <= zone.radiusMeters
        }
    }

    /**
     * Checks if a route segment (from→to) passes through any hazard zone.
     * Checks the midpoint and both endpoints.
     */
    fun doesSegmentIntersectAnyZone(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Boolean {
        val midLat = (lat1 + lat2) / 2.0
        val midLon = (lon1 + lon2) / 2.0
        return isPointInAnyZone(lat1, lon1) != null ||
               isPointInAnyZone(lat2, lon2) != null ||
               isPointInAnyZone(midLat, midLon) != null
    }

    /**
     * Builds the GraphHopper CustomModel JSON string with all active hazard zones
     * as blocked polygon areas. Inject this into the GHRequest via:
     *   req.putHint("custom_model", buildCustomModelJson())
     *
     * Each zone is approximated as a 16-point polygon for GH area format.
     */
    fun buildCustomModelJson(): String {
        if (_zones.isEmpty()) return "{}"

        val priorityArray = JSONArray()
        val featuresArray = JSONArray()

        _zones.forEachIndexed { index, zone ->
            val areaId = "hz_$index"

            // Build GeoJSON polygon feature (16-point circle approximation)
            val coordsArray = JSONArray()
            val ringArray = JSONArray()
            val points = circleToPolygonPoints(
                zone.center.latitude,
                zone.center.longitude,
                zone.radiusMeters,
                16
            )
            // GeoJSON: coordinates is array of rings, each ring is array of [lon, lat] pairs
            points.forEach { (lat, lon) ->
                ringArray.put(JSONArray().apply { put(lon); put(lat) })
            }
            // Close the ring by repeating first point
            val firstPoint = points.first()
            ringArray.put(JSONArray().apply { put(firstPoint.second); put(firstPoint.first) })
            coordsArray.put(ringArray)

            val geometry = JSONObject().apply {
                put("type", "Polygon")
                put("coordinates", coordsArray)
            }
            val feature = JSONObject().apply {
                put("type", "Feature")
                put("id", areaId)
                put("geometry", geometry)
                put("properties", JSONObject())
            }
            featuresArray.put(feature)

            // Priority statement: multiply by 0 = completely blocked
            val statement = JSONObject().apply {
                put("if", "in_$areaId")
                put("multiply_by", "0")
            }
            priorityArray.put(statement)
        }

        val areasFeatureCollection = JSONObject().apply {
            put("type", "FeatureCollection")
            put("features", featuresArray)
        }

        return JSONObject().apply {
            put("priority", priorityArray)
            put("areas", areasFeatureCollection)
        }.toString()
    }

    /**
     * Returns GeoJSON FeatureCollection of all hazard zones for map overlay rendering.
     * Each feature includes hazard type metadata for styling.
     */
    fun toMapGeoJson(): String {
        val featuresArray = JSONArray()
        _zones.forEach { zone ->
            val points = circleToPolygonPoints(
                zone.center.latitude,
                zone.center.longitude,
                zone.radiusMeters,
                32  // More points for smoother map rendering
            )
            val coordsArray = JSONArray()
            val ringArray = JSONArray()
            points.forEach { (lat, lon) ->
                ringArray.put(JSONArray().apply { put(lon); put(lat) })
            }
            val firstPoint = points.first()
            ringArray.put(JSONArray().apply { put(firstPoint.second); put(firstPoint.first) })
            coordsArray.put(ringArray)

            val geometry = JSONObject().apply {
                put("type", "Polygon")
                put("coordinates", coordsArray)
            }
            val properties = JSONObject().apply {
                put("id", zone.id)
                put("type", zone.type.name)
                put("label", zone.label)
                put("color", zone.type.colorHex)
            }
            val feature = JSONObject().apply {
                put("type", "Feature")
                put("id", zone.id)
                put("geometry", geometry)
                put("properties", properties)
            }
            featuresArray.put(feature)
        }
        return JSONObject().apply {
            put("type", "FeatureCollection")
            put("features", featuresArray)
        }.toString()
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun saveToCache() {
        try {
            val arr = JSONArray()
            _zones.forEach { zone ->
                arr.put(JSONObject().apply {
                    put("id", zone.id)
                    put("type", zone.type.name)
                    put("lat", zone.center.latitude)
                    put("lon", zone.center.longitude)
                    put("radius", zone.radiusMeters)
                    put("label", zone.label)
                    put("ts", zone.timestampMs)
                })
            }
            persistFile.writeText(arr.toString())
        } catch (e: Exception) {
            Log.e(tag, "Failed to save hazard zones", e)
        }
    }

    private fun loadFromCache() {
        try {
            if (!persistFile.exists()) return
            val arr = JSONArray(persistFile.readText())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val type = HazardType.valueOf(obj.getString("type"))
                _zones.add(HazardZone(
                    id            = obj.getString("id"),
                    type          = type,
                    center        = LatLng(obj.getDouble("lat"), obj.getDouble("lon")),
                    radiusMeters  = obj.getDouble("radius"),
                    label         = obj.getString("label"),
                    timestampMs   = obj.optLong("ts", System.currentTimeMillis())
                ))
            }
            Log.i(tag, "Loaded ${_zones.size} hazard zones from cache")
        } catch (e: Exception) {
            Log.e(tag, "Failed to load hazard zones from cache", e)
        }
    }

    // ── Geometry Helpers ──────────────────────────────────────────────────────

    /**
     * Approximates a circle as an N-point polygon.
     * Returns list of (lat, lon) pairs.
     */
    private fun circleToPolygonPoints(
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Double,
        numPoints: Int = 16
    ): List<Pair<Double, Double>> {
        val points = mutableListOf<Pair<Double, Double>>()
        val metersPerDegreeLat = 111320.0
        val metersPerDegreeLon = 111320.0 * cos(Math.toRadians(centerLat))

        for (i in 0 until numPoints) {
            val angle = 2.0 * PI * i / numPoints
            val dLat = (radiusMeters * sin(angle)) / metersPerDegreeLat
            val dLon = (radiusMeters * cos(angle)) / metersPerDegreeLon
            points.add(Pair(centerLat + dLat, centerLon + dLon))
        }
        return points
    }

    private fun distanceMeters(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val result = FloatArray(1)
        android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, result)
        return result[0].toDouble()
    }
}
