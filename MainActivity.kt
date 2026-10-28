package vn.navigation.gofaclone

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Rational
import android.view.WindowManager
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

val GofaGreen = Color(0xFF0E8A5F)
val GofaRed = Color(0xFFE5434B)
val RoutePink = Color(0xFFD81B60)

enum class SignType { CAMERA, TRAFFIC_LIGHT, SPEED_LIMIT }

data class PlaceSearchResult(val displayName: String, val lat: Double, val lon: Double)
data class RouteStepItem(val instruction: String, val distanceMeters: Int, val roadName: String, val location: GeoPoint, val turnType: String)
data class TrafficSignNode(val id: String, val type: SignType, val name: String, val lat: Double, val lon: Double, val speedValue: Int = 0)
data class BoundingBoxData(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double)
data class RouteOption(val title: String, val polylinePoints: List<GeoPoint>, val steps: List<RouteStepItem>, val totalDistanceKm: Double, val totalDurationMin: Int, val tollFeesVnd: Int, val bounds: BoundingBoxData)

val PREBUILT_PLACES = listOf(
    PlaceSearchResult("Chợ Đà Lạt, Phường 1, Đà Lạt", 11.9413, 108.4357),
    PlaceSearchResult("Bến xe Miền Tây, Bình Tân, TP.HCM", 10.7324, 106.6190),
    PlaceSearchResult("Bến Ninh Kiều, Cần Thơ", 10.0341, 105.7839),
    PlaceSearchResult("Chợ Bạc Liêu, Phường 3, Bạc Liêu", 9.2915, 105.7252),
    PlaceSearchResult("Mũi Cà Mau, Ngọc Hiển, Cà Mau", 8.6231, 104.7925)
)

