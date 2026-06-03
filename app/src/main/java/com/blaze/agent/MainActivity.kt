package com.blaze.agent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.blaze.agent.ai.BonsaiDownloader
import com.blaze.agent.accessibility.BlazeAccessibilityService
import com.blaze.agent.state.AppState
import com.blaze.agent.state.AppStateManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var bonsaiDownloader: BonsaiDownloader
    private val projectionManager by lazy { getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager }

    private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val projection = projectionManager.getMediaProjection(result.resultCode, result.data!!)
            BlazeAccessibilityService.instance?.initScreenCapture(projection)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bonsaiDownloader = BonsaiDownloader(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        setContent {
            BlazeTheme {
                BlazeHomeScreen(
                    bonsaiDownloader = bonsaiDownloader,
                    isAccessibilityEnabled = ::isAccessibilityEnabled,
                    openAccessibilitySettings = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    requestScreenCapture = { projectionLauncher.launch(projectionManager.createScreenCaptureIntent()) },
                    onStartListening = { AppStateManager.transitionTo(AppState.LISTENING) }
                )
            }
        }
        if (!bonsaiDownloader.isModelReady()) {
            lifecycleScope.launch { bonsaiDownloader.download() }
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains(packageName)
    }
}

@Composable
fun BlazeHomeScreen(
    bonsaiDownloader: BonsaiDownloader,
    isAccessibilityEnabled: () -> Boolean,
    openAccessibilitySettings: () -> Unit,
    requestScreenCapture: () -> Unit,
    onStartListening: () -> Unit
) {
    val appState by AppStateManager.state.collectAsState()
    val downloadState by bonsaiDownloader.state.collectAsState()
    val accessibilityEnabled = remember { mutableStateOf(isAccessibilityEnabled()) }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0A0F)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(modifier = Modifier.height(48.dp))
            Text("BLAZE", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF6B00), letterSpacing = 4.sp)
            Text("Your AI Phone Agent", fontSize = 14.sp, color = Color(0xFF888888))
            Spacer(modifier = Modifier.height(16.dp))
            BlazeStateOrb(appState)
            ModeChip(bonsaiDownloader.isModelReady())
            Spacer(modifier = Modifier.height(8.dp))
            AnimatedVisibility(visible = downloadState is BonsaiDownloader.DownloadState.Downloading) {
                BonsaiDownloadCard(downloadState)
            }
            if (!accessibilityEnabled.value) {
                SetupCard("Enable Accessibility", "Required for Blaze to control your phone", "Open Settings", openAccessibilitySettings)
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = onStartListening,
                enabled = accessibilityEnabled.value && appState == AppState.SLEEP,
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B00), disabledContainerColor = Color(0xFF333333))
            ) {
                Text(
                    text = when (appState) {
                        AppState.SLEEP -> "Tap to Talk"
                        AppState.LISTENING -> "Listening..."
                        AppState.ACTIVE -> "Working..."
                        AppState.MONITORING -> "Monitoring Active"
                    }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun BlazeStateOrb(state: AppState) {
    val color = when (state) {
        AppState.SLEEP -> Color(0xFF444444); AppState.LISTENING -> Color(0xFF2196F3)
        AppState.ACTIVE -> Color(0xFFFF6B00); AppState.MONITORING -> Color(0xFF4CAF50)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(100.dp).clip(CircleShape).background(color.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
            Box(modifier = Modifier.size(60.dp).clip(CircleShape).background(color))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = state.name, color = color, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
fun ModeChip(bonsaiReady: Boolean) {
    Surface(shape = RoundedCornerShape(50), color = Color(0xFF1A1A2E)) {
        Text(
            text = if (bonsaiReady) "Offline Ready (Bonsai)" else "Online Mode (Gemini)",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = Color(0xFFAAAAAA), fontSize = 13.sp
        )
    }
}

@Composable
fun BonsaiDownloadCard(state: BonsaiDownloader.DownloadState) {
    val downloading = state as? BonsaiDownloader.DownloadState.Downloading ?: return
    val progress by animateFloatAsState(targetValue = downloading.progressPercent / 100f, label = "dl")
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Downloading Bonsai AI Model", color = Color.White, fontWeight = FontWeight.SemiBold)
            Text("${downloading.downloadedMb.toInt()}MB / ${downloading.totalMb.toInt()}MB  at  ${downloading.speedKbps.toInt()} KB/s", color = Color(0xFF888888), fontSize = 12.sp)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)), color = Color(0xFFFF6B00), trackColor = Color(0xFF333333))
        }
    }
}

@Composable
fun SetupCard(title: String, subtitle: String, buttonText: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Color(0xFF888888), fontSize = 12.sp)
            }
            Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B00)), shape = RoundedCornerShape(10.dp)) { Text(buttonText, fontSize = 12.sp) }
        }
    }
}

@Composable
fun BlazeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFFF6B00), background = Color(0xFF0A0A0F), surface = Color(0xFF1A1A2E)), content = content)
}
