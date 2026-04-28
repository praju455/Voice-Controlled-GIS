package com.defense.tacticalmap

import android.content.Context
import android.location.Location
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class OfflinePlaceIndex(private val context: Context) {
    data class PlaceMatch(
        val name: String,
        val categoryKey: String,
        val categoryValue: String,
        val lat: Double,
        val lon: Double
    )

    private data class PlaceRecord(
        val name: String,
        val normalizedName: String,
        val categoryKey: String,
        val categoryValue: String,
        val lat: Double,
        val lon: Double
    )

    private val categoryAliases = mapOf(
        "hospital" to listOf("hospital", "clinic", "medical", "pharmacy"),
        "fuel" to listOf("fuel", "petrol", "gas station", "petrol bunk"),
        "airport" to listOf("airport", "aerodrome", "airfield"),
        "station" to listOf("station", "railway station", "bus station"),
        "police" to listOf("police"),
        "military" to listOf("military", "base", "camp"),
        "university" to listOf("university", "campus", "college")
    )

    @Volatile
    private var places: List<PlaceRecord>? = null
    private val loading = AtomicBoolean(false)
    @Volatile
    private var loadError: String? = null

    fun preloadAsync(onLoaded: (() -> Unit)? = null) {
        if (places != null) {
            onLoaded?.invoke()
            return
        }
        if (!loading.compareAndSet(false, true)) {
            return
        }
        Thread {
            try {
                places = loadPlaces()
                loadError = null
            } catch (exception: Exception) {
                loadError = exception.message ?: "Unknown place index load error"
            } finally {
                loading.set(false)
                onLoaded?.invoke()
            }
        }.start()
    }

    fun isReady(): Boolean = places != null

    fun getLoadError(): String? = loadError

    fun resolve(query: String, currentLocation: Location?): PlaceMatch? {
        val localPlaces = places ?: return null
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) return null

        val nearestPhrase = extractNearestCategory(normalizedQuery)
        if (nearestPhrase != null) {
            val nearest = resolveNearestByCategory(localPlaces, nearestPhrase, currentLocation)
            if (nearest != null) return nearest
        }

        val exactName = localPlaces.filter { it.normalizedName == normalizedQuery }
        pickBest(exactName, currentLocation)?.let { return it }

        val prefixName = localPlaces.filter { it.normalizedName.startsWith(normalizedQuery) }
        pickBest(prefixName, currentLocation)?.let { return it }

        val containsName = localPlaces.filter { it.normalizedName.contains(normalizedQuery) }
        pickBest(containsName, currentLocation)?.let { return it }

        val categoryMatch = resolveNearestByCategory(localPlaces, normalizedQuery, currentLocation)
        if (categoryMatch != null) return categoryMatch

        return null
    }

    private fun resolveNearestByCategory(
        localPlaces: List<PlaceRecord>,
        query: String,
        currentLocation: Location?
    ): PlaceMatch? {
        val expandedTerms = expandCategoryTerms(query)
        val categoryCandidates = localPlaces.filter { place ->
            expandedTerms.any { term ->
                place.categoryValue.contains(term) ||
                    place.categoryKey.contains(term) ||
                    place.normalizedName.contains(term)
            }
        }
        return pickBest(categoryCandidates, currentLocation)
    }

    private fun extractNearestCategory(normalizedQuery: String): String? {
        val regex = Regex("^(nearest|closest)(?:\\s+the)?\\s+(.+)$")
        val match = regex.find(normalizedQuery) ?: return null
        return match.groupValues[2].trim()
    }

    private fun expandCategoryTerms(query: String): Set<String> {
        val direct = mutableSetOf(query)
        categoryAliases.forEach { (canonical, aliases) ->
            if (query == canonical || aliases.any { alias -> query.contains(alias) }) {
                direct += canonical
                direct += aliases
            }
        }
        return direct.map { normalize(it) }.toSet()
    }

    private fun pickBest(candidates: List<PlaceRecord>, currentLocation: Location?): PlaceMatch? {
        if (candidates.isEmpty()) return null
        val chosen = if (currentLocation != null) {
            candidates.minByOrNull { distanceMeters(currentLocation.latitude, currentLocation.longitude, it.lat, it.lon) }
        } else {
            candidates.minByOrNull { it.name.length }
        } ?: return null

        return PlaceMatch(
            name = chosen.name,
            categoryKey = chosen.categoryKey,
            categoryValue = chosen.categoryValue,
            lat = chosen.lat,
            lon = chosen.lon
        )
    }

    private fun loadPlaces(): List<PlaceRecord> {
        val jsonText = context.assets.open("places/place_index.json").bufferedReader().use { it.readText() }
        val jsonArray = JSONArray(jsonText)
        val output = ArrayList<PlaceRecord>(jsonArray.length())
        for (index in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(index)
            output += PlaceRecord(
                name = item.getString("name"),
                normalizedName = item.getString("normalizedName"),
                categoryKey = item.optString("categoryKey"),
                categoryValue = normalize(item.optString("categoryValue")),
                lat = item.getDouble("lat"),
                lon = item.getDouble("lon")
            )
        }
        return output
    }

    private fun normalize(value: String): String {
        return value.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }
}
