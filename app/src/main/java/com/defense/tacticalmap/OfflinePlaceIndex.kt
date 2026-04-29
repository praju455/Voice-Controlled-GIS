package com.defense.tacticalmap

import android.content.Context
import android.location.Location
import android.util.JsonReader
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class OfflinePlaceIndex(
    private val context: Context,
    private val placeIndexAssetPath: String = DEFAULT_PLACE_INDEX_ASSET_PATH,
    private val savedTacticalPointsAssetPath: String = DEFAULT_SAVED_POINTS_ASSET_PATH
) {
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

    companion object {
        const val DEFAULT_PLACE_INDEX_ASSET_PATH = "places/place_index.json"
        const val DEFAULT_SAVED_POINTS_ASSET_PATH = "places/saved_tactical_points.json"
    }

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
        val output = ArrayList<PlaceRecord>()

        // Saved tactical points are loaded first so exact voice matches prefer mission-specific names
        // over similarly named generic OSM places.
        loadOptionalTacticalPoints(output)

        context.assets.open(placeIndexAssetPath).bufferedReader().use { reader ->
            JsonReader(reader).use { jsonReader ->
                appendPlaceArray(output, jsonReader)
            }
        }
        return output
    }

    private fun loadOptionalTacticalPoints(output: MutableList<PlaceRecord>) {
        runCatching {
            context.assets.open(savedTacticalPointsAssetPath).bufferedReader().use { reader ->
                JsonReader(reader).use { jsonReader ->
                    appendPlaceArray(output, jsonReader)
                }
            }
        }.onSuccess {
            // tactical points loaded successfully
        }
    }

    private fun appendPlaceArray(output: MutableList<PlaceRecord>, jsonReader: JsonReader) {
        jsonReader.beginArray()
        while (jsonReader.hasNext()) {
            appendPlaceRecord(output, jsonReader)
        }
        jsonReader.endArray()
    }

    private fun appendPlaceRecord(output: MutableList<PlaceRecord>, jsonReader: JsonReader) {
        val place = readPlaceDefinition(jsonReader)
        output += buildPlaceRecord(place)
        place.aliases.forEach { alias ->
            if (alias.isNotBlank()) {
                output += buildPlaceRecord(place, overrideName = alias)
            }
        }
    }

    private data class PlaceDefinition(
        val name: String,
        val normalizedName: String,
        val categoryKey: String,
        val categoryValue: String,
        val lat: Double,
        val lon: Double,
        val aliases: List<String>
    )

    private fun readPlaceDefinition(jsonReader: JsonReader): PlaceDefinition {
        var name = ""
        var normalizedName = ""
        var categoryKey = ""
        var categoryValue = ""
        var lat = 0.0
        var lon = 0.0
        val aliases = mutableListOf<String>()

        jsonReader.beginObject()
        while (jsonReader.hasNext()) {
            when (jsonReader.nextName()) {
                "name" -> name = jsonReader.nextString()
                "normalizedName" -> normalizedName = jsonReader.nextString()
                "categoryKey" -> categoryKey = jsonReader.nextString()
                "categoryValue" -> categoryValue = jsonReader.nextString()
                "lat" -> lat = jsonReader.nextDouble()
                "lon" -> lon = jsonReader.nextDouble()
                "aliases" -> {
                    jsonReader.beginArray()
                    while (jsonReader.hasNext()) {
                        aliases += jsonReader.nextString()
                    }
                    jsonReader.endArray()
                }
                else -> jsonReader.skipValue()
            }
        }
        jsonReader.endObject()

        return PlaceDefinition(
            name = name,
            normalizedName = normalizedName,
            categoryKey = categoryKey,
            categoryValue = categoryValue,
            lat = lat,
            lon = lon,
            aliases = aliases
        )
    }

    private fun buildPlaceRecord(place: PlaceDefinition, overrideName: String? = null): PlaceRecord {
        val name = overrideName ?: place.name
        return PlaceRecord(
            name = place.name,
            normalizedName = normalize(overrideName ?: place.normalizedName.ifBlank { name }),
            categoryKey = place.categoryKey,
            categoryValue = normalize(place.categoryValue),
            lat = place.lat,
            lon = place.lon
        )
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
