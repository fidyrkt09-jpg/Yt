package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.StorageHelper
import com.example.data.TelegramBotService
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Initialize the service so settings and autostart are loaded
        TelegramBotService.initialize(applicationContext)
        
        // Request runtime permissions if on Marshmallow or higher
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val permissions = mutableListOf<String>()
            
            // For Android 12 (API 32) and below, request READ_EXTERNAL_STORAGE
            if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.S_V2) {
                permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            
            // For Android 9 (API 28) and below, request WRITE_EXTERNAL_STORAGE
            if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P) {
                permissions.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            
            // For Android 13+ (API 33+), there are granular permissions like READ_MEDIA_VIDEO and READ_MEDIA_AUDIO,
            // but since we are downloading to public/internal folders and read-writing files we created,
            // standard permissions are sufficient or handled by fallback.
            
            if (permissions.isNotEmpty()) {
                requestPermissions(permissions.toTypedArray(), 101)
            }
        }
        
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    DashboardScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Service States (automatically triggers recomposition on mutation!)
    val isRunning = TelegramBotService.isRunning.value
    val botName = TelegramBotService.botNameState.value
    val totalDownloads = TelegramBotService.totalDownloadsCount.value
    val currentProgress = TelegramBotService.downloadProgress.value
    val currentSpeed = TelegramBotService.downloadSpeed.value
    val currentTitle = TelegramBotService.activeDownloadTitle.value
    val logList = TelegramBotService.logs // SnapshotStateList handles lists perfectly!

    // Settings States
    val settings = TelegramBotService.settings
    var tokenInput by remember { mutableStateOf(settings?.botToken?.value ?: "") }
    var chatIdInput by remember { mutableStateOf(settings?.chatId?.value ?: "") }
    var tokenVisible by remember { mutableStateOf(false) }

    // Dynamic states (polled lists and sizes)
    var fileList by remember { mutableStateOf(emptyList<StorageHelper.FileDetails>()) }
    var storageInfo by remember { mutableStateOf<StorageHelper.StorageSpace?>(null) }

    // Periodically update file List & storage info
    LaunchedEffect(key1 = true) {
        while (true) {
            fileList = StorageHelper.listFiles(context)
            storageInfo = StorageHelper.getStorageDetails(context)
            delay(3000)
        }
    }

    // Palette Colors
    val slate900 = Color(0xFF0F172A)
    val slate800 = Color(0xFF1E293B)
    val slate700 = Color(0xFF334155)
    val slate300 = Color(0xFFCBD5E1)
    val slate400 = Color(0xFF94A3B8)
    val teal300 = Color(0xFF5EEAD4)
    val teal400 = Color(0xFF2DD4BF)
    val teal500 = Color(0xFF14B8A6)
    val teal600 = Color(0xFF0D9488)
    val coralRed = Color(0xFFEF4444)
    val emeraldGreen = Color(0xFF10B981)
    val white = Color(0xFFF8FAFC)

    Column(
        modifier = modifier
            .background(slate900)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- HEADER SECTION ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "YTTelegram Bot",
                    color = white,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "YouTube Downloader Controller",
                    color = slate400,
                    fontSize = 12.sp
                )
            }

            // Connection Badge Status
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isRunning) emeraldGreen.copy(alpha = 0.15f) else coralRed.copy(alpha = 0.15f))
                    .border(
                        width = 1.dp,
                        color = if (isRunning) emeraldGreen else coralRed,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (isRunning) emeraldGreen else coralRed)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isRunning) "COURS" else "ARRÊT",
                    color = if (isRunning) emeraldGreen else coralRed,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // --- ACTIVE BOT / CONFIG OVERVIEW ---
        if (isRunning) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = slate800),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.SmartButton,
                        contentDescription = "Bot",
                        tint = teal400,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Bot connecté de manière active",
                            color = slate300,
                            fontSize = 12.sp
                        )
                        Text(
                            text = botName,
                            color = white,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // --- CARD 1: SETUP & CONFIGURATION ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = slate800),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Config",
                        tint = teal400,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Configurations du Canal",
                        color = white,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Field: Token
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    label = { Text("Token du Bot Telegram", color = slate400) },
                    singleLine = true,
                    visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { tokenVisible = !tokenVisible }) {
                            Icon(
                                imageVector = if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle Token",
                                tint = slate400
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = teal400,
                        unfocusedBorderColor = slate700,
                        focusedLabelColor = teal400,
                        focusedTextColor = white,
                        unfocusedTextColor = white
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Field: Chat ID
                OutlinedTextField(
                    value = chatIdInput,
                    onValueChange = { chatIdInput = it },
                    label = { Text("Chat ID Autorisé (Téléphone)", color = slate400) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = teal400,
                        unfocusedBorderColor = slate700,
                        focusedLabelColor = teal400,
                        focusedTextColor = white,
                        unfocusedTextColor = white
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Save Status & Apply
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            if (tokenInput.isEmpty() || chatIdInput.isEmpty()) {
                                Toast.makeText(context, "Tous les champs doivent être remplis !", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            settings?.saveBotToken(tokenInput)
                            settings?.saveChatId(chatIdInput)
                            Toast.makeText(context, "Configuration sauvegardée !", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = slate700),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "Save", tint = white, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Enregistrer", color = white, fontSize = 13.sp)
                    }

                    // Start/Stop Action control
                    Button(
                        onClick = {
                            if (isRunning) {
                                TelegramBotService.stopPolling(context)
                                Toast.makeText(context, "Contrôle arrêté !", Toast.LENGTH_SHORT).show()
                            } else {
                                if (tokenInput.isEmpty() || chatIdInput.isEmpty()) {
                                    Toast.makeText(context, "Configurez les identifiants d'abord !", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                settings?.saveBotToken(tokenInput)
                                settings?.saveChatId(chatIdInput)
                                TelegramBotService.startPolling(context)
                                Toast.makeText(context, "Démarrage en cours...", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRunning) coralRed else teal600
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = "Power",
                            tint = white,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isRunning) "Désactiver" else "Activer", color = white, fontSize = 13.sp)
                    }
                }
            }
        }

        // --- CARD 2: ACTIVE TÉLÉCHARGEMENT ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = slate800),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.DownloadForOffline,
                            contentDescription = "Downloads",
                            tint = teal400,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Téléchargements Actifs",
                            color = white,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(slate700)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Total : $totalDownloads",
                            color = teal300,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (currentTitle.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = currentTitle,
                            color = white,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )

                        LinearProgressIndicator(
                            progress = { currentProgress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = teal400,
                            trackColor = slate700
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Speed, contentDescription = "Speed", tint = slate400, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = currentSpeed.ifEmpty { "0 Ko/s" },
                                    color = slate300,
                                    fontSize = 12.sp
                                )
                            }
                            Text(
                                text = "$currentProgress%",
                                color = teal300,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Aucun téléchargement en cours",
                            color = slate400,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        // --- CARD 3: STORAGE DETAILS ---
        storageInfo?.let { space ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = slate800),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = "Storage",
                            tint = teal400,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Espace Disque",
                            color = white,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    val freeGB = space.freeMegaBytes / 1024f
                    val totalGB = space.totalMegaBytes / 1024f
                    val usedGB = totalGB - freeGB
                    val usedPercent = if (totalGB > 0) (usedGB / totalGB) else 0f

                    Text(
                        text = "Téléchargé sous: Download/YT_DLP_Bot",
                        color = slate400,
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    LinearProgressIndicator(
                        progress = { usedPercent },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (space.isWarningNeeded) coralRed else teal400,
                        trackColor = slate700
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = String.format("Espace Libre: %.2f Go", freeGB),
                            color = slate300,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = String.format("Total: %.2f Go", totalGB),
                            color = slate400,
                            fontSize = 12.sp
                        )
                    }

                    if (space.isWarningNeeded) {
                        Text(
                            text = "⚠️ Espace inférieur à 500 Mo ! Pensez à faire du tri.",
                            color = coralRed,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        // --- CARD 4: FILE MANAGER ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = slate800),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = "Folder",
                            tint = teal400,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Fichiers Local (${fileList.size})",
                            color = white,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (fileList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Aucun fichier téléchargé sur cet appareil",
                            color = slate400,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        fileList.take(6).forEach { file ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(slate700.copy(alpha = 0.5f))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = file.name,
                                        color = white,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = file.displaySize,
                                            color = teal300,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "•",
                                            color = slate400,
                                            fontSize = 11.sp
                                        )
                                        Text(
                                            text = file.displayDate,
                                            color = slate400,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = {
                                        val deleted = StorageHelper.deleteFileByName(context, file.name)
                                        if (deleted) {
                                            Toast.makeText(context, "Fichier supprimé du disque !", Toast.LENGTH_SHORT).show()
                                            fileList = StorageHelper.listFiles(context)
                                        } else {
                                            Toast.makeText(context, "Échec de suppression !", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete file",
                                        tint = coralRed.copy(alpha = 0.85f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        if (fileList.size > 6) {
                            Text(
                                text = "+ ${fileList.size - 6} autres fichiers présents. Consultez-les par votre Bot Telegram avec /list_files.",
                                color = slate400,
                                fontSize = 11.sp,
                                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // --- CARD 5: REAL-TIME CONSOLE LOGS ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = slate800),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.List,
                            contentDescription = "Logs",
                            tint = teal400,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Console d'Événements",
                            color = white,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "Vider",
                        color = teal300,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable {
                                TelegramBotService.logs.clear()
                                Toast.makeText(context, "Console réinitialisée", Toast.LENGTH_SHORT).show()
                            }
                            .padding(4.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
                        .padding(8.dp)
                ) {
                    if (logList.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Aucun log d'événement généré.",
                                color = slate700,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    } else {
                        LazyColumn(
                            reverseLayout = false,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(logList) { log ->
                                Text(
                                    text = log,
                                    color = Color(0xFF4AF626), // Classic green terminal text color
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
    }
}