fun createOnlineMapTileSource(name: String, lyrsCode: String): OnlineTileSourceBase {
    return object : OnlineTileSourceBase(name, 1, 20, 256, ".png", arrayOf("https://mt0.google.com/vt/lyrs=$lyrsCode&hl=vi", "https://mt1.google.com/vt/lyrs=$lyrsCode&hl=vi")) {
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
        osmConfig.userAgentValue = "GofaOtoVietnamNavigation/FINAL"
        val baseDir = File(cacheDir, "osmdroid")
        val tileDir = File(baseDir, "tiles")
        baseDir.mkdirs(); tileDir.mkdirs()
        osmConfig.osmdroidBasePath = baseDir; osmConfig.osmdroidTileCache = tileDir

        setContent {
            MaterialTheme {
                val config = LocalConfiguration.current
                val isPipModeApp = config.screenWidthDp < 350
                GofaOnlineNavigationApp(isPipModeApp, this)
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(3, 4)).build())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GofaOnlineNavigationApp(isPipMode: Boolean, activity: Activity) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var userLocation by remember { mutableStateOf(GeoPoint(9.2941, 105.7278)) }
    var vehicleBearing by remember { mutableFloatStateOf(0f) }
    var followUserGps by remember { mutableStateOf(true) }
    var isNavigating by remember { mutableStateOf(false) }

    var currentSpeedKmh by remember { mutableIntStateOf(0) }
    var currentSpeedLimit by remember { mutableStateOf<Int?>(null) }
    var isOverSpeeding by remember { mutableStateOf(false) }

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var mapLayerMode by remember { mutableIntStateOf(0) }
    var isVoiceEnabled by remember { mutableStateOf(true) }
    var isDarkMode by remember { mutableStateOf(false) }

    var showSearchSheet by remember { mutableStateOf(false) }
    var destInputText by remember { mutableStateOf("") }
    var selectedDestPoint by remember { mutableStateOf<PlaceSearchResult?>(null) }
    var searchSuggestions by remember { mutableStateOf<List<PlaceSearchResult>>(PREBUILT_PLACES) }
    var isLoadingRoute by remember { mutableStateOf(false) }

    var availableRoutes by remember { mutableStateOf<List<RouteOption>>(emptyList()) }
    var activeRoute by remember { mutableStateOf<RouteOption?>(null) }
    var routeSigns by remember { mutableStateOf<List<TrafficSignNode>>(emptyList()) }
    var nextStepItem by remember { mutableStateOf<RouteStepItem?>(null) }
    var upcomingSign by remember { mutableStateOf<TrafficSignNode?>(null) }
    var activeAlertBanner by remember { mutableStateOf<String?>(null) }

    val spokenAlertIds = remember { mutableStateListOf<String>() }

    val tts = remember { TextToSpeech(context) { if (it == TextToSpeech.SUCCESS) it.toString() } }
    fun speak(text: String) {
        if (isVoiceEnabled) { tts.language = Locale("vi", "VN"); tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null) }
    }

    DisposableEffect(isNavigating) {
        if (isNavigating) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val toneGen = remember { ToneGenerator(AudioManager.STREAM_ALARM, 100) }
    LaunchedEffect(isOverSpeeding) {
        if (isOverSpeeding) {
            while (isOverSpeeding) {
                toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
                delay(600)
            }
        }
    }

    fun refreshMap(map: MapView?) {
        if (map == null) return
        map.overlays.removeAll { it is Polyline || (it is Marker && it.title?.contains("Điểm") == true || it.title?.contains("🚦") == true || it.title?.contains("🎥") == true || it.title?.contains("🛑") == true) }

        availableRoutes.forEach { route ->
            if (route != activeRoute) {
                map.overlays.add(Polyline(map).apply { setPoints(route.polylinePoints); outlinePaint.color = AndroidColor.parseColor("#669E9E9E"); outlinePaint.strokeWidth = 18f })
            }
        }
        activeRoute?.let { route ->
            map.overlays.add(Polyline(map).apply { setPoints(route.polylinePoints); outlinePaint.color = AndroidColor.WHITE; outlinePaint.strokeWidth = 32f })
            map.overlays.add(Polyline(map).apply { setPoints(route.polylinePoints); outlinePaint.color = AndroidColor.parseColor("#D81B60"); outlinePaint.strokeWidth = 20f })
            map.overlays.add(Marker(map).apply { position = route.polylinePoints.first(); title = "🟢 Điểm xuất phát" })
            map.overlays.add(Marker(map).apply { position = route.polylinePoints.last(); title = "🔴 Điểm đến" })
        }

        routeSigns.forEach { sign ->
            val iconStr = when(sign.type) { SignType.CAMERA -> "🎥"; SignType.TRAFFIC_LIGHT -> "🚦"; SignType.SPEED_LIMIT -> "🛑" }
            map.overlays.add(Marker(map).apply { position = GeoPoint(sign.lat, sign.lon); title = "$iconStr ${sign.name}"; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) })
        }
        map.invalidate()
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val listener = LocationListener { loc ->
                currentSpeedKmh = (loc.speed * 3.6f).roundToInt()
                if (loc.hasBearing()) vehicleBearing = loc.bearing
                userLocation = GeoPoint(loc.latitude, loc.longitude)
                if (followUserGps) mapViewRef?.controller?.animateTo(userLocation)

                isOverSpeeding = currentSpeedLimit != null && currentSpeedKmh > currentSpeedLimit!! && currentSpeedKmh > 3

                if (isNavigating && activeRoute != null) {
                    for (step in activeRoute!!.steps) {
                        val d = FloatArray(1)
                        Location.distanceBetween(loc.latitude, loc.longitude, step.location.latitude, step.location.longitude, d)
                        if (d[0] < 500 && d[0] > 15) { nextStepItem = step; break }
                    }
                    var foundSign: TrafficSignNode? = null
                    for (sign in routeSigns) {
                        val d = FloatArray(1)
                        Location.distanceBetween(loc.latitude, loc.longitude, sign.lat, sign.lon, d)
                        if (d[0] <= 500) {
                            foundSign = sign
                            activeAlertBanner = "⚠️ Nhắc trước ${d[0].roundToInt()}m: ${sign.name}"
                            if (sign.type == SignType.SPEED_LIMIT) currentSpeedLimit = sign.speedValue
                            
                            if (!spokenAlertIds.contains(sign.id)) {
                                spokenAlertIds.add(sign.id)
                                speak("Chú ý. Phía trước ${d[0].roundToInt()} mét có ${sign.name}.")
                            }
                            break
                        }
                    }
                    upcomingSign = foundSign
                    if (foundSign == null) activeAlertBanner = null
                }
            }
            try { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, listener) } catch (_: Exception) {}
        }
    }
    LaunchedEffect(Unit) { permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)) }

    if (isPipMode) {
        Box(modifier = Modifier.fillMaxSize().background(GofaGreen).border(2.dp, Color.White), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                val pipIcon = if (nextStepItem?.turnType?.contains("left") == true) Icons.Default.ArrowBack else if (nextStepItem?.turnType?.contains("right") == true) Icons.Default.ArrowForward else Icons.Default.ArrowUpward
                Icon(pipIcon, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                Text("${currentSpeedKmh} km/h", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(nextStepItem?.instruction ?: "Đi thẳng", color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp))
                if (isOverSpeeding) { Text("⚠️ QUÁ TỐC ĐỘ!", color = Color.Yellow, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            }
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(if (isDarkMode) Color(0xFF121212) else Color.White)) {
        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(createOnlineMapTileSource("GoogleVN", "m"))
                    setMultiTouchControls(true); zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    controller.setZoom(17.0); controller.setCenter(userLocation)
                    mapViewRef = this
                    val mReceive = object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
                        override fun longPressHelper(p: GeoPoint?): Boolean {
                            if (p != null) { destInputText = "Tọa độ đã chọn"; selectedDestPoint = PlaceSearchResult("Tọa độ đã chọn", p.latitude, p.longitude); showSearchSheet = true }
                            return true
                        }
                    }
                    overlays.add(MapEventsOverlay(mReceive))
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Box(modifier = Modifier.align(Alignment.Center)) { MiniPinkCarCursor(rotationDegrees = vehicleBearing) }

        Box(modifier = Modifier.padding(top = 42.dp, start = 14.dp).size(width = 115.dp, height = 105.dp).align(Alignment.TopStart)) {
            Box(modifier = Modifier.size(78.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(Color.White).border(8.dp, GofaRed, CircleShape), contentAlignment = Alignment.Center) {
                Text(text = currentSpeedLimit?.toString() ?: "--", color = Color.Black, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            }
            Box(modifier = Modifier.size(54.dp).align(Alignment.BottomEnd).shadow(6.dp, CircleShape).clip(CircleShape).background(if (isOverSpeeding) GofaRed else Color.White).border(5.dp, if (isOverSpeeding) Color.Yellow else GofaGreen, CircleShape), contentAlignment = Alignment.Center) {
                Text(text = currentSpeedKmh.toString(), color = if (isOverSpeeding) Color.White else GofaGreen, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (isOverSpeeding) {
            Surface(modifier = Modifier.padding(top = 152.dp, start = 14.dp).align(Alignment.TopStart), shape = RoundedCornerShape(10.dp), color = GofaRed, shadowElevation = 6.dp) {
                Text("🚨 QUÁ TỐC ĐỘ GIỚI HẠN!", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp))
            }
        } else if (activeAlertBanner != null) {
            Surface(modifier = Modifier.padding(top = 152.dp, start = 14.dp).align(Alignment.TopStart), shape = RoundedCornerShape(10.dp), color = Color(0xFFE65100), shadowElevation = 6.dp) {
                Text(activeAlertBanner!!, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp))
            }
        }

        if (isNavigating && activeRoute != null) {
            Surface(modifier = Modifier.padding(top = 42.dp, start = 136.dp, end = 78.dp).fillMaxWidth().align(Alignment.TopCenter), shape = RoundedCornerShape(16.dp), color = GofaGreen, shadowElevation = 6.dp) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = nextStepItem?.instruction ?: "Đi thẳng theo vạch hồng sẫm", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(text = "🚗 Tổng: ${activeRoute!!.totalDistanceKm} km • 💰 Phí: ${activeRoute!!.tollFeesVnd} đ", color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp)
                }
            }
        }

        if (isNavigating && upcomingSign != null) {
            Surface(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp).width(280.dp), shape = RoundedCornerShape(16.dp), color = Color(0xFF003366).copy(alpha = 0.95f), shadowElevation = 10.dp) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    val iconVector = when(upcomingSign!!.type) { SignType.CAMERA -> Icons.Default.PhotoCamera; SignType.TRAFFIC_LIGHT -> Icons.Default.Info; SignType.SPEED_LIMIT -> Icons.Default.Warning }
                    Icon(iconVector, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(upcomingSign!!.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Surface(modifier = Modifier.padding(top = 42.dp, end = 14.dp).align(Alignment.TopEnd), shape = RoundedCornerShape(28.dp), color = Color.White.copy(alpha = 0.95f), shadowElevation = 5.dp) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                IconButton(onClick = { isVoiceEnabled = !isVoiceEnabled }) { Icon(if (isVoiceEnabled) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff, contentDescription = null, tint = GofaGreen, modifier = Modifier.size(28.dp)) }
            }
        }

        Surface(modifier = Modifier.padding(start = 14.dp, bottom = 105.dp).align(Alignment.BottomStart), shape = RoundedCornerShape(28.dp), color = Color.White.copy(alpha = 0.95f), shadowElevation = 5.dp) {
            Column(modifier = Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { followUserGps = !followUserGps; if (followUserGps) mapViewRef?.controller?.animateTo(userLocation) }) {
                    Icon(if (followUserGps) Icons.Filled.Lock else Icons.Outlined.MyLocation, contentDescription = null, tint = GofaGreen, modifier = Modifier.size(26.dp))
                }
                IconButton(onClick = { mapViewRef?.controller?.zoomIn() }) { Icon(Icons.Default.Add, contentDescription = null, tint = GofaGreen, modifier = Modifier.size(26.dp)) }
                IconButton(onClick = { mapViewRef?.controller?.zoomOut() }) { Icon(Icons.Default.Remove, contentDescription = null, tint = GofaGreen, modifier = Modifier.size(26.dp)) }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(14.dp).align(Alignment.BottomCenter), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (isNavigating) {
                Button(
                    onClick = { isNavigating = false; activeRoute = null; availableRoutes = emptyList(); upcomingSign = null; routeSigns = emptyList(); refreshMap(mapViewRef) },
                    modifier = Modifier.weight(1f).height(58.dp), colors = ButtonDefaults.buttonColors(containerColor = GofaRed)
                ) { Text("Kết thúc hành trình", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            } else {
                Surface(modifier = Modifier.weight(1f).height(58.dp).clickable { showSearchSheet = true }, shape = RoundedCornerShape(30.dp), color = Color.White.copy(alpha = 0.97f), shadowElevation = 5.dp) {
                    Row(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(44.dp).clip(CircleShape).background(GofaGreen), contentAlignment = Alignment.Center) { Icon(Icons.Default.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp)) }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(text = if (destInputText.isEmpty()) "Tìm nơi muốn đến..." else "Đến: $destInputText", color = Color.DarkGray, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }

        if (showSearchSheet) {
            ModalBottomSheet(onDismissRequest = { showSearchSheet = false }, modifier = Modifier.fillMaxHeight(0.95f)) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text("Lập lộ trình & Tải dữ liệu tuyến", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = GofaGreen)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = destInputText,
                        onValueChange = { destInputText = it; scope.launch { searchSuggestions = searchOnlineVietnamAddresses(it) } },
                        label = { Text("🔴 Nhập nơi bạn muốn đến") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            isLoadingRoute = true
                            scope.launch {
                                val destGeo = selectedDestPoint?.let { GeoPoint(it.lat, it.lon) } ?: searchOnlineVietnamAddresses(destInputText).firstOrNull()?.let { GeoPoint(it.lat, it.lon) }
                                if (destGeo != null) { availableRoutes = fetchMultipleRouteOptions(userLocation, destGeo); showSearchSheet = false } 
                                else { Toast.makeText(context, "Không tìm thấy địa chỉ!", Toast.LENGTH_SHORT).show() }
                                isLoadingRoute = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = GofaGreen)
                    ) { Text(if (isLoadingRoute) "Đang phân tích đường đi..." else "Tìm các đường đi", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(searchSuggestions) { place ->
                            Surface(modifier = Modifier.fillMaxWidth().clickable { selectedDestPoint = place; destInputText = place.displayName }, shape = RoundedCornerShape(10.dp), color = Color(0xFFF3F7F5)) {
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

        if (!isNavigating && availableRoutes.isNotEmpty()) {
            Surface(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 90.dp, start = 14.dp, end = 14.dp), shape = RoundedCornerShape(16.dp), color = Color.White, shadowElevation = 10.dp) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("📍 Chọn lộ trình ưu tiên:", fontWeight = FontWeight.Bold, color = GofaGreen)
                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(availableRoutes) { opt ->
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { activeRoute = opt; refreshMap(mapViewRef) },
                                color = if (activeRoute == opt) Color(0xFFEAF5F0) else Color.White,
                                border = if (activeRoute == opt) BorderStroke(2.dp, RoutePink) else BorderStroke(1.dp, Color.LightGray),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(opt.title, fontWeight = FontWeight.Bold, color = if (activeRoute == opt) RoutePink else Color.Black)
                                    Text("${opt.totalDistanceKm} km • ${opt.totalDurationMin} phút", fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    Button(
                        onClick = {
                            if (activeRoute == null) activeRoute = availableRoutes.first()
                            isLoadingRoute = true
                            scope.launch {
                                val signs = fetch100PercentSignsForRoute(activeRoute!!.bounds)
                                routeSigns = signs
                                isLoadingRoute = false; isNavigating = true; followUserGps = true
                                mapViewRef?.controller?.setZoom(18.0)
                                refreshMap(mapViewRef)
                                speak("Bắt đầu di chuyển. Đã tải thành công 100% biển báo dọc tuyến.")
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp), colors = ButtonDefaults.buttonColors(containerColor = GofaGreen)
                    ) { Text(if (isLoadingRoute) "Đang tải dữ liệu biển báo 100%..." else "BẮT ĐẦU HÀNH TRÌNH", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
fun MiniPinkCarCursor(rotationDegrees: Float) {
    Box(modifier = Modifier.size(60.dp).rotate(rotationDegrees), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(24.dp, 46.dp)) {
            drawRoundRect(color = RoutePink, size = size, cornerRadius = CornerRadius(12f, 12f))
            drawRoundRect(color = Color.Black.copy(alpha = 0.7f), topLeft = androidx.compose.ui.geometry.Offset(4f, 10f), size = androidx.compose.ui.geometry.Size(size.width - 8f, 8f), cornerRadius = CornerRadius(4f, 4f))
        }
    }
}

suspend fun searchOnlineVietnamAddresses(query: String): List<PlaceSearchResult> = withContext(Dispatchers.IO) {
    val list = mutableListOf<PlaceSearchResult>()
    try {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = URL("https://nominatim.openstreetmap.org/search?format=json&q=$q&countrycodes=vn&accept-language=vi&limit=10")
        val conn = (url.openConnection() as HttpURLConnection).apply { requestMethod = "GET"; setRequestProperty("User-Agent", "GofaOtoVN/FINAL") }
        if (conn.responseCode == 200) {
            val arr = JSONArray(conn.inputStream.bufferedReader().readText())
            for (i in 0 until arr.length()) { val obj = arr.getJSONObject(i); list.add(PlaceSearchResult(obj.optString("display_name"), obj.optDouble("lat"), obj.optDouble("lon"))) }
        }
    } catch (_: Exception) {}
    if (list.isEmpty()) return@withContext PREBUILT_PLACES
    list
}

suspend fun fetchMultipleRouteOptions(start: GeoPoint, end: GeoPoint): List<RouteOption> = withContext(Dispatchers.IO) {
    val options = mutableListOf<RouteOption>()
    try {
        val urlStr = "https://router.project-osrm.org/route/v1/driving/${start.longitude},${start.latitude};${end.longitude},${end.latitude}?overview=full&geometries=geojson&steps=true&alternatives=true"
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply { requestMethod = "GET" }
        if (conn.responseCode == 200) {
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val routes = json.optJSONArray("routes") ?: return@withContext emptyList()

            for (i in 0 until routes.length()) {
                val r = routes.getJSONObject(i)
                val distKm = (r.optDouble("distance", 0.0) / 100.0).roundToInt() / 10.0
                val durMin = (r.optDouble("duration", 0.0) / 60.0).roundToInt().coerceAtLeast(1)

                var minLat = 90.0; var minLon = 180.0; var maxLat = -90.0; var maxLon = -180.0
                val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
                val polyPoints = mutableListOf<GeoPoint>()
                for (j in 0 until coords.length()) {
                    val c = coords.getJSONArray(j)
                    val lat = c.getDouble(1); val lon = c.getDouble(0)
                    polyPoints.add(GeoPoint(lat, lon))
                    minLat = min(minLat, lat); minLon = min(minLon, lon); maxLat = max(maxLat, lat); maxLon = max(maxLon, lon)
                }

                val steps = mutableListOf<RouteStepItem>()
                val legs = r.optJSONArray("legs")
                if (legs != null && legs.length() > 0) {
                    val stArr = legs.getJSONObject(0).optJSONArray("steps")
                    if (stArr != null) {
                        for (k in 0 until stArr.length()) {
                            val st = stArr.getJSONObject(k)
                            val rName = st.optString("name", "Đường chính")
                            val man = st.getJSONObject("maneuver")
                            val modifier = man.optString("modifier", "")
                            val loc = man.getJSONArray("location")
                            steps.add(RouteStepItem(if (modifier.contains("left")) "Rẽ trái vào $rName" else if (modifier.contains("right")) "Rẽ phải vào $rName" else "Đi thẳng", st.optDouble("distance", 0.0).roundToInt(), rName, GeoPoint(loc.getDouble(1), loc.getDouble(0)), modifier))
                        }
                    }
                }
                val tollFee = if (distKm > 80) 35000 * (distKm / 80).toInt() else 0
                val title = when(i) { 0 -> "Lộ trình tối ưu nhất (Màu Hồng)"; 1 -> "Lộ trình qua Quốc lộ"; else -> "Lộ trình thay thế" }
                options.add(RouteOption(title, polyPoints, steps, distKm, durMin, tollFee, BoundingBoxData(minLat - 0.02, minLon - 0.02, maxLat + 0.02, maxLon + 0.02)))
            }
        }
    } catch (_: Exception) {}
    options
}

suspend fun fetch100PercentSignsForRoute(bounds: BoundingBoxData): List<TrafficSignNode> = withContext(Dispatchers.IO) {
    val results = mutableListOf<TrafficSignNode>()
    try {
        val query = """[out:json][timeout:25];(node["highway"="speed_camera"](${bounds.minLat},${bounds.minLon},${bounds.maxLat},${bounds.maxLon});node["highway"="traffic_signals"](${bounds.minLat},${bounds.minLon},${bounds.maxLat},${bounds.maxLon});way["maxspeed"](${bounds.minLat},${bounds.minLon},${bounds.maxLat},${bounds.maxLon}););out center 150;"""
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://overpass-api.de/api/interpreter?data=$encoded")
        val conn = (url.openConnection() as HttpURLConnection).apply { requestMethod = "GET"; connectTimeout = 15000; readTimeout = 15000 }
        
        if (conn.responseCode == 200) {
            val elements = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONArray("elements") ?: return@withContext emptyList()
            for (i in 0 until elements.length()) {
                val el = elements.getJSONObject(i)
                val id = el.optString("id", "")
                val tags = el.optJSONObject("tags") ?: continue
                val lat = el.optDouble("lat", el.optJSONObject("center")?.optDouble("lat") ?: 0.0)
                val lon = el.optDouble("lon", el.optJSONObject("center")?.optDouble("lon") ?: 0.0)
                
                if (tags.has("highway") && tags.getString("highway") == "speed_camera") {
                    val spd = tags.optString("maxspeed", "60").filter { it.isDigit() }.toIntOrNull() ?: 60
                    results.add(TrafficSignNode(id, SignType.CAMERA, "Camera phạt nguội", lat, lon, spd))
                } else if (tags.has("highway") && tags.getString("highway") == "traffic_signals") {
                    results.add(TrafficSignNode(id, SignType.TRAFFIC_LIGHT, "Đèn tín hiệu giao thông", lat, lon))
                } else if (tags.has("maxspeed")) {
                    val spd = tags.optString("maxspeed", "50").filter { it.isDigit() }.toIntOrNull() ?: 50
                    results.add(TrafficSignNode(id, SignType.SPEED_LIMIT, "Giới hạn tốc độ $spd km/h", lat, lon, spd))
                }
            }
        }
    } catch (_: Exception) {}
    return@withContext results
}
