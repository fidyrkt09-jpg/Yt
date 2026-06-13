package com.example.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response as NpResponse
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.io.File
import java.util.concurrent.TimeUnit

// ─── Downloader OkHttp requis par NewPipeExtractor ───────────────────────────
class OkHttpDownloader private constructor(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: NpRequest): NpResponse {
        val builder = Request.Builder().url(request.url())
        request.headers().forEach { (key, values) ->
            values.forEach { builder.addHeader(key, it) }
        }
        val body = request.dataToSend()?.let {
            it.toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())
        }
        if (body != null) builder.post(body) else builder.get()

        client.newCall(builder.build()).execute().use { resp ->
            val responseHeaders = resp.headers.toMultimap()
            val responseBody = resp.body?.string() ?: ""
            return NpResponse(
                resp.code,
                resp.message,
                responseHeaders,
                responseBody,
                resp.request.url.toString()
            )
        }
    }

    companion object {
        fun getInstance(client: OkHttpClient): OkHttpDownloader = OkHttpDownloader(client)
    }
}
// ─────────────────────────────────────────────────────────────────────────────

// Représente un stream sélectionnable proposé à l'utilisateur dans Telegram
data class StreamOption(
    val label: String,       // texte affiché
    val url: String,         // URL directe du stream
    val ext: String,         // extension fichier (mp4, webm, m4a…)
    val sizeBytes: Long,     // -1 si inconnue
)

object TelegramBotService {
    private const val TAG = "TelegramBotService"

    var settings: BotSettingsManager? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null

    // ── UI states ────────────────────────────────────────────────────────────
    val logs                 = mutableStateListOf<String>()
    val isRunning            = mutableStateOf(false)
    val botNameState         = mutableStateOf("Inconnu")
    val totalDownloadsCount  = mutableStateOf(0)
    val downloadProgress     = mutableStateOf(0)
    val downloadSpeed        = mutableStateOf("")
    val activeDownloadTitle  = mutableStateOf("")

    // ── Conversation state ───────────────────────────────────────────────────
    private var lastUpdateId      = 0
    private val pendingOptions    = mutableMapOf<String, List<StreamOption>>()  // chatId → options
    private val customFilenames   = mutableMapOf<String, String>()              // chatId → nom custom

    // ── Init ─────────────────────────────────────────────────────────────────
    fun initialize(context: Context) {
        settings = BotSettingsManager(context)
        // Init NewPipeExtractor avec notre downloader OkHttp
        NewPipe.init(
            OkHttpDownloader.getInstance(client),
            Localization.DEFAULT,
            ContentCountry.DEFAULT
        )
        addLog("Application initialisée (NewPipeExtractor v0.26.2).")
        if (settings?.isPollingActive?.value == true) startPolling(context)
    }

    // ── Polling start / stop ─────────────────────────────────────────────────
    fun startPolling(context: Context) {
        val token = settings?.botToken?.value ?: ""
        val chat  = settings?.chatId?.value  ?: ""
        if (token.isEmpty() || chat.isEmpty()) { addLog("Token ou Chat ID manquant !"); return }
        if (isRunning.value) return

        settings?.savePollingActive(true)
        isRunning.value = true
        addLog("Démarrage du contrôle à distance...")

        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        pollingJob = scope.launch {
            if (!verifyBot(token)) {
                withContext(Dispatchers.Main) { isRunning.value = false }
                settings?.savePollingActive(false)
                addLog("Token invalide ou pas de connexion.")
                return@launch
            }
            sendTelegramMessage(token, chat,
                "🤖 *Bot en ligne !* Envoyez `/help` pour voir les commandes.")
            while (isActive && isRunning.value) {
                try { pollUpdates(context, token, chat) } catch (e: Exception) {
                    Log.e(TAG, "pollUpdates error: ${e.message}"); delay(5000)
                }
                delay(1000)
            }
        }
    }

    fun stopPolling(context: Context) {
        isRunning.value = false
        settings?.savePollingActive(false)
        pollingJob?.cancel(); scope.cancel()
        addLog("Contrôle arrêté.")
        botNameState.value = "Inconnu"
    }

