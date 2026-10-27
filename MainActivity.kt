package vn.navigation.gofaclone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

val GofaGreen = Color(0xFF0E8A5F)
val GofaRed = Color(0xFFE5434B)

enum class RoadType(val label: String, val alertDistanceMeters: Int) {
    URBAN("Đô thị", 300),
    NATIONAL_HIGHWAY("Quốc lộ", 400),
    EXPRESSWAY("Cao tốc", 500)
}

data class PlaceSearchResult(
    val displayName: String,
    val lat: Double,
    val lon: Double
)

data class RouteStepItem(
    val instruction: String,
    val distanceMeters: Int,
    val roadName: String,
    val location: GeoPoint
)

data class RouteOption(
    val title: String,
    val polylinePoints: List<GeoPoint>,
    val steps: List<RouteStepItem>,
    val totalDistanceKm: Double,
    val totalDurationMin: Int
)

data class SpeedCameraNode(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val maxSpeed: Int,
    val roadType: RoadType
)

val PREBUILT_PLACES = listOf(
    PlaceSearchResult("TP. Đà Lạt, Lâm Đồng", 11.9404, 108.4583),
    PlaceSearchResult("Chợ Đà Lạt, Nguyễn Thị Minh Khai, Phường 1, Đà Lạt", 11.9413, 108.4357),
    PlaceSearchResult("Hồ Xuân Hương, Đà Lạt, Lâm Đồng", 11.9430, 108.4380),
    PlaceSearchResult("Đèo Bảo Lộc, Quốc lộ 20, Lâm Đồng", 11.4580, 107.7250),
    PlaceSearchResult("TP. Hồ Chí Minh (Trung tâm Quận 1)", 10.7769, 106.7009),
    PlaceSearchResult("Bến xe Miền Tây, Bình Tân, TP.HCM", 10.7324, 106.6190),
    PlaceSearchResult("Cao tốc TP.HCM - Long Thành - Dầu Giây", 10.8210, 106.9530),
    PlaceSearchResult("Nút giao IC3, Cần Thơ", 10.0152, 105.7881),
    PlaceSearchResult("TP. Cần Thơ (Bến Ninh Kiều)", 10.0341, 105.7839),
    PlaceSearchResult("Cao tốc Mỹ Thuận - Cần Thơ", 10.2541, 105.8950),
    PlaceSearchResult("TP. Sóc Trăng, Sóc Trăng", 9.6025, 105.9739),
    PlaceSearchResult("TP. Bạc Liêu", 9.2941, 105.7278),
    PlaceSearchResult("Cột mốc Mũi Cà Mau, Đất Mũi, Cà Mau", 8.6231, 104.7925),
    PlaceSearchResult("TP. Cà Mau, Cà Mau", 9.1768, 105.1524),
    PlaceSearchResult("Trạm dừng chân Mekong Rest Stop (Tiền Giang)", 10.3842, 106.3120)
)

