package com.defense.tacticalmap

import android.Manifest
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.drawable.AnimationDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import android.graphics.Color
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.style.layers.CircleLayer
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

    // ── Legacy hidden views (kept for code compatibility) ──
    private lateinit var statusText: TextView
    private lateinit var routeSummaryText: TextView

    // ── New UI components ──
    private lateinit var transcriptionText: TextView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var routeSummaryCard: CardView
    private lateinit var routeDestinationText: TextView
    private lateinit var routeRemainingText: TextView
    private lateinit var routeEtaText: TextView
    private lateinit var routeTotalText: TextView
    private lateinit var routeProgressBar: ProgressBar
    private lateinit var routeStatusChip: TextView
    private lateinit var destinationPanel: CardView
    private lateinit var destPanelName: TextView
    private lateinit var destPanelCoords: TextView
    private lateinit var destPanelDistance: TextView
    private lateinit var topBarRegion: TextView
    private lateinit var micIndicatorDot: View
    private lateinit var micStatusLabel: TextView
    private var micPulseAnim: AnimationDrawable? = null
    private var isDarkMode = true

    private val tag = "OfflineTacticalMap"
    private var mapPackageInfo: MapPackageInfo? = null
    private lateinit var locationManager: LocationManager
    private var currentLocation: Location? = null
    private var hasCenteredOnOperator = false
    private var selectedDestination: LatLng? = null
    private var activeRouteCoords: List<DoubleArray>? = null
    private var activeRouteTotalDistanceMeters: Double = 0.0
    private var activeRouteTotalDurationMillis: Long = 0L
    private var activeRouteDestinationLabel: String? = null
    private var lastRerouteTimestampMs: Long = 0L
    private var rerouteInProgress: Boolean = false

    private var speechService: SpeechService? = null
    private var model: Model? = null
    private lateinit var spatialEngine: SpatialIntelligenceEngine
    private lateinit var routingEngine: TacticalRouterEngine
    private lateinit var placeIndex: OfflinePlaceIndex
    private val locationListener = LocationListener { location ->
        currentLocation = location
        drawCurrentLocation(location)
        updateLocationStatus(location)
        updateActiveRouteProgress()
        maybeRerouteOnDeviation(location)
        if (!hasCenteredOnOperator && isInsideOfflineBounds(location)) {
            mapboxMap?.cameraPosition = CameraPosition.Builder()
                .target(LatLng(location.latitude, location.longitude))
                .zoom((mapPackageInfo?.maxZoom ?: 15).toDouble().coerceAtMost(16.0))
                .build()
            hasCenteredOnOperator = true
        }
    }
    
    companion object {
        private const val PERMISSIONS_REQUEST_RECORD_AUDIO = 1
        private const val PERMISSIONS_REQUEST_LOCATION = 2
        private const val OPERATOR_SOURCE_ID = "operator-location-source"
        private const val OPERATOR_LAYER_ID = "operator-location-layer"
        private const val DESTINATION_SOURCE_ID = "destination-source"
        private const val DESTINATION_LAYER_ID = "destination-layer"
        private const val ROUTE_SOURCE_ID = "tactical-route-source"
        private const val ROUTE_LAYER_ID = "tactical-route-layer"
        private const val OFF_ROUTE_THRESHOLD_METERS = 60.0
        private const val REROUTE_COOLDOWN_MS = 8000L
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
        // Restore dark/light mode preference before inflation
        val prefs = getSharedPreferences("veer_rakshak_prefs", MODE_PRIVATE)
        isDarkMode = prefs.getBoolean("dark_mode", true)
        val desiredNightMode = if (isDarkMode) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
        if (AppCompatDelegate.getDefaultNightMode() != desiredNightMode) {
            AppCompatDelegate.setDefaultNightMode(desiredNightMode)
        }

        super.onCreate(savedInstanceState)

        // Initialize MapLibre (legacy Mapbox class) before setting content view
        Mapbox.getInstance(this)
        setContentView(R.layout.activity_main)

        // ── Legacy views (kept for compat, visibility=gone) ──
        mapView = findViewById(R.id.mapView)
        statusText = findViewById(R.id.statusText)
        routeSummaryText = findViewById(R.id.routeSummaryText)

        // ── New UI views ──
        transcriptionText  = findViewById(R.id.transcriptionText)
        drawerLayout       = findViewById(R.id.drawerLayout)
        routeSummaryCard   = findViewById(R.id.routeSummaryCard)
        routeDestinationText = findViewById(R.id.routeDestinationText)
        routeRemainingText = findViewById(R.id.routeRemainingText)
        routeEtaText       = findViewById(R.id.routeEtaText)
        routeTotalText     = findViewById(R.id.routeTotalText)
        routeProgressBar   = findViewById(R.id.routeProgressBar)
        routeStatusChip    = findViewById(R.id.routeStatusChip)
        destinationPanel   = findViewById(R.id.destinationPanel)
        destPanelName      = findViewById(R.id.destPanelName)
        destPanelCoords    = findViewById(R.id.destPanelCoords)
        destPanelDistance  = findViewById(R.id.destPanelDistance)
        topBarRegion       = findViewById(R.id.topBarRegion)
        micIndicatorDot    = findViewById(R.id.micIndicatorDot)
        micStatusLabel     = findViewById(R.id.micStatusLabel)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        setupDrawerAndTopBar()
        
        spatialEngine = SpatialIntelligenceEngine(this)
        placeIndex = OfflinePlaceIndex(this)
        placeIndex.preloadAsync {
            Handler(Looper.getMainLooper()).post {
                Log.i(tag, "Offline place index ready.")
            }
        }
        
        unpackGraphHopperAssets()
        val routingCache = File(cacheDir, "graphhopper-cache").absolutePath
        routingEngine = TacticalRouterEngine(this, routingCache)
        
        mapView.onCreate(savedInstanceState)
        
        // Setup Map
        initializeMap()
        ensureLocationAccess()

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
                    currentLocation?.let { drawCurrentLocation(it) }
                    selectedDestination?.let { drawDestinationMarker(it) }
                    mapboxMap.addOnMapClickListener { point ->
                        handleDestinationSelection(point)
                        true
                    }
                    Log.i(tag, "Map loaded offline successfully.")
                }
            } catch (e: Exception) {
                statusText.text = "Error loading offline map: ${e.message}"
                Log.e(tag, "Failed to load map style", e)
            }
        }
    }

    // ── Drawer, top bar, and sidebar wiring ──────────────────────────────────

    private fun setupDrawerAndTopBar() {
        // Hamburger opens drawer
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener {
            drawerLayout.openDrawer(androidx.core.view.GravityCompat.START)
        }

        // Dark mode toggle (top bar icon)
        findViewById<ImageButton>(R.id.btnDarkModeToggle).setOnClickListener {
            isDarkMode = !isDarkMode
            getSharedPreferences("veer_rakshak_prefs", MODE_PRIVATE)
                .edit().putBoolean("dark_mode", isDarkMode).apply()
            applyThemeMode(isDarkMode)
            // Recreate to apply theme change
            recreate()
        }

        // Sidebar dark mode switch (mirrors top bar toggle)
        val switchDark = findViewById<androidx.appcompat.widget.SwitchCompat?>(R.id.switchDarkMode)
        switchDark?.isChecked = isDarkMode
        switchDark?.setOnCheckedChangeListener { _, checked ->
            isDarkMode = checked
            getSharedPreferences("veer_rakshak_prefs", MODE_PRIVATE)
                .edit().putBoolean("dark_mode", isDarkMode).apply()
            applyThemeMode(isDarkMode)
            drawerLayout.closeDrawers()
            recreate()
        }

        // Region items
        findViewById<View?>(R.id.regionBengaluru)?.setOnClickListener {
            topBarRegion.text = "● BENGALURU"
            drawerLayout.closeDrawers()
        }
        findViewById<View?>(R.id.regionSiachen)?.setOnClickListener {
            Toast.makeText(this, getString(R.string.region_not_loaded), Toast.LENGTH_SHORT).show()
        }
        findViewById<View?>(R.id.regionLoC)?.setOnClickListener {
            Toast.makeText(this, getString(R.string.region_not_loaded), Toast.LENGTH_SHORT).show()
        }

        // Sidebar map control buttons
        findViewById<View?>(R.id.btnDrawerRecenter)?.setOnClickListener {
            if (recenterOnOperator()) {
                transcriptionText.text = "Recentering on operator position."
            } else {
                transcriptionText.text = "Cannot recenter: waiting for GPS fix inside the offline zone."
            }
            drawerLayout.closeDrawers()
        }
        findViewById<View?>(R.id.btnDrawerClearRoute)?.setOnClickListener {
            clearRouteOverlay()
            transcriptionText.text = "Route cleared."
            drawerLayout.closeDrawers()
        }
        findViewById<View?>(R.id.btnDrawerClearDestination)?.setOnClickListener {
            clearDestinationSelection()
            transcriptionText.text = "Destination cleared."
            drawerLayout.closeDrawers()
        }

        // Route card close button
        findViewById<ImageButton?>(R.id.btnCloseRouteCard)?.setOnClickListener {
            clearRouteOverlay()
        }

        // Destination panel close
        findViewById<ImageButton?>(R.id.btnCloseDestPanel)?.setOnClickListener {
            hideDestinationPanel()
        }

        // Destination panel Route Here button
        findViewById<View?>(R.id.btnRouteHere)?.setOnClickListener {
            hideDestinationPanel()
            // Guard: ensure a destination has been selected before routing
            selectedDestination ?: return@setOnClickListener
            transcriptionText.text = "INTENT: Route to selected point\n\nCalculating Offline Path..."
            val routeRequestResult = buildRouteRequest("objective")
            if (routeRequestResult == null) {
                transcriptionText.text = "Routing blocked: waiting for offline map package."
                return@setOnClickListener
            }
            if (routeRequestResult.errorMessage != null) {
                transcriptionText.text = routeRequestResult.errorMessage
                return@setOnClickListener
            }
            val coords = routeRequestResult.coordinates ?: run {
                transcriptionText.text = "Routing blocked: operator GPS unavailable."
                return@setOnClickListener
            }
            try {
                val routeResult = routingEngine.calculateRoute(coords[0], coords[1], coords[2], coords[3])
                if (routeResult != null) {
                    transcriptionText.text = "Route calculated! Target: ${routeRequestResult.destinationLabel}"
                    drawRouteOnMap(routeResult.coordinates)
                    setActiveRoute(routeResult, routeRequestResult.destinationLabel)
                    focusCameraOnRoute(routeResult.coordinates)
                    updateActiveRouteProgress()
                } else {
                    transcriptionText.text = "Route calculation failed: ${routingEngine.getLastError()}"
                    clearRouteSummary()
                }
            } catch (e: Exception) {
                transcriptionText.text = "Routing Error: ${e.message}"
                clearRouteSummary()
            }
        }

        // Clear destination button in panel
        findViewById<View?>(R.id.btnClearDest)?.setOnClickListener {
            clearDestinationSelection()
            hideDestinationPanel()
            transcriptionText.text = "Destination cleared."
        }
    }

    private fun applyThemeMode(dark: Boolean) {
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun showDestinationPanel(point: LatLng, label: String) {
        destPanelName.text = label
        destPanelCoords.text = "%.5f°N  %.5f°E".format(point.latitude, point.longitude)
        val dist = currentLocation?.let {
            val result = FloatArray(1)
            Location.distanceBetween(it.latitude, it.longitude, point.latitude, point.longitude, result)
            formatDistance(result[0].toDouble())
        } ?: "—"
        destPanelDistance.text = dist
        destinationPanel.visibility = View.VISIBLE
    }

    private fun hideDestinationPanel() {
        destinationPanel.visibility = View.GONE
    }

    // ─────────────────────────────────────────────────────────────────────────

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
            // Start mic pulse animation
            micPulseAnim = micIndicatorDot.background as? AnimationDrawable
            micPulseAnim?.start()
            micStatusLabel.text = "LIVE"
        } catch (e: Exception) {
            Log.e(tag, "Exception starting speech service", e)
            micStatusLabel.text = "ERR"
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
        } else if (requestCode == PERMISSIONS_REQUEST_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                startLocationTracking()
            } else {
                statusText.text = "Offline Tactical Map Active (Location permission denied)"
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
                        val routeRequestResult = buildRouteRequest(intent.entity)
                        if (routeRequestResult == null) {
                            transcriptionText.text = "Routing blocked: waiting for offline map package."
                            return
                        }
                        if (routeRequestResult.errorMessage != null) {
                            transcriptionText.text = routeRequestResult.errorMessage
                            return
                        }
                        val routeRequest = routeRequestResult.coordinates ?: run {
                            transcriptionText.text = "Routing blocked: operator GPS unavailable."
                            return
                        }
                        try {
                            val routeResult = routingEngine.calculateRoute(
                                routeRequest[0],
                                routeRequest[1],
                                routeRequest[2],
                                routeRequest[3]
                            )
                            if (routeResult != null) {
                                val destinationLabel = routeRequestResult.destinationLabel
                                transcriptionText.text = "Route calculated! Target: $destinationLabel"
                                drawRouteOnMap(routeResult.coordinates)
                                setActiveRoute(routeResult, destinationLabel)
                                focusCameraOnRoute(routeResult.coordinates)
                                updateActiveRouteProgress()
                            } else {
                                val routingError = routingEngine.getLastError() ?: "Unknown GraphHopper error"
                                transcriptionText.text = "Route calculation failed: $routingError"
                                clearRouteSummary()
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "Routing engine error", e)
                            transcriptionText.text = "Routing Error: ${e.message}"
                            clearRouteSummary()
                        }
                    } else if (intent.action == "clear_route") {
                        clearRouteOverlay()
                        transcriptionText.text = "Route cleared."
                    } else if (intent.action == "clear_destination") {
                        clearDestinationSelection()
                        transcriptionText.text = "Destination cleared."
                    } else if (intent.action == "recenter") {
                        if (recenterOnOperator()) {
                            transcriptionText.text = "Recentering on operator position."
                        } else {
                            transcriptionText.text = "Cannot recenter: waiting for GPS fix inside the offline zone."
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
                style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, featureCollection))
                style.addLayer(LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                    PropertyFactory.lineWidth(5f),
                    PropertyFactory.lineColor("#F3C969") // Tactical amber — better contrast on dark map
                ))
            }
        }
    }

    private fun drawCurrentLocation(location: Location) {
        mapboxMap?.getStyle { style ->
            val featureCollection = FeatureCollection.fromFeatures(
                arrayOf(Feature.fromGeometry(Point.fromLngLat(location.longitude, location.latitude)))
            )

            val source = style.getSourceAs<GeoJsonSource>(OPERATOR_SOURCE_ID)
            if (source != null) {
                source.setGeoJson(featureCollection)
            } else {
                style.addSource(GeoJsonSource(OPERATOR_SOURCE_ID, featureCollection))
                style.addLayer(
                    CircleLayer(OPERATOR_LAYER_ID, OPERATOR_SOURCE_ID).withProperties(
                        PropertyFactory.circleRadius(7f),
                        PropertyFactory.circleColor("#00BCD4"),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(2f)
                    )
                )
            }
        }
    }

    private fun drawDestinationMarker(destination: LatLng) {
        mapboxMap?.getStyle { style ->
            val featureCollection = FeatureCollection.fromFeatures(
                arrayOf(Feature.fromGeometry(Point.fromLngLat(destination.longitude, destination.latitude)))
            )

            val source = style.getSourceAs<GeoJsonSource>(DESTINATION_SOURCE_ID)
            if (source != null) {
                source.setGeoJson(featureCollection)
            } else {
                style.addSource(GeoJsonSource(DESTINATION_SOURCE_ID, featureCollection))
                style.addLayer(
                    CircleLayer(DESTINATION_LAYER_ID, DESTINATION_SOURCE_ID).withProperties(
                        PropertyFactory.circleRadius(8f),
                        PropertyFactory.circleColor("#FF3B30"),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(2f)
                    )
                )
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
        val location = currentLocation
        val targetLatLng = if (location != null && isInsideOfflineBounds(location)) {
            LatLng(location.latitude, location.longitude)
        } else {
            LatLng(packageInfo.centerLat, packageInfo.centerLon)
        }
        mapboxMap?.cameraPosition = CameraPosition.Builder()
            .target(targetLatLng)
            .zoom(packageInfo.minZoom.toDouble())
            .build()
    }

    private data class RouteRequestResult(
        val coordinates: DoubleArray? = null,
        val errorMessage: String? = null,
        val destinationLabel: String = "objective"
    )

    private data class ResolvedDestination(
        val latitude: Double,
        val longitude: Double,
        val label: String
    )

    private fun buildRouteRequest(destinationPhrase: String): RouteRequestResult? {
        val packageInfo = mapPackageInfo ?: return null
        val location = currentLocation
            ?: return RouteRequestResult(errorMessage = "Waiting for GPS fix before routing.")
        if (!isInsideOfflineBounds(location)) {
            return RouteRequestResult(
                errorMessage = "Current GPS is outside the offline mission zone. Move inside the loaded area to calculate a route."
            )
        }

        val resolvedDestination = resolveDestinationPoint(destinationPhrase, packageInfo, location)
            ?: return RouteRequestResult(errorMessage = "Destination \"$destinationPhrase\" was not found in the offline place index.")
        return RouteRequestResult(
            coordinates = doubleArrayOf(
                location.latitude,
                location.longitude,
                resolvedDestination.latitude,
                resolvedDestination.longitude
            ),
            destinationLabel = resolvedDestination.label
        )
    }

    private fun resolveDestinationPoint(
        destinationPhrase: String,
        packageInfo: MapPackageInfo,
        currentLocation: Location
    ): ResolvedDestination? {
        val normalized = destinationPhrase.lowercase().trim()
        if (normalized in setOf("objective", "target", "point", "selected point")) {
            selectedDestination?.let {
                return ResolvedDestination(it.latitude, it.longitude, "selected point")
            }
            val objectivePoint = buildObjectivePoint(packageInfo)
            return ResolvedDestination(objectivePoint[0], objectivePoint[1], "objective")
        }

        if (!placeIndex.isReady()) {
            val loadError = placeIndex.getLoadError()
            if (loadError != null) {
                transcriptionText.text = "Offline place index failed to load: $loadError"
            } else {
                transcriptionText.text = "Offline place index is still loading. Try the destination command again in a moment."
            }
            return null
        }

        val placeMatch = placeIndex.resolve(destinationPhrase, currentLocation) ?: return null
        val destinationLatLng = LatLng(placeMatch.lat, placeMatch.lon)
        selectedDestination = destinationLatLng
        drawDestinationMarker(destinationLatLng)
        return ResolvedDestination(placeMatch.lat, placeMatch.lon, placeMatch.name)
    }

    private fun buildObjectivePoint(packageInfo: MapPackageInfo): DoubleArray {
        selectedDestination?.let {
            return doubleArrayOf(it.latitude, it.longitude)
        }
        val latSpan = packageInfo.bounds[3] - packageInfo.bounds[1]
        val lonSpan = packageInfo.bounds[2] - packageInfo.bounds[0]
        return doubleArrayOf(
            packageInfo.centerLat + (latSpan * 0.05),
            packageInfo.centerLon + (lonSpan * 0.05)
        )
    }

    private fun handleDestinationSelection(point: LatLng) {
        if (!isInsideOfflineBounds(point)) {
            transcriptionText.text = "Selected point is outside the offline mission zone."
            return
        }
        selectedDestination = point
        drawDestinationMarker(point)
        transcriptionText.text = "Destination selected. Say \"route to objective\" to navigate."
        showDestinationPanel(point, "Selected Point")
    }

    private fun clearRouteOverlay() {
        mapboxMap?.getStyle { style ->
            val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID) ?: return@getStyle
            source.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        }
        clearActiveRoute()
        clearRouteSummary()
    }

    private fun clearDestinationSelection() {
        selectedDestination = null
        clearRouteOverlay()
        mapboxMap?.getStyle { style ->
            val source = style.getSourceAs<GeoJsonSource>(DESTINATION_SOURCE_ID) ?: return@getStyle
            source.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        }
    }

    private fun ensureLocationAccess() {
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fineGranted || coarseGranted) {
            startLocationTracking()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                PERMISSIONS_REQUEST_LOCATION
            )
        }
    }

    private fun startLocationTracking() {
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val gpsLast = runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
        val networkLast = runCatching { locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
        val bestLast = listOfNotNull(gpsLast, networkLast).maxByOrNull { it.time }
        bestLast?.let {
            currentLocation = it
            drawCurrentLocation(it)
            updateLocationStatus(it)
        } ?: run {
            statusText.text = "Offline Tactical Map Active (Waiting for GPS fix)"
        }

        runCatching {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 5f, locationListener)
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 5f, locationListener)
            }
        }.onFailure { exception ->
            Log.e(tag, "Failed to start location tracking", exception)
        }
    }

    private fun isInsideOfflineBounds(location: Location): Boolean {
        val packageInfo = mapPackageInfo ?: return false
        val lon = location.longitude
        val lat = location.latitude
        return lon in packageInfo.bounds[0]..packageInfo.bounds[2] &&
            lat in packageInfo.bounds[1]..packageInfo.bounds[3]
    }

    private fun isInsideOfflineBounds(point: LatLng): Boolean {
        val packageInfo = mapPackageInfo ?: return false
        return point.longitude in packageInfo.bounds[0]..packageInfo.bounds[2] &&
            point.latitude in packageInfo.bounds[1]..packageInfo.bounds[3]
    }

    private fun updateLocationStatus(location: Location) {
        statusText.text = if (isInsideOfflineBounds(location)) {
            "Offline Tactical Map Active"
        } else {
            "Offline Tactical Map Active (GPS outside mission zone)"
        }
    }

    private fun focusCameraOnRoute(routeCoords: List<DoubleArray>) {
        val firstPoint = routeCoords.firstOrNull() ?: return
        val lastPoint = routeCoords.lastOrNull() ?: return
        val targetLat = (firstPoint[1] + lastPoint[1]) / 2.0
        val targetLon = (firstPoint[0] + lastPoint[0]) / 2.0
        val zoom = ((mapPackageInfo?.maxZoom ?: 15) - 1).toDouble().coerceAtLeast(13.0)
        mapboxMap?.cameraPosition = CameraPosition.Builder()
            .target(LatLng(targetLat, targetLon))
            .zoom(zoom)
            .build()
    }

    private fun updateRouteSummary(routeResult: TacticalRouterEngine.RouteResult, destinationLabel: String) {
        // Update new route card
        routeDestinationText.text = destinationLabel
        routeTotalText.text = formatDistance(routeResult.distanceMeters)
        routeEtaText.text = formatDuration(routeResult.durationMillis)
        routeRemainingText.text = formatDistance(routeResult.distanceMeters)
        routeProgressBar.progress = 0
        routeStatusChip.text = getString(R.string.route_status_on)
        routeStatusChip.setTextColor(ContextCompat.getColor(this, R.color.status_on_route))
        routeSummaryCard.visibility = View.VISIBLE
        // Also hide destination panel when route is active
        hideDestinationPanel()
    }

    private fun setActiveRoute(routeResult: TacticalRouterEngine.RouteResult, destinationLabel: String) {
        activeRouteCoords = routeResult.coordinates
        activeRouteTotalDistanceMeters = routeResult.distanceMeters
        activeRouteTotalDurationMillis = routeResult.durationMillis
        activeRouteDestinationLabel = destinationLabel
    }

    private fun clearActiveRoute() {
        activeRouteCoords = null
        activeRouteTotalDistanceMeters = 0.0
        activeRouteTotalDurationMillis = 0L
        activeRouteDestinationLabel = null
    }

    private fun updateActiveRouteProgress() {
        val routeCoords = activeRouteCoords ?: return
        val location = currentLocation ?: return
        if (routeCoords.size < 2) return

        val nearestIndex = findNearestRouteIndex(location, routeCoords)
        val remainingDistanceMeters = calculateRemainingDistanceMeters(location, routeCoords, nearestIndex)
        val remainingRatio = if (activeRouteTotalDistanceMeters > 0.0) {
            (remainingDistanceMeters / activeRouteTotalDistanceMeters).coerceIn(0.0, 1.0)
        } else {
            1.0
        }
        val remainingDurationMillis = (activeRouteTotalDurationMillis * remainingRatio).toLong()
        val progressPct = ((1.0 - remainingRatio) * 100).toInt()
        val destinationLabel = activeRouteDestinationLabel ?: "objective"

        // Update new route card UI
        routeDestinationText.text = destinationLabel
        routeRemainingText.text = formatDistance(remainingDistanceMeters)
        routeEtaText.text = formatDuration(remainingDurationMillis)
        routeTotalText.text = formatDistance(activeRouteTotalDistanceMeters)
        routeProgressBar.progress = progressPct
        routeSummaryCard.visibility = View.VISIBLE
    }

    private fun maybeRerouteOnDeviation(location: Location) {
        val routeCoords = activeRouteCoords ?: return
        if (routeCoords.size < 2 || rerouteInProgress) return
        if (!isInsideOfflineBounds(location)) return

        val now = System.currentTimeMillis()
        if (now - lastRerouteTimestampMs < REROUTE_COOLDOWN_MS) return

        val distanceToRoute = distanceToRouteMeters(location, routeCoords)
        if (distanceToRoute <= OFF_ROUTE_THRESHOLD_METERS) {
            // Update status chip to ON ROUTE if it was previously off
            if (::routeStatusChip.isInitialized) {
                routeStatusChip.text = getString(R.string.route_status_on)
                routeStatusChip.setTextColor(ContextCompat.getColor(this, R.color.status_on_route))
            }
            return
        }
        // Show off-route status
        if (::routeStatusChip.isInitialized) {
            routeStatusChip.text = getString(R.string.route_status_rerouting)
            routeStatusChip.setTextColor(ContextCompat.getColor(this, R.color.status_rerouting))
        }

        val packageInfo = mapPackageInfo ?: return
        val destination = currentDestinationForReroute(packageInfo) ?: return
        rerouteInProgress = true
        transcriptionText.text = "Off route detected (${distanceToRoute.toInt()} m). Recalculating..."

        try {
            val routeResult = routingEngine.calculateRoute(
                location.latitude,
                location.longitude,
                destination.latitude,
                destination.longitude
            )
            if (routeResult != null) {
                drawRouteOnMap(routeResult.coordinates)
                setActiveRoute(routeResult, destination.label)
                focusCameraOnRoute(routeResult.coordinates)
                updateActiveRouteProgress()
                transcriptionText.text = "Route recalculated. Target: ${destination.label}"
                lastRerouteTimestampMs = now
            } else {
                val routingError = routingEngine.getLastError() ?: "Unknown reroute error"
                transcriptionText.text = "Reroute failed: $routingError"
            }
        } catch (exception: Exception) {
            Log.e(tag, "Automatic reroute failed", exception)
            transcriptionText.text = "Reroute error: ${exception.message}"
        } finally {
            rerouteInProgress = false
        }
    }

    private fun currentDestinationForReroute(packageInfo: MapPackageInfo): ResolvedDestination? {
        selectedDestination?.let {
            val label = activeRouteDestinationLabel ?: "selected point"
            return ResolvedDestination(it.latitude, it.longitude, label)
        }

        val objectivePoint = buildObjectivePoint(packageInfo)
        return ResolvedDestination(
            objectivePoint[0],
            objectivePoint[1],
            activeRouteDestinationLabel ?: "objective"
        )
    }

    private fun findNearestRouteIndex(location: Location, routeCoords: List<DoubleArray>): Int {
        var nearestIndex = 0
        var nearestDistance = Double.MAX_VALUE
        routeCoords.forEachIndexed { index, coord ->
            val distance = distanceBetweenMeters(location.latitude, location.longitude, coord[1], coord[0])
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearestIndex = index
            }
        }
        return nearestIndex
    }

    private fun calculateRemainingDistanceMeters(
        location: Location,
        routeCoords: List<DoubleArray>,
        nearestIndex: Int
    ): Double {
        val nearestCoord = routeCoords[nearestIndex]
        var remaining = distanceBetweenMeters(location.latitude, location.longitude, nearestCoord[1], nearestCoord[0])
        for (index in nearestIndex until routeCoords.lastIndex) {
            val start = routeCoords[index]
            val end = routeCoords[index + 1]
            remaining += distanceBetweenMeters(start[1], start[0], end[1], end[0])
        }
        return remaining
    }

    private fun clearRouteSummary() {
        routeSummaryText.visibility = View.GONE
        routeSummaryText.text = ""
        if (::routeSummaryCard.isInitialized) {
            routeSummaryCard.visibility = View.GONE
        }
    }

    private fun formatDistance(distanceMeters: Double): String {
        return if (distanceMeters >= 1000.0) {
            String.format("%.1f km", distanceMeters / 1000.0)
        } else {
            "${distanceMeters.toInt()} m"
        }
    }

    private fun formatDuration(durationMillis: Long): String {
        val totalMinutes = (durationMillis / 60000L).coerceAtLeast(1L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
            hours > 0L -> "${hours}h"
            else -> "${minutes} min"
        }
    }

    private fun distanceBetweenMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, result)
        return result[0].toDouble()
    }

    private fun distanceToRouteMeters(location: Location, routeCoords: List<DoubleArray>): Double {
        var minDistance = Double.MAX_VALUE
        for (index in 0 until routeCoords.lastIndex) {
            val start = routeCoords[index]
            val end = routeCoords[index + 1]
            val distance = pointToSegmentDistanceMeters(
                location.latitude,
                location.longitude,
                start[1],
                start[0],
                end[1],
                end[0]
            )
            if (distance < minDistance) {
                minDistance = distance
            }
        }
        return minDistance
    }

    private fun pointToSegmentDistanceMeters(
        pointLat: Double,
        pointLon: Double,
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double
    ): Double {
        val refLatRad = Math.toRadians(pointLat)
        val metersPerDegLat = 111320.0
        val metersPerDegLon = 111320.0 * kotlin.math.cos(refLatRad)

        val px = pointLon * metersPerDegLon
        val py = pointLat * metersPerDegLat
        val sx = startLon * metersPerDegLon
        val sy = startLat * metersPerDegLat
        val ex = endLon * metersPerDegLon
        val ey = endLat * metersPerDegLat

        val dx = ex - sx
        val dy = ey - sy
        if (dx == 0.0 && dy == 0.0) {
            return kotlin.math.hypot(px - sx, py - sy)
        }

        val t = (((px - sx) * dx) + ((py - sy) * dy)) / ((dx * dx) + (dy * dy))
        val clampedT = t.coerceIn(0.0, 1.0)
        val nearestX = sx + clampedT * dx
        val nearestY = sy + clampedT * dy
        return kotlin.math.hypot(px - nearestX, py - nearestY)
    }

    private fun recenterOnOperator(): Boolean {
        val location = currentLocation ?: return false
        if (!isInsideOfflineBounds(location)) {
            return false
        }
        mapboxMap?.cameraPosition = CameraPosition.Builder()
            .target(LatLng(location.latitude, location.longitude))
            .zoom((mapPackageInfo?.maxZoom ?: 15).toDouble().coerceAtMost(16.0))
            .build()
        return true
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
        if (hasLocationPermission()) {
            startLocationTracking()
        }
        speechService?.setPause(false)
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        speechService?.setPause(true)
        locationManager.removeUpdates(locationListener)
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
        speechService?.setPause(true)
        locationManager.removeUpdates(locationListener)
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

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
