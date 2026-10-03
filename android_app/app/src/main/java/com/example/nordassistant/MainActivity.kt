package com.example.nordassistant

import android.Manifest
import android.app.SearchManager
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.nordassistant.security.BossSecurityManager
import com.example.nordassistant.theme.NordAssistantTheme
import com.example.nordassistant.ui.AssistantState
import com.example.nordassistant.ui.GideonFaceView
import kotlinx.coroutines.*
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private lateinit var audioManager: AudioManager
    private lateinit var bossSecurity: BossSecurityManager
    
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    
    private var isListening by mutableStateOf(false)
    private var assistantState by mutableStateOf(AssistantState.IDLE)
    private var transcript by mutableStateOf("Ready for voice command...")
    private var securityStatus by mutableStateOf("DEVIL // BOSS SECURED")
    
    private val requiredPermissions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_MEDIA_AUDIO
        )
    } else {
        arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.all { it.value }) {
            setupAssistant()
            if (intent?.action == Intent.ACTION_VOICE_COMMAND) {
                startListening()
            }
        } else {
            transcript = "Permissions required."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(this, this)
        bossSecurity = BossSecurityManager.getInstance(this)
        securityStatus = bossSecurity.getSecurityStatus()

        // Initialize on-device LLM helper (copies model + loads it in background)
        scope.launch(Dispatchers.IO) {
            LlamaHelper.init(this@MainActivity)
        }
        
        setContent {
            NordAssistantTheme {
                GideonFaceView(
                    state = assistantState,
                    transcript = transcript,
                    securityStatus = securityStatus,
                    onAvatarClick = {
                        if (assistantState == AssistantState.IDLE) {
                            startListening()
                        }
                    }
                )
            }
        }
        
        permissionLauncher.launch(requiredPermissions)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US

            try {
                val availableVoices = tts.voices
                if (!availableVoices.isNullOrEmpty()) {
                    // Log all available voices for debugging
                    availableVoices.filter { it.locale.language == "en" }.forEach { v ->
                        Log.i("MainActivity", "Available voice: ${v.name}, features: ${v.features}")
                    }

                    // Prioritize energetic, bright, punchy voices (tpd is the lively Google Assistant voice, iob is upbeat)
                    val energeticVoice = availableVoices.find { v ->
                        v.locale.language == "en" &&
                        !v.isNetworkConnectionRequired &&
                        (v.name.contains("tpd", ignoreCase = true) || // Lively, bright Google Assistant
                         v.name.contains("iob", ignoreCase = true) || // Upbeat female
                         v.name.contains("iom", ignoreCase = true))   // Clear energetic female
                    } ?: availableVoices.find { v ->
                        v.locale.language == "en" &&
                        !v.isNetworkConnectionRequired &&
                        (v.name.contains("female", ignoreCase = true) || v.features?.contains("female") == true) &&
                        !v.name.contains("sfg", ignoreCase = true) // Skip soft-spoken sfg
                    } ?: availableVoices.find { v ->
                        v.locale.language == "en" &&
                        !v.isNetworkConnectionRequired
                    }

                    if (energeticVoice != null) {
                        tts.voice = energeticVoice
                        Log.i("MainActivity", "Selected energetic voice: ${energeticVoice.name}")
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error selecting TTS voice", e)
            }

            // Lively, energetic, brisk speaking rate (1.12f sounds confident, upbeat and alert)
            tts.setSpeechRate(1.12f)

            // Bright, cheerful, energetic feminine pitch (1.22f gives dynamic enthusiasm)
            tts.setPitch(1.22f)

            // Track speaking progress to animate Gideon hologram
            tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    scope.launch(Dispatchers.Main) {
                        assistantState = AssistantState.SPEAKING
                    }
                }
                override fun onDone(utteranceId: String?) {
                    scope.launch(Dispatchers.Main) {
                        assistantState = AssistantState.IDLE
                    }
                }
                override fun onError(utteranceId: String?) {
                    scope.launch(Dispatchers.Main) {
                        assistantState = AssistantState.IDLE
                    }
                }
            })
        }
    }

    private fun speakResponse(text: String) {
        transcript = text
        assistantState = AssistantState.SPEAKING
        val utteranceId = "Gideon_${System.currentTimeMillis()}"
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun setupAssistant() {
        if (::speechRecognizer.isInitialized) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { 
                isListening = true 
                assistantState = AssistantState.LISTENING
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { 
                isListening = false 
                assistantState = AssistantState.THINKING
            }
            override fun onError(error: Int) {
                isListening = false
                assistantState = AssistantState.IDLE
                transcript = "Error: $error"
                stopSco()
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    transcript = text
                    assistantState = AssistantState.THINKING
                    processCommand(text)
                } else {
                    assistantState = AssistantState.IDLE
                }
                stopSco()
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        
        try {
            if (audioManager.isBluetoothScoAvailableOffCall && audioManager.isBluetoothA2dpOn) {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Bluetooth SCO setup", e)
        }
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            // Fast endpointing: detect end of command quickly (700ms silence vs default 2000ms)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
        }
        speechRecognizer.startListening(intent)
    }
    
    private fun stopSco() {
        if (audioManager.isBluetoothScoOn) {
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
        }
    }

    private val vipSenders = setOf("uj", "baddu", "bethol", "amma", "uma", "barath", "bharath", "mom", "mother")

    private fun isVipSender(sender: String): Boolean {
        val clean = sender.lowercase(Locale.getDefault()).trim()
        return vipSenders.any { clean.contains(it) || it.contains(clean) }
    }

    private fun normalizeContactName(spoken: String): String {
        val lower = spoken.lowercase(Locale.getDefault()).trim()
        return when (lower) {
            "uma", "u j" -> "UJ"
            "moon", "moon cat", "cat", "gayatri" -> "gaytri"
            "barath" -> "baddu"
            "bharath" -> "bethol"
            "mom", "mother" -> "amma"
            else -> spoken
        }
    }

    private fun handlePersonIntroduction(input: String): Boolean {
        val lower = input.lowercase(Locale.getDefault()).trim()

        // Filter out non-introduction queries (calls, music, navigation, etc.)
        if (lower.startsWith("call ") || lower.startsWith("play ") || lower.startsWith("open ") || lower.startsWith("directions to")) {
            return false
        }

        // Introduction & greeting trigger keywords
        val isIntro = lower.contains("meet") ||
                lower.contains("this is") ||
                lower.contains("he is") ||
                lower.contains("she is") ||
                lower.contains("name is") ||
                lower.contains("greet") ||
                lower.contains("say hi") ||
                lower.contains("say hello") ||
                lower.contains("say hey") ||
                lower.contains("welcome") ||
                lower.contains("introduce") ||
                lower.contains("hacker")

        if (!isIntro) return false

        // 1. VIP / Special Custom Introductions
        if (lower.contains("uma")) {
            bossSecurity.introduceGuest("Uma sir")
            securityStatus = bossSecurity.getSecurityStatus()
            speakResponse("Hello Uma sir! It is an absolute honor to meet you. Guest session authorized for you. How are you doing today, sir?")
            return true
        }

        if (lower.contains("yashwanth") || (lower.contains("hacker") && !lower.contains("call"))) {
            val name = if (lower.contains("yashwanth")) "Yashwanth" else "friend"
            bossSecurity.introduceGuest(name)
            securityStatus = bossSecurity.getSecurityStatus()
            speakResponse("Greetings $name! Welcome, it's awesome to meet you! A true hacker in the house. Guest privileges authorized. How are you doing today?")
            return true
        }

        if (lower.contains("baddu") || lower.contains("barath")) {
            bossSecurity.introduceGuest("Baddu")
            securityStatus = bossSecurity.getSecurityStatus()
            speakResponse("Hey Baddu! Great to meet you bro. Guest session granted. How are you doing today?")
            return true
        }

        if (lower.contains("bethol") || lower.contains("bharath")) {
            bossSecurity.introduceGuest("Bethol")
            securityStatus = bossSecurity.getSecurityStatus()
            speakResponse("Hello Bethol! Awesome to meet you. Guest session granted. Hope you're doing great today!")
            return true
        }

        if (lower.contains("amma") || lower.contains("mom") || lower.contains("mother")) {
            bossSecurity.introduceGuest("Amma")
            securityStatus = bossSecurity.getSecurityStatus()
            speakResponse("Namaste Amma! It is a true blessing to meet you. Guest session authorized. How are you doing today?")
            return true
        }

        // 2. Generic Name Extraction for any person introduction
        // Handles: "this is rahul", "meet my friend anand", "say hi to priya", "his name is karthik", "greet dr smith"
        val regexes = listOf(
            Regex("(?i).*(?:his|her)\\s+name\\s+is\\s+([a-zA-Z]+(?:\\s+[a-zA-Z]+)?)"),
            Regex("(?i).*(?:say\\s+(?:hi|hello|hey)\\s+to|greet|welcome)\\s+(?:my\\s+\\w+\\s+|our\\s+\\w+\\s+)?([a-zA-Z]+(?:\\s+[a-zA-Z]+)?)"),
            Regex("(?i).*(?:this\\s+is|he\\s+is|she\\s+is|meet)\\s+(?:my\\s+\\w+\\s+|our\\s+\\w+\\s+)?([a-zA-Z]+(?:\\s+[a-zA-Z]+)?)"),
            Regex("(?i).*introduce\\s+(?:yourself\\s+to\\s+)?(?:my\\s+\\w+\\s+|our\\s+\\w+\\s+)?([a-zA-Z]+(?:\\s+[a-zA-Z]+)?)")
        )

        val fillerWords = setOf("a", "an", "the", "my", "our", "him", "her", "them", "someone", "everyone", "devil", "assistant")

        for (regex in regexes) {
            val match = regex.find(input)
            if (match != null) {
                var candidate = match.groupValues[1].trim()
                candidate = candidate.replace(Regex("(?i)^(?:my\\s+friend|our\\s+friend|friend|colleague|brother|bro|sister)\\s+"), "").trim()
                val candidateLower = candidate.lowercase(Locale.getDefault())
                if (!fillerWords.contains(candidateLower) && candidate.length > 1) {
                    val formattedName = candidate.split(" ").joinToString(" ") { word ->
                        word.replaceFirstChar { it.uppercase() }
                    }
                    bossSecurity.introduceGuest(formattedName)
                    securityStatus = bossSecurity.getSecurityStatus()
                    speakResponse("Hi $formattedName! How are you? Guest privileges have been activated. It's a real pleasure to meet you!")
                    return true
                }
            }
        }

        // 3. Fallback greeting
        speakResponse("Hello! How are you? It's a real pleasure to meet you!")
        return true
    }

    private fun processCommand(command: String) {
        val trimmed = command.trim()
        val greetingRegex = Regex("(?i)^(?:hello|hi|hey|good\\s+(?:morning|afternoon|evening)|namaste|hola)\\b.*")
        val identityRegex = Regex("(?i).*(?:who\\s+are\\s+you|what(?:'s|\\s+is)\\s+your\\s+name|what\\s+are\\s+you|introduce\\s+yourself(?!(?:\\s+to|\\s+with))).*")
        val howAreYouRegex = Regex("(?i).*(?:how\\s+are\\s+you|how's\\s+it\\s+going|how\\s+are\\s+things).*")
        val thanksRegex = Regex("(?i).*(?:thank\\s+you|thanks|thx).*")
        val helpRegex = Regex("(?i).*(?:what\\s+can\\s+you\\s+do|help\\s+me|help).*")
        val devilCallRegex = Regex("(?i).*(?:hey\\s+devil|ok\\s+devil|hello\\s+devil|devil).*")

        // Enhanced WhatsApp detection
        val isWhatsApp = trimmed.contains(Regex("(?i)\\bwhatsapp\\b"))
        // Enhanced SMS detection
        val isSms = trimmed.contains(Regex("(?i)\\b(?:texts?|sms|messages?)\\b")) && !isWhatsApp

        val callRegex = Regex("(?i)call\\s+(.+)")
        val shuffleRegex = Regex("(?i)(?:shuffle(?:\\s+play)?|play\\s+on\\s+shuffle|play\\s+random)(?:\\s+(.+))?")
        val playRegex = Regex("(?i)(?:play|start\\s+playing)(?:\\s+(.+))?")
        val openAppRegex = Regex("(?i)(?:open|launch|start)\\s+(.+)")
        val distanceRegex = Regex("(?i)(?:what(?:'s|\\s+is)\\s+the\\s+)?distance\\s+(?:between|from)\\s+(.+?)\\s+(?:and|to)\\s+(.+)")
        val howFarRegex = Regex("(?i)how\\s+far\\s+is\\s+(.+?)\\s+from\\s+(.+)")
        val directionsRegex = Regex("(?i)(?:directions?|route|navigate)\\s+to\\s+(.+)")

        val isLockdown = trimmed.matches(Regex("(?i).*(?:lock\\s*down|cancel\\s+guest|revoke\\s+guest|boss\\s+only).*"))
        val isAuthCheck = trimmed.matches(Regex("(?i).*(?:who\\s+is\\s+authorized|authorization\\s+status|who\\s+can\\s+talk|who\\s+has\\s+access).*"))

        when {
            // ── 0. Boss Security Lockdown & Authorization ──
            isLockdown -> {
                val msg = bossSecurity.revokeGuest()
                securityStatus = bossSecurity.getSecurityStatus()
                speakResponse(msg)
            }
            isAuthCheck -> {
                if (bossSecurity.isGuestSessionActive()) {
                    speakResponse("You are the Boss with master voice clearance. Active guest ${bossSecurity.activeGuest} is currently authorized to speak with DEVIL.")
                } else {
                    speakResponse("Boss-only mode is active. Master voice clearance is required. No outside guests are authorized.")
                }
            }

            // ── 1. Person Introduction & Greeting Fast-Path ──
            handlePersonIntroduction(trimmed) -> {
                // Handled directly inside handlePersonIntroduction with 0-latency
            }

            // ── 2. Conversational Fast-Path (0 latency) ──
            identityRegex.matches(trimmed) -> {
                speakResponse("I'm DEVIL, I'm here to help.")
            }
            greetingRegex.matches(trimmed) -> {
                speakResponse("Hello! I'm DEVIL, I'm here to help.")
            }
            trimmed.matches(Regex("(?i)^(?:hey\\s+|ok\\s+|hello\\s+)?devil\\b.*")) && trimmed.length <= 15 -> {
                speakResponse("Yes, I'm DEVIL. How can I help?")
            }
            howAreYouRegex.matches(trimmed) -> {
                speakResponse("I'm doing well, thank you! I'm DEVIL, how can I help you today?")
            }
            thanksRegex.matches(trimmed) -> {
                speakResponse("You're very welcome! I'm always here to help.")
            }
            helpRegex.matches(trimmed) -> {
                speakResponse("I'm DEVIL. You can ask me questions, introduce people, or ask me to play music in VLC, navigate with Maps, call contacts, or summarize your WhatsApp and text messages.")
            }

            // ── 2. WhatsApp Messages ──
            isWhatsApp -> {
                val fromMatch = Regex("(?i)(?:from|by)\\s+(.+)").find(trimmed)
                val sender = fromMatch?.groupValues?.get(1)?.trim()
                readWhatsAppMessages(fromSender = sender)
            }

            // ── 3. SMS & Text Messages ──
            isSms && (trimmed.contains(Regex("(?i)\\b(?:read|check|get|any|latest|recent)\\b")) || trimmed.contains("unread", ignoreCase = true)) -> {
                val fromMatch = Regex("(?i)(?:from|by)\\s+(.+)").find(trimmed)
                val sender = fromMatch?.groupValues?.get(1)?.trim()
                val isUnread = trimmed.contains("unread", ignoreCase = true)
                readMessages(fromSender = sender, unreadOnly = isUnread)
            }

            // ── 4. Phone Calls ──
            callRegex.matches(trimmed) -> {
                val spokenName = callRegex.find(trimmed)?.groupValues?.get(1) ?: return
                val contactName = normalizeContactName(spokenName)
                callContact(contactName)
            }

            // ── 5. Maps & Navigation ──
            distanceRegex.matches(trimmed) -> {
                val match = distanceRegex.find(trimmed)
                val origin = match?.groupValues?.get(1)?.trim() ?: return
                val destination = match.groupValues.get(2).trim()
                openMapsRoute(origin, destination)
            }
            howFarRegex.matches(trimmed) -> {
                val match = howFarRegex.find(trimmed)
                val destination = match?.groupValues?.get(1)?.trim() ?: return
                val origin = match.groupValues.get(2).trim()
                openMapsRoute(origin, destination)
            }
            directionsRegex.matches(trimmed) -> {
                val destination = directionsRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: return
                openMapsNavigation(destination)
            }

            // ── 6. Apps & VLC ──
            openAppRegex.matches(trimmed) -> {
                val appName = openAppRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: return
                if (!openApp(appName)) {
                    queryBackend(trimmed)
                }
            }
            // Direct mention of VLC like "vlc", "vlc player"
            trimmed.contains("vlc", ignoreCase = true) -> {
                openApp("vlc")
            }
            shuffleRegex.matches(trimmed) -> {
                val mediaQuery = shuffleRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: ""
                playMedia(mediaQuery, shuffle = true)
            }
            playRegex.matches(trimmed) -> {
                var mediaQuery = playRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: ""
                var isShuffle = false
                if (mediaQuery.contains(Regex("(?i)\\bon\\s+shuffle\\b|\\bshuffle\\b|\\brandom\\b"))) {
                    isShuffle = true
                    mediaQuery = mediaQuery.replace(Regex("(?i)\\bon\\s+shuffle\\b|\\bshuffle\\b|\\brandom\\b"), "").trim()
                }
                playMedia(mediaQuery, shuffle = isShuffle)
            }
            else -> {
                queryBackend(trimmed)
            }
        }
    }

    private fun getContactName(phoneNumber: String): String {
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0) ?: phoneNumber
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error looking up contact name", e)
        }
        return phoneNumber
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(pkgName)
    }

    private fun readWhatsAppMessages(fromSender: String? = null) {
        if (!isNotificationServiceEnabled()) {
            speakResponse("Please enable Notification Access for Nord Assistant in Settings so I can read WhatsApp messages.")
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            } catch (e: Exception) {
                Log.e("MainActivity", "Error opening notification settings", e)
            }
            return
        }

        val normalizedSender = fromSender?.let { normalizeContactName(it) }
        val messages = WhatsAppNotificationService.getRecentMessages(normalizedSender)

        if (messages.isEmpty()) {
            val text = if (fromSender != null) {
                "No recent WhatsApp messages from $fromSender."
            } else {
                "No recent WhatsApp messages found."
            }
            speakResponse(text)
            return
        }

        // ── Prioritize UJ, Baddu, Bethol, Amma FIRST ──
        val (vipMessages, regularMessages) = messages.partition { isVipSender(it.sender) }
        val prioritized = vipMessages + regularMessages

        if (LlamaHelper.isReady) {
            scope.launch(Dispatchers.IO) {
                withContext(Dispatchers.Main) {
                    assistantState = AssistantState.THINKING
                    transcript = "DEVIL is briefing messages..."
                }
                try {
                    val rawSummaryText = prioritized.take(4).joinToString("\n") { msg ->
                        val vipLabel = if (isVipSender(msg.sender)) "[URGENT/IMPORTANT]" else "[NORMAL]"
                        "$vipLabel From ${msg.sender}: ${msg.message}"
                    }
                    val prompt = """
                    You are DEVIL, an AI voice assistant. Summarize what each sender is trying to say in 1-2 spoken sentences.
                    MANDATORY: Always mention messages marked [URGENT/IMPORTANT] from UJ, Baddu, Bethol, or Amma FIRST.
                    Explain what they want concisely. Do not repeat long text verbatim.
                    $rawSummaryText
                    DEVIL:
                    """.trimIndent()
                    val summary = LlamaHelper.generateAnswer(prompt)
                    withContext(Dispatchers.Main) {
                        speakResponse(summary)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        speakPriorityFallback(prioritized)
                    }
                }
            }
        } else {
            speakPriorityFallback(prioritized)
        }
    }

    private fun speakPriorityFallback(messages: List<WhatsAppNotificationService.StoredMessage>) {
        val sb = StringBuilder()
        val (vip, regular) = messages.partition { isVipSender(it.sender) }

        if (vip.isNotEmpty()) {
            sb.append("Important priority alert. ")
            for (msg in vip.take(2)) {
                val clean = if (msg.message.length > 90) msg.message.substring(0, 90) + "..." else msg.message
                sb.append("From ${msg.sender}: $clean. ")
            }
        }
        if (regular.isNotEmpty()) {
            if (vip.isNotEmpty()) sb.append("Other notifications: ")
            for (msg in regular.take(2)) {
                val clean = if (msg.message.length > 70) msg.message.substring(0, 70) + "..." else msg.message
                sb.append("${msg.sender} says: $clean. ")
            }
        }
        speakResponse(sb.toString().trim())
    }

    private fun readMessages(fromSender: String? = null, unreadOnly: Boolean = false) {
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            speakResponse("I need permission to read your messages. Please grant SMS permission.")
            permissionLauncher.launch(requiredPermissions)
            return
        }

        try {
            val normalizedSender = fromSender?.let { normalizeContactName(it) }?.lowercase(Locale.getDefault())

            val uri = Telephony.Sms.Inbox.CONTENT_URI
            val projection = arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.READ
            )

            val selection = if (unreadOnly) "${Telephony.Sms.READ} = 0" else null
            val sortOrder = "${Telephony.Sms.DATE} DESC LIMIT 100"

            val messages = mutableListOf<Pair<String, String>>()

            contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
                val addressIdx = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = cursor.getColumnIndex(Telephony.Sms.BODY)

                while (cursor.moveToNext() && messages.size < 3) {
                    val address = if (addressIdx != -1) cursor.getString(addressIdx) ?: "Unknown" else "Unknown"
                    val body = if (bodyIdx != -1) cursor.getString(bodyIdx) ?: "" else ""
                    val contactName = getContactName(address)

                    // Skip commercial, spam, and marketing messages!
                    if (MessageFilter.isCommercialOrSpam(contactName, body)) {
                        continue
                    }

                    if (normalizedSender != null) {
                        val matchesSender = contactName.lowercase(Locale.getDefault()).contains(normalizedSender) ||
                                normalizedSender.contains(contactName.lowercase(Locale.getDefault())) ||
                                address.contains(normalizedSender)
                        if (matchesSender) {
                            messages.add(Pair(contactName, body))
                        }
                    } else {
                        messages.add(Pair(contactName, body))
                    }
                }
            }

            if (messages.isEmpty()) {
                val text = if (fromSender != null) "You have no recent messages from $fromSender." else "You have no recent personal messages."
                speakResponse(text)
                return
            }

            val speakBuilder = StringBuilder()
            if (fromSender != null) {
                speakBuilder.append("Found ${messages.size} message${if (messages.size > 1) "s" else ""} from $fromSender. ")
            } else {
                speakBuilder.append("You have ${messages.size} recent message${if (messages.size > 1) "s" else ""}. ")
            }

            for ((index, msg) in messages.withIndex()) {
                val (sender, body) = msg
                val cleanBody = if (body.length > 150) body.substring(0, 150) + "..." else body
                if (fromSender == null) {
                    speakBuilder.append("Message ${index + 1} from $sender: $cleanBody. ")
                } else {
                    speakBuilder.append("Message ${index + 1}: $cleanBody. ")
                }
            }

            speakResponse(speakBuilder.toString().trim())

        } catch (e: Exception) {
            Log.e("MainActivity", "Error reading SMS", e)
            speakResponse("Sorry, I had trouble reading your messages.")
        }
    }

    private fun openApp(appName: String): Boolean {
        val cleanName = appName.trim().lowercase(Locale.getDefault())

        try {
            if (cleanName.contains("vlc")) {
                val intent = packageManager.getLaunchIntentForPackage("org.videolan.vlc")
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    speakResponse("Opening VLC player")
                    return true
                }
            }
            if (cleanName.contains("youtube")) {
                val intent = packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                    ?: packageManager.getLaunchIntentForPackage("com.google.android.apps.youtube.music")
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    speakResponse("Opening YouTube")
                    return true
                }
            }
            if (cleanName.contains("spotify")) {
                val intent = packageManager.getLaunchIntentForPackage("com.spotify.music")
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    speakResponse("Opening Spotify")
                    return true
                }
            }
            if (cleanName.contains("map")) {
                val intent = packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    speakResponse("Opening Google Maps")
                    return true
                }
            }

            // Search through launcher activities
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val apps = packageManager.queryIntentActivities(mainIntent, 0)
            for (info in apps) {
                val label = info.loadLabel(packageManager).toString().lowercase(Locale.getDefault())
                if (label.contains(cleanName) || cleanName.contains(label)) {
                    val launchIntent = packageManager.getLaunchIntentForPackage(info.activityInfo.packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent)
                        speakResponse("Opening ${info.loadLabel(packageManager)}")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error opening app: $appName", e)
        }
        return false
    }

    private fun openMapsRoute(origin: String, destination: String) {
        try {
            val mapsUri = Uri.parse("https://www.google.com/maps/dir/?api=1&origin=${Uri.encode(origin)}&destination=${Uri.encode(destination)}")
            val mapIntent = Intent(Intent.ACTION_VIEW, mapsUri).apply {
                setPackage("com.google.android.apps.maps")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (mapIntent.resolveActivity(packageManager) != null) {
                startActivity(mapIntent)
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, mapsUri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }
            speakResponse("Showing route from $origin to $destination in Google Maps")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error opening Maps", e)
            speakResponse("Could not open Google Maps")
        }
    }

    private fun openMapsNavigation(destination: String) {
        try {
            val navUri = Uri.parse("google.navigation:q=${Uri.encode(destination)}")
            val mapIntent = Intent(Intent.ACTION_VIEW, navUri).apply {
                setPackage("com.google.android.apps.maps")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (mapIntent.resolveActivity(packageManager) != null) {
                startActivity(mapIntent)
            } else {
                val webUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(destination)}")
                startActivity(Intent(Intent.ACTION_VIEW, webUri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }
            speakResponse("Starting navigation to $destination")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error starting navigation", e)
            speakResponse("Could not open Google Maps")
        }
    }

    private fun callContact(name: String) {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$name%")

        contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            if (!cursor.moveToFirst()) {
                transcript = "Contact not found: $name"
                tts.speak("Contact not found", TextToSpeech.QUEUE_FLUSH, null, null)
                return
            }

            data class ContactMatch(val displayName: String, val number: String, val score: Int)
            val matches = mutableListOf<ContactMatch>()
            do {
                val displayName = cursor.getString(0) ?: continue
                val number = cursor.getString(1) ?: continue

                val score = when {
                    displayName.equals(name, ignoreCase = true) -> 3
                    displayName.startsWith(name, ignoreCase = true) -> 2
                    else -> 1
                }
                matches.add(ContactMatch(displayName, number, score))
            } while (cursor.moveToNext())

            val best = matches.maxByOrNull { it.score * 1000 - it.displayName.length }

            if (best != null) {
                val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${best.number}"))
                startActivity(callIntent)
                transcript = "Calling ${best.displayName}"
            } else {
                transcript = "Contact not found: $name"
                tts.speak("Contact not found", TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }
    }

    private fun findMatchingTrackUri(keyword: String, shuffle: Boolean = false, mediaType: String? = null): Uri? {
        val hasPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasPermission) return null

        try {
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM)

            val isGeneric = keyword.isBlank() || keyword.lowercase(Locale.getDefault()) in listOf("song", "songs", "music", "audio", "something", "track", "tracks")
            val (selection, selectionArgs) = when {
                isGeneric -> Pair(null, null)
                mediaType == "album" -> Pair("${MediaStore.Audio.Media.ALBUM} LIKE ?", arrayOf("%$keyword%"))
                mediaType == "artist" -> Pair("${MediaStore.Audio.Media.ARTIST} LIKE ?", arrayOf("%$keyword%"))
                else -> Pair(
                    "${MediaStore.Audio.Media.ARTIST} LIKE ? OR ${MediaStore.Audio.Media.ALBUM} LIKE ? OR ${MediaStore.Audio.Media.TITLE} LIKE ?",
                    arrayOf("%$keyword%", "%$keyword%", "%$keyword%")
                )
            }

            val trackIds = mutableListOf<Long>()
            contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                while (cursor.moveToNext() && trackIds.size < 2000) {
                    trackIds.add(cursor.getLong(idCol))
                }
            }

            // Fallback to broader search if specific album/artist search yielded 0 results
            if (trackIds.isEmpty() && !isGeneric && mediaType != null) {
                val fallbackSelection = "${MediaStore.Audio.Media.ARTIST} LIKE ? OR ${MediaStore.Audio.Media.ALBUM} LIKE ? OR ${MediaStore.Audio.Media.TITLE} LIKE ?"
                val fallbackArgs = arrayOf("%$keyword%", "%$keyword%", "%$keyword%")
                contentResolver.query(uri, projection, fallbackSelection, fallbackArgs, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    while (cursor.moveToNext() && trackIds.size < 2000) {
                        trackIds.add(cursor.getLong(idCol))
                    }
                }
            }

            if (trackIds.isNotEmpty()) {
                val chosenId = if (shuffle) trackIds.random() else trackIds.first()
                return ContentUris.withAppendedId(uri, chosenId)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error finding local track for: $keyword", e)
        }
        return null
    }

    private fun playMedia(query: String, shuffle: Boolean = false) {
        val cleanQuery = query.replace(Regex("(?i)\\b(?:in|on|from|using|with)\\s+vlc\\b"), "")
                              .replace(Regex("(?i)\\bvlc\\b"), "")
                              .trim()
        val isGeneric = cleanQuery.isEmpty() || cleanQuery.lowercase(Locale.getDefault()) in listOf("song", "songs", "music", "audio", "something", "track", "tracks")

        try {
            if (isGeneric && !shuffle) {
                // Launch VLC app directly
                val launchIntent = packageManager.getLaunchIntentForPackage("org.videolan.vlc")
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                    transcript = "Opening VLC player"
                    tts.speak("Opening VLC player", TextToSpeech.QUEUE_FLUSH, null, null)
                    return
                }
            }

            // Detect if query is asking for an album, playlist, artist, or track
            val playlistRegex = Regex("(?i)(?:the\\s+)?playlist\\s+(.+)|(.+?)\\s+playlist")
            val albumRegex = Regex("(?i)(?:the\\s+)?album\\s+(.+)|(.+?)\\s+album")
            val artistPattern1 = Regex("(?i)(?:all\\s+)?(?:songs?\\s+(?:of|by|from)|artist)\\s+(.+)")
            val artistPattern2 = Regex("(?i)(?:all\\s+)?(.+?)\\s+(?:songs?|hits|all\\s+songs)")

            var mediaFocus = "vnd.android.cursor.item/*"
            var targetExtraKey: String? = null
            var targetName = cleanQuery
            var isArtist = false
            var mediaType: String? = null

            when {
                playlistRegex.matches(cleanQuery) -> {
                    val match = playlistRegex.find(cleanQuery)
                    targetName = (match?.groupValues?.get(1)?.ifEmpty { null } ?: match?.groupValues?.get(2) ?: cleanQuery).trim()
                    mediaFocus = "vnd.android.cursor.item/playlist"
                    targetExtraKey = MediaStore.EXTRA_MEDIA_PLAYLIST
                    mediaType = "playlist"
                }
                albumRegex.matches(cleanQuery) -> {
                    val match = albumRegex.find(cleanQuery)
                    targetName = (match?.groupValues?.get(1)?.ifEmpty { null } ?: match?.groupValues?.get(2) ?: cleanQuery).trim()
                    mediaFocus = "vnd.android.cursor.item/album"
                    targetExtraKey = MediaStore.EXTRA_MEDIA_ALBUM
                    mediaType = "album"
                }
                artistPattern1.matches(cleanQuery) -> {
                    val match = artistPattern1.find(cleanQuery)
                    targetName = (match?.groupValues?.get(1) ?: cleanQuery).trim()
                    mediaFocus = "vnd.android.cursor.item/artist"
                    targetExtraKey = MediaStore.EXTRA_MEDIA_ARTIST
                    isArtist = true
                    mediaType = "artist"
                }
                artistPattern2.matches(cleanQuery) -> {
                    val match = artistPattern2.find(cleanQuery)
                    val candidate = (match?.groupValues?.get(1) ?: cleanQuery).trim()
                    if (candidate.lowercase(Locale.getDefault()) !in listOf("my", "some", "the", "any", "all")) {
                        targetName = candidate
                        mediaFocus = "vnd.android.cursor.item/artist"
                        targetExtraKey = MediaStore.EXTRA_MEDIA_ARTIST
                        isArtist = true
                        mediaType = "artist"
                    }
                }
            }

            val actionPrefix = if (shuffle) "Shuffling" else "Playing"
            val displayTitle = if (targetName.isBlank() || isGeneric) "music" else targetName

            // 1. Check if there is a matching track on device storage
            val localTrackUri = findMatchingTrackUri(targetName, shuffle = shuffle, mediaType = mediaType)
            if (localTrackUri != null) {
                val directPlayIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(localTrackUri, "audio/*")
                    setPackage("org.videolan.vlc")
                    if (shuffle) {
                        putExtra("android.intent.extra.SHUFFLE", true)
                        putExtra("shuffle", true)
                        putExtra("EXTRA_SHUFFLE", true)
                    }
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                if (directPlayIntent.resolveActivity(packageManager) != null) {
                    startActivity(directPlayIntent)
                    transcript = "$actionPrefix $displayTitle in VLC"
                    tts.speak("$actionPrefix $displayTitle in VLC", TextToSpeech.QUEUE_FLUSH, null, null)
                    return
                }
            }

            // 2. Attempt to search & play in VLC
            val vlcSearchIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(SearchManager.QUERY, targetName)
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, mediaFocus)
                if (targetExtraKey != null) {
                    putExtra(targetExtraKey, targetName)
                }
                if (isArtist) {
                    putExtra(MediaStore.EXTRA_MEDIA_ARTIST, targetName)
                }
                if (shuffle) {
                    putExtra("android.intent.extra.SHUFFLE", true)
                    putExtra("shuffle", true)
                    putExtra("EXTRA_SHUFFLE", true)
                }
                setPackage("org.videolan.vlc")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            if (vlcSearchIntent.resolveActivity(packageManager) != null) {
                startActivity(vlcSearchIntent)
                transcript = "$actionPrefix $displayTitle in VLC"
                tts.speak("$actionPrefix $displayTitle in VLC", TextToSpeech.QUEUE_FLUSH, null, null)
            } else {
                // If specific search intent didn't resolve, launch VLC directly
                val launchIntent = packageManager.getLaunchIntentForPackage("org.videolan.vlc")
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                    transcript = "Opening VLC"
                    tts.speak("Opening VLC", TextToSpeech.QUEUE_FLUSH, null, null)
                } else {
                    val genericIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                        putExtra(SearchManager.QUERY, targetName)
                        putExtra(MediaStore.EXTRA_MEDIA_FOCUS, mediaFocus)
                        if (shuffle) {
                            putExtra("android.intent.extra.SHUFFLE", true)
                            putExtra("shuffle", true)
                        }
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(genericIntent)
                    transcript = "$actionPrefix $displayTitle"
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error playing media", e)
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage("org.videolan.vlc")
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                    transcript = "Opening VLC"
                    tts.speak("Opening VLC", TextToSpeech.QUEUE_FLUSH, null, null)
                } else {
                    transcript = "VLC not found"
                    tts.speak("VLC not found", TextToSpeech.QUEUE_FLUSH, null, null)
                }
            } catch (ex: Exception) {
                transcript = "Media error"
                tts.speak("Could not open media player", TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }
    }

    private fun queryBackend(query: String) {
        scope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                assistantState = AssistantState.THINKING
                transcript = "S is thinking..."
            }
            try {
                // Friendly, concise prompt identifying as S
                val system = "You are S, a friendly, concise AI voice assistant. Always identify as S. Answer in 1 short sentence in plain conversational text."
                val fullPrompt = "$system\nUser: $query\nS:"
                val answer = LlamaHelper.generateAnswer(fullPrompt)
                withContext(Dispatchers.Main) {
                    speakResponse(answer)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    speakResponse("I'm having trouble analyzing that query right now.")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::speechRecognizer.isInitialized) speechRecognizer.destroy()
        if (::tts.isInitialized) tts.shutdown()
        LlamaHelper.release()
        scope.cancel()
    }
}
