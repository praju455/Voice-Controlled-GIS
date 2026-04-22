package com.defense.tacticalmap

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : AppCompatActivity(), RecognitionListener {

    private lateinit var mapView: MapView
    private lateinit var statusText: TextView
    private lateinit var transcriptionText: TextView
    private val TAG = "OfflineTacticalMap"

    private var speechService: SpeechService? = null
    private var model: Model? = null
    private lateinit var spatialEngine: SpatialIntelligenceEngine
    private lateinit var routingEngine: TacticalRouterEngine
    
    companion object {
        private const val PERMISSIONS_REQUEST_RECORD_AUDIO = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize MapLibre before setting content view
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        statusText = findViewById(R.id.statusText)
        transcriptionText = findViewById(R.id.transcriptionText)
        
        spatialEngine = SpatialIntelligenceEngine(this)
        
        val routingCache = File(cacheDir, "graphhopper-cache").absolutePath
        routingEngine = TacticalRouterEngine(this, routingCache)
        
        mapView.onCreate(savedInstanceState)
        
        // Setup Map
        initializeMap()

        // Check Permissions for Vosk
        val permissionCheck = ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO)
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSIONS_REQUEST_RECORD_AUDIO)
        } else {
            initModel()
        }
    }

    private fun initializeMap() {
        mapView.getMapAsync { mapboxMap ->
            statusText.text = "Map Ready, Loading offline style..."
            try {
                val mbtilesPath = copyAssetToCache("mbtiles/sample_tactical.mbtiles")
                val stylePath = prepareStyleJson(mbtilesPath)
                
                mapboxMap.setStyle(Style.Builder().fromUri("file://$stylePath")) { style ->
                    statusText.text = "Offline Tactical Map Active"
                    Log.i(TAG, "Map loaded offline successfully.")
                }
            } catch (e: Exception) {
                statusText.text = "Error loading offline map: ${e.message}"
                Log.e(TAG, "Failed to load map style", e)
            }
        }
    }

    private fun initModel() {
        transcriptionText.text = "Unpacking Offline Acoustic Model..."
        StorageService.unpack(this, "models", "model",
            { model: Model ->
                this.model = model
                transcriptionText.text = "Acoustic Model Loaded. Listening..."
                recognizeMicrophone()
            },
            { exception: IOException ->
                transcriptionText.text = "Failed to unpack model: ${exception.message}\n(Please ensure Vosk model exists in assets/models)"
                Log.e(TAG, "Failed to unpack model", exception)
            })
    }

    private fun recognizeMicrophone() {
        try {
            val rec = Recognizer(model, 16000.0f)
            speechService = SpeechService(rec, 16000.0f)
            speechService?.startListening(this)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting speech service", e)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initModel()
            } else {
                transcriptionText.text = "Microphone permission denied. Voice disabled."
            }
        }
    }

    // --- Vosk RecognitionListener overrides ---

    override fun onResult(hypothesis: String?) {
        hypothesis?.let { 
            val match = Regex("\"text\"\\s*:\\s*\"(.*?)\"").find(it)
            val parsedText = match?.groups?.get(1)?.value ?: ""
            if (parsedText.isNotBlank()) {
                transcriptionText.text = "Voice string: $parsedText"
                
                // Pass the string to the Spatial Intelligence Engine
                val intent = spatialEngine.parseCommand(parsedText)
                if (intent != null) {
                    if (intent.action == "route") {
                        transcriptionText.text = "INTENT: Route to ${intent.entity}\n\nCalculating Offline Path (GraphHopper)..."
                        // Mock coordinates from operator to objective
                        val routeCoords = routingEngine.calculateRoute(46.498295, 11.354758, 46.501, 11.360)
                        if (routeCoords != null) {
                            transcriptionText.text = "Route calculated! Target: ${intent.entity}"
                            // Normally we would pass routeCoords to MapLibre GeoJsonSource
                        } else {
                            transcriptionText.text = "Route calculation failed. Ensure .gh map exists."
                        }
                    } else {
                        val formatted = "INTENT:\nAction: ${intent.action}\nTarget: ${intent.entity}\nRange: ${intent.distance} ${intent.unit}"
                        transcriptionText.text = "$formatted\n\nCalculating SpatiaLite Buffer..."
                        spatialEngine.computeGeometricBuffer(intent)
                    }
                } else {
                    transcriptionText.text = "Unrecognized command phrase."
                }
            }
        }
    }

    override fun onPartialResult(hypothesis: String?) {
        hypothesis?.let {
            val match = Regex("\"partial\"\\s*:\\s*\"(.*?)\"").find(it)
            val parsedText = match?.groups?.get(1)?.value ?: ""
            if (parsedText.isNotBlank()) {
                transcriptionText.text = "Hearing: $parsedText"
            }
        }
    }

    override fun onFinalResult(hypothesis: String?) {
        // Similar to onResult, triggers when there is a longer pause
        onResult(hypothesis)
    }

    override fun onError(exception: Exception?) {
        transcriptionText.text = "Voice Engine Error: ${exception?.message}"
        Log.e(TAG, "Vosk Error", exception)
    }

    override fun onTimeout() {
        speechService?.startListening(this)
    }

    // --- Utility Methods for Offline Map ---

    private fun prepareStyleJson(mbtilesPath: String): String {
        val styleFile = File(cacheDir, "tactical_offline_style.json")
        try {
            val styleString = assets.open("styles/tactical_offline_style.json").bufferedReader().use { it.readText() }
            val updatedStyle = styleString.replace("{PLACEHOLDER_PATH}", mbtilesPath)
            
            FileOutputStream(styleFile).use { fos ->
                fos.write(updatedStyle.toByteArray())
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error processing style json", e)
            throw e
        }
        return styleFile.absolutePath
    }
    
    private fun copyAssetToCache(assetName: String): String {
        val outFile = File(cacheDir, File(assetName).name)
        if (!outFile.exists()) {
            try {
                assets.open(assetName).use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Warning: asset $assetName not found. Return placeholder path.")
                return outFile.absolutePath
            }
        }
        return outFile.absolutePath
    }

    // --- MapView and App Lifecycle ---

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        speechService?.setPause(true)
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
        speechService?.setPause(true)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDestroy()
        speechService?.cancel()
        speechService?.shutdown()
        model?.close()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}
