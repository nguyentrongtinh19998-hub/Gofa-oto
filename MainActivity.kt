package vn.navigation.gofaclone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
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
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.roundToInt

// Màu chủ đạo trích xuất từ giao diện Gofa
val GofaGreen = Color(0xFF0E8A5F)
val GofaRed = Color(0xFFE5434B)
val MapBgColor = Color(0xFFEBF1F5)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                GofaNavigationApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GofaNavigationApp() {
    val context = LocalContext.current

    // Trạng thái tốc độ thực tế (km/h) và tốc độ giới hạn
    var currentSpeed by remember { mutableIntStateOf(0) }
    var speedLimit by remember { mutableStateOf<Int?>(null) } // null sẽ hiển thị "--" như hình mẫu
    var isVoiceEnabled by remember { mutableStateOf(true) }
    var showSettingsSheet by remember { mutableStateOf(false) }

    // Trạng thái tùy chọn dẫn đường
    var avoidTolls by remember { mutableStateOf(false) }
    var avoidHighways by remember { mutableStateOf(false) }
    var avoidFerries by remember { mutableStateOf(false) }
    var autoUpdateDaily by remember { mutableStateOf(true) }

    // Khởi tạo giọng nói tiếng Việt cảnh báo tốc độ
    val tts = remember {
        var textToSpeech: TextToSpeech? = null
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale("vi", "VN")
            }
        }
        textToSpeech
    }

    // Xin quyền GPS và lắng nghe tốc độ thực tế
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            startLocationUpdates(context) { speedKmh ->
                currentSpeed = speedKmh
                if (speedLimit != null && speedKmh > speedLimit!! && isVoiceEnabled) {
                    tts?.speak("Chú ý! Bạn đang chạy quá tốc độ giới hạn.", TextToSpeech.QUEUE_FLUSH, null, null)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startLocationUpdates(context) { speedKmh -> currentSpeed = speedKmh }
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MapBgColor)
    ) {
        // 1. LỚP BẢN ĐỒ NỀN (Nơi gắn MapLibre / Offline MapView)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Biểu tượng mũi tên dẫn đường màu xanh ở tâm kèm vòng tròn tỏa sáng
            NavigationCursor()
        }

        // 2. CỤM BIỂN BÁO TỐC ĐỘ GÓC TRÊN BÊN TRÁI (Giống hệt giao diện mẫu)
        Box(
            modifier = Modifier
                .padding(top = 48.dp, start = 16.dp)
                .size(width = 115.dp, height = 105.dp)
                .align(Alignment.TopStart)
        ) {
            // Biển báo tốc độ giới hạn (Vòng tròn đỏ)
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(8.dp, GofaRed, CircleShape)
                    .clickable {
                        // Chạm thử để đổi mức giới hạn tốc độ (Test cảnh báo: null -> 50 -> 60 -> 80)
                        speedLimit = when (speedLimit) {
                            null -> 50
                            50 -> 60
                            60 -> 80
                            else -> null
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = speedLimit?.toString() ?: "--",
                    color = Color.Black,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            // Vòng tròn tốc độ GPS hiện tại (Viền xanh lá đè góc dưới phải)
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .align(Alignment.BottomEnd)
                    .shadow(4.dp, CircleShape)
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

        // 3. THANH CÔNG CỤ NỔI BÊN PHẢI (4 nút: Lớp bản đồ, Loa, Báo cáo, Camera)
        Surface(
            modifier = Modifier
                .padding(top = 48.dp, end = 14.dp)
                .align(Alignment.TopEnd),
            shape = RoundedCornerShape(28.dp),
            color = Color.White.copy(alpha = 0.92f),
            shadowElevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(onClick = { /* Đổi lớp bản đồ Vệ tinh / Giao thông */ }) {
                    Icon(Icons.Outlined.Layers, contentDescription = "Lớp bản đồ", tint = GofaGreen, modifier = Modifier.size(28.dp))
                }
                IconButton(onClick = { isVoiceEnabled = !isVoiceEnabled }) {
                    Icon(
                        if (isVoiceEnabled) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                        contentDescription = "Âm thanh",
                        tint = GofaGreen,
                        modifier = Modifier.size(28.dp)
                    )
                }
                IconButton(onClick = { /* Đóng góp báo cáo biển báo / phạt nguội */ }) {
                    Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "Báo cáo", tint = GofaGreen, modifier = Modifier.size(26.dp))
                }
                IconButton(onClick = { /* Chế độ hiển thị Camera phạt nguội */ }) {
                    Icon(Icons.Outlined.PhotoCamera, contentDescription = "Camera phạt nguội", tint = GofaGreen, modifier = Modifier.size(28.dp))
                }
            }
        }

        // 4. CỤM NÚT ĐỊNHị VỊ & THU PHÓNG GÓC DƯỚI BÊN TRÁI
        Surface(
            modifier = Modifier
                .padding(start = 14.dp, bottom = 105.dp)
                .align(Alignment.BottomStart),
            shape = RoundedCornerShape(28.dp),
            color = Color.White.copy(alpha = 0.92f),
            shadowElevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = { /* Đưa tâm bản đồ về vị trí GPS hiện tại */ }) {
                    Icon(Icons.Outlined.MyLocation, contentDescription = "Định vị", tint = GofaGreen, modifier = Modifier.size(26.dp))
                }
                IconButton(onClick = { /* Phóng to bản đồ */ }) {
                    Icon(Icons.Default.Add, contentDescription = "Phóng to", tint = GofaGreen, modifier = Modifier.size(26.dp))
                }
                IconButton(onClick = { /* Thu nhỏ bản đồ */ }) {
                    Icon(Icons.Default.Remove, contentDescription = "Thu nhỏ", tint = GofaGreen, modifier = Modifier.size(26.dp))
                }
            }
        }

        // 5. THANH TÌM KIẾM ĐỊA ĐIỂM & NÚT CÀI ĐẶT DƯỚI CÙNG
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 24.dp)
                .align(Alignment.BottomCenter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Thanh "Tìm kiếm địa điểm"
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(58.dp)
                    .clickable { /* Mở màn hình nhập điểm đến */ },
                shape = RoundedCornerShape(30.dp),
                color = Color.White.copy(alpha = 0.95f),
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
                        text = "Tìm kiếm địa điểm",
                        color = Color.DarkGray,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Nút Cài đặt (Bánh răng xanh lá)
            Surface(
                modifier = Modifier
                    .size(58.dp)
                    .clickable { showSettingsSheet = true },
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.95f),
                shadowElevation = 4.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Cài đặt", tint = GofaGreen, modifier = Modifier.size(30.dp))
                }
            }
        }

        // BẢNG CÀI ĐẶT LỘ TRÌNH & DỮ LIỆU OFFLINE
        if (showSettingsSheet) {
            ModalBottomSheet(onDismissRequest = { showSettingsSheet = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text("Tùy chọn Dẫn đường & Cảnh báo", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GofaGreen)
                    Spacer(modifier = Modifier.height(12.dp))

                    SettingSwitchItem("Né trạm thu phí (BOT)", avoidTolls) { avoidTolls = it }
                    SettingSwitchItem("Né đường cao tốc", avoidHighways) { avoidHighways = it }
                    SettingSwitchItem("Né bến phà", avoidFerries) { avoidFerries = it }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    SettingSwitchItem("Tự động cập nhật bản đồ & biển báo mỗi ngày khi có mạng", autoUpdateDaily) { autoUpdateDaily = it }

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
            .size(100.dp)
            .clip(CircleShape)
            .background(GofaGreen.copy(alpha = 0.25f)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(48.dp)) {
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
fun SettingSwitchItem(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
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
fun startLocationUpdates(context: Context, onSpeedChanged: (Int) -> Unit) {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // Chuyển đổi từ m/s sang km/h
            val speedKmh = (location.speed * 3.6f).roundToInt()
            onSpeedChanged(speedKmh)
        }
    }
    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, listener)
}
