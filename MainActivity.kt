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
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
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

data class RouteResult(
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

// Nguồn bản đồ trực tuyến Tiếng Việt tốc độ cao (Tiêu chuẩn, Giao thông, Vệ tinh)
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

        // Khởi tạo bộ nhớ đệm bản đồ Online để hiển thị mượt và không bị màn hình xám
        val osmConfig = Configuration.getInstance()
        osmConfig.userAgentValue = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36"
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

    // Tọa độ mặc định ban đầu tại trung tâm Bạc Liêu
    var userLocation by remember { mutableStateOf(GeoPoint(9.2941, 105.7278)) }
    var hasGpsFix by remember { mutableStateOf(false) }
    var vehicleBearing by remember { mutableFloatStateOf(0f) }
    var followUserGps by remember { mutableStateOf(true) }

    // Trạng thái tốc độ & biển báo
    var currentSpeedKmh by remember { mutableIntStateOf(0) }
    var currentSpeedLimit by remember { mutableStateOf<Int?>(50) }
    var currentRoadName by remember { mutableStateOf("Đang dò tên đường...") }
    var activeAlertBanner by remember { mutableStateOf<String?>(null) }

    // Trạng thái bản đồ & nút chức năng
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var mapLayerMode by remember { mutableIntStateOf(0) } // 0: Tiêu chuẩn, 1: Giao thông, 2: Vệ tinh
    var isVoiceEnabled by remember { mutableStateOf(true) }
    var showCamerasOnMap by remember { mutableStateOf(true) }

    // Trạng thái Tìm kiếm Điểm đầu - Điểm cuối & Dẫn đường
    var showSearchSheet by remember { mutableStateOf(false) }
    var showStepsSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }

    var startInputText by remember { mutableStateOf("Vị trí GPS hiện tại của tôi") }
    var destInputText by remember { mutableStateOf("") }
    var selectedStartPoint by remember { mutableStateOf<PlaceSearchResult?>(null) } // null = dùng GPS hiện tại
    var selectedDestPoint by remember { mutableStateOf<PlaceSearchResult?>(null) }

    var searchSuggestions by remember { mutableStateOf<List<PlaceSearchResult>>(emptyList()) }
    var isSearchingStart by remember { mutableStateOf(false) }
    var isLoadingSearch by remember { mutableStateOf(false) }
    var isLoadingRoute by remember { mutableStateOf(false) }

    // Lộ trình hiện tại
    var activeRoute by remember { mutableStateOf<RouteResult?>(null) }
    var nextStepInstruction by remember { mutableStateOf<String?>(null) }

    // Tùy chọn né đường cho Ô tô con
    var avoidTolls by remember { mutableStateOf(false) }
    var avoidHighways by remember { mutableStateOf(false) }
    var avoidFerries by remember { mutableStateOf(false) }

    // Danh sách camera phạt nguội & điểm cảnh báo tốc độ
    val cameraNodes = remember {
        mutableStateListOf(
            SpeedCameraNode("BL_QL1", "Camera phạt nguội QL1A - Võ Văn Kiệt (Bạc Liêu)", 9.2985, 105.7240, 50, RoadType.URBAN),
            SpeedCameraNode("BL_TP", "Khu đô thị Trần Phú - Phường Bạc Liêu", 9.2915, 105.7252, 50, RoadType.URBAN),
            SpeedCameraNode("CM_QL1", "Camera phạt nguội cửa ngõ TP. Cà Mau", 9.1768, 105.1524, 50, RoadType.URBAN),
            SpeedCameraNode("ST_QL1", "Trạm bắn tốc độ QL1A Sóc Trăng", 9.6025, 105.9739, 60, RoadType.NATIONAL_HIGHWAY),
            SpeedCameraNode("CT_IC3", "Camera phạt nguội nút giao IC3 Cần Thơ", 10.0152, 105.7881, 60, RoadType.NATIONAL_HIGHWAY),
            SpeedCameraNode("MT_CT", "Cao tốc Mỹ Thuận - Cần Thơ", 10.2541, 105.8950, 90, RoadType.EXPRESSWAY),
            SpeedCameraNode("TL_MT", "Cao tốc Trung Lương - Mỹ Thuận", 10.4350, 106.3420, 90, RoadType.EXPRESSWAY),
            SpeedCameraNode("HCM_VVK", "Camera tốc độ Đại lộ Võ Văn Kiệt TP.HCM", 10.7503, 106.6634, 60, RoadType.URBAN),
            SpeedCameraNode("LTDG", "Cao tốc TP.HCM - Long Thành - Dầu Giây", 10.8210, 106.9530, 120, RoadType.EXPRESSWAY),
            SpeedCameraNode("QL20_BL", "Camera tốc độ QL20 - Đèo Bảo Lộc", 11.4580, 107.7250, 50, RoadType.NATIONAL_HIGHWAY),
            SpeedCameraNode("LK_DL", "Cao tốc Liên Khương - Prenn Đà Lạt", 11.7520, 108.3810, 90, RoadType.EXPRESSWAY)
        )
    }
    val spokenAlertIds = remember { mutableStateListOf<String>() }

    // Khởi tạo giọng nói tiếng Việt
    val tts = remember {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale("vi", "VN")
            }
        }
        engine
    }

    fun speakVietnamese(text: String) {
        if (isVoiceEnabled) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    // Hàm vẽ lại lớp đường đi và ghim Camera trên bản đồ
    fun refreshMapOverlays(map: MapView?) {
        if (map == null) return
        map.overlays.clear()

        // 1. Vẽ đường dẫn (Polyline) nếu đang dẫn đường
        activeRoute?.let { route ->
            val routeLine = Polyline(map).apply {
                setPoints(route.polylinePoints)
                outlinePaint.color = AndroidColor.parseColor("#0E8A5F")
                outlinePaint.strokeWidth = 20f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                outlinePaint.isAntiAlias = true
            }
            map.overlays.add(routeLine)

            // Ghim điểm xuất phát & điểm đến
            if (route.polylinePoints.isNotEmpty()) {
                val startMarker = Marker(map).apply {
                    position = route.polylinePoints.first()
                    title = "Điểm xuất phát"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                val endMarker = Marker(map).apply {
                    position = route.polylinePoints.last()
                    title = destInputText.ifEmpty { "Điểm đến" }
                    snippet = "Tổng: ${route.totalDistanceKm} km (${route.totalDurationMin} phút)"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                map.overlays.add(startMarker)
                map.overlays.add(endMarker)
            }
        }

        // 2. Vẽ các điểm Camera phạt nguội & Biển báo tốc độ
        if (showCamerasOnMap) {
            for (cam in cameraNodes) {
                val camMarker = Marker(map).apply {
                    position = GeoPoint(cam.lat, cam.lon)
                    title = "🎥 ${cam.name}"
                    snippet = "Giới hạn: ${cam.maxSpeed} km/h | Báo trước: ${cam.roadType.alertDistanceMeters}m"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                map.overlays.add(camMarker)
            }
        }

        map.invalidate()
    }

    // Lắng nghe GPS thực tế và kiểm tra cảnh báo 300m / 400m / 500m
    var lastRoadQueryTime by remember { mutableLongStateOf(0L) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            startLiveGpsListener(context) { location ->
                hasGpsFix = true
                val speed = (location.speed * 3.6f).roundToInt()
                currentSpeedKmh = speed
                if (location.hasBearing()) {
                    vehicleBearing = location.bearing
                }
                val newPoint = GeoPoint(location.latitude, location.longitude)
                userLocation = newPoint

                if (followUserGps) {
                    mapViewRef?.controller?.animateTo(newPoint)
                }

                // Tự động tra cứu giới hạn tốc độ & tên đường thực tế từ Online mỗi 12 giây
                val now = System.currentTimeMillis()
                if (now - lastRoadQueryTime > 12000L) {
                    lastRoadQueryTime = now
                    scope.launch {
                        val roadInfo = fetchOnlineRoadSpeedLimit(location.latitude, location.longitude)
                        if (roadInfo.first != null) {
                            currentSpeedLimit = roadInfo.first
                        }
                        if (roadInfo.second.isNotEmpty()) {
                            currentRoadName = roadInfo.second
                        }
                    }
                }

                // Kiểm tra khoảng cách đến Camera phạt nguội (300m Đô thị, 400m Quốc lộ, 500m Cao tốc)
                var foundNearbyCam = false
                for (cam in cameraNodes) {
                    val distArr = FloatArray(1)
                    Location.distanceBetween(location.latitude, location.longitude, cam.lat, cam.lon, distArr)
                    val distance = distArr[0].roundToInt()

                    if (distance <= cam.roadType.alertDistanceMeters) {
                        foundNearbyCam = true
                        currentSpeedLimit = cam.maxSpeed
                        activeAlertBanner = "⚠️ Cách ${distance}m (${cam.roadType.label}): ${cam.name} [${cam.maxSpeed} km/h]"

                        if (!spokenAlertIds.contains(cam.id)) {
                            spokenAlertIds.add(cam.id)
                            speakVietnamese(
                                "Chú ý! Phía trước $distance mét có ${cam.name}, giới hạn tốc độ ${cam.maxSpeed} ki lô mét trên giờ."
                            )
                        }
                        break
                    }
                }
                if (!foundNearbyCam) {
                    activeAlertBanner = null
                }

                // Cảnh báo nếu chạy vượt tốc độ giới hạn
                if (currentSpeedLimit != null && speed > currentSpeedLimit!! && speed > 20) {
                    activeAlertBanner = "🚨 BẠN ĐANG CHẠY QUÁ TỐC ĐỘ GIỚI HẠN ($speed / $currentSpeedLimit km/h)!"
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
        // Tải thêm các camera phạt nguội thực tế từ máy chủ Online khi mở app
        scope.launch {
            val onlineCams = fetchOnlineCamerasAround(userLocation.latitude, userLocation.longitude)
            for (c in onlineCams) {
                if (cameraNodes.none { it.id == c.id }) {
                    cameraNodes.add(c)
                }
            }
            refreshMapOverlays(mapViewRef)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. LỚP BẢN ĐỒ ONLINE TRỰC TUYẾN TOÀN MÀN HÌNH
        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(createOnlineMapTileSource("GoogleStandardVN", "m"))
                    setMultiTouchControls(true)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    controller.setZoom(16.0)
                    controller.setCenter(userLocation)
                    mapViewRef = this
                    refreshMapOverlays(this)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Biểu tượng mũi tên xanh lá ở tâm (Xoay theo hướng xe di chuyển)
        Box(modifier = Modifier.align(Alignment.Center)) {
            NavigationCursor(rotationDegrees = vehicleBearing)
        }

        // 2. CỤM BIỂN BÁO TỐC ĐỘ GÓC TRÊN BÊN TRÁI (Giống hệt giao diện Gofa)
        val isOverSpeed = currentSpeedLimit != null && currentSpeedKmh > currentSpeedLimit!!
        Box(
            modifier = Modifier
                .padding(top = 42.dp, start = 14.dp)
                .size(width = 115.dp, height = 105.dp)
                .align(Alignment.TopStart)
        ) {
            // Vòng tròn đỏ biển báo giới hạn tốc độ
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(8.dp, GofaRed, CircleShape)
                    .clickable {
                        // Chạm vào biển báo để đọc trạng thái tốc độ hiện tại
                        val limitStr = currentSpeedLimit?.toString() ?: "chưa xác định"
                        speakVietnamese("Tốc độ giới hạn đoạn đường này là $limitStr ki lô mét trên giờ. Tốc độ hiện tại của bạn là $currentSpeedKmh ki lô mét trên giờ.")
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = currentSpeedLimit?.toString() ?: "--",
                    color = Color.Black,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            // Vòng tròn hiển thị tốc độ GPS thực tế
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .align(Alignment.BottomEnd)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(if (isOverSpeed) GofaRed else Color.White)
                    .border(5.dp, if (isOverSpeed) Color.Yellow else GofaGreen, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = currentSpeedKmh.toString(),
                    color = if (isOverSpeed) Color.White else GofaGreen,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // THANH CHỈ ĐƯỜNG NỔI Ở TRÊN (Hiển thị khi đang có lộ trình dẫn đường)
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
                    Text(
                        text = nextStepInstruction ?: "Đi theo đường dẫn màu xanh",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "🚗 ${route.totalDistanceKm} km  •  ⏱ ${route.totalDurationMin} phút (Chạm xem chi tiết)",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 12.sp
                    )
                }
            }
        }

        // BĂNG RÔN CẢNH BÁO PHẠT NGUỘI / QUÁ TỐC ĐỘ
        activeAlertBanner?.let { alertMsg ->
            Surface(
                modifier = Modifier
                    .padding(top = 152.dp, start = 14.dp, end = 78.dp)
                    .align(Alignment.TopStart),
                shape = RoundedCornerShape(12.dp),
                color = GofaRed,
                shadowElevation = 6.dp
            ) {
                Text(
                    text = alertMsg,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        // 3. THANH CÔNG CỤ 4 NÚT BÊN PHẢI (Hoạt động đầy đủ 100%)
        Surface(
            modifier = Modifier
                .padding(top = 42.dp, end = 14.dp)
                .align(Alignment.TopEnd),
            shape = RoundedCornerShape(28.dp),
            color = Color.White.copy(alpha = 0.95f),
            shadowElevation = 5.dp
        ) {
            Column(
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Nút 1: Đổi lớp bản đồ Online (Tiêu chuẩn -> Giao thông -> Vệ tinh)
                IconButton(onClick = {
                    mapLayerMode = (mapLayerMode + 1) % 3
                    val (sourceName, lyrs, label) = when (mapLayerMode) {
                        1 -> Triple("GoogleTrafficVN", "m,traffic", "Bản đồ Giao thông trực tuyến (Báo kẹt xe)")
                        2 -> Triple("GoogleSatelliteVN", "y", "Bản đồ Vệ tinh kết hợp tên đường")
                        else -> Triple("GoogleStandardVN", "m", "Bản đồ Tiêu chuẩn Gofa")
                    }
                    mapViewRef?.setTileSource(createOnlineMapTileSource(sourceName, lyrs))
                    mapViewRef?.invalidate()
                    Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Outlined.Layers, contentDescription = "Đổi bản đồ", tint = GofaGreen, modifier = Modifier.size(28.dp))
                }

                // Nút 2: Bật / Tắt giọng nói cảnh báo tiếng Việt
                IconButton(onClick = {
                    isVoiceEnabled = !isVoiceEnabled
                    if (isVoiceEnabled) {
                        speakVietnamese("Đã bật giọng nói dẫn đường và cảnh báo tốc độ.")
                        Toast.makeText(context, "🔊 Đã bật giọng nói", Toast.LENGTH_SHORT).show()
                    } else {
                        tts?.stop()
                        Toast.makeText(context, "🔇 Đã tắt giọng nói", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(
                        if (isVoiceEnabled) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                        contentDescription = "Âm thanh",
                        tint = if (isVoiceEnabled) GofaGreen else Color.Gray,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Nút 3: Mở bảng Chi tiết các bước chỉ đường (Turn-by-Turn)
                IconButton(onClick = {
                    showStepsSheet = true
                }) {
                    Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "Chi tiết lộ trình", tint = GofaGreen, modifier = Modifier.size(26.dp))
                }

                // Nút 4: Bật / Tắt hiển thị Camera phạt nguội & Quét camera mới quanh xe
                IconButton(onClick = {
                    showCamerasOnMap = !showCamerasOnMap
                    refreshMapOverlays(mapViewRef)
                    val msg = if (showCamerasOnMap) {
                        "🎥 Đã hiện ${cameraNodes.size} điểm Camera phạt nguội trên bản đồ"
                    } else {
                        "Đã ẩn biểu tượng Camera trên bản đồ"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    if (showCamerasOnMap) {
                        scope.launch {
                            val center = mapViewRef?.mapCenter
                            if (center != null) {
                                val moreCams = fetchOnlineCamerasAround(center.latitude, center.longitude)
                                for (c in moreCams) {
                                    if (cameraNodes.none { it.id == c.id }) cameraNodes.add(c)
                                }
                                refreshMapOverlays(mapViewRef)
                            }
                        }
                    }
                }) {
                    Icon(
                        Icons.Outlined.PhotoCamera,
                        contentDescription = "Camera phạt nguội",
                        tint = if (showCamerasOnMap) GofaGreen else Color.Gray,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        // 4. CỤM NÚT ĐỊNH VỊ & PHÓNG TO / THU NHỎ GÓC DƯỚI BÊN TRÁI
        Surface(
            modifier = Modifier
                .padding(start = 14.dp, bottom = 105.dp)
                .align(Alignment.BottomStart),
            shape = RoundedCornerShape(28.dp),
            color = Color.White.copy(alpha = 0.95f),
            shadowElevation = 5.dp
        ) {
            Column(
                modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Nút đưa tâm bản đồ về đúng vị trí GPS hiện tại
                IconButton(onClick = {
                    followUserGps = true
                    mapViewRef?.controller?.animateTo(userLocation)
                    mapViewRef?.controller?.setZoom(17.0)
                    val gpsNote = if (hasGpsFix) {
                        "📍 Đã khóa tâm theo vị trí GPS của bạn ($currentRoadName)"
                    } else {
                        "📍 Đang lấy tín hiệu GPS... Hãy bật Vị trí (Location) trên điện thoại"
                    }
                    Toast.makeText(context, gpsNote, Toast.LENGTH_SHORT).show()
                }) {
                    Icon(
                        Icons.Outlined.MyLocation,
                        contentDescription = "Định vị",
                        tint = if (followUserGps) GofaGreen else Color.Gray,
                        modifier = Modifier.size(26.dp)
                    )
                }
                IconButton(onClick = {
                    followUserGps = false
                    mapViewRef?.controller?.zoomIn()
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Phóng to", tint = GofaGreen, modifier = Modifier.size(26.dp))
                }
                IconButton(onClick = {
                    followUserGps = false
                    mapViewRef?.controller?.zoomOut()
                }) {
                    Icon(Icons.Default.Remove, contentDescription = "Thu nhỏ", tint = GofaGreen, modifier = Modifier.size(26.dp))
                }
            }
        }

        // 5. THANH TÌM KIẾM ĐỊA ĐIỂM & NÚT CÀI ĐẶT DƯỚI CÙNG
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 22.dp)
                .align(Alignment.BottomCenter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(58.dp)
                    .clickable { showSearchSheet = true },
                shape = RoundedCornerShape(30.dp),
                color = Color.White.copy(alpha = 0.97f),
                shadowElevation = 5.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(GofaGreen),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Tìm kiếm", tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (destInputText.isEmpty()) "Tìm kiếm địa điểm (Điểm đi - Điểm đến)" else "Đến: $destInputText",
                            color = Color.DarkGray,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (activeRoute != null) {
                            Text(
                                text = "Đang dẫn đường • ${activeRoute!!.totalDistanceKm} km (${activeRoute!!.totalDurationMin} phút)",
                                color = GofaGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .size(58.dp)
                    .clickable { showSettingsSheet = true },
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.97f),
                shadowElevation = 5.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Cài đặt", tint = GofaGreen, modifier = Modifier.size(30.dp))
                }
            }
        }

        // =========================================================================
        // BẢNG TÌM KIẾM CHI TIẾT CÓ ĐIỂM ĐẦU & ĐIỂM CUỐI (ONLINE GEOCODING + ROUTING)
        // =========================================================================
        if (showSearchSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSearchSheet = false },
                modifier = Modifier.fillMaxHeight(0.92f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Lập lộ trình dẫn đường Ô tô",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = GofaGreen
                        )
                        // Nút đảo chiều Điểm đầu <-> Điểm cuối
                        TextButton(onClick = {
                            val tempText = startInputText
                            val tempPoint = selectedStartPoint
                            startInputText = destInputText.ifEmpty { "Vị trí GPS hiện tại của tôi" }
                            selectedStartPoint = selectedDestPoint
                            destInputText = if (tempText == "Vị trí GPS hiện tại của tôi") "" else tempText
                            selectedDestPoint = tempPoint
                        }) {
                            Icon(Icons.Default.SwapVert, contentDescription = "Đảo chiều", tint = GofaGreen)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Đảo chiều", color = GofaGreen, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Ô NHẬP ĐIỂM ĐẦU (ĐIỂM XUẤT PHÁT)
                    OutlinedTextField(
                        value = startInputText,
                        onValueChange = {
                            startInputText = it
                            isSearchingStart = true
                        },
                        label = { Text("🟢 Điểm đầu (Điểm xuất phát)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            Row {
                                // Nút lấy nhanh GPS hiện tại làm điểm đầu
                                IconButton(onClick = {
                                    selectedStartPoint = null
                                    startInputText = "Vị trí GPS hiện tại của tôi"
                                    Toast.makeText(context, "Đã chọn Điểm đầu là vị trí GPS hiện tại", Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(Icons.Default.MyLocation, contentDescription = "Dùng GPS", tint = GofaGreen)
                                }
                                // Nút tìm kiếm địa chỉ Điểm đầu
                                IconButton(onClick = {
                                    if (startInputText.isNotBlank() && startInputText != "Vị trí GPS hiện tại của tôi") {
                                        isSearchingStart = true
                                        isLoadingSearch = true
                                        scope.launch {
                                            searchSuggestions = searchPlacesOnlineVietnam(startInputText)
                                            isLoadingSearch = false
                                        }
                                    }
                                }) {
                                    Icon(Icons.Default.Search, contentDescription = "Tìm điểm đầu", tint = GofaGreen)
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Ô NHẬP ĐIỂM CUỐI (ĐIỂM ĐẾN)
                    OutlinedTextField(
                        value = destInputText,
                        onValueChange = {
                            destInputText = it
                            isSearchingStart = false
                        },
                        label = { Text("🔴 Điểm cuối (Nhập địa chỉ, đường, phường, tỉnh...)") },
                        placeholder = { Text("VD: Phường Bạc Liêu, Chợ Đà Lạt, Bệnh viện...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            Row {
                                if (destInputText.isNotEmpty()) {
                                    IconButton(onClick = {
                                        destInputText = ""
                                        selectedDestPoint = null
                                    }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Xoá")
                                    }
                                }
                                IconButton(onClick = {
                                    if (destInputText.isNotBlank()) {
                                        isSearchingStart = false
                                        isLoadingSearch = true
                                        scope.launch {
                                            searchSuggestions = searchPlacesOnlineVietnam(destInputText)
                                            isLoadingSearch = false
                                        }
                                    }
                                }) {
                                    Icon(Icons.Default.Search, contentDescription = "Tìm điểm đến", tint = GofaGreen)
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Hàng nút chọn nhanh tùy chọn né đường
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = avoidTolls,
                            onClick = { avoidTolls = !avoidTolls },
                            label = { Text("Né trạm BOT", fontSize = 12.sp) }
                        )
                        FilterChip(
                            selected = avoidHighways,
                            onClick = { avoidHighways = !avoidHighways },
                            label = { Text("Né cao tốc", fontSize = 12.sp) }
                        )
                        FilterChip(
                            selected = avoidFerries,
                            onClick = { avoidFerries = !avoidFerries },
                            label = { Text("Né phà", fontSize = 12.sp) }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // NÚT TÍNH TOÁN LỘ TRÌNH & BẮT ĐẦU DẪN ĐƯỜNG ONLINE
                    Button(
                        onClick = {
                            if (destInputText.isBlank()) {
                                Toast.makeText(context, "Vui lòng nhập Điểm cuối (Điểm đến)!", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isLoadingRoute = true
                            scope.launch {
                                // 1. Xác định tọa độ Điểm đầu
                                val startGeo: GeoPoint = if (selectedStartPoint != null) {
                                    GeoPoint(selectedStartPoint!!.lat, selectedStartPoint!!.lon)
                                } else if (startInputText != "Vị trí GPS hiện tại của tôi" && startInputText.isNotBlank()) {
                                    val foundStart = searchPlacesOnlineVietnam(startInputText).firstOrNull()
                                    if (foundStart != null) GeoPoint(foundStart.lat, foundStart.lon) else userLocation
                                } else {
                                    userLocation
                                }

                                // 2. Xác định tọa độ Điểm cuối
                                val destPlace: PlaceSearchResult? = selectedDestPoint
                                    ?: searchPlacesOnlineVietnam(destInputText).firstOrNull()

                                if (destPlace == null) {
                                    isLoadingRoute = false
                                    Toast.makeText(context, "Không tìm thấy địa điểm '$destInputText'. Hãy thử gõ rõ hơn!", Toast.LENGTH_LONG).show()
                                    return@launch
                                }
                                selectedDestPoint = destPlace
                                destInputText = destPlace.displayName.substringBefore(",")

                                val endGeo = GeoPoint(destPlace.lat, destPlace.lon)

                                // 3. Gọi máy chủ định tuyến Online (OSRM) để lấy đường đi thực tế
                                val route = fetchOnlineDrivingRoute(
                                    start = startGeo,
                                    end = endGeo,
                                    avoidHighways = avoidHighways,
                                    avoidTolls = avoidTolls,
                                    avoidFerries = avoidFerries
                                )
                                isLoadingRoute = false

                                if (route != null) {
                                    activeRoute = route
                                    nextStepInstruction = route.steps.firstOrNull()?.instruction
                                        ?: "Đi theo lộ trình đến ${destInputText}"
                                    refreshMapOverlays(mapViewRef)
                                    followUserGps = false
                                    mapViewRef?.controller?.animateTo(startGeo)
                                    mapViewRef?.controller?.setZoom(14.5)
                                    showSearchSheet = false

                                    speakVietnamese(
                                        "Bắt đầu dẫn đường đến $destInputText. Tổng quãng đường ${route.totalDistanceKm} ki lô mét, thời gian dự kiến ${route.totalDurationMin} phút. ${nextStepInstruction ?: ""}"
                                    )
                                } else {
                                    Toast.makeText(context, "Không thể tính lộ trình. Vui lòng kiểm tra kết nối mạng!", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GofaGreen),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (isLoadingRoute) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Đang tính lộ trình tối ưu...", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.Navigation, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Tìm đường & Bắt đầu dẫn đường", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isLoadingSearch) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = GofaGreen)
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    Text(
                        text = if (searchSuggestions.isNotEmpty()) {
                            "📍 Kết quả tìm kiếm (Chạm vào địa điểm để chọn):"
                        } else {
                            "💡 Mẹo: Nhập tên địa điểm rồi bấm biểu tượng kính lúp 🔍 ở góc phải ô nhập để xem danh sách địa chỉ chi tiết."
                        },
                        fontSize = 13.sp,
                        color = Color.Gray,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Danh sách kết quả địa điểm chi tiết trả về từ máy chủ Online
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(searchSuggestions) { place ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isSearchingStart) {
                                            selectedStartPoint = place
                                            startInputText = place.displayName
                                            Toast.makeText(context, "Đã chọn Điểm đầu!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            selectedDestPoint = place
                                            destInputText = place.displayName
                                            Toast.makeText(context, "Đã chọn Điểm cuối! Hãy bấm 'Tìm đường & Bắt đầu dẫn đường'", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFFF3F7F5)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Place, contentDescription = null, tint = GofaGreen)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = place.displayName,
                                        fontSize = 14.sp,
                                        color = Color.Black
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // =========================================================================
        // BẢNG CHI TIẾT CÁC BƯỚC RẼ (TURN-BY-TURN) & HUỶ LỘ TRÌNH (NÚT SỐ 3 BÊN PHẢI)
        // =========================================================================
        if (showStepsSheet) {
            ModalBottomSheet(onDismissRequest = { showStepsSheet = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Chi tiết lộ trình dẫn đường", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = GofaGreen)
                        if (activeRoute != null) {
                            TextButton(onClick = {
                                activeRoute = null
                                nextStepInstruction = null
                                destInputText = ""
                                selectedDestPoint = null
                                refreshMapOverlays(mapViewRef)
                                showStepsSheet = false
                                Toast.makeText(context, "Đã kết thúc dẫn đường", Toast.LENGTH_SHORT).show()
                            }) {
                                Text("Xoá lộ trình", color = GofaRed, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (activeRoute == null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Hiện chưa có lộ trình nào. Hãy nhấn vào thanh 'Tìm kiếm địa điểm' ở dưới cùng để chọn Điểm đầu và Điểm cuối.", fontSize = 15.sp, color = Color.DarkGray)
                        Spacer(modifier = Modifier.height(24.dp))
                    } else {
                        Text(
                            text = "Tổng quãng đường: ${activeRoute!!.totalDistanceKm} km • Thời gian: ${activeRoute!!.totalDurationMin} phút",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.DarkGray
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 380.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(activeRoute!!.steps) { step ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            mapViewRef?.controller?.animateTo(step.location)
                                            mapViewRef?.controller?.setZoom(17.5)
                                            showStepsSheet = false
                                        },
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFFF2F6F4)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(step.instruction, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            if (step.roadName.isNotEmpty()) {
                                                Text(step.roadName, fontSize = 12.sp, color = Color.Gray)
                                            }
                                        }
                                        Text("${step.distanceMeters} m", fontWeight = FontWeight.Bold, color = GofaGreen, fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }
            }
        }

        // =========================================================================
        // BẢNG CÀI ĐẶT CẢNH BÁO & TÙY CHỌN XE Ô TÔ CON (NÚT BÁNH RĂNG DƯỚI CÙNG)
        // =========================================================================
        if (showSettingsSheet) {
            ModalBottomSheet(onDismissRequest = { showSettingsSheet = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text("Cài đặt Dẫn đường Ô tô con (Online)", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GofaGreen)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("• Khoảng cách cảnh báo: Đô thị 300m | Quốc lộ 400m | Cao tốc 500m", fontSize = 13.sp, color = Color.Gray)
                    Text("• Đường hiện tại: $currentRoadName", fontSize = 13.sp, color = GofaGreen, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(12.dp))

                    SettingSwitchRow("Né trạm thu phí (BOT)", avoidTolls) { avoidTolls = it }
                    SettingSwitchRow("Né đường cao tốc", avoidHighways) { avoidHighways = it }
                    SettingSwitchRow("Né bến phà", avoidFerries) { avoidFerries = it }
                    SettingSwitchRow("Hiển thị Camera phạt nguội trên bản đồ", showCamerasOnMap) {
                        showCamerasOnMap = it
                        refreshMapOverlays(mapViewRef)
                    }
                    SettingSwitchRow("Giọng nói tiếng Việt chỉ đường & báo tốc độ", isVoiceEnabled) { isVoiceEnabled = it }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
fun NavigationCursor(rotationDegrees: Float = 0f) {
    Box(
        modifier = Modifier
            .size(92.dp)
            .clip(CircleShape)
            .background(GofaGreen.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .size(44.dp)
                .rotate(rotationDegrees)
        ) {
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = title, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = GofaGreen)
        )
    }
}

@SuppressLint("MissingPermission")
fun startLiveGpsListener(context: Context, onLocationUpdated: (Location) -> Unit) {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val listener = LocationListener { loc -> onLocationUpdated(loc) }
    try {
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, listener)
        locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 2f, listener)
        val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        if (lastGps != null) {
            onLocationUpdated(lastGps)
        }
    } catch (_: Exception) {
    }
}

// 1. Hàm tìm kiếm địa chỉ chi tiết tại Việt Nam (Online Geocoding Nominatim)
suspend fun searchPlacesOnlineVietnam(query: String): List<PlaceSearchResult> = withContext(Dispatchers.IO) {
    val list = mutableListOf<PlaceSearchResult>()
    try {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = URL("https://nominatim.openstreetmap.org/search?format=json&q=$encoded&countrycodes=vn&accept-language=vi&limit=8")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "GofaOtoVietnamNavigation/2.0")
            connectTimeout = 10000
            readTimeout = 10000
        }
        if (conn.responseCode == 200) {
            val text = conn.inputStream.bufferedReader().readText()
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    PlaceSearchResult(
                        displayName = obj.optString("display_name"),
                        lat = obj.optDouble("lat"),
                        lon = obj.optDouble("lon")
                    )
                )
            }
        }
    } catch (_: Exception) {
    }
    return@withContext list
}

// 2. Hàm tính toán lộ trình dẫn đường Ô tô Online & dịch từng bước sang tiếng Việt
suspend fun fetchOnlineDrivingRoute(
    start: GeoPoint,
    end: GeoPoint,
    avoidHighways: Boolean,
    avoidTolls: Boolean,
    avoidFerries: Boolean
): RouteResult? = withContext(Dispatchers.IO) {
    try {
        val urlStr = "https://router.project-osrm.org/route/v1/driving/${start.longitude},${start.latitude};${end.longitude},${end.latitude}?overview=full&geometries=geojson&steps=true&alternatives=true"
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "GofaOtoVietnamNavigation/2.0")
            connectTimeout = 12000
            readTimeout = 12000
        }
        if (conn.responseCode == 200) {
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val routes = json.optJSONArray("routes") ?: return@withContext null
            if (routes.length() == 0) return@withContext null

            val chosenRoute = routes.getJSONObject(0)
            val distKm = (chosenRoute.optDouble("distance", 0.0) / 100.0).roundToInt() / 10.0
            val durMin = (chosenRoute.optDouble("duration", 0.0) / 60.0).roundToInt().coerceAtLeast(1)

            val coords = chosenRoute.getJSONObject("geometry").getJSONArray("coordinates")
            val polyPoints = mutableListOf<GeoPoint>()
            for (i in 0 until coords.length()) {
                val c = coords.getJSONArray(i)
                polyPoints.add(GeoPoint(c.getDouble(1), c.getDouble(0)))
            }

            val stepItems = mutableListOf<RouteStepItem>()
            val legs = chosenRoute.optJSONArray("legs")
            if (legs != null && legs.length() > 0) {
                val steps = legs.getJSONObject(0).optJSONArray("steps")
                if (steps != null) {
                    for (j in 0 until steps.length()) {
                        val s = steps.getJSONObject(j)
                        val roadName = s.optString("name", "")
                        val stepDist = s.optDouble("distance", 0.0).roundToInt()
                        val maneuver = s.getJSONObject("maneuver")
                        val type = maneuver.optString("type", "")
                        val modifier = maneuver.optString("modifier", "")
                        val locArr = maneuver.getJSONArray("location")
                        val stepGeo = GeoPoint(locArr.getDouble(1), locArr.getDouble(0))

                        val vnInstruction = translateManeuverToVietnamese(type, modifier, roadName)
                        if (stepDist > 5 || type == "arrive") {
                            stepItems.add(RouteStepItem(vnInstruction, stepDist, roadName, stepGeo))
                        }
                    }
                }
            }
            return@withContext RouteResult(polyPoints, stepItems, distKm, durMin)
        }
    } catch (_: Exception) {
    }
    return@withContext null
}

fun translateManeuverToVietnamese(type: String, modifier: String, roadName: String): String {
    val roadTarget = if (roadName.isNotBlank()) " vào $roadName" else ""
    val dir = when (modifier) {
        "left" -> "Rẽ trái"
        "slight left" -> "Chếch sang trái"
        "sharp left" -> "Rẽ ngoặt sang trái"
        "right" -> "Rẽ phải"
        "slight right" -> "Chếch sang phải"
        "sharp right" -> "Rẽ ngoặt sang phải"
        "uturn" -> "Quay đầu xe"
        else -> "Đi thẳng"
    }
    return when (type) {
        "depart" -> "Xuất phát$roadTarget"
        "arrive" -> "Đến điểm đích"
        "roundabout", "rotary" -> "Đi vào vòng xuyến rồi ra$roadTarget"
        "merge" -> "Nhập làn$roadTarget"
        "fork" -> "$dir tại ngã ba$roadTarget"
        else -> "$dir$roadTarget"
    }
}

// 3. Hàm truy vấn trực tuyến tốc độ giới hạn & tên đường tại đúng vị trí xe đang chạy
suspend fun fetchOnlineRoadSpeedLimit(lat: Double, lon: Double): Pair<Int?, String> = withContext(Dispatchers.IO) {
    try {
        val query = """[out:json][timeout:8];way(around:40,$lat,$lon)["highway"];out tags 1;"""
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://overpass-api.de/api/interpreter?data=$encoded")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6000
            readTimeout = 6000
        }
        if (conn.responseCode == 200) {
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val elements = json.optJSONArray("elements")
            if (elements != null && elements.length() > 0) {
                val tags = elements.getJSONObject(0).optJSONObject("tags")
                if (tags != null) {
                    val name = tags.optString("name", "")
                    val maxSpeedRaw = tags.optString("maxspeed", "").filter { it.isDigit() }.toIntOrNull()
                    val highwayType = tags.optString("highway", "")
                    val inferredSpeed = maxSpeedRaw ?: when (highwayType) {
                        "motorway" -> 100
                        "trunk" -> 80
                        "primary" -> 60
                        "secondary", "tertiary", "residential" -> 50
                        else -> 50
                    }
                    return@withContext Pair(inferredSpeed, name)
                }
            }
        }
    } catch (_: Exception) {
    }
    return@withContext Pair(null, "")
}

// 4. Hàm tải trực tuyến danh sách Camera phạt nguội xung quanh khu vực bản đồ
suspend fun fetchOnlineCamerasAround(lat: Double, lon: Double): List<SpeedCameraNode> = withContext(Dispatchers.IO) {
    val result = mutableListOf<SpeedCameraNode>()
    try {
        val query = """[out:json][timeout:10];node["highway"="speed_camera"](around:80000,$lat,$lon);out body 50;"""
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://overpass-api.de/api/interpreter?data=$encoded")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
        }
        if (conn.responseCode == 200) {
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val elements = json.optJSONArray("elements")
            if (elements != null) {
                for (i in 0 until elements.length()) {
                    val el = elements.getJSONObject(i)
                    val id = "OSM_${el.optLong("id")}"
                    val cLat = el.optDouble("lat")
                    val cLon = el.optDouble("lon")
                    val tags = el.optJSONObject("tags")
                    val maxSpd = tags?.optString("maxspeed", "60")?.filter { it.isDigit() }?.toIntOrNull() ?: 60
                    val rType = when {
                        maxSpd >= 90 -> RoadType.EXPRESSWAY
                        maxSpd >= 60 -> RoadType.NATIONAL_HIGHWAY
                        else -> RoadType.URBAN
                    }
                    result.add(SpeedCameraNode(id, "Camera giám sát tốc độ", cLat, cLon, maxSpd, rType))
                }
            }
        }
    } catch (_: Exception) {
    }
    return@withContext result
}
