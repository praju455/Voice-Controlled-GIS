package com.defense.tacticalmap

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var statusText: TextView
    private val TAG = "OfflineTacticalMap"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize MapLibre before setting content view
        MapLibre.getInstance(this)
        
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        statusText = findViewById(R.id.statusText)
        
        mapView.onCreate(savedInstanceState)

        mapView.getMapAsync { mapboxMap ->
            statusText.text = "Map Ready, Loading offline style..."
            
            try {
                // 1. Copy mbtiles to a writable location (mock placeholder for now)
                val mbtilesPath = copyAssetToCache("mbtiles/sample_tactical.mbtiles")
                
                // 2. Prepare the Style JSON
                val stylePath = prepareStyleJson(mbtilesPath)
                
                // 3. Load the localized style
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

    private fun prepareStyleJson(mbtilesPath: String): String {
        val styleFile = File(cacheDir, "tactical_offline_style.json")
        try {
            val styleString = assets.open("styles/tactical_offline_style.json").bufferedReader().use { it.readText() }
            
            // Inject the absolute mbtiles path into the JSON as requested in the architecture document
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
                Log.e(TAG, "Failed to copy asset: $assetName (this is expected if no placeholder is provided yet)", e)
                // We return a fake path if dummy asset doesn't exist just to test configuration
                return outFile.absolutePath
            }
        }
        return outFile.absolutePath
    }

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
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}
