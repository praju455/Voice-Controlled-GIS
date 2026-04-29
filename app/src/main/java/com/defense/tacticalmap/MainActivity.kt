package com.defense.tacticalmap

import android.Manifest
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.drawable.AnimationDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.style.layers.CircleLayer
import com.mapbox.mapboxsdk.style.layers.FillLayer
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
import kotlin.concurrent.thread
import kotlin.math.sqrt
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity(), RecognitionListener, SensorEventListener {

    private lateinit var mapView: MapView
    private var mapboxMap: MapboxMap? = null

    // ── Legacy hidden views (kept for code compatibility) ──
    private lateinit var statusText: TextView
    private lateinit var routeSummaryText: TextView

    // ── New UI components ──
    private lateinit var transcriptionText: TextView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var launchOverlay: View
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
    private lateinit var compassFloatingButton: ImageButton
    private lateinit var micIndicatorDot: View
    private lateinit var micStatusLabel: TextView
    private var micPulseAnim: AnimationDrawable? = null
    private var isDarkMode = true
    private lateinit var hazardManager: HazardZoneManager
    private var isHazardDrawMode = false

    // MapLibre layer IDs for hazard overlays
    // ── Team Mesh ─────────────────────────────────────────────────────
    private lateinit var teamLocationManager: TeamLocationManager
    private var myDeviceId: String = ""
    private var myCallsign: String = "ALPHA-1"
    private val TEAM_SOURCE_ID = "team-positions-source"
    private val TEAM_LAYER_ID  = "team-positions-layer"
    private val TEAM_LABEL_LAYER_ID = "team-labels-layer"
    private lateinit var lbm: LocalBroadcastManager
    private val meshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            intent ?: return
            when (intent.action) {
                TeamMeshService.ACTION_PEER_POSITION -> {
                    val id       = intent.getStringExtra(TeamMeshService.EXTRA_MEMBER_ID) ?: return
                    val callsign = intent.getStringExtra(TeamMeshService.EXTRA_CALLSIGN) ?: "UNKNOWN"
                    val lat      = intent.getDoubleExtra(TeamMeshService.EXTRA_LAT, 0.0)
                    val lon      = intent.getDoubleExtra(TeamMeshService.EXTRA_LON, 0.0)
                    val accuracy = intent.getFloatExtra(TeamMeshService.EXTRA_ACCURACY, 0f)
                    teamLocationManager.updateMember(id, callsign, lat, lon, accuracy)
                }
                TeamMeshService.ACTION_HAZARD_ADD -> {
                    val zoneJson = intent.getStringExtra(TeamMeshService.EXTRA_ZONE_JSON) ?: return
                    applyRemoteHazardAdd(zoneJson)
                }
                TeamMeshService.ACTION_HAZARD_CLEAR -> {
                    val zoneId = intent.getStringExtra(TeamMeshService.EXTRA_ZONE_ID) ?: return
                    hazardManager.removeZone(zoneId)
                    updateHazardZoneOverlay()
                    transcriptionText.text = "⚠️ Peer cleared hazard zone."
                    rerouteActiveRoute("Peer cleared a hazard. Refreshing route.")
                }
                TeamMeshService.ACTION_HAZARD_CLEAR_ALL -> {
                    hazardManager.clearAll()
                    updateHazardZoneOverlay()
                    transcriptionText.text = "⚠️ Peer cleared ALL hazard zones."
                    rerouteActiveRoute("Peer cleared hazards. Refreshing route.")
                }
                TeamMeshService.ACTION_STATE_REQUEST -> {
                    // A new peer joined — send them our full hazard state
                    broadcastHazardStateDump()
                }
            }
        }
    }
    private val HAZARD_SOURCE_ID = "hazard-zones-source"
    private val HAZARD_FILL_LAYER_ID = "hazard-zones-fill"
    private val HAZARD_STROKE_LAYER_ID = "hazard-zones-stroke"
    private lateinit var selectedRegion: RegionProfile

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
    private var smoothedSpeedMetersPerSecond: Double? = null
    private var lastEtaSampleLocation: Location? = null
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var shakeImpulseCount: Int = 0
    private var lastShakeImpulseTimestampMs: Long = 0L
    private var lastShakeZoomTriggerTimestampMs: Long = 0L
    private var nextShakeZoomsIn: Boolean = true

    private var speechService: SpeechService? = null
    private var model: Model? = null
    private lateinit var spatialEngine: SpatialIntelligenceEngine
    private lateinit var routingEngine: TacticalRouterEngine
    private lateinit var placeIndex: OfflinePlaceIndex
    private var launchStartedAtMs: Long = 0L
    private var launchOverlayDismissed: Boolean = false
    private var audioPermissionGranted: Boolean = false
    private var mapInitializationComplete: Boolean = false
    private var voiceModelInitializationStarted: Boolean = false
    @Volatile private var routingEngineReady: Boolean = false
    private val locationListener = LocationListener { location ->
        updateObservedSpeed(location)
        currentLocation = location
        refreshOperatorPresentation()
        updateActiveRouteProgress()
        maybeRerouteOnDeviation(location)
        // Broadcast our position to the team mesh
        if (::teamLocationManager.isInitialized) {
            updateMeshServicePosition(location.latitude, location.longitude, location.accuracy)
        }
        if (!hasCenteredOnOperator && effectiveOperatorLocation()?.let { isInsideOfflineBounds(it) } == true) {
            val operatorLocation = effectiveOperatorLocation() ?: return@LocationListener
            mapboxMap?.cameraPosition = CameraPosition.Builder()
                .target(LatLng(operatorLocation.latitude, operatorLocation.longitude))
                .zoom((mapPackageInfo?.maxZoom ?: 15).toDouble().coerceAtMost(16.0))
                .build()
            hasCenteredOnOperator = true
        }
    }
    
    companion object {
        private const val PERMISSIONS_REQUEST_RECORD_AUDIO = 1
        private const val PERMISSIONS_REQUEST_LOCATION = 2
        private const val PREFS_NAME = "veer_rakshak_prefs"
        private const val PREF_KEY_DARK_MODE = "dark_mode"
        private const val PREF_KEY_REGION = "selected_region"
        private const val OPERATOR_SOURCE_ID = "operator-location-source"
        private const val OPERATOR_LAYER_ID = "operator-location-layer"
        private const val DESTINATION_SOURCE_ID = "destination-source"
        private const val DESTINATION_LAYER_ID = "destination-layer"
        private const val ROUTE_SOURCE_ID = "tactical-route-source"
        private const val ROUTE_LAYER_ID = "tactical-route-layer"
        private const val OFF_ROUTE_THRESHOLD_METERS = 60.0
        private const val REROUTE_COOLDOWN_MS = 8000L
        private const val SHAKE_ACCEL_THRESHOLD = 15.0
        private const val SHAKE_IMPULSE_WINDOW_MS = 1200L
        private const val SHAKE_ZOOM_COOLDOWN_MS = 2500L
        private const val SHAKE_ZOOM_DELTA = 1.15
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

    private data class RegionProfile(
        val key: String,
        val badgeLabel: String,
        val displayName: String,
        val usesDemoOperator: Boolean,
        val demoOperatorLat: Double? = null,
        val demoOperatorLon: Double? = null,
        val mbtilesAssetPath: String,
        val graphhopperZipAssetPath: String,
        val placeIndexAssetPath: String,
        val savedTacticalPointsAssetPath: String,
        val startLatFactor: Double,
        val startLonFactor: Double
    )

    private val bengaluruRegion = RegionProfile(
        key = "bengaluru",
        badgeLabel = "● BENGALURU",
        displayName = "Bengaluru Zone",
        usesDemoOperator = false,
        demoOperatorLat = null,
        demoOperatorLon = null,
        mbtilesAssetPath = "mbtiles/sample_tactical.mbtiles",
        graphhopperZipAssetPath = "graphhopper/graphhopper-cache.zip",
        placeIndexAssetPath = OfflinePlaceIndex.DEFAULT_PLACE_INDEX_ASSET_PATH,
        savedTacticalPointsAssetPath = OfflinePlaceIndex.DEFAULT_SAVED_POINTS_ASSET_PATH,
        startLatFactor = -0.12,
        startLonFactor = -0.12
    )
    private val siachenRegion = RegionProfile(
        key = "siachen",
        badgeLabel = "● SIACHEN BORDER",
        displayName = "Siachen Border",
        usesDemoOperator = true,
        demoOperatorLat = 35.1943,
        demoOperatorLon = 77.2131,
        mbtilesAssetPath = "mbtiles/siachen_border.mbtiles",
        graphhopperZipAssetPath = "graphhopper/siachen_border-gh.zip",
        placeIndexAssetPath = "places/siachen_place_index.json",
        savedTacticalPointsAssetPath = "places/siachen_saved_tactical_points.json",
        startLatFactor = -0.18,
        startLonFactor = -0.20
    )
    private val locRegion = RegionProfile(
        key = "loc",
        badgeLabel = "● LINE OF CONTROL",
        displayName = "Line of Control",
        usesDemoOperator = true,
        demoOperatorLat = 34.4306,
        demoOperatorLon = 75.7574,
        mbtilesAssetPath = "mbtiles/line_of_control.mbtiles",
        graphhopperZipAssetPath = "graphhopper/line_of_control-gh.zip",
        placeIndexAssetPath = "places/line_of_control_place_index.json",
        savedTacticalPointsAssetPath = "places/line_of_control_saved_tactical_points.json",
        startLatFactor = -0.08,
        startLonFactor = -0.22
    )

    private fun resolveRegion(key: String?): RegionProfile {
        return when (key) {
            siachenRegion.key -> siachenRegion
            locRegion.key -> locRegion
            else -> bengaluruRegion
        }
    }

    private fun regionFallbackAssetPath(region: RegionProfile, assetPath: String): String {
        if (assetExists(assetPath)) {
            return assetPath
        }
        Log.i(tag, "Asset $assetPath missing for region ${region.key}, falling back to Bengaluru defaults.")
        return when (assetPath) {
            region.mbtilesAssetPath -> bengaluruRegion.mbtilesAssetPath
            region.graphhopperZipAssetPath -> bengaluruRegion.graphhopperZipAssetPath
            region.placeIndexAssetPath -> bengaluruRegion.placeIndexAssetPath
            region.savedTacticalPointsAssetPath -> bengaluruRegion.savedTacticalPointsAssetPath
            else -> assetPath
        }
    }

    private fun resolvedMbtilesAssetPath(region: RegionProfile = selectedRegion): String =
        regionFallbackAssetPath(region, region.mbtilesAssetPath)

    private fun resolvedGraphhopperZipAssetPath(region: RegionProfile = selectedRegion): String =
        regionFallbackAssetPath(region, region.graphhopperZipAssetPath)

    private fun resolvedPlaceIndexAssetPath(region: RegionProfile = selectedRegion): String =
        regionFallbackAssetPath(region, region.placeIndexAssetPath)

    private fun resolvedSavedTacticalPointsAssetPath(region: RegionProfile = selectedRegion): String =
        regionFallbackAssetPath(region, region.savedTacticalPointsAssetPath)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Restore dark/light mode preference before inflation
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        isDarkMode = prefs.getBoolean(PREF_KEY_DARK_MODE, true)
        selectedRegion = resolveRegion(prefs.getString(PREF_KEY_REGION, bengaluruRegion.key))
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
        launchOverlay      = findViewById(R.id.launchOverlay)
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
        compassFloatingButton = findViewById(R.id.btnCompassFloating)
        micIndicatorDot    = findViewById(R.id.micIndicatorDot)
        micStatusLabel     = findViewById(R.id.micStatusLabel)
        launchStartedAtMs = SystemClock.elapsedRealtime()
        topBarRegion.text = selectedRegion.badgeLabel

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        setupDrawerAndTopBar()
        hazardManager = HazardZoneManager(this)
        setupHazardFab()
        setupTeamMesh()
        
        spatialEngine = SpatialIntelligenceEngine(this)
        placeIndex = OfflinePlaceIndex(
            this,
            placeIndexAssetPath = resolvedPlaceIndexAssetPath(),
            savedTacticalPointsAssetPath = resolvedSavedTacticalPointsAssetPath()
        )
        placeIndex.preloadAsync {
            Handler(Looper.getMainLooper()).post {
                Log.i(tag, "Offline place index ready.")
                if (::transcriptionText.isInitialized && selectedRegion.usesDemoOperator) {
                    transcriptionText.text = getString(R.string.region_demo_mode_message, selectedRegion.displayName)
                }
            }
        }
        
        mapView.onCreate(savedInstanceState)

        initializeRoutingEngineAsync()
        initializeMap()
        ensureLocationAccess()

        // Check Permissions for Vosk
        val permissionCheck = ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO)
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSIONS_REQUEST_RECORD_AUDIO)
        } else {
            audioPermissionGranted = true
            maybeStartVoiceModel()
        }
    }

    private fun initializeMap() {
        mapView.getMapAsync { mapboxMap ->
            this.mapboxMap = mapboxMap
            statusText.text = "Preparing offline map..."
            thread(name = "offline-map-init-${selectedRegion.key}") {
                try {
                    val mbtilesPath = copyAssetToCache(resolvedMbtilesAssetPath())
                    val packageInfo = inspectMbtilesPackage(mbtilesPath)

                    if (packageInfo.tileCount <= 0) {
                        Log.e(tag, "MBTiles package is empty: $mbtilesPath")
                        runOnUiThread {
                            if (!isDestroyed && !isFinishing) {
                                statusText.text = "Offline map package contains 0 tiles. Rebuild the selected region MBTiles pack."
                            }
                        }
                        return@thread
                    }

                    val tilesRoot = extractMbtilesToRasterTiles(mbtilesPath, packageInfo)
                    val stylePath = prepareStyleJson(tilesRoot, packageInfo)

                    runOnUiThread {
                        if (isDestroyed || isFinishing) return@runOnUiThread
                        mapPackageInfo = packageInfo
                        mapboxMap.setStyle(Style.Builder().fromUri("file://$stylePath")) { _ ->
                            mapInitializationComplete = true
                            statusText.text = "Offline Tactical Map Active"
                            mapboxMap.addOnCameraMoveListener { syncCompassBearing() }
                            positionCamera(packageInfo)
                            syncCompassBearing()
                            refreshOperatorPresentation()
                            selectedDestination?.let { drawDestinationMarker(it) }
                            mapboxMap.addOnMapClickListener { point ->
                                if (isHazardDrawMode) {
                                    showHazardTypePickerDialog(point)
                                } else {
                                    handleDestinationSelection(point)
                                }
                                true
                            }
                            mapboxMap.addOnMapLongClickListener { point ->
                                showHazardTypePickerDialog(point)
                                true
                            }
                            if (hazardManager.hasZones()) updateHazardZoneOverlay()
                            dismissLaunchOverlayIfReady()
                            maybeStartVoiceModel()
                            Log.i(tag, "Map loaded offline successfully.")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Failed to load map style", e)
                    runOnUiThread {
                        if (!isDestroyed && !isFinishing) {
                            statusText.text = "Error loading offline map: ${e.message}"
                        }
                    }
                }
            }
        }
    }

    private fun initializeRoutingEngineAsync() {
        routingEngineReady = false
        statusText.text = "Preparing offline routing..."
        val routingCache = File(cacheDir, "graphhopper-cache-${selectedRegion.key}").absolutePath
        thread(name = "offline-routing-init-${selectedRegion.key}") {
            try {
                unpackGraphHopperAssets()
                val engine = TacticalRouterEngine(this, routingCache)
                val initError = engine.getLastError()
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    routingEngine = engine
                    routingEngineReady = initError == null
                    if (initError != null) {
                        transcriptionText.text = "Offline routing failed: $initError"
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to initialize offline routing", e)
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) {
                        transcriptionText.text = "Offline routing failed: ${e.message}"
                    }
                }
            }
        }
    }

    private fun ensureRoutingReady(): Boolean {
        if (!::routingEngine.isInitialized || !routingEngineReady) {
            transcriptionText.text = "Offline routing is still loading. Wait a few seconds and try again."
            return false
        }
        return true
    }

    private fun maybeStartVoiceModel() {
        if (!audioPermissionGranted || !mapInitializationComplete || voiceModelInitializationStarted) {
            return
        }
        voiceModelInitializationStarted = true
        initModel()
    }

    private fun dismissLaunchOverlayIfReady() {
        if (launchOverlayDismissed || !::launchOverlay.isInitialized) return
        val elapsed = SystemClock.elapsedRealtime() - launchStartedAtMs
        val delayMs = (1500L - elapsed).coerceAtLeast(0L)
        launchOverlayDismissed = true
        Handler(Looper.getMainLooper()).postDelayed({
            if (!::launchOverlay.isInitialized || isDestroyed || isFinishing) return@postDelayed
            launchOverlay.animate()
                .alpha(0f)
                .setDuration(320L)
                .withEndAction {
                    launchOverlay.visibility = View.GONE
                }
                .start()
        }, delayMs)
    }

    // ── Team Mesh Setup ──────────────────────────────────────────────────

    private fun setupTeamMesh() {
        lbm = LocalBroadcastManager.getInstance(this)
        teamLocationManager = TeamLocationManager()

        // Register receivers
        val filter = IntentFilter().apply {
            addAction(TeamMeshService.ACTION_PEER_POSITION)
            addAction(TeamMeshService.ACTION_HAZARD_ADD)
            addAction(TeamMeshService.ACTION_HAZARD_CLEAR)
            addAction(TeamMeshService.ACTION_HAZARD_CLEAR_ALL)
            addAction(TeamMeshService.ACTION_STATE_REQUEST)
        }
        lbm.registerReceiver(meshReceiver, filter)

        // Load or generate device ID
        val prefs = getSharedPreferences("veer_rakshak_prefs", MODE_PRIVATE)
        myDeviceId = prefs.getString("device_id", null) ?: run {
            val id = java.util.UUID.randomUUID().toString().take(8)
            prefs.edit().putString("device_id", id).apply()
            id
        }
        myCallsign = prefs.getString("callsign", null) ?: run {
            // First launch — show callsign setup dialog
            showCallsignSetupDialog()
            "ALPHA-1"
        }

        // Update sidebar callsign label
        findViewById<TextView?>(R.id.myCallsignLabel)?.text = myCallsign

        // Edit callsign button
        findViewById<android.widget.Button?>(R.id.btnEditCallsign)?.setOnClickListener {
            drawerLayout.closeDrawers()
            showCallsignSetupDialog()
        }

        // Listen for team updates — refresh map markers
        teamLocationManager.setListener(object : TeamLocationManager.Listener {
            override fun onTeamUpdated(members: List<TeamMember>) {
                updateTeamOverlay(members)
                updateTeamSidebarList(members)
            }
        })

        // Start the mesh service
        startMeshService()
    }

    private fun startMeshService() {
        val intent = Intent(this, TeamMeshService::class.java).apply {
            putExtra(TeamMeshService.EXTRA_MEMBER_ID, myDeviceId)
            putExtra(TeamMeshService.EXTRA_CALLSIGN, myCallsign)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        // Announce ourselves and request state from existing peers
        val stateReqIntent = Intent(this, TeamMeshService::class.java).apply {
            action = TeamMeshService.ACTION_SEND_STATE_REQUEST
            putExtra(TeamMeshService.EXTRA_MEMBER_ID, myDeviceId)
        }
        startService(stateReqIntent)
    }

    private fun updateMeshServicePosition(lat: Double, lon: Double, accuracy: Float) {
        val intent = Intent(this, TeamMeshService::class.java).apply {
            putExtra(TeamMeshService.EXTRA_MEMBER_ID, myDeviceId)
            putExtra(TeamMeshService.EXTRA_CALLSIGN,  myCallsign)
            putExtra(TeamMeshService.EXTRA_LAT,       lat)
            putExtra(TeamMeshService.EXTRA_LON,       lon)
            putExtra(TeamMeshService.EXTRA_ACCURACY,  accuracy)
        }
        startService(intent)
    }

    private fun showCallsignSetupDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "e.g. BRAVO-2"
            setText(myCallsign)
            filters = arrayOf(android.text.InputFilter.AllCaps(), android.text.InputFilter.LengthFilter(12))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("👤 Set Your Callsign")
            .setMessage("Used to identify you on the team map.")
            .setView(input)
            .setPositiveButton("CONFIRM") { _, _ ->
                val newCallsign = input.text.toString().trim().ifBlank { "ALPHA-1" }
                myCallsign = newCallsign
                getSharedPreferences("veer_rakshak_prefs", MODE_PRIVATE)
                    .edit().putString("callsign", newCallsign).apply()
                findViewById<TextView?>(R.id.myCallsignLabel)?.text = newCallsign
                startMeshService()
                transcriptionText.text = "Callsign set to $newCallsign"
            }
            .setCancelable(false)
            .show()
    }

    private fun applyRemoteHazardAdd(json: String) {
        try {
            // Could be a single zone object or a JSON array (state dump)
            if (json.startsWith("[")) {
                val arr = org.json.JSONArray(json)
                for (i in 0 until arr.length()) applyZoneObject(arr.getJSONObject(i))
            } else {
                applyZoneObject(org.json.JSONObject(json))
            }
            updateHazardZoneOverlay()
        } catch (e: Exception) {
            Log.e(tag, "Failed to apply remote hazard: ${e.message}")
        }
    }

    private fun applyZoneObject(obj: org.json.JSONObject) {
        val typeStr = obj.optString("hazardType", obj.optString("type", "HOSTILE_AREA"))
        val type = try { HazardZoneManager.HazardType.valueOf(typeStr) }
                   catch (e: Exception) { HazardZoneManager.HazardType.HOSTILE_AREA }
        val lat    = obj.optDouble("lat", 0.0)
        val lon    = obj.optDouble("lon", 0.0)
        val radius = obj.optDouble("radiusMeters", 200.0)
        val label  = obj.optString("label", type.label)
        val existingId = obj.optString("id", "")
        if (existingId.isNotBlank() && hazardManager.zones.any { it.id == existingId }) return
        hazardManager.addZone(
            type = type,
            center = com.mapbox.mapboxsdk.geometry.LatLng(lat, lon),
            radiusMeters = radius,
            label = label,
            id = existingId.ifBlank { null }
        )
        transcriptionText.text = "⚠️ Peer shared: ${type.label} zone added to map."
        rerouteActiveRoute("Peer hazard update applied.")
    }

    private fun broadcastHazardStateDump() {
        if (!hazardManager.hasZones()) return
        val zonesArr = org.json.JSONArray()
        hazardManager.zones.forEach { zone ->
            zonesArr.put(org.json.JSONObject().apply {
                put("id", zone.id)
                put("hazardType", zone.type.name)
                put("lat", zone.center.latitude)
                put("lon", zone.center.longitude)
                put("radiusMeters", zone.radiusMeters)
                put("label", zone.label)
            })
        }
        val intent = Intent(this, TeamMeshService::class.java).apply {
            action = TeamMeshService.ACTION_SEND_STATE_DUMP
            putExtra(TeamMeshService.EXTRA_ZONE_JSON, zonesArr.toString())
        }
        startService(intent)
    }

    private fun broadcastHazardAdd(zone: HazardZoneManager.HazardZone) {
        val zoneJson = org.json.JSONObject().apply {
            put("id",           zone.id)
            put("hazardType",   zone.type.name)
            put("lat",          zone.center.latitude)
            put("lon",          zone.center.longitude)
            put("radiusMeters", zone.radiusMeters)
            put("label",        zone.label)
        }.toString()
        val meshPacket = org.json.JSONObject().apply {
            put("type", "hazard_add")
            put("senderId", myDeviceId)
            put("zone", org.json.JSONObject(zoneJson))
        }.toString()
        val intent = Intent(this, TeamMeshService::class.java).apply {
            action = TeamMeshService.ACTION_SEND_HAZARD_ADD
            putExtra(TeamMeshService.EXTRA_ZONE_JSON, meshPacket)
        }
        startService(intent)
    }

    private fun broadcastHazardClear(zoneId: String) {
        val intent = Intent(this, TeamMeshService::class.java).apply {
            action = TeamMeshService.ACTION_SEND_HAZARD_CLEAR
            putExtra(TeamMeshService.EXTRA_ZONE_ID, zoneId)
        }
        startService(intent)
    }

    private fun broadcastHazardClearAll() {
        startService(Intent(this, TeamMeshService::class.java).apply {
            action = TeamMeshService.ACTION_SEND_HAZARD_CLEAR_ALL
        })
    }

    // ── Team Map Overlay ───────────────────────────────────────────────

    private fun updateTeamOverlay(members: List<TeamMember>) {
        mapboxMap?.getStyle { style ->
            style.removeLayer(TEAM_LABEL_LAYER_ID)
            style.removeLayer(TEAM_LAYER_ID)
            style.removeSource(TEAM_SOURCE_ID)
            if (members.isEmpty()) return@getStyle

            val featuresArray = org.json.JSONArray()
            members.forEach { m ->
                val color = if (m.isStale) "#607D8B" else "#00E5FF"
                val feat = org.json.JSONObject().apply {
                    put("type", "Feature")
                    put("geometry", org.json.JSONObject().apply {
                        put("type", "Point")
                        put("coordinates", org.json.JSONArray().apply {
                            put(m.lon); put(m.lat)
                        })
                    })
                    put("properties", org.json.JSONObject().apply {
                        put("callsign", m.callsign)
                        put("color", color)
                        put("stale", m.isStale)
                    })
                }
                featuresArray.put(feat)
            }
            val fc = org.json.JSONObject().apply {
                put("type", "FeatureCollection")
                put("features", featuresArray)
            }.toString()

            try {
                style.addSource(GeoJsonSource(TEAM_SOURCE_ID, fc))
                style.addLayer(
                    com.mapbox.mapboxsdk.style.layers.CircleLayer(TEAM_LAYER_ID, TEAM_SOURCE_ID)
                        .withProperties(
                            PropertyFactory.circleRadius(10f),
                            PropertyFactory.circleColor(
                                com.mapbox.mapboxsdk.style.expressions.Expression.get("color")
                            ),
                            PropertyFactory.circleStrokeWidth(2f),
                            PropertyFactory.circleStrokeColor("#FFFFFF")
                        )
                )
                style.addLayer(
                    com.mapbox.mapboxsdk.style.layers.SymbolLayer(TEAM_LABEL_LAYER_ID, TEAM_SOURCE_ID)
                        .withProperties(
                            PropertyFactory.textField(
                                com.mapbox.mapboxsdk.style.expressions.Expression.get("callsign")
                            ),
                            PropertyFactory.textSize(11f),
                            PropertyFactory.textColor("#FFFFFF"),
                            PropertyFactory.textOffset(arrayOf(0f, -1.8f)),
                            PropertyFactory.textFont(arrayOf("Open Sans Bold", "Arial Unicode MS Bold"))
                        )
                )
            } catch (e: Exception) {
                Log.e(tag, "Team overlay error: ${e.message}")
            }
        }
    }

    private fun updateTeamSidebarList(members: List<TeamMember>) {
        val container = findViewById<android.widget.LinearLayout?>(R.id.teamMemberList) ?: return
        val statusText = findViewById<TextView?>(R.id.meshStatusText)
        container.removeAllViews()
        if (members.isEmpty()) {
            statusText?.text = "◌  SOLO — no peers"
            statusText?.setTextColor(android.graphics.Color.parseColor("#607D8B"))
            return
        }
        statusText?.text = "● ${members.size} ONLINE"
        statusText?.setTextColor(android.graphics.Color.parseColor("#00E5FF"))
        members.forEach { m ->
            val row = android.widget.TextView(this).apply {
                val age = (System.currentTimeMillis() - m.lastSeenMs) / 1000
                text = if (m.isStale) "◌ ${m.callsign}  (${age}s ago)"
                       else           "● ${m.callsign}  LIVE"
                setTextColor(if (m.isStale)
                    android.graphics.Color.parseColor("#607D8B")
                else android.graphics.Color.parseColor("#00E5FF"))
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = 6
                layoutParams = lp
            }
            container.addView(row)
        }
    }

    // ──────────────────────────────────────────────────

    // ── Drawer, top bar, and sidebar wiring ──────────────────────────────────

    private fun setupDrawerAndTopBar() {
        // Hamburger opens drawer
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener {
            drawerLayout.openDrawer(androidx.core.view.GravityCompat.START)
        }

        // Top-right compass / recenter action
        findViewById<ImageButton>(R.id.btnDarkModeToggle).setOnClickListener {
            if (recenterOnOperator()) {
                transcriptionText.text = "Compass aligned to operator position."
            } else {
                transcriptionText.text = "Compass unavailable: waiting for operator position."
            }
        }

        compassFloatingButton.setOnClickListener {
            val currentCamera = mapboxMap?.cameraPosition
            val target = currentCamera?.target ?: effectiveOperatorLocation()?.let {
                LatLng(it.latitude, it.longitude)
            }
            if (target != null) {
                mapboxMap?.cameraPosition = CameraPosition.Builder()
                    .target(target)
                    .zoom(currentCamera?.zoom ?: (mapPackageInfo?.maxZoom ?: 15).toDouble().coerceAtMost(16.0))
                    .bearing(0.0)
                    .tilt(0.0)
                    .build()
                syncCompassBearing()
                transcriptionText.text = "Compass reset to north."
            } else {
                transcriptionText.text = "Compass unavailable: map is still loading."
            }
        }

        // Region items
        findViewById<View?>(R.id.regionBengaluru)?.setOnClickListener {
            applyRegionSelection(bengaluruRegion)
        }
        findViewById<View?>(R.id.regionSiachen)?.setOnClickListener {
            applyRegionSelection(siachenRegion)
        }
        findViewById<View?>(R.id.regionLoC)?.setOnClickListener {
            applyRegionSelection(locRegion)
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
        findViewById<View?>(R.id.btnDrawerClearHazards)?.setOnClickListener {
            hazardManager.clearAll()
            updateHazardZoneOverlay()
            broadcastHazardClearAll()
            rerouteActiveRoute("Hazards cleared. Refreshing route.")
            transcriptionText.text = "All hazard zones cleared."
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
            if (!ensureRoutingReady()) return@setOnClickListener
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
                val routeResult = routingEngine.calculateRoute(coords[0], coords[1], coords[2], coords[3], hazardManager)
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

    private fun applyRegionSelection(region: RegionProfile) {
        if (selectedRegion.key == region.key) {
            drawerLayout.closeDrawers()
            return
        }
        selectedRegion = region
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(PREF_KEY_REGION, region.key)
            .apply()
        val message = if (region.usesDemoOperator) {
            getString(R.string.region_demo_mode_message, region.displayName)
        } else {
            getString(R.string.region_live_mode_message, region.displayName)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        drawerLayout.closeDrawers()
        recreate()
    }

    private fun refreshOperatorPresentation() {
        val operatorLocation = effectiveOperatorLocation()
        if (operatorLocation != null) {
            drawCurrentLocation(operatorLocation)
            updateLocationStatus(operatorLocation)
        }
    }

    private fun showDestinationPanel(point: LatLng, label: String) {
        destPanelName.text = label
        destPanelCoords.text = "%.5f°N  %.5f°E".format(point.latitude, point.longitude)
        val dist = effectiveOperatorLocation()?.let {
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
                audioPermissionGranted = true
                maybeStartVoiceModel()
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
                        if (!ensureRoutingReady()) return
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
                                routeRequest[3],
                                hazardManager
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

                    // ── Hazard Zone Intents ─────────────────────────────────────────
                    } else if (intent.action == "clear_hazards") {
                        hazardManager.clearAll()
                        updateHazardZoneOverlay()
                        broadcastHazardClearAll()  // sync to team
                        transcriptionText.text = "All hazard zones cleared."
                        rerouteActiveRoute("Hazards cleared. Refreshing route.")

                    } else if (intent.action == "mark_hazard") {
                        val loc = currentLocation
                        if (loc == null) {
                            transcriptionText.text = "Cannot mark hazard: no GPS fix yet."
                        } else {
                            val hazardType = try {
                                HazardZoneManager.HazardType.valueOf(intent.entity)
                            } catch (e: Exception) {
                                HazardZoneManager.HazardType.HOSTILE_AREA
                            }
                            val radiusMeters = if (intent.unit.startsWith("k"))
                                intent.distance * 1000.0 else intent.distance.toDouble()
                            val center = com.mapbox.mapboxsdk.geometry.LatLng(
                                loc.latitude, loc.longitude
                            )
                            val zone = hazardManager.addZone(hazardType, center, radiusMeters)
                            updateHazardZoneOverlay()
                            broadcastHazardAdd(zone)  // sync to team
                            transcriptionText.text = "⚠️ ${zone.type.label} marked at operator position (${radiusMeters.toInt()}m radius).\nRoute recalculation will avoid this zone."
                            rerouteActiveRoute("${zone.type.label} added. Recalculating safe route.")
                        }
                    // ─────────────────────────────────────────────────────────────────
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
                    PropertyFactory.lineColor("#2F80ED")
                ))
            }
        }
    }

    // ── Hazard Zone FAB & Overlay ────────────────────────────────────────────────

    private fun setupHazardFab() {
        val fab = findViewById<android.widget.ImageButton?>(R.id.hazardFab) ?: return
        fab.setOnClickListener {
            isHazardDrawMode = !isHazardDrawMode
            if (isHazardDrawMode) {
                fab.setBackgroundResource(R.drawable.bg_hazard_fab)
                fab.setColorFilter(android.graphics.Color.parseColor("#E74C3C"))
                transcriptionText.text = "⚠️ HAZARD DRAW MODE ACTIVE\nLong-press map to place a hazard zone at that location."
            } else {
                fab.clearColorFilter()
                transcriptionText.text = "Hazard draw mode off."
            }
        }
        // Restore hazard overlay after map style loads
        mapboxMap?.getStyle { updateHazardZoneOverlay() }
    }

    /**
     * Called when the map is long-pressed and hazard draw mode is active.
     * Wraps the existing onMapLongClick handler.
     */
    fun onHazardMapLongPress(latLng: com.mapbox.mapboxsdk.geometry.LatLng) {
        if (!isHazardDrawMode) return
        showHazardTypePickerDialog(latLng)
    }

    private fun showHazardTypePickerDialog(latLng: com.mapbox.mapboxsdk.geometry.LatLng) {
        val types = HazardZoneManager.HazardType.values()
        val labels = types.map { it.label }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("⚠️ Mark Hazard Zone")
            .setItems(labels) { _, which ->
                val type = types[which]
                showRadiusPickerDialog(latLng, type)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRadiusPickerDialog(
        latLng: com.mapbox.mapboxsdk.geometry.LatLng,
        type: HazardZoneManager.HazardType
    ) {
        val radiusOptions = arrayOf("50m", "100m", "200m", "500m", "1 km")
        val radiusValues = doubleArrayOf(50.0, 100.0, 200.0, 500.0, 1000.0)
        android.app.AlertDialog.Builder(this)
            .setTitle("Radius — ${type.label}")
            .setItems(radiusOptions) { _, which ->
                val zone = hazardManager.addZone(type, latLng, radiusValues[which])
                updateHazardZoneOverlay()
                broadcastHazardAdd(zone)  // sync to team
                // Warn if destination is inside this zone
                val destWarning = selectedDestination?.let { dest ->
                    if (hazardManager.isPointInAnyZone(dest.latitude, dest.longitude) != null)
                        "\n⚠️ WARNING: Selected destination is inside a hazard zone!"
                    else ""
                } ?: ""
                transcriptionText.text = "⚠️ ${zone.type.label} zone placed (${radiusValues[which].toInt()}m radius).$destWarning"
                rerouteActiveRoute("${zone.type.label} zone added. Recalculating safe route.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Renders all active hazard zones as coloured fill polygons on the map.
     * Uses per-zone color from HazardType for visual distinction.
     */
    private fun updateHazardZoneOverlay() {
        mapboxMap?.getStyle { style ->
            // Remove existing layers/source
            style.removeLayer(HAZARD_FILL_LAYER_ID)
            style.removeLayer(HAZARD_STROKE_LAYER_ID)
            style.removeSource(HAZARD_SOURCE_ID)

            if (!hazardManager.hasZones()) return@getStyle

            try {
                val geoJsonStr = hazardManager.toMapGeoJson()
                val source = GeoJsonSource(HAZARD_SOURCE_ID, geoJsonStr)
                style.addSource(source)

                // Semi-transparent fill (color driven by feature property)
                val fillLayer = FillLayer(HAZARD_FILL_LAYER_ID, HAZARD_SOURCE_ID).withProperties(
                    PropertyFactory.fillOpacity(0.35f),
                    PropertyFactory.fillColor(
                        com.mapbox.mapboxsdk.style.expressions.Expression.get("color")
                    )
                )
                style.addLayerBelow(fillLayer, ROUTE_LAYER_ID)

                // Stroke
                val strokeLayer = LineLayer(HAZARD_STROKE_LAYER_ID, HAZARD_SOURCE_ID).withProperties(
                    PropertyFactory.lineWidth(2f),
                    PropertyFactory.lineColor(
                        com.mapbox.mapboxsdk.style.expressions.Expression.get("color")
                    ),
                    PropertyFactory.lineOpacity(0.9f)
                )
                style.addLayerBelow(strokeLayer, ROUTE_LAYER_ID)

            } catch (e: Exception) {
                Log.e(tag, "Failed to render hazard zones on map", e)
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────

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
        val styleFile = File(cacheDir, "tactical_offline_style_${selectedRegion.key}.json")
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

    private fun extractMbtilesToRasterTiles(mbtilesPath: String, packageInfo: MapPackageInfo): String {
        val tilesRoot = File(cacheDir, "offline-raster-tiles-${selectedRegion.key}")
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
        val location = effectiveOperatorLocation()
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
        val location = effectiveOperatorLocation()
            ?: return RouteRequestResult(errorMessage = routeUnavailableMessage())
        if (!selectedRegion.usesDemoOperator && !isInsideOfflineBounds(location)) {
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

        val boundsFilter = OfflinePlaceIndex.BoundsFilter(
            west = packageInfo.bounds[0],
            south = packageInfo.bounds[1],
            east = packageInfo.bounds[2],
            north = packageInfo.bounds[3]
        )
        val placeMatch = placeIndex.resolve(destinationPhrase, currentLocation, boundsFilter)
            ?: placeIndex.resolve(destinationPhrase, currentLocation)
            ?: return null
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
        transcriptionText.text = getString(R.string.destination_selected_hint)
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
            refreshOperatorPresentation()
        } ?: run {
            statusText.text = if (selectedRegion.usesDemoOperator) {
                getString(R.string.region_demo_status, selectedRegion.displayName)
            } else {
                "Offline Tactical Map Active (Waiting for GPS fix)"
            }
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
        statusText.text = if (selectedRegion.usesDemoOperator) {
            getString(R.string.region_demo_status, selectedRegion.displayName)
        } else if (isInsideOfflineBounds(location)) {
            getString(R.string.region_live_status, selectedRegion.displayName)
        } else {
            getString(R.string.region_outside_status, selectedRegion.displayName)
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
        val location = effectiveOperatorLocation() ?: return
        if (routeCoords.size < 2) return

        val nearestIndex = findNearestRouteIndex(location, routeCoords)
        val remainingDistanceMeters = calculateRemainingDistanceMeters(location, routeCoords, nearestIndex)
        val remainingRatio = if (activeRouteTotalDistanceMeters > 0.0) {
            (remainingDistanceMeters / activeRouteTotalDistanceMeters).coerceIn(0.0, 1.0)
        } else {
            1.0
        }
        val remainingDurationMillis = estimateRemainingDurationMillis(remainingDistanceMeters, remainingRatio)
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

    private fun estimateRemainingDurationMillis(
        remainingDistanceMeters: Double,
        remainingRatio: Double
    ): Long {
        val routeAverageSpeed = if (activeRouteTotalDurationMillis > 0L) {
            activeRouteTotalDistanceMeters / (activeRouteTotalDurationMillis / 1000.0)
        } else {
            0.0
        }
        val observedSpeed = smoothedSpeedMetersPerSecond
        val effectiveSpeed = when {
            selectedRegion.usesDemoOperator -> routeAverageSpeed
            observedSpeed == null || observedSpeed < 0.8 -> routeAverageSpeed
            routeAverageSpeed <= 0.0 -> observedSpeed
            else -> {
                val minSpeed = routeAverageSpeed * 0.55
                val maxSpeed = routeAverageSpeed * 1.85
                observedSpeed.coerceIn(minSpeed, maxSpeed)
            }
        }

        if (effectiveSpeed > 0.0) {
            return ((remainingDistanceMeters / effectiveSpeed) * 1000.0).toLong().coerceAtLeast(60_000L)
        }

        return (activeRouteTotalDurationMillis * remainingRatio).toLong().coerceAtLeast(60_000L)
    }

    private fun updateObservedSpeed(location: Location) {
        if (selectedRegion.usesDemoOperator) return

        val rawSpeed = when {
            location.hasSpeed() && location.speed > 0.0f -> location.speed.toDouble()
            else -> {
                val previous = lastEtaSampleLocation
                if (previous != null && location.time > previous.time) {
                    val deltaSeconds = (location.time - previous.time) / 1000.0
                    if (deltaSeconds >= 1.0) {
                        distanceBetweenMeters(
                            previous.latitude,
                            previous.longitude,
                            location.latitude,
                            location.longitude
                        ) / deltaSeconds
                    } else {
                        0.0
                    }
                } else {
                    0.0
                }
            }
        }

        if (rawSpeed in 0.8..55.0) {
            smoothedSpeedMetersPerSecond = smoothedSpeedMetersPerSecond?.let { existing ->
                (existing * 0.65) + (rawSpeed * 0.35)
            } ?: rawSpeed
        }

        lastEtaSampleLocation = Location(location)
    }

    private fun maybeRerouteOnDeviation(location: Location) {
        if (selectedRegion.usesDemoOperator) return
        val routeCoords = activeRouteCoords ?: return
        if (routeCoords.size < 2 || rerouteInProgress) return
        if (!::routingEngine.isInitialized || !routingEngineReady) return
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
                destination.longitude,
                hazardManager
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

    private fun rerouteActiveRoute(statusMessage: String) {
        val packageInfo = mapPackageInfo ?: return
        val operatorLocation = effectiveOperatorLocation() ?: return
        val destination = currentDestinationForReroute(packageInfo) ?: return
        if (rerouteInProgress || !::routingEngine.isInitialized || !routingEngineReady) return

        rerouteInProgress = true
        try {
            val routeResult = routingEngine.calculateRoute(
                operatorLocation.latitude,
                operatorLocation.longitude,
                destination.latitude,
                destination.longitude,
                hazardManager
            )
            if (routeResult != null) {
                drawRouteOnMap(routeResult.coordinates)
                setActiveRoute(routeResult, destination.label)
                updateActiveRouteProgress()
                transcriptionText.text = statusMessage
            } else {
                val routingError = routingEngine.getLastError() ?: "Unknown reroute error"
                transcriptionText.text = "$statusMessage\nRoute refresh failed: $routingError"
            }
        } catch (exception: Exception) {
            Log.e(tag, "Hazard-triggered reroute failed", exception)
            transcriptionText.text = "$statusMessage\nRoute refresh error: ${exception.message}"
        } finally {
            rerouteInProgress = false
        }
    }

    private fun handleShakeZoomTrigger() {
        val map = mapboxMap ?: return
        val currentCamera = map.cameraPosition
        val packageInfo = mapPackageInfo
        val minZoom = (packageInfo?.minZoom ?: 10).toDouble().coerceAtLeast(8.0)
        val maxZoom = (packageInfo?.maxZoom ?: 15).toDouble().coerceAtMost(17.0)
        val nextZoom = if (nextShakeZoomsIn) {
            (currentCamera.zoom + SHAKE_ZOOM_DELTA).coerceAtMost(maxZoom)
        } else {
            (currentCamera.zoom - SHAKE_ZOOM_DELTA).coerceAtLeast(minZoom)
        }

        map.cameraPosition = CameraPosition.Builder(currentCamera)
            .zoom(nextZoom)
            .build()
        transcriptionText.text = if (nextShakeZoomsIn) {
            "Shake zoom: zoomed in."
        } else {
            "Shake zoom: zoomed out."
        }
        nextShakeZoomsIn = !nextShakeZoomsIn
    }

    private fun syncCompassBearing() {
        if (!::compassFloatingButton.isInitialized) return
        val bearing = mapboxMap?.cameraPosition?.bearing?.toFloat() ?: 0f
        compassFloatingButton.rotation = -bearing
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values.getOrNull(0)?.toDouble() ?: return
        val y = event.values.getOrNull(1)?.toDouble() ?: return
        val z = event.values.getOrNull(2)?.toDouble() ?: return
        val accelerationDelta = sqrt((x * x) + (y * y) + (z * z)) - SensorManager.GRAVITY_EARTH
        if (kotlin.math.abs(accelerationDelta) < SHAKE_ACCEL_THRESHOLD) return

        val now = System.currentTimeMillis()
        if (now - lastShakeZoomTriggerTimestampMs < SHAKE_ZOOM_COOLDOWN_MS) return

        shakeImpulseCount = if (now - lastShakeImpulseTimestampMs <= SHAKE_IMPULSE_WINDOW_MS) {
            shakeImpulseCount + 1
        } else {
            1
        }
        lastShakeImpulseTimestampMs = now

        if (shakeImpulseCount >= 2) {
            shakeImpulseCount = 0
            lastShakeZoomTriggerTimestampMs = now
            handleShakeZoomTrigger()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

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
        val location = effectiveOperatorLocation() ?: return false
        if (!selectedRegion.usesDemoOperator && !isInsideOfflineBounds(location)) {
            return false
        }
        mapboxMap?.cameraPosition = CameraPosition.Builder()
            .target(LatLng(location.latitude, location.longitude))
            .zoom((mapPackageInfo?.maxZoom ?: 15).toDouble().coerceAtMost(16.0))
            .build()
        return true
    }

    private fun effectiveOperatorLocation(): Location? {
        val packageInfo = mapPackageInfo
        return if (selectedRegion.usesDemoOperator && packageInfo != null) {
            buildDemoOperatorLocation(packageInfo)
        } else {
            currentLocation
        }
    }

    private fun routeUnavailableMessage(): String {
        return if (selectedRegion.usesDemoOperator) {
            "Routing blocked: waiting for offline region package."
        } else {
            "Waiting for GPS fix before routing."
        }
    }

    private fun buildDemoOperatorLocation(packageInfo: MapPackageInfo): Location {
        selectedRegion.demoOperatorLat?.let { lat ->
            val lon = selectedRegion.demoOperatorLon ?: packageInfo.centerLon
            return Location("demo-${selectedRegion.key}").apply {
                latitude = lat
                longitude = lon
                accuracy = 3f
                time = System.currentTimeMillis()
            }
        }
        val latSpan = packageInfo.bounds[3] - packageInfo.bounds[1]
        val lonSpan = packageInfo.bounds[2] - packageInfo.bounds[0]
        val lat = packageInfo.centerLat + (latSpan * selectedRegion.startLatFactor)
        val lon = packageInfo.centerLon + (lonSpan * selectedRegion.startLonFactor)
        return Location("demo-${selectedRegion.key}").apply {
            latitude = lat
            longitude = lon
            accuracy = 3f
            time = System.currentTimeMillis()
        }
    }
    
    private fun copyAssetToCache(assetName: String): String {
        val outFile = File(cacheDir, "${selectedRegion.key}-${File(assetName).name}")
        if (outFile.exists() && outFile.length() > 0L) {
            return outFile.absolutePath
        }
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
        val cacheFolder = File(cacheDir, "graphhopper-cache-${selectedRegion.key}")
        val markerFile = File(cacheFolder, ".graphhopper-extract-signature")
        val assetSignature = buildGraphhopperAssetSignature()
        val graphFolderIsComplete = hasCompleteGraphCache(cacheFolder)

        if (markerFile.exists() && markerFile.readText() == assetSignature && graphFolderIsComplete) {
            Log.i(tag, "GraphHopper cache already unpacked with current asset signature.")
            return
        }

        if (cacheFolder.exists()) {
            cacheFolder.deleteRecursively()
        }
        cacheFolder.mkdirs()
        
        try {
            assets.open(resolvedGraphhopperZipAssetPath()).use { inputStream ->
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
            runOnUiThread {
                if (!isDestroyed && !isFinishing) {
                    statusText.text = "Error unpacking GraphHopper: ${e.message}"
                }
            }
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
        val totalSize = assets.open(resolvedGraphhopperZipAssetPath()).use { input ->
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

    private fun assetExists(assetPath: String): Boolean {
        return runCatching {
            assets.open(assetPath).close()
            true
        }.getOrDefault(false)
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
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        speechService?.setPause(true)
        locationManager.removeUpdates(locationListener)
        sensorManager.unregisterListener(this)
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
        speechService?.setPause(true)
        locationManager.removeUpdates(locationListener)
        sensorManager.unregisterListener(this)
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
        sensorManager.unregisterListener(this)
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
