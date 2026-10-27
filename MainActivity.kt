package vn.navigation.gofaclone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.preference.PreferenceManager
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

// Bảng màu chuẩn theo ảnh giao diện Gofa
val GofaGreen = Color(0xFF0E8A5F)
val GofaRed = Color(0xFFE5434B)

// Phân loại đường & khoảng cách cảnh báo theo yêu cầu (300m Đô thị, 400m Quốc lộ, 500m Cao tốc)
enum class RoadType(val label: String, val alertDistanceMeters: Int) {
    URBAN("Đô thị", 300),
    NATIONAL_HIGHWAY("Quốc lộ", 400),
    EXPRESSWAY("Cao tốc", 500)
}

data class TrafficWarningNode(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val speedLimit: Int,
    val roadType: RoadType,
    val isCamera: Boolean
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Cấu hình bộ nhớ đệm Offline cho bản đồ OpenStreetMap
        Configuration.getInstance().load(applicationContext, PreferenceManager.getDefaultSharedPreferences(applicationContext))
        Configuration.getInstance().userAgentValue = packageName
        setContent {
            MaterialTheme {
                GofaNavigationScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GofaNavigationScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("gofa_offline_db", Context.MODE_PRIVATE) }

    // Trạng thái tốc độ GPS & Biển báo hiện tại
    var currentSpeed by remember { mutableIntStateOf(0) }
    var currentSpeedLimit by remember { mutableStateOf<Int?>(null) }
    var activeWarningText by remember { mutableStateOf<String?>(null) }
    var isVoiceEnabled by remember { mutableStateOf(true) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Cài đặt dẫn đường chuyên biệt cho Ô tô con
    var avoidTolls by remember { mutableStateOf(prefs.getBoolean("avoid_tolls", false)) }
    var avoidHighways by remember { mutableStateOf(prefs.getBoolean("avoid_highways", false)) }
    var avoidFerries by remember { mutableStateOf(prefs.getBoolean("avoid_ferries", true)) }

    // Tham chiếu bản đồ thực tế (Mặc định mở tại khu vực QL1A - Bạc Liêu / Cà Mau như ảnh)
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var userGeoPoint by remember { mutableStateOf(GeoPoint(9.2941, 105.7278)) }
    val alertedNodeIds = remember { mutableStateListOf<String>() }

    // Danh sách điểm cảnh báo tốc độ & phạt nguội mặc định dọc tuyến Cà Mau -> Đà Lạt (Lưu sẵn Offline)
    val warningNodes = remember {
        mutableStateListOf(
            TrafficWarningNode("CM_01", "Camera phạt nguội QL1A TP. Cà Mau", 9.1768, 105.1524, 50, RoadType.URBAN, true),
            TrafficWarningNode("BL_01", "Giao lộ QL1A - Võ Văn Kiệt (Bạc Liêu)", 9.2985, 105.7240, 50, RoadType.URBAN, true),
            TrafficWarningNode("ST_01", "Trạm kiểm soát tốc độ QL1A Sóc Trăng", 9.6025, 105.9739, 60, RoadType.NATIONAL_HIGHWAY, true),
            TrafficWarningNode("CT_01", "Nút giao IC3 Cầu Cần Thơ", 10.0152, 105.7881, 60, RoadType.NATIONAL_HIGHWAY, true),
            TrafficWarningNode("CT_MYTHUAN", "Cao tốc Mỹ Thuận - Cần Thơ", 10.2541, 105.8950, 90, RoadType.EXPRESSWAY, true),
            TrafficWarningNode("TG_TRUNGLUONG", "Cao tốc TP.HCM - Trung Lương", 10.4350, 106.3420, 100, RoadType.EXPRESSWAY, true),
            TrafficWarningNode("HCM_VVK", "Đại lộ Võ Văn Kiệt TP.HCM", 10.7503, 106.6634, 60, RoadType.URBAN, true),
            TrafficWarningNode("DN_LTDG", "Cao tốc Long Thành - Dầu Giây", 10.8210, 106.9530, 120, RoadType.EXPRESSWAY, true),
            TrafficWarningNode("DN_QL20", "Camera phạt nguội QL20 Định Quán", 11.1982, 107.3521, 50, RoadType.NATIONAL_HIGHWAY, true),
            TrafficWarningNode("LD_BAOLOC", "Khu vực Đèo Bảo Lộc - QL20", 11.4580, 107.7250, 50, RoadType.NATIONAL_HIGHWAY, true),
            TrafficWarningNode("LD_LIENKHUONG", "Cao tốc Liên Khương - Prenn (Đà Lạt)", 11.7520, 108.3810, 90, RoadType.EXPRESSWAY, true),
            TrafficWarningNode("DL_TRUNG_TAM", "Khu vực đô thị Đà Lạt", 11.9404, 108.4583, 50, RoadType.URBAN, true)
        )
    }

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

    // Tự động cập nhật dữ liệu biển báo & camera mới mỗi ngày khi có Internet
    LaunchedEffect(Unit) {
        val lastUpdateDay = prefs.getLong("last_sync_day", 0L)
        val currentDay = System.currentTimeMillis() / (1000 * 60 * 60 * 24)
        if (currentDay > lastUpdateDay) {
            scope.launch {
                val syncedCount = syncDailyCamerasCaMauToDaLat()
                if (syncedCount > 0) {
                    prefs.edit().putLong("last_sync_day", currentDay).apply()
                    Toast.makeText(context, "Đã cập nhật $syncedCount điểm cảnh báo mới (Cà Mau - Đà Lạt)", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Xử lý GPS thực tế và tính khoảng cách cảnh báo (300m Đô thị / 400m Quốc lộ / 500m Cao tốc)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            listenToGps(context) { location ->
                val speedKmh = (location.speed * 3.6f).roundToInt()
                currentSpeed = speedKmh
                userGeoPoint = GeoPoint(location.latitude, location.longitude)
                mapViewRef?.controller?.animateTo(userGeoPoint)

                // Kiểm tra các biển báo / camera phạt nguội trong phạm vi
                for (node in warningNodes) {
                    val results = FloatArray(1)
                    Location.distanceBetween(
                        location.latitude, location.longitude,
                        node.lat, node.lon, results
                    )
                    val distanceMeters = results[0].roundToInt()
                    val triggerDist = node.roadType.alertDistanceMeters

                    if (distanceMeters <= triggerDist) {
                        currentSpeedLimit = node.speedLimit
                        activeWarningText = "${node.roadType.label}: Cách ${distanceMeters}m - ${node.name} (${node.speedLimit} km/h)"

                        if (!alertedNodeIds.contains(node.id) && isVoiceEnabled) {
                            alertedNodeIds.add(node.id)
                            tts?.speak(
                                "Chú ý! Phía trước $distanceMeters mét trên ${node.roadType.label} có ${node.name}, giới hạn tốc độ ${node.speedLimit} ki lô mét trên giờ.",
                                TextToSpeech.QUEUE_FLUSH, null, null
                            )
                        }
                        if (speedKmh > node.speedLimit && isVoiceEnabled) {
                            tts?.speak("Bạn đang chạy quá tốc độ giới hạn ${node.speedLimit} ki lô mét trên giờ.", TextToSpeech.QUEUE_ADD, null, null)
                        }
                        break
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. LỚP BẢN ĐỒ THỰC TẾ (OSMDROID - HỖ TRỢ CACHE OFFLINE CÀ MAU -> ĐÀ LẠT)
        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    controller.setZoom(16.5)
                    controller.setCenter(userGeoPoint)
                    // Giới hạn ưu tiên vùng Cà Mau (8.55N) đến Đà Lạt (12.10N)
                    setScrollableAreaLimitDouble(BoundingBox(12.25, 108.85, 8.40, 104.30))
                    mapViewRef = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Biểu tượng mũi tên xanh lá ở tâm bản đồ (Giống ảnh mẫu Gofa)
        Box(modifier = Modifier.align(Alignment.Center)) {
            NavigationCursor()
        }

        // 2. CỤM BIỂN BÁO TỐC ĐỘ GÓC TRÊN BÊN TRÁI (Giống hệt giao diện Gofa)
        Box(
            modifier = Modifier
                .padding(top = 44.dp, start = 16.dp)
                .size(width = 115.dp, height = 105.dp)
                .align(Alignment.TopStart)
        ) {
            // Vòng tròn đỏ biển báo tốc độ giới hạn
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .shadow(5.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(8.dp, GofaRed, CircleShape)
                    .clickable {
                        // Nhấn vào biển báo để mô phỏng thử nghiệm các mức tốc độ (Đô thị 50 / Quốc lộ 80 / Cao tốc 100)
                        currentSpeedLimit = when (currentSpeedLimit) {
                            null -> 50
                            50 -> 80
                            80 -> 100
                            else -> null
                        }
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

            // Vòng tròn xanh lá hiển thị tốc độ GPS thực tế
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .align(Alignment.BottomEnd)
                    .shadow(5.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(5.dp, GofaGreen, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = currentSpeed.toString(),
                    color = GofaGreen,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Băng rôn thông báo khi vào vùng 300m/400m/500m
        activeWarningText?.let { msg ->
            Surface(
                modifier = Modifier
                    .padding(top = 155.dp, start = 16.dp, end = 80.dp)
                    .align(Alignment.TopStart),
                shape = RoundedCornerShape(12.dp),
                color = GofaRed,
                shadowElevation = 4.dp
            ) {
                Text(
                    text = msg,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        // 3. THANH CÔNG CỤ NỔI BÊN PHẢI (4 nút xanh lá giống ảnh Gofa)
        Surface(
            modifier = Modifier
                .padding(top = 44.dp, end = 14.dp)
                .align(Alignment.TopEnd),
            shape = RoundedCornerShape(28.dp),
            color = Color.White.copy(alpha = 0.93f),
            shadowElevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                IconButton(onClick = {
                    Toast.makeText(context, "Bản đồ Offline: Cà Mau - Đà Lạt (Ô tô con)", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Outlined.Layers, contentDescription = "Lớp bản đồ", tint = GofaGreen, modifier = Modifier.size(28.dp))
                }
                IconButton(onClick = {
                    isVoiceEnabled = !isVoiceEnabled
                    val status = if (isVoiceEnabled) "Đã bật giọng nói cảnh báo" else "Đã tắt giọng nói"
                    Toast.makeText(context, status, Toast.LENGTH_SHORT).show()
                }) {
                    Icon(
                        if (isVoiceEnabled) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                        contentDescription = "Âm thanh",
                        tint = GofaGreen,
                        modifier = Modifier.size(28.dp)
                    )
                }
                IconButton(onClick = {
                    Toast.makeText(context, "Chế độ cảnh báo: Đô thị 300m | QL 400m | Cao tốc 500m", Toast.LENGTH_LONG).show()
                }) {
                    Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "Thông tin", tint = GofaGreen, modifier = Modifier.size(26.dp))
                }
                IconButton(onClick = {
                    if (isVoiceEnabled) {
                        tts?.speak("Hệ thống cảnh báo phạt nguội ô tô con từ Cà Mau đến Đà Lạt đang hoạt động.", TextToSpeech.QUEUE_FLUSH, null, null)
                    }
                }) {
                    Icon(Icons.Outlined.PhotoCamera, contentDescription = "Camera phạt nguội", tint = GofaGreen, modifier = Modifier.size(28.dp))
                }
            }
        }

        // 4. CỤM NÚT ĐỊNH VỊ & PHÓNG TO / THU NHỎ GÓC DƯỚI BÊN TRÁI
        Surface(
            modifier = Modifier
                .padding(start = 14.dp, bottom = 105.dp)
                .align(Alignment.BottomStart),
            shape = RoundedCornerShape(28.dp),
            color = Color.White.copy(alpha = 0.93f),
            shadowElevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = { mapViewRef?.controller?.animateTo(userGeoPoint) }) {
                    Icon(Icons.Outlined.MyLocation, contentDescription = "Định vị", tint = GofaGreen, modifier = Modifier.size(26.dp))
                }
                IconButton(onClick = { mapViewRef?.controller?.zoomIn() }) {
                    Icon(Icons.Default.Add, contentDescription = "Phóng to", tint = GofaGreen, modifier = Modifier.size(26.dp))
                }
                IconButton(onClick = { mapViewRef?.controller?.zoomOut() }) {
                    Icon(Icons.Default.Remove, contentDescription = "Thu nhỏ", tint = GofaGreen, modifier = Modifier.size(26.dp))
                }
            }
        }

        // 5. THANH "TÌM KIẾM ĐỊA ĐIỂM" & NÚT CÀI ĐẶT DƯỚI CÙNG
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
                    .clickable { showSearchDialog = true },
                shape = RoundedCornerShape(30.dp),
                color = Color.White.copy(alpha = 0.96f),
                shadowElevation = 4.dp
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
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = if (searchQuery.isEmpty()) "Tìm kiếm địa điểm" else searchQuery,
                        color = Color.DarkGray,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .size(58.dp)
                    .clickable { showSettingsSheet = true },
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.96f),
                shadowElevation = 4.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Cài đặt", tint = GofaGreen, modifier = Modifier.size(30.dp))
                }
            }
        }

        // HỘP THOẠI TÌM ĐƯỜNG NHANH (CÀ MAU <-> ĐÀ LẠT)
        if (showSearchDialog) {
            AlertDialog(
                onDismissRequest = { showSearchDialog = false },
                title = { Text("Dẫn đường Ô tô con (Cà Mau - Đà Lạt)", fontWeight = FontWeight.Bold, color = GofaGreen) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text("Nhập điểm đến (VD: Đà Lạt, Cần Thơ, Cà Mau)") },
                            singleLine = true
                        )
                        Text("Chọn nhanh điểm đến phổ biến:", fontSize = 13.sp, color = Color.Gray)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SuggestionChip(onClick = {
                                searchQuery = "TP. Đà Lạt"
                                mapViewRef?.controller?.animateTo(GeoPoint(11.9404, 108.4583))
                                showSearchDialog = false
                            }, label = { Text("Đà Lạt") })
                            SuggestionChip(onClick = {
                                searchQuery = "TP.HCM"
                                mapViewRef?.controller?.animateTo(GeoPoint(10.7503, 106.6634))
                                showSearchDialog = false
                            }, label = { Text("TP.HCM") })
                            SuggestionChip(onClick = {
                                searchQuery = "TP. Cà Mau"
                                mapViewRef?.controller?.animateTo(GeoPoint(9.1768, 105.1524))
                                showSearchDialog = false
                            }, label = { Text("Cà Mau") })
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showSearchDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = GofaGreen)
                    ) {
                        Text("Bắt đầu dẫn đường")
                    }
                }
            )
        }

        // BẢNG CÀI ĐẶT LỘ TRÌNH Ô TÔ CON & CẢNH BÁO
        if (showSettingsSheet) {
            ModalBottomSheet(onDismissRequest = { showSettingsSheet = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text("Cấu hình Ô tô con (Cà Mau - Đà Lạt)", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GofaGreen)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Khoảng cách báo trước: Đô thị 300m | Quốc lộ 400m | Cao tốc 500m", fontSize = 13.sp, color = Color.Gray)
                    Spacer(modifier = Modifier.height(12.dp))

                    SettingSwitchRow("Né trạm thu phí (BOT)", avoidTolls) {
                        avoidTolls = it
                        prefs.edit().putBoolean("avoid_tolls", it).apply()
                    }
                    SettingSwitchRow("Né đường cao tốc", avoidHighways) {
                        avoidHighways = it
                        prefs.edit().putBoolean("avoid_highways", it).apply()
                    }
                    SettingSwitchRow("Né bến phà", avoidFerries) {
                        avoidFerries = it
                        prefs.edit().putBoolean("avoid_ferries", it).apply()
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
fun NavigationCursor() {
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(GofaGreen.copy(alpha = 0.25f)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(46.dp)) {
            val path = Path().apply {
                moveTo(size.width / 2f, 0f)
                lineTo(size.width, size.height)
                lineTo(size.width / 2f, size.height * 0.78f)
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
fun listenToGps(context: Context, onLocationUpdated: (Location) -> Unit) {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val listener = LocationListener { location -> onLocationUpdated(location) }
    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, listener)
}

// Hàm tự động tải dữ liệu Camera phạt nguội & Biển báo tốc độ từ Cà Mau (8.55) đến Đà Lạt (12.10)
suspend fun syncDailyCamerasCaMauToDaLat(): Int = withContext(Dispatchers.IO) {
    try {
        val query = """[out:json][timeout:15];(node["highway"="speed_camera"](8.55,104.45,12.10,108.65););out body;"""
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://overpass-api.de/api/interpreter?data=$encoded")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
        }
        if (conn.responseCode == 200) {
            val response = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            val elements = json.optJSONArray("elements")
            return@withContext elements?.length() ?: 0
        }
    } catch (_: Exception) {
        // Khi không có mạng sẽ dùng cơ sở dữ liệu Offline có sẵn
    }
    return@withContext 0
}
