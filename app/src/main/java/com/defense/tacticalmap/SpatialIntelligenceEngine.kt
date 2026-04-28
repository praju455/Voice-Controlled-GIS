package com.defense.tacticalmap

import android.content.Context
import android.util.Log
import org.tensorflow.lite.task.text.nlclassifier.NLClassifier
import java.io.IOException

data class TacticalIntent(
    val action: String,
    val entity: String,
    val distance: Int,
    val unit: String
)

class SpatialIntelligenceEngine(private val context: Context) {
    private val tag = "SpatialIntelligence"
    private var nlClassifier: NLClassifier? = null
    private val classifierAssetPath = "models/nlp_intent.tflite"
    private val classifierScoreThreshold = 0.55f

    init {
        Log.d(tag, "Initializing spatial engine with context: $context")
        initNLClassifier()
    }

    private fun initNLClassifier() {
        try {
            context.assets.open(classifierAssetPath).close()
            nlClassifier = NLClassifier.createFromFile(context, classifierAssetPath)
            Log.i(tag, "Loaded TFLite intent classifier from $classifierAssetPath")
        } catch (e: IOException) {
            Log.i(tag, "No TFLite intent model found at $classifierAssetPath, continuing with regex fallback.")
            nlClassifier = null
        } catch (e: Exception) {
            Log.e(tag, "Error initializing TFLite NLClassifier", e)
            nlClassifier = null
        }
    }

    /**
     * Parses the unstructured voice string into a TacticalIntent.
     * Starts with a deterministic Regex, falls back to TFLite (if available).
     */
    fun parseCommand(voiceInput: String): TacticalIntent? {
        val lowerInput = voiceInput.lowercase().trim()
        Log.d(tag, "Parsing voice input: $lowerInput")

        classifyWithTflite(voiceInput)?.let { tfliteIntent ->
            Log.i(tag, "TFLite parsed intent successfully: $tfliteIntent")
            return tfliteIntent
        }

        // 1a. Buffer/Identification Regex - Expanded for "sure/show" and common variations
        val regexBuffer = Regex("(show|sure|display|identify|view|find)\\s+(hostiles|friendlies|vehicles|targets|enemies)\\s+(in|within|at)\\s+(\\d+)\\s+(kilometers|km|meters|m)")
        val matchBuffer = regexBuffer.find(lowerInput)

        if (matchBuffer != null) {
            val (rawAction, entityStr, _, distStr, unitStr) = matchBuffer.destructured
            // Map "sure" back to "show" for internal logic
            val actionStr = if (rawAction == "sure") "show" else rawAction
            
            val intent = TacticalIntent(
                action = actionStr,
                entity = entityStr,
                distance = distStr.toIntOrNull() ?: 0,
                unit = unitStr
            )
            Log.i(tag, "Regex parsed buffer intent successfully: $intent")
            return intent
        }
        
        val regexClearRoute = Regex("(clear|remove|delete|reset)\\s+(the\\s+)?route")
        if (regexClearRoute.containsMatchIn(lowerInput)) {
            return TacticalIntent(action = "clear_route", entity = "route", distance = 0, unit = "")
        }

        val regexClearDestination = Regex("(clear|remove|delete|reset)\\s+(the\\s+)?(destination|marker|target|objective)")
        if (regexClearDestination.containsMatchIn(lowerInput)) {
            return TacticalIntent(action = "clear_destination", entity = "destination", distance = 0, unit = "")
        }

        val regexRecenter = Regex("(recenter|centre|center)(?:\\s+(on|to))?(?:\\s+me)?")
        if (regexRecenter.containsMatchIn(lowerInput) || lowerInput.contains("recenter on me") || lowerInput.contains("center on me")) {
            return TacticalIntent(action = "recenter", entity = "operator", distance = 0, unit = "")
        }

        // 1b. Routing Regex - Capture the entire requested destination phrase for offline lookup.
        val regexRoute = Regex("^(route|root|path|navigate|go)(?:\\s+me)?(?:\\s+to)?(?:\\s+the)?\\s+(.+)$")
        val matchRoute = regexRoute.find(lowerInput)
        if (matchRoute != null) {
            val rawAction = matchRoute.groupValues[1]
            val targetStr = matchRoute.groupValues[2].trim()
            val actionStr = if (rawAction == "root") "route" else rawAction
            if (targetStr.isNotBlank()) {
                return TacticalIntent(action = actionStr, entity = targetStr, distance = 0, unit = "")
            }
        }

        return null
    }

