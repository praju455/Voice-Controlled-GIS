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

    private val phraseAliases = mapOf(
        "hebbal" to listOf("hebble", "hebal", "hebbel", "hebal", "heb ball"),
        "yelahanka" to listOf("yelahanka", "yelanka", "yelhanka", "yellahanka", "yelahanaka"),
        "kempegowda international airport" to listOf(
            "bangalore airport",
            "bengaluru airport",
            "international airport",
            "kempegowda airport",
            "airport"
        ),
        "police" to listOf("police station", "cop station"),
        "hospital" to listOf("medical", "clinic")
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
        val normalizedQuery = normalizeAndAlias(query)
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

        val fuzzyMatch = resolveFuzzyByName(localPlaces, normalizedQuery, currentLocation)
        if (fuzzyMatch != null) return fuzzyMatch

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

    private fun resolveFuzzyByName(
        localPlaces: List<PlaceRecord>,
        normalizedQuery: String,
        currentLocation: Location?
    ): PlaceMatch? {
        val queryTokens = normalizedQuery.split(" ").filter { it.isNotBlank() }
        if (queryTokens.isEmpty()) return null

        val scored = localPlaces.mapNotNull { place ->
            val score = fuzzyScore(normalizedQuery, queryTokens, place)
            if (score == Double.NEGATIVE_INFINITY) null else place to score
        }

        val bestScore = scored.maxOfOrNull { it.second } ?: return null
        if (bestScore < 0.55) return null

        val topCandidates = scored
            .filter { (_, score) -> score >= bestScore - 0.05 }
            .map { it.first }

        return pickBest(topCandidates, currentLocation)
    }

    private fun fuzzyScore(query: String, queryTokens: List<String>, place: PlaceRecord): Double {
        val name = place.normalizedName
        val nameTokens = name.split(" ").filter { it.isNotBlank() }
        if (nameTokens.isEmpty()) return Double.NEGATIVE_INFINITY

        val tokenMatches = queryTokens.count { q ->
            nameTokens.any { n -> n == q || levenshteinDistance(q, n) <= tokenDistanceThreshold(q.length) }
        }
        val tokenCoverage = tokenMatches.toDouble() / queryTokens.size.toDouble()

        val wholeNameDistance = levenshteinDistance(query, name)
        val wholeNameScore = 1.0 - (wholeNameDistance.toDouble() / maxOf(query.length, name.length).toDouble())

        val containsBonus = when {
            name.contains(query) -> 0.15
            queryTokens.all { token -> name.contains(token) } -> 0.1
            else -> 0.0
        }

        return (tokenCoverage * 0.7) + (wholeNameScore * 0.3) + containsBonus
    }

    private fun tokenDistanceThreshold(length: Int): Int {
        return when {
            length <= 4 -> 1
            length <= 8 -> 2
            else -> 3
        }
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)

        for (i in a.indices) {
            curr[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                curr[j + 1] = minOf(
                    curr[j] + 1,
                    prev[j + 1] + 1,
                    prev[j] + cost
                )
            }
            for (j in prev.indices) {
                prev[j] = curr[j]
            }
        }
        return prev[b.length]
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

    private fun normalizeAndAlias(value: String): String {
        var normalized = normalize(value)
        phraseAliases.forEach { (canonical, aliases) ->
            if (normalized == canonical || aliases.any { alias -> normalized == normalize(alias) }) {
                normalized = canonical
                return@forEach
            }
        }
        return normalized
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
