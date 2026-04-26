package com.defense.tacticalmap

import android.Manifest
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.graphics.Color
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.style.layers.LineLayer
import com.mapbox.mapboxsdk.style.layers.PropertyFactory
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity(), RecognitionListener {

    private lateinit var mapView: MapView
    private var mapboxMap: MapboxMap? = null
    private lateinit var statusText: TextView
    private lateinit var transcriptionText: TextView
    private val tag = "OfflineTacticalMap"
    private var mapPackageInfo: MapPackageInfo? = null

    private var speechService: SpeechService? = null
    private var model: Model? = null
    private lateinit var spatialEngine: SpatialIntelligenceEngine
    private lateinit var routingEngine: TacticalRouterEngine
    
    companion object {
        private const val PERMISSIONS_REQUEST_RECORD_AUDIO = 1
    }

    data class MapPackageInfo(
        val tileCount: Int,
        val minZoom: Int,
        val maxZoom: Int,
        val bounds: DoubleArray
    ) {
        val centerLat: Double
            get() = (bounds[1] + bounds[3]) / 2.0

        val centerLon: Double
            get() = (bounds[0] + bounds[2]) / 2.0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize MapLibre (legacy Mapbox class) before setting content view
        Mapbox.getInstance(this)
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        statusText = findViewById(R.id.statusText)
        transcriptionText = findViewById(R.id.transcriptionText)
        
        spatialEngine = SpatialIntelligenceEngine(this)
        
        unpackGraphHopperAssets()
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
            this.mapboxMap = mapboxMap
            statusText.text = "Map Ready, Loading offline style..."
            try {
                val mbtilesPath = copyAssetToCache("mbtiles/sample_tactical.mbtiles")
                val packageInfo = inspectMbtilesPackage(mbtilesPath)
                mapPackageInfo = packageInfo

                if (packageInfo.tileCount <= 0) {
                    statusText.text = "Offline map package contains 0 tiles. Rebuild sample_tactical.mbtiles."
                    Log.e(tag, "MBTiles package is empty: $mbtilesPath")
                    return@getMapAsync
                }

                val tilesRoot = extractMbtilesToRasterTiles(mbtilesPath)
                val stylePath = prepareStyleJson(tilesRoot, packageInfo)

                mapboxMap.setStyle(Style.Builder().fromUri("file://$stylePath")) { _ ->
                    statusText.text = "Offline Tactical Map Active"
                    positionCamera(packageInfo)
                    Log.i(tag, "Map loaded offline successfully.")
                }
            } catch (e: Exception) {
                statusText.text = "Error loading offline map: ${e.message}"
                Log.e(tag, "Failed to load map style", e)
            }
        }
    }

    private fun initModel() {
        transcriptionText.text = "Unpacking Offline Acoustic Model..."
        StorageService.unpack(this, "models/model", "model",
            { model: Model ->
                this.model = model
                transcriptionText.text = "Acoustic Model Loaded. Listening..."
                recognizeMicrophone()
            },
            { exception: IOException ->
                transcriptionText.text = "Failed to unpack model: ${exception.message}\n(Please ensure Vosk model exists in assets/models)"
                Log.e(tag, "Failed to unpack model", exception)
            })
    }

    private fun recognizeMicrophone() {
        try {
            val rec = Recognizer(model, 16000.0f)
            speechService = SpeechService(rec, 16000.0f)
            speechService?.startListening(this)
        } catch (e: Exception) {
            Log.e(tag, "Exception starting speech service", e)
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
                        val demoRoute = buildDemoRouteRequest()
                        if (demoRoute == null) {
                            transcriptionText.text = "Routing blocked: offline map bounds unavailable."
                            return
                        }
                        try {
                            val routeCoords = routingEngine.calculateRoute(
                                demoRoute[0],
                                demoRoute[1],
                                demoRoute[2],
                                demoRoute[3]
                            )
                            if (routeCoords != null) {
                                transcriptionText.text = "Route calculated! Target: ${intent.entity}"
                                drawRouteOnMap(routeCoords)
                            } else {
                                val routingError = routingEngine.getLastError() ?: "Unknown GraphHopper error"
                                val fallbackRoute = buildFallbackRoute(demoRoute)
                                if (fallbackRoute != null) {
                                    transcriptionText.text = "GraphHopper unavailable. Rendering demo route.\n$routingError"
                                    drawRouteOnMap(fallbackRoute)
                                } else {
                                    transcriptionText.text = "Route calculation failed: $routingError"
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "Routing engine error", e)
                            transcriptionText.text = "Routing Error: ${e.message}"
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
        Log.e(tag, "Vosk Error", exception)
    }

    override fun onTimeout() {
        speechService?.startListening(this)
    }

    private fun drawRouteOnMap(routeCoords: List<DoubleArray>) {
        mapboxMap?.getStyle { style ->
            val points = routeCoords.map { Point.fromLngLat(it[0], it[1]) }
            val lineString = LineString.fromLngLats(points)
            val feature = Feature.fromGeometry(lineString)
            val featureCollection = FeatureCollection.fromFeatures(arrayOf(feature))
            
            val source = style.getSourceAs<GeoJsonSource>("tactical-route-source")
            if (source != null) {
                source.setGeoJson(featureCollection)
            } else {
                style.addSource(GeoJsonSource("tactical-route-source", featureCollection))
                style.addLayer(LineLayer("tactical-route-layer", "tactical-route-source").withProperties(
                    PropertyFactory.lineWidth(5f),
                    PropertyFactory.lineColor(Color.RED)
                ))
            }
        }
    }

    // --- Utility Methods for Offline Map ---

    private fun prepareStyleJson(tilesRoot: String, packageInfo: MapPackageInfo): String {
        val styleFile = File(cacheDir, "tactical_offline_style.json")
        try {
            val styleString = assets.open("styles/tactical_offline_style.json").bufferedReader().use { it.readText() }
            val updatedStyle = styleString
                .replace("{PLACEHOLDER_PATH}", "file://$tilesRoot/{z}/{x}/{y}.png")
                .replace("{PLACEHOLDER_BOUNDS}", packageInfo.bounds.joinToString(","))
                .replace("{PLACEHOLDER_MIN_ZOOM}", packageInfo.minZoom.toString())
                .replace("{PLACEHOLDER_MAX_ZOOM}", packageInfo.maxZoom.toString())
            
            FileOutputStream(styleFile).use { fos ->
                fos.write(updatedStyle.toByteArray())
            }
        } catch (e: IOException) {
            Log.e(tag, "Error processing style json", e)
            throw e
        }
        return styleFile.absolutePath
    }

    private fun inspectMbtilesPackage(mbtilesPath: String): MapPackageInfo {
        val db = SQLiteDatabase.openDatabase(mbtilesPath, null, SQLiteDatabase.OPEN_READONLY)
        db.use { database ->
            val tileCount = database.rawQuery("SELECT COUNT(*) FROM tiles", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }

            val zoomRange = database.rawQuery("SELECT MIN(zoom_level), MAX(zoom_level) FROM tiles", null).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0) && !cursor.isNull(1)) {
                    intArrayOf(cursor.getInt(0), cursor.getInt(1))
                } else {
                    intArrayOf(0, 0)
                }
            }

            val boundsString = database.rawQuery("SELECT value FROM metadata WHERE name = 'bounds'", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: throw IOException("MBTiles metadata is missing bounds")

            val bounds = boundsString.split(",").map { it.trim().toDouble() }.toDoubleArray()
            if (bounds.size != 4) {
                throw IOException("MBTiles bounds metadata is invalid: $boundsString")
            }

            return MapPackageInfo(
                tileCount = tileCount,
                minZoom = zoomRange[0],
                maxZoom = zoomRange[1],
                bounds = bounds
            )
        }
    }

    private fun extractMbtilesToRasterTiles(mbtilesPath: String): String {
        val packageInfo = mapPackageInfo ?: throw IOException("Map package info missing before tile extraction")
        val tilesRoot = File(cacheDir, "offline-raster-tiles")
        val markerFile = File(tilesRoot, ".extract-complete")
        val sourceSignature = buildMbtilesSignature(File(mbtilesPath), packageInfo)
        if (markerFile.exists() && markerFile.readText() == sourceSignature) {
            return tilesRoot.absolutePath
        }

        if (tilesRoot.exists()) {
            tilesRoot.deleteRecursively()
        }
        tilesRoot.mkdirs()

        val db = SQLiteDatabase.openDatabase(mbtilesPath, null, SQLiteDatabase.OPEN_READONLY)
        db.use { database ->
            database.rawQuery(
                "SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val zoom = cursor.getInt(0)
                    val x = cursor.getInt(1)
                    val tmsY = cursor.getInt(2)
                    val xyzY = ((1 shl zoom) - 1) - tmsY
                    val tileData = cursor.getBlob(3)

                    val outputFile = File(tilesRoot, "$zoom/$x/$xyzY.png")
                    outputFile.parentFile?.mkdirs()
                    FileOutputStream(outputFile).use { output ->
                        output.write(tileData)
                    }
                }
            }
        }

        FileOutputStream(markerFile).use { output ->
            output.write(sourceSignature.toByteArray())
        }

        return tilesRoot.absolutePath
    }

    private fun buildMbtilesSignature(mbtilesFile: File, packageInfo: MapPackageInfo): String {
        val digest = MessageDigest.getInstance("MD5")
        val digestBytes = digest.digest(mbtilesFile.absolutePath.toByteArray() + mbtilesFile.length().toString().toByteArray())
        val digestString = digestBytes.joinToString("") { "%02x".format(it) }
        return "tiles=${packageInfo.tileCount};digest=$digestString"
    }

    private fun positionCamera(packageInfo: MapPackageInfo) {
        mapboxMap?.cameraPosition = CameraPosition.Builder()
            .target(LatLng(packageInfo.centerLat, packageInfo.centerLon))
            .zoom(packageInfo.minZoom.toDouble())
            .build()
    }

    private fun buildDemoRouteRequest(): DoubleArray? {
        val packageInfo = mapPackageInfo ?: return null
        val latSpan = packageInfo.bounds[3] - packageInfo.bounds[1]
        val lonSpan = packageInfo.bounds[2] - packageInfo.bounds[0]
        val centerLat = packageInfo.centerLat
        val centerLon = packageInfo.centerLon

        return doubleArrayOf(
            centerLat - (latSpan * 0.05),
            centerLon - (lonSpan * 0.05),
            centerLat + (latSpan * 0.05),
            centerLon + (lonSpan * 0.05)
        )
    }

    private fun buildFallbackRoute(routeRequest: DoubleArray): List<DoubleArray>? {
        if (routeRequest.size < 4) return null

        val startLat = routeRequest[0]
        val startLon = routeRequest[1]
        val endLat = routeRequest[2]
        val endLon = routeRequest[3]
        val midLat = (startLat + endLat) / 2.0
        val midLon = (startLon + endLon) / 2.0

        return listOf(
            doubleArrayOf(startLon, startLat),
            doubleArrayOf(midLon, midLat),
            doubleArrayOf(endLon, endLat)
        )
    }
    
    private fun copyAssetToCache(assetName: String): String {
        val outFile = File(cacheDir, File(assetName).name)
        try {
            assets.open(assetName).use { input ->
                FileOutputStream(outFile, false).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: IOException) {
            Log.w(tag, "Warning: asset $assetName not found. Return placeholder path.")
            return outFile.absolutePath
        }
        return outFile.absolutePath
    }

    private fun unpackGraphHopperAssets() {
        val cacheFolder = File(cacheDir, "graphhopper-cache")
        val markerFile = File(cacheFolder, ".graphhopper-extract-signature")
        val assetSignature = buildGraphhopperAssetSignature()
        val graphFolderIsComplete = hasCompleteGraphCache(cacheFolder)

        if (markerFile.exists() && markerFile.readText() == assetSignature && graphFolderIsComplete) {
            Log.i(tag, "GraphHopper cache already unpacked with current asset signature.")
            return
        }

        statusText.text = "Unpacking GraphHopper map data..."
        if (cacheFolder.exists()) {
            cacheFolder.deleteRecursively()
        }
        cacheFolder.mkdirs()
        
        try {
            assets.open("graphhopper/graphhopper-cache.zip").use { inputStream ->
                ZipInputStream(inputStream).use { zis ->
                    var zipEntry = zis.nextEntry
                    while (zipEntry != null) {
                        val newFile = File(cacheFolder, zipEntry.name)
                        Log.d(tag, "Unpacking: ${zipEntry.name} to ${newFile.absolutePath}")
                        if (zipEntry.isDirectory) {
                            newFile.mkdirs()
                        } else {
                            newFile.parentFile?.mkdirs()
                            FileOutputStream(newFile).use { fos ->
                                zis.copyTo(fos)
                            }
                        }
                        zipEntry = zis.nextEntry
                    }
                }
            }
            FileOutputStream(markerFile).use { output ->
                output.write(assetSignature.toByteArray())
            }
            Log.i(tag, "GraphHopper cache unpacked successfully.")
        } catch (e: Exception) {
            Log.e(tag, "Failed to unpack GraphHopper", e)
            statusText.text = "Error unpacking GraphHopper: ${e.message}"
        }
    }

    private fun hasCompleteGraphCache(cacheFolder: File): Boolean {
        val graphFolder = cacheFolder.listFiles()
            ?.firstOrNull { it.isDirectory && it.name.endsWith("-gh") }
            ?: return false

        val requiredFiles = listOf("nodes", "edges", "geometry", "location_index", "properties")
        return requiredFiles.all { File(graphFolder, it).exists() }
    }

    private fun buildGraphhopperAssetSignature(): String {
        val digest = MessageDigest.getInstance("MD5")
        val totalSize = assets.open("graphhopper/graphhopper-cache.zip").use { input ->
            ZipInputStream(input).use { zis ->
                var sizeAccumulator = 0L
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    digest.update(entry.name.toByteArray())
                    sizeAccumulator += entry.size.coerceAtLeast(0L)
                    entry = zis.nextEntry
                }
                sizeAccumulator
            }
        }
        val digestString = digest.digest(totalSize.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        return "zip_size=$totalSize;digest=$digestString"
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