    // ── Helpers log / verifyBot ──────────────────────────────────────────────
    private fun addLog(message: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        Handler(Looper.getMainLooper()).post {
            logs.add(0, "[$ts] $message")
            if (logs.size > 100) logs.removeAt(logs.lastIndex)
        }
    }

    private suspend fun verifyBot(token: String): Boolean {
        val req = Request.Builder()
            .url("https://api.telegram.org/bot$token/getMe").build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val json = JSONObject(resp.body?.string() ?: "")
                val result = json.getJSONObject("result")
                val username = result.optString("username", "")
                val firstName = result.getString("first_name")
                withContext(Dispatchers.Main) { botNameState.value = "@$username ($firstName)" }
                addLog("Bot connecté : @$username")
                true
            }
        } catch (e: Exception) { Log.e(TAG, "verifyBot: ${e.message}"); false }
    }

    // ── Polling loop ─────────────────────────────────────────────────────────
    private suspend fun pollUpdates(context: Context, token: String, chatId: String) {
        val req = Request.Builder()
            .url("https://api.telegram.org/bot$token/getUpdates?offset=$lastUpdateId&timeout=20")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return
            val json = JSONObject(resp.body?.string() ?: "")
            if (!json.getBoolean("ok")) return
            val updates = json.getJSONArray("result")
            for (i in 0 until updates.length()) {
                val update   = updates.getJSONObject(i)
                lastUpdateId = update.getInt("update_id") + 1
                val message  = update.optJSONObject("message") ?: continue
                val incomingChatId = message.getJSONObject("chat").getLong("id").toString()
                val text = message.optString("text", "").trim()
                if (incomingChatId != chatId) {
                    sendTelegramMessage(token, incomingChatId,
                        "⚠️ *Accès refusé.* Vous n'êtes pas autorisé.")
                    continue
                }
                if (text.isNotEmpty()) {
                    addLog("Commande Telegram : $text")
                    handleCommand(context, token, chatId, text)
                }
            }
        }
    }

    // ── Gestion des commandes ────────────────────────────────────────────────
    private suspend fun handleCommand(
        context: Context, token: String, chatId: String, text: String
    ) {
        // /help ou /start
        if (text == "/help" || text == "/start") {
            sendTelegramMessage(token, chatId,
                "💻 *Commandes disponibles :*\n\n" +
                "🔹 `/yt-dlp \"lien\"` — Analyse + propose les formats\n" +
                "🔹 `/internet` — État de la connexion\n" +
                "🔹 `/storage` — Espace disque\n" +
                "🔹 `/list_files` — Fichiers téléchargés\n" +
                "🔹 `/delete_file \"nom\"` ou `/delete_file 2` — Supprimer un fichier\n\n" +
                "💡 _Après l'analyse, répondez avec le numéro du format voulu._")
            return
        }

        // /internet
        if (text == "/internet") {
            val status = NetworkHelper.getNetworkStatus(context)
            val dot = if (status.isConnected) "🟢" else "🔴"
            sendTelegramMessage(token, chatId,
                "📶 *État réseau :*\n\n" +
                "$dot *Internet* : ${if (status.isConnected) "Connecté" else "Déconnecté"}\n" +
                "🌐 *Type* : ${status.typeLabel}\n" +
                "⚡ *Vitesse estimée* : ${status.speedLabel}\n" +
                "🔬 *Signal* : ${status.signalStrengthRating}")
            return
        }

        // /storage
        if (text == "/storage") {
            val space = StorageHelper.getStorageDetails(context)
            val warn = if (space.isWarningNeeded) "\n⚠️ *Moins de 500 Mo libres !*" else ""
            sendTelegramMessage(token, chatId,
                "💾 *Stockage :*\n\n" +
                "📂 `Download/YT_DLP_Bot`\n" +
                "✅ Libre : ${formatBytes(space.availableBytes)}\n" +
                "📊 Total : ${formatBytes(space.totalBytes)}$warn")
            return
        }

        // /list_files
        if (text == "/list_files") {
            val files = StorageHelper.listFiles(context)
            if (files.isEmpty()) {
                sendTelegramMessage(token, chatId, "📁 Dossier vide."); return
            }
            val sb = StringBuilder("📁 *Fichiers (${files.size}) :*\n\n")
            files.forEach { f ->
                sb.append("📍 *${f.index}.* `${f.name}`\n")
                sb.append("     ${f.format} | ${f.displaySize} | ${f.displayDate}\n\n")
            }
            sendTelegramMessage(token, chatId, sb.toString())
            return
        }

        // /delete_file
        if (text.startsWith("/delete_file")) {
            val arg = text.removePrefix("/delete_file").trim()
            if (arg.isEmpty()) {
                sendTelegramMessage(token, chatId,
                    "⚠️ Usage : `/delete_file \"nom.mp4\"` ou `/delete_file 2`")
                return
            }
            val idx = arg.toIntOrNull()
            if (idx != null) {
                val (ok, name) = StorageHelper.deleteFileByIndex(context, idx)
                sendTelegramMessage(token, chatId,
                    if (ok) "🗑️ Supprimé : `$name`" else "❌ Échec : $name")
            } else {
                val clean = arg.removeSurrounding("\"").removeSurrounding("'")
                val ok = StorageHelper.deleteFileByName(context, clean)
                sendTelegramMessage(token, chatId,
                    if (ok) "🗑️ Supprimé : `$clean`" else "❌ Fichier `$clean` introuvable.")
            }
            return
        }

        // /yt-dlp "lien" ["nom_custom"]
        if (text.startsWith("/yt-dlp")) {
            val raw = text.removePrefix("/yt-dlp").trim()
            if (raw.isEmpty()) {
                sendTelegramMessage(token, chatId,
                    "⚠️ Usage : `/yt-dlp https://youtu.be/xxxxx`")
                return
            }
            // Parse arguments entre guillemets ou espaces
            val pattern = java.util.regex.Pattern.compile("\"([^\"]*)\"|'([^']*)'|(\\S+)")
            val matcher = pattern.matcher(raw)
            val args = mutableListOf<String>()
            while (matcher.find()) {
                args.add(matcher.group(1) ?: matcher.group(2) ?: matcher.group(3) ?: "")
            }
            val link = args.getOrElse(0) { "" }
            val customName = args.getOrElse(1) { "" }

            if (link.isEmpty()) {
                sendTelegramMessage(token, chatId, "❌ Lien manquant."); return
            }
            if (customName.isNotEmpty()) customFilenames[chatId] = customName

            addLog("Analyse NewPipe : $link")
            sendTelegramMessage(token, chatId,
                "🔍 *Analyse en cours...* (NewPipeExtractor)")
            scope.launch { fetchAndProposeFormats(token, chatId, link) }
            return
        }

        // Sélection du numéro de format
        val num = text.trim().toIntOrNull()
        if (num != null) {
            val options = pendingOptions[chatId]
            if (options == null) {
                sendTelegramMessage(token, chatId,
                    "⚠️ Aucune analyse en cours. Lancez `/yt-dlp \"lien\"` d'abord.")
                return
            }
            if (num < 1 || num > options.size) {
                sendTelegramMessage(token, chatId,
                    "❌ Numéro invalide. Choisissez entre 1 et ${options.size}.")
                return
            }
            val chosen = options[num - 1]
            val name = customFilenames.remove(chatId) ?: ""
            pendingOptions.remove(chatId)
            scope.launch { triggerDownload(context, token, chatId, chosen, name) }
            return
        }

        // Texte libre = nom de fichier custom si une analyse est en attente
        if (pendingOptions.containsKey(chatId) && !text.startsWith("/")) {
            val parsed = text.removeSurrounding("\"").removeSurrounding("'").trim()
            if (parsed.lowercase() == "ok" || parsed.lowercase() == "default") {
                customFilenames.remove(chatId)
                sendTelegramMessage(token, chatId,
                    "✅ Nom par défaut conservé. Choisissez maintenant le numéro de format.")
            } else {
                customFilenames[chatId] = parsed
                sendTelegramMessage(token, chatId,
                    "📝 Nom configuré : `$parsed`. Choisissez maintenant le numéro.")
            }
            return
        }

        sendTelegramMessage(token, chatId,
            "❓ Commande inconnue. Tapez `/help`.")
    }

    // ── Extraction NewPipeExtractor ──────────────────────────────────────────
    private suspend fun fetchAndProposeFormats(
        token: String, chatId: String, url: String
    ) {
        try {
            // StreamInfo.getInfo() fait tout : page scraping + déchiffrement JS (Rhino)
            val info: StreamInfo = withContext(Dispatchers.IO) {
                val extractor = ServiceList.YouTube.getStreamExtractor(url)
                extractor.fetchPage()
                StreamInfo.getInfo(extractor)
            }

            val title    = info.name
            val uploader = info.uploaderName
            val duration = info.duration  // secondes

            val options = mutableListOf<StreamOption>()
            val sb = java.lang.StringBuilder()
            sb.append("🎬 *$title*\n")
            sb.append("👤 $uploader | ⏱️ ${formatDuration(duration)}\n\n")

            if (customFilenames.containsKey(chatId)) {
                sb.append("📝 Nom custom : `${customFilenames[chatId]}`\n\n")
            } else {
                sb.append("💡 _Répondez avec un nom pour renommer le fichier, ou 'ok' pour garder le titre._\n\n")
            }

            sb.append("📥 *Choisissez un format (répondez avec le numéro) :*\n\n")

            // ── Streams vidéo+audio (muxed) ───────────────────────────────
            val muxed: List<VideoStream> = info.videoStreams
                .filter { !it.isVideoOnly }
                .sortedByDescending { it.height }
            if (muxed.isNotEmpty()) {
                sb.append("🎥 *Vidéo + Audio :*\n")
                muxed.take(5).forEach { vs ->
                    val size = vs.itagItem?.contentLength?.let { formatBytes(it) } ?: "?"
                    val label = "${vs.resolution} (${vs.format?.name ?: vs.format?.suffix ?: "?"}) — $size"
                    options.add(StreamOption(label, vs.content, vs.format?.suffix ?: "mp4", vs.itagItem?.contentLength ?: -1L))
                    sb.append("  *${options.size}.* $label\n")
                }
            }

            // ── Streams vidéo uniquement (HD sans audio) ─────────────────
            val videoOnly: List<VideoStream> = info.videoOnlyStreams
                .sortedByDescending { it.height }
            if (videoOnly.isNotEmpty()) {
                sb.append("\n🎞️ *Vidéo seulement (sans audio) :*\n")
                videoOnly.take(4).forEach { vs ->
                    val size = vs.itagItem?.contentLength?.let { formatBytes(it) } ?: "?"
                    val label = "${vs.resolution} video-only (${vs.format?.name ?: vs.format?.suffix ?: "?"}) — $size"
                    options.add(StreamOption(label, vs.content, vs.format?.suffix ?: "mp4", vs.itagItem?.contentLength ?: -1L))
                    sb.append("  *${options.size}.* $label\n")
                }
            }

            // ── Streams audio uniquement ──────────────────────────────────
            val audioOnly: List<AudioStream> = info.audioStreams
                .sortedByDescending { it.averageBitrate }
            if (audioOnly.isNotEmpty()) {
                sb.append("\n🔊 *Audio seulement :*\n")
                audioOnly.take(4).forEach { aus ->
                    val size = aus.itagItem?.contentLength?.let { formatBytes(it) } ?: "?"
                    val br   = if (aus.averageBitrate > 0) "${aus.averageBitrate}kbps" else "?"
                    val label = "Audio $br (${aus.format?.name ?: aus.format?.suffix ?: "?"}) — $size"
                    options.add(StreamOption(label, aus.content, aus.format?.suffix ?: "m4a", aus.itagItem?.contentLength ?: -1L))
                    sb.append("  *${options.size}.* $label\n")
                }
            }

            if (options.isEmpty()) {
                sendTelegramMessage(token, chatId,
                    "❌ Aucun format disponible pour ce lien.")
                return
            }

            pendingOptions[chatId] = options
            sendTelegramMessage(token, chatId, sb.toString())
            addLog("${options.size} formats proposés pour '$title'")

        } catch (e: Exception) {
            Log.e(TAG, "fetchAndProposeFormats error", e)
            sendTelegramMessage(token, chatId,
                "❌ Erreur d'extraction : ${e.message}")
            addLog("Erreur extraction : ${e.message}")
        }
    }

    // ── Téléchargement ───────────────────────────────────────────────────────
    private var dlStartTime = System.currentTimeMillis()

    private suspend fun triggerDownload(
        context: Context, token: String, chatId: String,
        option: StreamOption, customName: String
    ) {
        try {
            val ext = option.ext.trimStart('.')
            val baseName = (if (customName.isNotEmpty()) customName else option.label)
                .replace("[\\\\/:*?\"<>|]".toRegex(), "_")
            val filename = if (baseName.endsWith(".$ext", true)) baseName else "$baseName.$ext"

            // Vérif espace
            val storage = StorageHelper.getStorageDetails(context)
            if (option.sizeBytes > 0 && option.sizeBytes > storage.availableBytes) {
                sendTelegramMessage(token, chatId,
                    "⚠️ *Espace insuffisant !*\n" +
                    "Requis : ${formatBytes(option.sizeBytes)}\n" +
                    "Disponible : ${formatBytes(storage.availableBytes)}")
                return
            }

            sendTelegramMessage(token, chatId,
                "🚀 *Téléchargement démarré :* `$filename`")
            addLog("Téléchargement : $filename")

            withContext(Dispatchers.Main) {
                totalDownloadsCount.value++
                activeDownloadTitle.value = filename
                downloadProgress.value = 0
                downloadSpeed.value = "0 Ko/s"
            }

            dlStartTime = System.currentTimeMillis()
            val outDir = StorageHelper.getDownloadDirectory(context)

            withContext(Dispatchers.IO) {
                val okReq = Request.Builder()
                    .url(option.url)
                    .addHeader("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0.0.0 Safari/537.36")
                    .addHeader("Accept", "*/*")
                    .build()

                val targetFile = File(outDir, filename)
                if (targetFile.exists()) targetFile.delete()

                client.newCall(okReq).execute().use { resp ->
                    if (!resp.isSuccessful)
                        throw Exception("HTTP ${resp.code} ${resp.message}")

                    val body = resp.body ?: throw Exception("Corps de réponse vide.")
                    val totalBytes = if (option.sizeBytes > 0) option.sizeBytes
                                     else body.contentLength().let { if (it > 0) it else 1L }

                    var bytesRead = 0L
                    var lastSentPct = 0
                    var lastSentTime = System.currentTimeMillis()
                    val buffer = ByteArray(64 * 1024)

                    body.byteStream().use { input ->
                        targetFile.outputStream().use { output ->
                            var read = input.read(buffer)
                            while (read != -1) {
                                output.write(buffer, 0, read)
                                bytesRead += read
                                val pct = ((bytesRead.toDouble() / totalBytes) * 100)
                                    .toInt().coerceIn(0, 100)
                                scope.launch(Dispatchers.Main) {
                                    downloadProgress.value = pct
                                }
                                val now = System.currentTimeMillis()
                                if (pct - lastSentPct >= 10 ||
                                    (now - lastSentTime >= 4000 && pct != lastSentPct)) {
                                    lastSentPct = pct
                                    lastSentTime = now
                                    val speed = calcSpeed(totalBytes, pct, now)
                                    scope.launch(Dispatchers.Main) {
                                        downloadSpeed.value = speed
                                    }
                                    scope.launch {
                                        sendTelegramMessage(token, chatId,
                                            "📥 *$pct%* | `$speed` — `$filename`")
                                    }
                                }
                                read = input.read(buffer)
                            }
                        }
                    }

                    scope.launch {
                        sendTelegramMessage(token, chatId,
                            "🎉 *Terminé !*\n" +
                            "📁 `$filename`\n" +
                            "📦 ${formatBytes(targetFile.length())}\n" +
                            "📍 `${targetFile.absolutePath}`")
                        addLog("Terminé : $filename")
                        withContext(Dispatchers.Main) {
                            activeDownloadTitle.value = ""
                            downloadProgress.value = 100
                            downloadSpeed.value = "Terminé"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "triggerDownload error", e)
            sendTelegramMessage(token, chatId, "❌ Erreur : ${e.message}")
            addLog("Échec : ${e.message}")
            withContext(Dispatchers.Main) { activeDownloadTitle.value = "" }
        }
    }

    // ── Utilitaires ──────────────────────────────────────────────────────────
    private fun calcSpeed(totalBytes: Long, pct: Int, now: Long): String {
        if (pct <= 0 || totalBytes <= 0) return "calcul..."
        val downloaded = (totalBytes * pct / 100.0).toLong()
        val elapsed    = (now - dlStartTime + 1).toDouble() / 1000.0
        val bps        = (downloaded / elapsed).toLong()
        return when {
            bps >= 1024 * 1024 -> "%.2f Mo/s".format(bps / 1048576.0)
            bps >= 1024         -> "%.2f Ko/s".format(bps / 1024.0)
            else                -> "$bps o/s"
        }
    }

    private fun formatBytes(b: Long): String = when {
        b >= 1_073_741_824 -> "%.2f Go".format(b / 1_073_741_824.0)
        b >= 1_048_576     -> "%.2f Mo".format(b / 1_048_576.0)
        b >= 1_024         -> "%.2f Ko".format(b / 1_024.0)
        else               -> "$b o"
    }

    private fun formatDuration(sec: Long): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    suspend fun sendTelegramMessage(token: String, chatId: String, mdText: String) {
        val body = JSONObject().apply {
            put("chat_id", chatId)
            put("text", mdText)
            put("parse_mode", "Markdown")
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

        val req = Request.Builder()
            .url("https://api.telegram.org/bot$token/sendMessage")
            .post(body).build()
        try {
            withContext(Dispatchers.IO) {
                client.newCall(req).execute().use { /* consume */ }
            }
        } catch (e: Exception) { Log.e(TAG, "sendMessage: ${e.message}") }
    }

    // ── Helper extractVideoId (utilisé par les tests unitaires via réflexion) ──
    private fun extractVideoId(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        
        // 1. Try to find v=XXXX parameter (covers youtube.com/watch?v=ID, m.youtube.com/watch?v=ID, etc.)
        val vParamPattern = java.util.regex.Pattern.compile("[?&]v=([a-zA-Z0-9_-]{11})")
        val vParamMatcher = vParamPattern.matcher(trimmed)
        if (vParamMatcher.find()) {
            return vParamMatcher.group(1)
        }
        
        // 2. Try to find youtu.be/XXXX (covers youtu.be/ID)
        val shortLinkPattern = java.util.regex.Pattern.compile("youtu\\.be/([a-zA-Z0-9_-]{11})")
        val shortLinkMatcher = shortLinkPattern.matcher(trimmed)
        if (shortLinkMatcher.find()) {
            return shortLinkMatcher.group(1)
        }
        
        // 3. Try to find shorts/XXXX or embed/XXXX or v/XXXX or live/XXXX
        val pathPattern = java.util.regex.Pattern.compile("/(?:shorts|embed|v|live)/([a-zA-Z0-9_-]{11})")
        val pathMatcher = pathPattern.matcher(trimmed)
        if (pathMatcher.find()) {
            return pathMatcher.group(1)
        }
        
        // Fallback: If it's a raw 11-character alphanumeric string, return it
        if (trimmed.length == 11 && trimmed.matches("^[a-zA-Z0-9_-]{11}$".toRegex())) {
            return trimmed
        }
        
        return null
    }
}
