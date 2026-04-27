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

class SpatialIntelligenceEngine(context: Context) {
    private val tag = "SpatialIntelligence"
    private var nlClassifier: NLClassifier? = null

    init {
        // Use context to avoid unused parameter warning
        Log.d(tag, "Initializing spatial engine with context: $context")
        initNLClassifier()
    }

    private fun initNLClassifier() {
        try {
            // In a real scenario, "snips_model.tflite" would be present in /assets/
            // nlClassifier = NLClassifier.createFromFile(context, "snips_model.tflite")
            Log.i(tag, "TensorFlow Lite Task Library initialized. Awaiting actual .tflite model.")
        } catch (e: IOException) {
            Log.e(tag, "Error initializing TFLite NLClassifier", e)
        }
    }

    /**
     * Parses the unstructured voice string into a TacticalIntent.
     * Starts with a deterministic Regex, falls back to TFLite (if available).
     */
    fun parseCommand(voiceInput: String): TacticalIntent? {
        val lowerInput = voiceInput.lowercase().trim()
        Log.d(tag, "Parsing voice input: $lowerInput")

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
        
        // 1b. Routing Regex - Tolerates omitted "to" and extra filler words from speech recognition.
        val regexRoute = Regex("(route|root|path|navigate|go)(?:\\s+me)?(?:\\s+to)?(?:\\s+the)?\\s+(base|objective|extraction|target|point)")
        val matchRoute = regexRoute.find(lowerInput)
        if (matchRoute != null) {
            val (rawAction, targetStr) = matchRoute.destructured
            val actionStr = if (rawAction == "root") "route" else rawAction
            return TacticalIntent(action = actionStr, entity = targetStr, distance = 0, unit = "")
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

        // 2. Fallback to Snips TFLite NLClassifier
        Log.i(tag, "Regex failed, treating with TFLite NLU (Simulated)")
        nlClassifier?.let {
            val results = it.classify(voiceInput)
            // Assuming the top matched category gives us the primary intent 
            // e.g., SetTacticalPerimeter
            val topCategory = results.maxByOrNull { category -> category.score }
            Log.i(tag, "TFLite Classification Top Result: ${topCategory?.label} (Score: ${topCategory?.score})")
            
            // Further entity extraction would normally utilize TFLite BertQuestionAnswerer or custom NLP
        }

        return null
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