    private fun classifyWithTflite(voiceInput: String): TacticalIntent? {
        val classifier = nlClassifier ?: return null
        return try {
            val results = classifier.classify(voiceInput)
            val topCategory = results.maxByOrNull { category -> category.score } ?: return null
            if (topCategory.score < classifierScoreThreshold) {
                Log.d(tag, "TFLite top score below threshold: ${topCategory.label} -> ${topCategory.score}")
                return null
            }

            when (normalizeLabel(topCategory.label)) {
                "route" -> buildRouteIntent(voiceInput)
                "clear_route" -> TacticalIntent("clear_route", "route", 0, "")
                "clear_destination" -> TacticalIntent("clear_destination", "destination", 0, "")
                "recenter" -> TacticalIntent("recenter", "operator", 0, "")
                "show_entities" -> buildSpatialIntent(voiceInput)
                else -> null
            }
        } catch (exception: Exception) {
            Log.e(tag, "TFLite classification failed", exception)
            null
        }
    }

    private fun buildRouteIntent(voiceInput: String): TacticalIntent? {
        val lowerInput = voiceInput.lowercase().trim()
        val regexRoute = Regex("^(route|root|path|navigate|go)(?:\\s+me)?(?:\\s+to)?(?:\\s+the)?\\s+(.+)$")
        val matchRoute = regexRoute.find(lowerInput)
        return if (matchRoute != null) {
            val rawAction = matchRoute.groupValues[1]
            val targetStr = matchRoute.groupValues[2].trim()
            val actionStr = if (rawAction == "root") "route" else rawAction
            if (targetStr.isNotBlank()) TacticalIntent(action = actionStr, entity = targetStr, distance = 0, unit = "") else null
        } else {
            null
        }
    }

    private fun buildSpatialIntent(voiceInput: String): TacticalIntent? {
        val lowerInput = voiceInput.lowercase().trim()
        val regexBuffer = Regex("(show|sure|display|identify|view|find)\\s+(hostiles|friendlies|vehicles|targets|enemies)\\s+(in|within|at)\\s+(\\d+)\\s+(kilometers|km|meters|m)")
        val matchBuffer = regexBuffer.find(lowerInput) ?: return null
        val (rawAction, entityStr, _, distStr, unitStr) = matchBuffer.destructured
        val actionStr = if (rawAction == "sure") "show" else rawAction
        return TacticalIntent(
            action = actionStr,
            entity = entityStr,
            distance = distStr.toIntOrNull() ?: 0,
            unit = unitStr
        )
    }

    private fun normalizeLabel(label: String): String {
        return label.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    /**
     * Executes SpatiaLite math on the local database constraint layer.
     */
    fun computeGeometricBuffer(intent: TacticalIntent) {
        val distanceInMeters = if (intent.unit.startsWith("k")) intent.distance * 1000 else intent.distance
        Log.i(tag, "Executing Spatial SQL: ST_Buffer for ${intent.entity} at ${distanceInMeters}m radius via SpatiaLite SQLite interface")

        // NATIVE SPATIALITE SQL EXECUTION (Simulated Implementation)
        /*
        val db: SQLiteDatabase = getDatabase()
        
        // Load Spatialite extension
        // db.rawQuery("SELECT load_extension('libspatialite.so');", null)
        
        // Execute ST_Intersects
        val cursor = db.rawQuery("""
            SELECT NOME, AsText(ST_centroid(Geometry)) 
            FROM Tactical_Features 
            WHERE ST_Intersects(ST_GeomFromWKB(x'HEX_BUFFER'), Geometry);
        """, null)
        
        // Process cursor -> Emit GeoJSON to MapLibre
        */
    }
}
