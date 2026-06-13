package com.example.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.github.kiulian.downloader.YoutubeDownloader
import com.github.kiulian.downloader.downloader.request.RequestVideoInfo
import com.github.kiulian.downloader.downloader.request.RequestVideoFileDownload
import com.github.kiulian.downloader.downloader.YoutubeProgressCallback
import com.github.kiulian.downloader.downloader.response.Response
import com.github.kiulian.downloader.model.videos.VideoInfo
import com.github.kiulian.downloader.model.videos.formats.Format
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object TelegramBotService {
    private const val TAG = "TelegramBotService"

    // Configuration / Manager references
    var settings: BotSettingsManager? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Coroutine Scope for background polling
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null

    // UI Observable Statuses
    val logs = mutableStateListOf<String>()
    val isRunning = mutableStateOf(false)
    val botNameState = mutableStateOf("Inconnu")
    val totalDownloadsCount = mutableStateOf(0)
    val downloadProgress = mutableStateOf(0)
    val downloadSpeed = mutableStateOf("")
    val activeDownloadTitle = mutableStateOf("")

    // In-Memory state for managing conversations
    private var lastUpdateId = 0
    private var pendingVideos = mutableMapOf<String, VideoInfo>() // chatid -> VideoInfo
    private var pendingFormats = mutableMapOf<String, List<Format>>() // chatid -> Map of formats
    private var customFilenames = mutableMapOf<String, String>() // chatid -> custom filename

    fun initialize(context: Context) {
        settings = BotSettingsManager(context)
        Log.i(TAG, "Initialized settings.")
        addLog("Application initialisée.")
        
        // Auto-start if it was active
        if (settings?.isPollingActive?.value == true) {
            startPolling(context)
        }
    }

    fun startPolling(context: Context) {
        val token = settings?.botToken?.value ?: ""
        val chat = settings?.chatId?.value ?: ""

        if (token.isEmpty() || chat.isEmpty()) {
            addLog("Erreur: Bot Token ou Chat ID manquant dans les réglages!")
            return
        }

        if (isRunning.value) return

        // Save active state
        settings?.savePollingActive(true)
        isRunning.value = true
        addLog("Démarrage du contrôle à distance...")

        // Refresh scope
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        pollingJob = scope.launch {
            // First, fetch bot profile info to verify connectivity
            val verified = verifyBot(token)
            if (!verified) {
                withContext(Dispatchers.Main) {
                    isRunning.value = false
                    settings?.savePollingActive(false)
                }
                addLog("Échec: Bot Token invalide ou pas de connexion internet.")
                return@launch
            }

            sendTelegramMessage(token, chat, "🤖 *Contrôle à distance activé!* Le bot est en ligne et à l'écoute sur votre appareil.")

            // Polling loop
            while (isActive && isRunning.value) {
                try {
                    pollUpdates(context, token, chat)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in polling loop: ${e.message}")
                    delay(5000) // Delay to avoid hammering on network failure
                }
                delay(1000) // Small interval between requests
            }
        }
    }

    fun stopPolling(context: Context) {
        isRunning.value = false
        settings?.savePollingActive(false)
        pollingJob?.cancel()
        scope.cancel()
        addLog("Contrôle à distance arrêté.")
        botNameState.value = "Inconnu"
    }

    private fun addLog(message: String) {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        val timestamp = sdf.format(java.util.Date())
        Handler(Looper.getMainLooper()).post {
            logs.add(0, "[$timestamp] $message")
            // Cap log capacity dynamically
            if (logs.size > 100) {
                logs.removeLast()
            }
        }
    }

    private suspend fun verifyBot(token: String): Boolean {
        val url = "https://api.telegram.org/bot$token/getMe"
        val request = Request.Builder().url(url).build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    val json = JSONObject(bodyStr)
                    val result = json.getJSONObject("result")
                    val isBot = result.getBoolean("is_bot")
                    val firstName = result.getString("first_name")
                    val username = result.optString("username", "")
                    withContext(Dispatchers.Main) {
                        botNameState.value = "@$username ($firstName)"
                    }
                    addLog("Bot connecté: @$username")
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "verifyBot failed", e)
            false
        }
    }

    private suspend fun pollUpdates(context: Context, token: String, chatId: String) {
        val url = "https://api.telegram.org/bot$token/getUpdates?offset=$lastUpdateId&timeout=20"
        val request = Request.Builder().url(url).build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return
                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val ok = json.getBoolean("ok")
                if (!ok) return

                val updates = json.getJSONArray("result")
                for (i in 0 until updates.length()) {
                    val update = updates.getJSONObject(i)
                    val updateId = update.getInt("update_id")
                    
                    // Increment offset to fetch next batch
                    lastUpdateId = updateId + 1

                    // Parse incoming message
                    val message = update.optJSONObject("message") ?: continue
                    val chatObj = message.getJSONObject("chat")
                    val incomingChatId = chatObj.getLong("id").toString()
                    val text = message.optString("text", "").trim()

                    // Match userChatId for security check!
                    if (incomingChatId != chatId) {
                        Log.w(TAG, "Unauthorized message from Chat ID: $incomingChatId. Authorized is: $chatId")
                        sendTelegramMessage(token, incomingChatId, "⚠️ *Accès Refusé.* Vous n'êtes pas autorisé à contrôler cette application.")
                        continue
                    }

                    if (text.isNotEmpty()) {
                        addLog("Telegram Command: $text")
                        handleCommand(context, token, chatId, text)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "pollUpdates query failed: ${e.message}")
        }
    }

    private suspend fun handleCommand(context: Context, token: String, chatId: String, text: String) {
        // Quick help route
        if (text == "/help" || text == "/start") {
            val helpMsg = "💻 *Commandes disponibles YT-DLP controller :*\n\n" +
                    "🔹 `/yt-dlp \"lien_youtube\"` : Analyse un lien et propose les formats\n" +
                    "🔹 `/internet` : Vérifie l'état détaillé de la connexion de l'appareil\n" +
                    "🔹 `/storage` : Affiche l'espace libre / total du dossier Download\n" +
                    "🔹 `/list_files` : Liste les fichiers téléchargés présents sur l'appareil\n" +
                    "🔹 `/delete_file \"nom\"` : Supprime un fichier par son nom ou numéro de ligne\n\n" +
                    "💡 _Renseignez simplement un numéro proposé après l'analyse d'un lien pour lancer un téléchargement._"
            sendTelegramMessage(token, chatId, helpMsg)
            return
        }

        // 1. COMMAND: /internet
        if (text == "/internet") {
            val status = NetworkHelper.getNetworkStatus(context)
            val colorIndicator = if (status.isConnected) "🟢" else "🔴"
            val response = "📶 *État de la Connexion Réseau :*\n\n" +
                    "$colorIndicator *Internet* : ${if (status.isConnected) "Connecté" else "Déconnecté"}\n" +
                    "🌐 *Type* : ${status.typeLabel}\n" +
                    "⚡ *Vitesse estimée* : ${status.speedLabel}\n" +
                    "🔬 *Qualité du signal* : ${status.signalStrengthRating}"
            sendTelegramMessage(token, chatId, response)
            return
        }

        // 2. COMMAND: /storage
        if (text == "/storage") {
            val space = StorageHelper.getStorageDetails(context)
            val warning = if (space.isWarningNeeded) "\n⚠️ *Attention: Moins de 500 Mo libres!*" else ""
            val fullInfo = if (space.isFull) "\n❌ *Le stockage est plein!*" else ""
            
            val response = "💾 *État du Stockage (Destination) :*\n\n" +
                    "📂 *Dossier* : `Download/YT_DLP_Bot`\n" +
                    "✅ *Espace Libre* : ${formatBytes(space.availableBytes)} (${space.freeMegaBytes} Mo)\n" +
                    "📊 *Espace Total* : ${formatBytes(space.totalBytes)} (${space.totalMegaBytes} Mo)\n" +
                    warning + fullInfo
            sendTelegramMessage(token, chatId, response)
            return
        }

        // 3. COMMAND: /list_files
        if (text == "/list_files") {
            val filesList = StorageHelper.listFiles(context)
            if (filesList.isEmpty()) {
                sendTelegramMessage(token, chatId, "📁 Le dossier de téléchargement est actuellement vide.")
                return
            }

            val sb = java.lang.StringBuilder()
            sb.append("📁 *Fichiers téléchargés (${filesList.size}) :*\n\n")
            
            for (file in filesList) {
                sb.append("📍 *${file.index}.* `${file.name}`\n")
                sb.append("     Format: *${file.format}* | Taille: *${file.displaySize}*\n")
                sb.append("     Date: *${file.displayDate}*\n\n")
            }
            sendTelegramMessage(token, chatId, sb.toString())
            return
        }

        // 4. COMMAND: /delete_file
        if (text.startsWith("/delete_file")) {
            val arg = text.removePrefix("/delete_file").trim()
            if (arg.isEmpty()) {
                sendTelegramMessage(token, chatId, "⚠️ Spécifiez un nom de fichier existant (ex : `/delete_file \"video.mp4\"`) ou son numéro de ligne (ex : `/delete_file 2`).")
                return
            }

            // Check if it's an integer index or literal name
            val isIndex = arg.toIntOrNull()
            if (isIndex != null) {
                val result = StorageHelper.deleteFileByIndex(context, isIndex)
                if (result.first) {
                    sendTelegramMessage(token, chatId, "🗑️ *Fichier supprimé avec succès :* `${result.second}`")
                    addLog("Fichier #${isIndex} supprimé.")
                } else {
                    sendTelegramMessage(token, chatId, "❌ Suppr échec : ${result.second}")
                }
            } else {
                // Remove raw quotes if present
                val cleanedName = arg.removeSurrounding("\"").removeSurrounding("'")
                val deleted = StorageHelper.deleteFileByName(context, cleanedName)
                if (deleted) {
                    sendTelegramMessage(token, chatId, "🗑️ *Fichier supprimé avec succès :* `$cleanedName`")
                    addLog("Fichier '$cleanedName' supprimé.")
                } else {
                    sendTelegramMessage(token, chatId, "❌ Suppr échec : Fichier `$cleanedName` introuvable sur le disque.")
                }
            }
            return
        }

        // 5. COMMAND: /yt-dlp "link"
        if (text.startsWith("/yt-dlp")) {
            val rawParams = text.removePrefix("/yt-dlp").trim()
            if (rawParams.isEmpty()) {
                sendTelegramMessage(token, chatId, "⚠️ Format requis : `/yt-dlp http://lien-youtube` ou `/yt-dlp \"http://lien\" \"nom_fichier\"`")
                return
            }

            // Extract link and potential custom name
            var link = ""
            var customName = ""

            // Regex for grabbing quotes
            val pattern = java.util.regex.Pattern.compile("\"([^\"]*)\"|'([^']*)'|(\\S+)")
            val matcher = pattern.matcher(rawParams)
            val args = mutableListOf<String>()
            while (matcher.find()) {
                val group1 = matcher.group(1)
                val group2 = matcher.group(2)
                val group3 = matcher.group(3)
                args.add(group1 ?: group2 ?: group3 ?: "")
            }

            if (args.isNotEmpty()) {
                link = args[0]
                if (args.size > 1) {
                    customName = args[1]
                }
            } else {
                link = rawParams
            }

            if (link.isEmpty()) {
                sendTelegramMessage(token, chatId, "❌ Erreur: Lien YouTube manquant.")
                return
            }

            addLog("Analyse en cours : $link")
            sendTelegramMessage(token, chatId, "🔍 *Analyse des ressources disponibles...* Récupération des formats en cours...")

            scope.launch {
                fetchAndProposeFormats(context, token, chatId, link, customName)
            }
            return
        }

        // 6. RAW RESPONSE SELECTION OR CONVERSATON FLOW
        val cleanNumberStr = text.removePrefix("/select_format ").removePrefix("/select ").trim()
        val flowIndex = cleanNumberStr.toIntOrNull()
        if (flowIndex != null) {
            val formats = pendingFormats[chatId]
            val video = pendingVideos[chatId]
            if (formats == null || video == null) {
                sendTelegramMessage(token, chatId, "⚠️ Aucun téléchargement en cours de configuration. Lancez l'analyse d'une vidéo avec `/yt-dlp \"link\"` d'abord.")
                return
            }

            if (flowIndex < 1 || flowIndex > formats.size) {
                sendTelegramMessage(token, chatId, "❌ Choix invalide ($flowIndex). Entrez un numéro entre 1 et ${formats.size}.")
                return
            }

            val chosenFormat = formats[flowIndex - 1]
            val chosenCustomName = customFilenames[chatId] ?: ""

            // Clear configuration states
            pendingFormats.remove(chatId)
            pendingVideos.remove(chatId)
            customFilenames.remove(chatId)

            scope.launch {
                triggerDownload(context, token, chatId, video, chosenFormat, chosenCustomName)
            }
            return
        }

        // If the user replies with some text and we have video pending, but no number, maybe it's the filename!
        val activeVideo = pendingVideos[chatId]
        if (activeVideo != null && !text.startsWith("/")) {
            // Treat the message as custom filename!
            val parsedFilename = text.removeSurrounding("\"").removeSurrounding("'").trim()
            if (parsedFilename.lowercase() == "ok" || parsedFilename.lowercase() == "default") {
                customFilenames.remove(chatId)
                sendTelegramMessage(token, chatId, "✅ OK, conservation du titre par défaut.\n\nVeuillez maintenant choisir le format en répondant avec son numéro.")
            } else {
                customFilenames[chatId] = parsedFilename
                sendTelegramMessage(token, chatId, "📝 Titre personnalisé configuré : `$parsedFilename`.\n\nVeuillez maintenant choisir le format en répondant avec son numéro.")
            }
            return
        }

        sendTelegramMessage(token, chatId, "❓ Commande inconnue. Entrez `/help` pour lister les options.")
    }

    private suspend fun fetchAndProposeFormats(context: Context, token: String, chatId: String, url: String, initialCustomName: String) {
        try {
            // Extract Video ID
            val videoId = extractVideoId(url)
            if (videoId.isNullOrEmpty()) {
                sendTelegramMessage(token, chatId, "❌ Impossible d'extraire l'identifiant (VideoId) depuis ce lien YouTube.")
                return
            }

            val downloader = YoutubeDownloader()
            val request = RequestVideoInfo(videoId)
            
            // Running retrieve in IO dispatchers
            val response: Response<VideoInfo> = withContext(Dispatchers.IO) {
                downloader.getVideoInfo(request)
            }

            if (!response.ok()) {
                sendTelegramMessage(token, chatId, "❌ Échec de la récupération des métadonnées de la vidéo.")
                return
            }

            val videoInfo = response.data()
            val videoTitle = videoInfo.details().title()

            // Save conversation state
            pendingVideos[chatId] = videoInfo
            if (initialCustomName.isNotEmpty()) {
                customFilenames[chatId] = initialCustomName
            }

            // Grouping and sorting formats
            val allFormats = mutableListOf<Format>()
            
            // 1. Add video with audio (multiplexed muxes) - best compatibility
            val muxedFormats = videoInfo.videoWithAudioFormats() ?: emptyList()
            // 2. Add video only format
            val videoOnly = videoInfo.videoFormats() ?: emptyList()
            // 3. Add audio only format
            val audioOnly = videoInfo.audioFormats() ?: emptyList()

            val sb = StringBuilder()
            sb.append("🎬 *Vidéo* : `${videoTitle}`\n")
            sb.append("👤 *Chaîne* : ${videoInfo.details().author()}\n")
            sb.append("⏱️ *Durée* : ${videoInfo.details().lengthSeconds()} sec\n\n")

            if (initialCustomName.isNotEmpty()) {
                sb.append("📝 *Nom de fichier personnalisé* : `$initialCustomName`\n\n")
            } else {
                sb.append("💡 _Vous pouvez modifier le titre du fichier en répondant directement à ce message avec le texte de votre choix. Envoyez 'ok' sinon._\n\n")
            }

            sb.append("📥 *Choisissez un format* (Répondez simplement avec son numéro) :\n\n")

            var counter = 1
            
            sb.append("🎥 *Formats Vidéo (Muxed / Audio inclus)* :\n")
            if (muxedFormats.isEmpty()) {
                sb.append("  _Aucun format direct détecté_\n")
            } else {
                for (fmt in muxedFormats.take(5)) {
                    allFormats.add(fmt)
                    val sizeLabel = fmt.contentLength()?.let { formatBytes(it) } ?: "Inconnue"
                    sb.append("  *${counter}.* Video: ${fmt.videoQuality().name} (${fmt.extension().value()}) - ${sizeLabel}\n")
                    counter++
                }
            }

            sb.append("\n🔊 *Formats Audio uniquement (MP3/M4A/etc.)* :\n")
            if (audioOnly.isEmpty()) {
                sb.append("  _Aucun format audio direct détecté_\n")
            } else {
                for (fmt in audioOnly.take(5)) {
                    allFormats.add(fmt)
                    val sizeLabel = fmt.contentLength()?.let { formatBytes(it) } ?: "Inconnue"
                    sb.append("  *${counter}.* Audio: ${fmt.audioQuality().name} (${fmt.extension().value()}) - ${sizeLabel}\n")
                    counter++
                }
            }

            pendingFormats[chatId] = allFormats

            sendTelegramMessage(token, chatId, sb.toString())
            addLog("Mises à disposition de ${allFormats.size} formats pour '${videoTitle}'")

        } catch (e: Exception) {
            Log.e(TAG, "Failed fetchAndProposeFormats", e)
            sendTelegramMessage(token, chatId, "❌ Échec critique lors de l'analyse : ${e.message}")
        }
    }

    private suspend fun triggerDownload(context: Context, token: String, chatId: String, video: VideoInfo, format: Format, customName: String) {
        try {
            val title = video.details().title()
            val formatExt = format.extension().value()
            
            // Construct Filename
            val fileTitle = if (customName.isNotEmpty()) {
                customName
            } else {
                title
            }.replace("[\\\\/:*?\"<>|]".toRegex(), "_") // Sanitization for disk writing

            val cleanFilename = if (fileTitle.endsWith(".$formatExt", ignoreCase = true)) {
                fileTitle
            } else {
                "$fileTitle.$formatExt"
            }

            val storage = StorageHelper.getStorageDetails(context)
            val totalSizeInBytes = format.contentLength() ?: 0L

            // ⚠️ STORAGE CHECK
            if (totalSizeInBytes > 0 && totalSizeInBytes > storage.availableBytes) {
                val errorMsg = "⚠️ *Téléchargement Annulé - Espace Insuffisant!*\n\n" +
                        "📉 *Requis* : ${formatBytes(totalSizeInBytes)}\n" +
                        "📊 *Disponible* : ${formatBytes(storage.availableBytes)}\n\n" +
                        "💡 _Vous pouvez libérer de l'espace en supprimant des fichiers avec la commande /delete_file_."
                sendTelegramMessage(token, chatId, errorMsg)
                addLog("Espace insuffisant pour $cleanFilename (Requis: ${formatBytes(totalSizeInBytes)})")
                return
            }

            sendTelegramMessage(token, chatId, "🚀 *Préparation...* Lancement du téléchargement de `$cleanFilename` (${formatBytes(totalSizeInBytes)})...")
            addLog("Téléchargement : $cleanFilename")

            // Setup UI indicators
            withContext(Dispatchers.Main) {
                totalDownloadsCount.value++
                activeDownloadTitle.value = cleanFilename
                downloadProgress.value = 0
                downloadSpeed.value = "0 Ko/s"
            }

            val outDir = StorageHelper.getDownloadDirectory(context)
            val downloader = YoutubeDownloader()

            var lastProgressTime = System.currentTimeMillis()
            var lastProgressSentPct = 0

            val request = RequestVideoFileDownload(format)
                .saveTo(outDir)
                .renameTo(fileTitle)
                .overwriteIfExists(true)
                .callback(object : YoutubeProgressCallback<File> {
                    override fun onDownloading(progress: Int) {
                        scope.launch(Dispatchers.Main) {
                            downloadProgress.value = progress
                        }

                        val now = System.currentTimeMillis()
                        // Throttled notification updates to avoid hitting Telegram's rate-limits!
                        // Updates Telegram every 10% progress increment or every 4 seconds
                        if (progress - lastProgressSentPct >= 10 || (now - lastProgressTime >= 4000 && progress != lastProgressSentPct)) {
                            lastProgressSentPct = progress
                            lastProgressTime = now
                            
                            val speed = calculateSpeedEstimate(totalSizeInBytes, progress, now)
                            scope.launch(Dispatchers.Main) {
                                downloadSpeed.value = speed
                            }

                            scope.launch {
                                sendTelegramMessage(token, chatId, "📥 *Progression* : `$progress%` | Vitesse : `$speed` de `$cleanFilename`")
                            }
                        }
                    }

                    override fun onFinished(file: File) {
                        // Rename standard library generated file to the custom/sanitized file
                        val targetFile = File(outDir, cleanFilename)
                        if (file.name != cleanFilename) {
                            file.renameTo(targetFile)
                        }

                        scope.launch {
                            val successMsg = "🎉 *Téléchargement Réussi!*\n\n" +
                                    "📁 *Fichier* : `${cleanFilename}`\n" +
                                    "📦 *Taille* : ${formatBytes(targetFile.length())}\n" +
                                    "📍 *Chemin* : `${targetFile.absolutePath}`"
                            sendTelegramMessage(token, chatId, successMsg)
                            addLog("Terminé : $cleanFilename")
                            
                            withContext(Dispatchers.Main) {
                                activeDownloadTitle.value = ""
                                downloadProgress.value = 100
                                downloadSpeed.value = "Terminé"
                            }
                        }
                    }

                    override fun onError(throwable: Throwable) {
                        scope.launch {
                            sendTelegramMessage(token, chatId, "❌ *Erreur lors du téléchargement* : ${throwable.message}")
                            addLog("Échec téléchargement : ${throwable.message}")
                            withContext(Dispatchers.Main) {
                                activeDownloadTitle.value = ""
                            }
                        }
                    }
                })

            withContext(Dispatchers.IO) {
                downloader.downloadVideoFile(request)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Download trigger failed", e)
            sendTelegramMessage(token, chatId, "❌ Erreur critique lors de l'envoi au moteur : ${e.message}")
            withContext(Dispatchers.Main) {
                activeDownloadTitle.value = ""
            }
        }
    }

    private var initialDownloadStartTime = System.currentTimeMillis()
    private fun calculateSpeedEstimate(totalBytes: Long, progress: Int, now: Long): String {
        if (progress <= 0 || totalBytes <= 0) return "En calcul..."
        val ratio = progress.toDouble() / 100.0
        val downloadedBytes = (totalBytes * ratio).toLong()
        val elapsedMs = now - initialDownloadStartTime + 1 // avoid div by 0
        val bytesPerSec = (downloadedBytes.toDouble() / (elapsedMs.toDouble() / 1000.0)).toLong()
        
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format("%.2f Mo/s", bytesPerSec.toDouble() / (1024 * 1024))
            bytesPerSec >= 1024 -> String.format("%.2f Ko/s", bytesPerSec.toDouble() / 1024)
            else -> "$bytesPerSec Octets/s"
        }
    }

    suspend fun sendTelegramMessage(token: String, chatId: String, mdText: String) {
        val url = "https://api.telegram.org/bot$token/sendMessage"
        
        val json = JSONObject()
        json.put("chat_id", chatId)
        json.put("text", mdText)
        json.put("parse_mode", "Markdown")

        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val requestBody = json.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        try {
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Telegram sendMessage call failed: ${response.body?.string()}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage error", e)
        }
    }

    private fun extractVideoId(url: String): String? {
        val pattern = "^(?:https?:\\/\\/)?(?:www\\.)?(?:youtube\\.com\\/(?:[^\\/\\n\\s]+\\/\\S+\\/|(?:v|e(?:mbed)?)\\/|\\S*?[?&]v=)|youtu\\.be\\/)([a-zA-Z0-9_-]{11})"
        val compiledPattern = java.util.regex.Pattern.compile(pattern)
        val matcher = compiledPattern.matcher(url)
        return if (matcher.find()) {
            matcher.group(1)
        } else {
            null
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format("%.2f Go", bytes.toDouble() / (1024 * 1024 * 1024))
            bytes >= 1024 * 1024 -> String.format("%.2f Mo", bytes.toDouble() / (1024 * 1024))
            bytes >= 1024 -> String.format("%.2f Ko", bytes.toDouble() / 1024)
            else -> "$bytes O"
        }
    }
}