fun createOnlineMapTileSource(name: String, lyrsCode: String): OnlineTileSourceBase {
    return object : OnlineTileSourceBase(
        name, 1, 20, 256, ".png",
        arrayOf(
            "https://mt0.google.com/vt/lyrs=$lyrsCode&hl=vi",
            "https://mt1.google.com/vt/lyrs=$lyrsCode&hl=vi",
            "https://mt2.google.com/vt/lyrs=$lyrsCode&hl=vi",
            "https://mt3.google.com/vt/lyrs=$lyrsCode&hl=vi"
        )
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val z = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            return "$baseUrl&x=$x&y=$y&z=$z"
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val osmConfig = Configuration.getInstance()
        osmConfig.userAgentValue = "GofaOtoVietnamNavigation/3.1"
        val baseDir = File(cacheDir, "osmdroid")
        val tileDir = File(baseDir, "tiles")
        baseDir.mkdirs()
        tileDir.mkdirs()
        osmConfig.osmdroidBasePath = baseDir
        osmConfig.osmdroidTileCache = tileDir

        setContent {
            MaterialTheme {
                GofaOnlineNavigationApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GofaOnlineNavigationApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var userLocation by remember { mutableStateOf(GeoPoint(9.2941, 105.7278)) }
    var hasGpsFix by remember { mutableStateOf(false) }
    var vehicleBearing by remember { mutableFloatStateOf(0f) }
    var followUserGps by remember { mutableStateOf(true) }

    var currentSpeedKmh by remember { mutableIntStateOf(0) }
    var currentSpeedLimit by remember { mutableStateOf<Int?>(50) }
    var currentRoadName by remember { mutableStateOf("Đang cập nhật đường...") }
    var activeAlertBanner by remember { mutableStateOf<String?>(null) }

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var mapLayerMode by remember { mutableIntStateOf(0) }
    var isVoiceEnabled by remember { mutableStateOf(true) }
    var showCamerasOnMap by remember { mutableStateOf(true) }

    var showSearchSheet by remember { mutableStateOf(false) }
    var showRouteOptionsSheet by remember { mutableStateOf(false) }
    var showStepsSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showMapLongClickDialog by remember { mutableStateOf(false) }
    var longClickGeoPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var longClickAddressName by remember { mutableStateOf("") }

    var startInputText by remember { mutableStateOf("Vị trí GPS hiện tại của tôi") }
    var destInputText by remember { mutableStateOf("") }
    var selectedStartPoint by remember { mutableStateOf<PlaceSearchResult?>(null) }
    var selectedDestPoint by remember { mutableStateOf<PlaceSearchResult?>(null) }
    var searchSuggestions by remember { mutableStateOf<List<PlaceSearchResult>>(PREBUILT_PLACES) }
    var isSelectingStartField by remember { mutableStateOf(false) }
    var isLoadingRoute by remember { mutableStateOf(false) }

    var availableRoutes by remember { mutableStateOf<List<RouteOption>>(emptyList()) }
    var activeRoute by remember { mutableStateOf<RouteOption?>(null) }
    var nextStepInstruction by remember { mutableStateOf<String?>(null) }

    var avoidTolls by remember { mutableStateOf(false) }
    var avoidHighways by remember { mutableStateOf(false) }
    var avoidFerries by remember { mutableStateOf(false) }

    val cameraNodes = remember {
        mutableStateListOf(
            SpeedCameraNode("BL_QL1", "Camera phạt nguội QL1A Bạc Liêu", 9.2985, 105.7240, 50, RoadType.URBAN),
            SpeedCameraNode("CM_QL1", "Camera phạt nguội cửa ngõ Cà Mau", 9.1768, 105.1524, 50, RoadType.URBAN),
            SpeedCameraNode("ST_QL1", "Trạm bắn tốc độ QL1A Sóc Trăng", 9.6025, 105.9739, 60, RoadType.NATIONAL_HIGHWAY),
            SpeedCameraNode("CT_IC3", "Camera phạt nguội nút giao IC3 Cần Thơ", 10.0152, 105.7881, 60, RoadType.NATIONAL_HIGHWAY),
            SpeedCameraNode("MT_CT", "Cao tốc Mỹ Thuận - Cần Thơ", 10.2541, 105.8950, 90, RoadType.EXPRESSWAY),
            SpeedCameraNode("TL_MT", "Cao tốc Trung Lương - Mỹ Thuận", 10.4350, 106.3420, 90, RoadType.EXPRESSWAY),
            SpeedCameraNode("HCM_VVK", "Camera tốc độ Đại lộ Võ Văn Kiệt TP.HCM", 10.7503, 106.6634, 60, RoadType.URBAN),
            SpeedCameraNode("LTDG", "Cao tốc TP.HCM - Long Thành - Dầu Giây", 10.8210, 106.9530, 120, RoadType.EXPRESSWAY),
            SpeedCameraNode("QL20_BL", "Camera tốc độ QL20 Đèo Bảo Lộc", 11.4580, 107.7250, 50, RoadType.NATIONAL_HIGHWAY),
            SpeedCameraNode("LK_DL", "Cao tốc Liên Khương - Prenn Đà Lạt", 11.7520, 108.3810, 90, RoadType.EXPRESSWAY)
        )
    }
    val spokenAlertIds = remember { mutableStateListOf<String>() }

    val tts = remember {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale("vi", "VN")
            }
        }
        engine
    }

    fun speak(text: String) {
        if (isVoiceEnabled) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    fun refreshMap(map: MapView?) {
        if (map == null) return
        map.overlays.removeAll { it is Polyline || (it is Marker && it.title?.startsWith("🎥") == true) }

        activeRoute?.let { route ->
            val polyline = Polyline(map).apply {
                setPoints(route.polylinePoints)
                outlinePaint.color = AndroidColor.parseColor("#0E8A5F")
                outlinePaint.strokeWidth = 22f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                outlinePaint.isAntiAlias = true
            }
            map.overlays.add(polyline)

            if (route.polylinePoints.isNotEmpty()) {
                val startM = Marker(map).apply {
                    position = route.polylinePoints.first()
                    title = "🟢 Điểm xuất phát"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                val endM = Marker(map).apply {
                    position = route.polylinePoints.last()
                    title = "🔴 Điểm đến: $destInputText"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                map.overlays.add(startM)
                map.overlays.add(endM)
            }
        }

        if (showCamerasOnMap) {
            for (cam in cameraNodes) {
                val m = Marker(map).apply {
                    position = GeoPoint(cam.lat, cam.lon)
                    title = "🎥 ${cam.name}"
                    snippet = "Giới hạn: ${cam.maxSpeed} km/h"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                map.overlays.add(m)
            }
        }
        map.invalidate()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true || perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val listener = LocationListener { loc ->
                hasGpsFix = true
                currentSpeedKmh = (loc.speed * 3.6f).roundToInt()
                if (loc.hasBearing()) vehicleBearing = loc.bearing
                val p = GeoPoint(loc.latitude, loc.longitude)
                userLocation = p
                if (followUserGps) {
                    mapViewRef?.controller?.animateTo(p)
                }

                for (cam in cameraNodes) {
                    val dist = FloatArray(1)
                    Location.distanceBetween(loc.latitude, loc.longitude, cam.lat, cam.lon, dist)
                    val dMeters = dist[0].roundToInt()
                    if (dMeters <= cam.roadType.alertDistanceMeters) {
                        currentSpeedLimit = cam.maxSpeed
                        activeAlertBanner = "⚠️ Cách ${dMeters}m (${cam.roadType.label}): ${cam.name} [${cam.maxSpeed} km/h]"
                        if (!spokenAlertIds.contains(cam.id)) {
                            spokenAlertIds.add(cam.id)
                            speak("Chú ý! Phía trước $dMeters mét có ${cam.name}, giới hạn tốc độ ${cam.maxSpeed} ki lô mét trên giờ.")
                        }
                        break
                    }
                }
                if (currentSpeedLimit != null && currentSpeedKmh > currentSpeedLimit!! && currentSpeedKmh > 15) {
                    activeAlertBanner = "🚨 BẠN ĐANG VƯỢT TỐC ĐỘ GIỚI HẠN ($currentSpeedKmh / $currentSpeedLimit km/h)!"
                }
            }
            try {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, listener)
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 2f, listener)
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { listener.onLocationChanged(it) }
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(createOnlineMapTileSource("GoogleStandardVN", "m"))
                    setMultiTouchControls(true)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    controller.setZoom(16.0)
                    controller.setCenter(userLocation)
                    mapViewRef = this

                    val mReceive = object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
                        override fun longPressHelper(p: GeoPoint?): Boolean {
                            if (p != null) {
                                longClickGeoPoint = p
                                longClickAddressName = "Tọa độ [${String.format("%.4f", p.latitude)}, ${String.format("%.4f", p.longitude)}]"
                                showMapLongClickDialog = true
                            }
                            return true
                        }
                    }
                    overlays.add(MapEventsOverlay(mReceive))
                    refreshMap(this)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Box(modifier = Modifier.align(Alignment.Center)) {
            NavigationCursor(rotationDegrees = vehicleBearing)
        }

        val isOver = currentSpeedLimit != null && currentSpeedKmh > currentSpeedLimit!!
        Box(
            modifier = Modifier
                .padding(top = 42.dp, start = 14.dp)
                .size(width = 115.dp, height = 105.dp)
                .align(Alignment.TopStart)
        ) {
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(8.dp, GofaRed, CircleShape)
                    .clickable { speak("Giới hạn tốc độ ${currentSpeedLimit ?: "--"} ki lô mét trên giờ.") },
                contentAlignment = Alignment.Center
            ) {
                Text(text = currentSpeedLimit?.toString() ?: "--", color = Color.Black, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            }
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .align(Alignment.BottomEnd)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(if (isOver) GofaRed else Color.White)
                    .border(5.dp, if (isOver) Color.Yellow else GofaGreen, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(text = currentSpeedKmh.toString(), color = if (isOver) Color.White else GofaGreen, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }

        activeRoute?.let { route ->
            Surface(
                modifier = Modifier
                    .padding(top = 42.dp, start = 136.dp, end = 78.dp)
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .clickable { showStepsSheet = true },
                shape = RoundedCornerShape(16.dp),
                color = GofaGreen,
                shadowElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(text = nextStepInstruction ?: "Đi theo đường màu xanh", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "🚗 ${route.totalDistanceKm} km  •  ⏱ ${route.totalDurationMin} phút (Chạm xem chi tiết bước rẽ)", color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp)
                }
            }
        }

        activeAlertBanner?.let { alert ->
            Surface(
                modifier = Modifier
                    .padding(top = 152.dp, start = 14.dp, end = 78.dp)
                    .align(Alignment.TopStart),
                shape = RoundedCornerShape(12.dp),
                color = GofaRed,
                shadowElevation = 6.dp
            ) {
                Text(text = alert, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }

        Surface(
            modifier = Modifier.padding(top = 42.dp, end = 14.dp).align(Alignment.TopEnd),
            shape = RoundedCornerShape(28.dp), color = Color.White.copy(alpha = 0.95f), shadowElevation = 5.dp
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                IconButton(onClick = {
                    mapLayerMode = (mapLayerMode + 1) % 3
                    val (src, lyrs, lbl) = when(mapLayerMode) {
                        1 -> Triple("GoogleTraffic", "m,traffic", "Bản đồ Giao thông trực tuyến")
                        2 -> Triple("GoogleSat", "y", "Bản đồ Vệ tinh")
                        else -> Triple("GoogleStandard", "m", "Bản đồ Tiêu chuẩn")
                    }
                    mapViewRef?.setTileSource(createOnlineMapTileSource(src, lyrs))
                    mapViewRef?.invalidate()
                    Toast.makeText(context, lbl, Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Outlined.Layers, contentDescription = null, tint = GofaGreen, modifier = Modifier.size(28.dp))
                }
                IconButton(onClick = {
                    isVoiceEnabled = !isVoiceEnabled
                    Toast.makeText(context, if (isVoiceEnabled) "Đã bật giọng nói" else "Đã tắt giọng nói", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(if (isVoiceEnabled) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff, contentDescription = null, tint = if (isVoiceEnabled) GofaGreen else Color.Gray, modifier = Modifier.size(28.dp))
                }
                IconButton(onClick = { showStepsSheet = true }) {
                    Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = GofaGreen, modifier = Modifier.size(26.dp))
                }
                IconButton(onClick = {
                    showCamerasOnMap = !showCamerasOnMap
                    refreshMap(mapViewRef)
                    Toast.makeText(context, if (showCamerasOnMap) "Đã bật hiển thị Camera phạt nguội" else "Đã ẩn Camera", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Outlined.PhotoCamera, contentDescription = null, tint = if (showCamerasOnMap) GofaGreen else Color.Gray, modifier = Modifier.size(28.dp))
                }
            }
        }

        Surface(
            modifier = Modifier.padding(start = 14.dp, bottom = 105.dp).align(Alignment.BottomStart),
            shape = RoundedCornerShape(28.dp), color = Color.White.copy(alpha = 0.95f), shadowElevation = 5.dp
        ) {
            Column(modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = {
                    followUserGps = true
                    mapViewRef?.controller?.animateTo(userLocation)
                    mapViewRef?.controller?.setZoom(17.0)
                    Toast.makeText(context, "📍 Đã đưa tâm về vị trí GPS của bạn", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Outlined.MyLocation, contentDescription = null, tint = if (followUserGps) GofaGreen else Color.Gray, modifier = Modifier.size(26.dp))
                }
                IconButton(onClick = { followUserGps = false; mapViewRef?.controller?.zoomIn() }) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = GofaGreen, modifier = Modifier.size(26.dp))
                }
                IconButton(onClick = { followUserGps = false; mapViewRef?.controller?.zoomOut() }) {
                    Icon(Icons.Default.Remove, contentDescription = null, tint = GofaGreen, modifier = Modifier.size(26.dp))
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 22.dp).align(Alignment.BottomCenter),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.weight(1f).height(58.dp).clickable { showSearchSheet = true },
                shape = RoundedCornerShape(30.dp), color = Color.White.copy(alpha = 0.97f), shadowElevation = 5.dp
            ) {
                Row(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(44.dp).clip(CircleShape).background(GofaGreen), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = if (destInputText.isEmpty()) "Tìm kiếm địa điểm, quán xá, tỉnh thành..." else "Đến: $destInputText", color = Color.DarkGray, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (activeRoute != null) {
                            Text(text = "Đang dẫn đường • ${activeRoute!!.totalDistanceKm} km (${activeRoute!!.totalDurationMin} phút)", color = GofaGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.size(58.dp).clickable { showSettingsSheet = true },
                shape = CircleShape, color = Color.White.copy(alpha = 0.97f), shadowElevation = 5.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Settings, contentDescription = null, tint = GofaGreen, modifier = Modifier.size(30.dp))
                }
            }
        }

        if (showMapLongClickDialog && longClickGeoPoint != null) {
            AlertDialog(
                onDismissRequest = { showMapLongClickDialog = false },
                title = { Text("📍 Điểm đã chọn trên bản đồ", fontWeight = FontWeight.Bold, color = GofaGreen) },
                text = { Text(longClickAddressName) },
                confirmButton = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = {
                            selectedDestPoint = PlaceSearchResult(longClickAddressName, longClickGeoPoint!!.latitude, longClickGeoPoint!!.longitude)
                            destInputText = longClickAddressName
                            showMapLongClickDialog = false
                            showSearchSheet = true
                            Toast.makeText(context, "Đã chọn làm Điểm đến!", Toast.LENGTH_SHORT).show()
                        }, colors = ButtonDefaults.buttonColors(containerColor = GofaGreen)) {
                            Text("Đặt làm Điểm đến (Đến đây)")
                        }
                        OutlinedButton(onClick = {
                            selectedStartPoint = PlaceSearchResult(longClickAddressName, longClickGeoPoint!!.latitude, longClickGeoPoint!!.longitude)
                            startInputText = longClickAddressName
                            showMapLongClickDialog = false
                            showSearchSheet = true
                            Toast.makeText(context, "Đã chọn làm Điểm xuất phát!", Toast.LENGTH_SHORT).show()
                        }) {
                            Text("Đặt làm Điểm xuất phát (Từ đây)")
                        }
                    }
                }
            )
        }

        if (showSearchSheet) {
            ModalBottomSheet(onDismissRequest = { showSearchSheet = false }, modifier = Modifier.fillMaxHeight(0.92f)) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Lập lộ trình dẫn đường Ô tô", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GofaGreen)
                        TextButton(onClick = {
                            val tTxt = startInputText
                            val tPt = selectedStartPoint
                            startInputText = destInputText.ifEmpty { "Vị trí GPS hiện tại của tôi" }
                            selectedStartPoint = selectedDestPoint
                            destInputText = if (tTxt == "Vị trí GPS hiện tại của tôi") "" else tTxt
                            selectedDestPoint = tPt
                        }) {
                            Icon(Icons.Default.SwapVert, contentDescription = null, tint = GofaGreen)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Đảo chiều", color = GofaGreen, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = startInputText,
                        onValueChange = {
                            startInputText = it
                            isSelectingStartField = true
                            searchSuggestions = filterPlacesIgnoreCase(it)
                        },
                        label = { Text("🟢 Điểm xuất phát") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = {
                                selectedStartPoint = null
                                startInputText = "Vị trí GPS hiện tại của tôi"
                                Toast.makeText(context, "Đã chọn GPS hiện tại", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.MyLocation, contentDescription = null, tint = GofaGreen)
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = destInputText,
                        onValueChange = {
                            destInputText = it
                            isSelectingStartField = false
                            searchSuggestions = filterPlacesIgnoreCase(it)
                        },
                        label = { Text("🔴 Điểm đến (VD: da lat, cho da lat, can tho...)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            if (destInputText.isNotEmpty()) {
                                IconButton(onClick = { destInputText = ""; selectedDestPoint = null }) {
                                    Icon(Icons.Default.Clear, contentDescription = null)
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = avoidTolls, onClick = { avoidTolls = !avoidTolls }, label = { Text("Né BOT", fontSize = 12.sp) })
                        FilterChip(selected = avoidHighways, onClick = { avoidHighways = !avoidHighways }, label = { Text("Né cao tốc", fontSize = 12.sp) })
                        FilterChip(selected = avoidFerries, onClick = { avoidFerries = !avoidFerries }, label = { Text("Né phà", fontSize = 12.sp) })
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            if (destInputText.isBlank()) {
                                Toast.makeText(context, "Vui lòng nhập Điểm đến!", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isLoadingRoute = true
                            scope.launch {
                                val startGeo = selectedStartPoint?.let { GeoPoint(it.lat, it.lon) }
                                    ?: (if (startInputText != "Vị trí GPS hiện tại của tôi") filterPlacesIgnoreCase(startInputText).firstOrNull()?.let { GeoPoint(it.lat, it.lon) } else null)
                                    ?: userLocation

                                val destPlace = selectedDestPoint ?: filterPlacesIgnoreCase(destInputText).firstOrNull()
                                if (destPlace == null) {
                                    isLoadingRoute = false
                                    Toast.makeText(context, "Không tìm thấy địa điểm '$destInputText'. Hãy chọn từ danh sách gợi ý bên dưới!", Toast.LENGTH_LONG).show()
                                    return@launch
                                }
                                selectedDestPoint = destPlace
                                destInputText = destPlace.displayName.substringBefore(",")
                                val endGeo = GeoPoint(destPlace.lat, destPlace.lon)

                                val options = fetchMultipleRouteOptions(startGeo, endGeo)
                                isLoadingRoute = false

                                if (options.isNotEmpty()) {
                                    availableRoutes = options
                                    showSearchSheet = false
                                    showRouteOptionsSheet = true
                                } else {
                                    Toast.makeText(context, "Không thể tính lộ trình. Kiểm tra kết nối mạng!", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GofaGreen),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (isLoadingRoute) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Đang tính toán các lộ trình...", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.Navigation, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Tìm các lộ trình đi Ô tô", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(text = "📍 Danh sách gợi ý địa điểm (Không phân biệt hoa thường):", fontSize = 13.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))

                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(searchSuggestions) { place ->
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    if (isSelectingStartField) {
                                        selectedStartPoint = place
                                        startInputText = place.displayName
                                        Toast.makeText(context, "Đã chọn Điểm đầu!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        selectedDestPoint = place
                                        destInputText = place.displayName
                                        Toast.makeText(context, "Đã chọn Điểm đến!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = RoundedCornerShape(10.dp), color = Color(0xFFF3F7F5)
                            ) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Place, contentDescription = null, tint = GofaGreen)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(text = place.displayName, fontSize = 14.sp, color = Color.Black)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showRouteOptionsSheet) {
            ModalBottomSheet(onDismissRequest = { showRouteOptionsSheet = false }) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
                    Text("Lựa chọn lộ trình lái xe", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GofaGreen)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Hệ thống đã tìm thấy ${availableRoutes.size} phương án tuyến đường tối ưu:", fontSize = 13.sp, color = Color.Gray)
                    Spacer(modifier = Modifier.height(10.dp))

                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(availableRoutes) { opt ->
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    activeRoute = opt
                                    nextStepInstruction = opt.steps.firstOrNull()?.instruction ?: "Bắt đầu di chuyển"
                                    refreshMap(mapViewRef)
                                    showRouteOptionsSheet = false
                                    followUserGps = false
                                    speak("Đã chọn lộ trình ${opt.title}. Quãng đường ${opt.totalDistanceKm} ki lô mét, thời gian ${opt.totalDurationMin} phút.")
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFFEAF5F0),
                                border = BorderStroke(1.5.dp, GofaGreen)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(text = opt.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = GofaGreen)
                                        Text(text = "⏱ ${opt.totalDurationMin} phút", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.DarkGray)
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(text = "Quãng đường: ${opt.totalDistanceKm} km", fontSize = 14.sp, color = Color.DarkGray)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(text = "Đường chính: ${opt.steps.firstOrNull()?.roadName?.ifEmpty { "Đường ô tô chính" } ?: "Đường chính"}", fontSize = 12.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }

        if (showStepsSheet) {
            ModalBottomSheet(onDismissRequest = { showStepsSheet = false }) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Chi tiết các bước rẽ", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = GofaGreen)
                        if (activeRoute != null) {
                            TextButton(onClick = {
                                activeRoute = null
                                nextStepInstruction = null
                                destInputText = ""
                                selectedDestPoint = null
                                refreshMap(mapViewRef)
                                showStepsSheet = false
                                Toast.makeText(context, "Đã kết thúc dẫn đường", Toast.LENGTH_SHORT).show()
                            }) {
                                Text("Xoá lộ trình", color = GofaRed, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (activeRoute == null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Chưa có lộ trình nào được kích hoạt.", fontSize = 15.sp, color = Color.DarkGray)
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyColumn(modifier = Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(activeRoute!!.steps) { step ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        mapViewRef?.controller?.animateTo(step.location)
                                        mapViewRef?.controller?.setZoom(17.5)
                                        showStepsSheet = false
                                    },
                                    shape = RoundedCornerShape(10.dp), color = Color(0xFFF2F6F4)
                                ) {
                                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(step.instruction, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        }
                                        Text("${step.distanceMeters} m", fontWeight = FontWeight.Bold, color = GofaGreen, fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }

        if (showSettingsSheet) {
            ModalBottomSheet(onDismissRequest = { showSettingsSheet = false }) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text("Cài đặt Lái xe Ô tô", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GofaGreen)
                    Spacer(modifier = Modifier.height(8.dp))
                    SettingSwitchRow("Hiển thị Camera phạt nguội", showCamerasOnMap) { showCamerasOnMap = it; refreshMap(mapViewRef) }
                    SettingSwitchRow("Bật giọng nói tiếng Việt", isVoiceEnabled) { isVoiceEnabled = it }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
fun NavigationCursor(rotationDegrees: Float = 0f) {
    Box(modifier = Modifier.size(92.dp).clip(CircleShape).background(GofaGreen.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(44.dp).rotate(rotationDegrees)) {
            val path = Path().apply {
                moveTo(size.width / 2f, 0f)
                lineTo(size.width, size.height)
                lineTo(size.width / 2f, size.height * 0.76f)
                lineTo(0f, size.height)
                close()
            }
            drawPath(path = path, color = Color.White, style = Stroke(width = 8f))
            drawPath(path = path, color = GofaGreen)
        }
    }
}

@Composable
fun SettingSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = title, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = GofaGreen))
    }
}

fun filterPlacesIgnoreCase(rawQuery: String): List<PlaceSearchResult> {
    val q = rawQuery.trim().lowercase(Locale.ROOT)
    if (q.isEmpty()) return PREBUILT_PLACES
    return PREBUILT_PLACES.filter {
        it.displayName.lowercase(Locale.ROOT).contains(q) || removeDiacritics(it.displayName).contains(removeDiacritics(q))
    }.ifEmpty { PREBUILT_PLACES }
}

fun removeDiacritics(str: String): String {
    val normalized = java.text.Normalizer.normalize(str, java.text.Normalizer.Form.NFD)
    val pattern = java.util.regex.Pattern.compile("\\p{InCombiningDiacriticalMarks}+")
    return pattern.matcher(normalized).replaceAll("").lowercase(Locale.ROOT)
}

suspend fun fetchMultipleRouteOptions(start: GeoPoint, end: GeoPoint): List<RouteOption> = withContext(Dispatchers.IO) {
    val options = mutableListOf<RouteOption>()
    try {
        val urlStr = "https://router.project-osrm.org/route/v1/driving/${start.longitude},${start.latitude};${end.longitude},${end.latitude}?overview=full&geometries=geojson&steps=true&alternatives=true"
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "GofaOtoVietnamNavigation/3.1")
            connectTimeout = 10000
            readTimeout = 10000
        }
        if (conn.responseCode == 200) {
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val routes = json.optJSONArray("routes") ?: return@withContext emptyList()

            for (i in 0 until routes.length()) {
                val r = routes.getJSONObject(i)
                val distKm = (r.optDouble("distance", 0.0) / 100.0).roundToInt() / 10.0
                val durMin = (r.optDouble("duration", 0.0) / 60.0).roundToInt().coerceAtLeast(1)

                val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
                val polyPoints = mutableListOf<GeoPoint>()
                for (j in 0 until coords.length()) {
                    val c = coords.getJSONArray(j)
                    polyPoints.add(GeoPoint(c.getDouble(1), c.getDouble(0)))
                }

                val steps = mutableListOf<RouteStepItem>()
                val legs = r.optJSONArray("legs")
                if (legs != null && legs.length() > 0) {
                    val stArr = legs.getJSONObject(0).optJSONArray("steps")
                    if (stArr != null) {
                        for (k in 0 until stArr.length()) {
                            val st = stArr.getJSONObject(k)
                            val rName = st.optString("name", "")
                            val sDist = st.optDouble("distance", 0.0).roundToInt()
                            val man = st.getJSONObject("maneuver")
                            val loc = man.getJSONArray("location")
                            val stepGeo = GeoPoint(loc.getDouble(1), loc.getDouble(0))
                            val ins = translateManeuver(man.optString("type", ""), man.optString("modifier", ""), rName)
                            steps.add(RouteStepItem(ins, sDist, rName, stepGeo))
                        }
                    }
                }

                val title = when(i) {
                    0 -> "Lộ trình tối ưu chính"
                    1 -> "Lộ trình phụ thay thế 1"
                    else -> "Lộ trình thay thế $i"
                }
                options.add(RouteOption(title, polyPoints, steps, distKm, durMin))
            }
        }
    } catch (_: Exception) {}

    if (options.isEmpty()) {
        val fallbackPoints = listOf(start, GeoPoint((start.latitude + end.latitude)/2, (start.longitude + end.longitude)/2), end)
        val fallbackSteps = listOf(RouteStepItem("Bắt đầu di chuyển thẳng theo tuyến đường chính", 500, "Đường quốc lộ", start))
        options.add(RouteOption("Lộ trình an toàn", fallbackPoints, fallbackSteps, 12.5, 25))
    }
    return@withContext options
}

fun translateManeuver(type: String, modifier: String, roadName: String): String {
    val r = if (roadName.isNotBlank()) " vào $roadName" else ""
    val d = when (modifier) {
        "left" -> "Rẽ trái"
        "right" -> "Rẽ phải"
        "uturn" -> "Quay đầu xe"
        else -> "Đi thẳng"
    }
    return when(type) {
        "depart" -> "Xuất phát$r"
        "arrive" -> "Đến nơi"
        else -> "$d$r"
    }
}
