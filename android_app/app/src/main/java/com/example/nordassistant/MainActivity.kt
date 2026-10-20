package com.example.nordassistant

import android.Manifest
import android.app.SearchManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
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
import android.view.KeyEvent
import androidx.core.content.FileProvider
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.app.NotificationManager
import com.example.nordassistant.data.BossMemoryVault
import com.example.nordassistant.data.DarwinboxShiftTracker
import com.example.nordassistant.data.DarwinboxAlarmReceiver
import com.example.nordassistant.data.CorporateCalendarEngine
import com.example.nordassistant.protocol.DevilProtocol
import com.example.nordassistant.protocol.DevilProtocolManager
import com.example.nordassistant.security.BossSecurityManager
import com.example.nordassistant.security.BossVoiceRecorder
import com.example.nordassistant.security.VoiceBiometrics
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
    private lateinit var memoryVault: BossMemoryVault
    private val voiceRecorder = BossVoiceRecorder()
    
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    
    private var isListening by mutableStateOf(false)
    private var assistantState by mutableStateOf(AssistantState.IDLE)
    private var transcript by mutableStateOf("Ready for voice command...")
    private var securityStatus by mutableStateOf("DEVIL // BOSS SECURED")
    private var autoListenAfterSpeech = false
    private var inConversationSession = false
    private var conversationSessionEndTime = 0L
    private val conversationSessionDurationMs = 40_000L // 40-second continuous conversation window
    private var conversationWatchdogJob: Job? = null

    private fun refreshConversationSession() {
        inConversationSession = true
        autoListenAfterSpeech = true
        conversationSessionEndTime = System.currentTimeMillis() + conversationSessionDurationMs
        conversationWatchdogJob?.cancel()
        conversationWatchdogJob = scope.launch {
            while (System.currentTimeMillis() < conversationSessionEndTime) {
                val remaining = conversationSessionEndTime - System.currentTimeMillis()
                delay(minOf(remaining, 1000L))
            }
            withContext(Dispatchers.Main) {
                if (assistantState != AssistantState.SPEAKING && assistantState != AssistantState.THINKING) {
                    inConversationSession = false
                    autoListenAfterSpeech = false
                    if (isListening) {
                        try { speechRecognizer.stopListening() } catch (_: Exception) {}
                        isListening = false
                    }
                    assistantState = AssistantState.IDLE
                    transcript = "Standing by, Boss."
                    stopSco()
                    Log.i("MainActivity", "40-second conversation session ended naturally. Resting to IDLE.")
                }
            }
        }
    }

    private fun endConversationSession(goodbyeMessage: String? = null) {
        inConversationSession = false
        autoListenAfterSpeech = false
        conversationSessionEndTime = 0L
        conversationWatchdogJob?.cancel()
        conversationWatchdogJob = null
        if (goodbyeMessage != null) {
            speakResponse(goodbyeMessage, continueConversation = false)
        } else {
            assistantState = AssistantState.IDLE
            transcript = "Standing by, Boss."
            stopSco()
        }
    }

    private val jokes = listOf(
        "Why do programmers prefer dark mode, Boss? Because light attracts bugs!",
        "Why did the smartphone go to therapy, Boss? Because it lost its connection and had too many tabs open in its head.",
        "There are only 10 types of people in the world, Boss: those who understand binary, and those who don't.",
        "Why was the cell phone wearing glasses, Boss? Because it lost all its contacts!",
        "I asked my phone why it was being slow. It told me it was just taking a byte to think.",
        "Why did the computer catch a cold, Boss? Because it left its Windows open!"
    )
    private var jokeIndex = 0

    private fun getNextJoke(): String {
        val joke = jokes[jokeIndex % jokes.size]
        jokeIndex++
        return joke
    }

    data class ContactInfo(val displayName: String, val number: String)

    private var voiceNoteSynthesisCallback: (() -> Unit)? = null
    
    private val requiredPermissions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
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
        clearAppCache()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(this, this)
        bossSecurity = BossSecurityManager.getInstance(this)
        memoryVault = BossMemoryVault.getInstance(this)
        DevilProtocolManager.init(this)
        securityStatus = if (DevilProtocolManager.currentProtocol != DevilProtocol.BOSS_MODE) {
            DevilProtocolManager.currentProtocol.title
        } else {
            bossSecurity.getSecurityStatus()
        }
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
                            refreshConversationSession()
                            startListening()
                        } else if (assistantState == AssistantState.SPEAKING) {
                            autoListenAfterSpeech = false
                            tts.stop()
                            assistantState = AssistantState.IDLE
                            if (System.currentTimeMillis() < conversationSessionEndTime) {
                                scope.launch(Dispatchers.Main) {
                                    delay(200)
                                    startListening()
                                }
                            } else {
                                endConversationSession()
                            }
                        }
                    }
                )
            }
        }
        
        permissionLauncher.launch(requiredPermissions)
        CorporateCalendarEngine.init(this)
        handleIncomingIntent(intent)
        DarwinboxShiftTracker.scheduleDaily1030AmCheck(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val isWake = intent?.getBooleanExtra("EXTRA_WAKE_DEVIL", false) ?: false
        val cmd = intent?.getStringExtra("query") ?: intent?.getStringExtra("command")
        val action = intent?.getStringExtra("EXTRA_ACTION")

        scope.launch(Dispatchers.Main) {
            if (!::tts.isInitialized) {
                delay(300)
            }
            if (action == "DARWINBOX_CHECK_OUT") {
                handleDarwinboxAttendance(DevilAccessibilityService.DarwinboxAction.CHECK_OUT)
            } else if (!cmd.isNullOrBlank()) {
                refreshConversationSession()
                processCommand(cmd)
            } else if (isWake) {
                refreshConversationSession()
                speakResponse("Yes Boss?", continueConversation = true)
            }
        }
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

            // Lively, energetic, brisk speaking rate (1.18f sounds confident, snappy and responsive)
            tts.setSpeechRate(1.18f)

            // Bright, cheerful, energetic feminine pitch (1.22f gives dynamic enthusiasm)
            tts.setPitch(1.22f)

            try {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts.setAudioAttributes(audioAttributes)
            } catch (_: Exception) {}

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
                        if (utteranceId?.startsWith("VOICE_NOTE_") == true) {
                            val cb = voiceNoteSynthesisCallback
                            voiceNoteSynthesisCallback = null
                            cb?.invoke()
                        } else if (inConversationSession && System.currentTimeMillis() < conversationSessionEndTime) {
                            delay(350)
                            if (!isListening && assistantState == AssistantState.IDLE) {
                                startListening()
                            }
                        } else if (autoListenAfterSpeech) {
                            autoListenAfterSpeech = false
                            delay(350)
                            if (!isListening && assistantState == AssistantState.IDLE) {
                                startListening()
                            }
                        }
                    }
                }
                override fun onError(utteranceId: String?) {
                    scope.launch(Dispatchers.Main) {
                        assistantState = AssistantState.IDLE
                        autoListenAfterSpeech = false
                        if (utteranceId?.startsWith("VOICE_NOTE_") == true) {
                            voiceNoteSynthesisCallback = null
                            speakResponse("Sorry Boss, I encountered an issue preparing the audio note.")
                        }
                    }
                }
            })
            // Apply active protocol voice profile
            applyProtocolVoiceProfile(DevilProtocolManager.currentProtocol)
        }
    }

    private fun applyProtocolVoiceProfile(protocol: DevilProtocol) {
        if (!::tts.isInitialized) return
        try {
            val availableVoices = tts.voices
            when (protocol) {
                DevilProtocol.BARN_DOOR -> {
                    // Whispered, brief, formal tone: low quiet pitch and calm rate
                    tts.setSpeechRate(0.92f)
                    tts.setPitch(0.85f)
                    val calmVoice = availableVoices?.find {
                        it.name.contains("sfg", ignoreCase = true) || it.name.contains("gbc", ignoreCase = true)
                    }
                    if (calmVoice != null) tts.voice = calmVoice
                }
                DevilProtocol.HOUSE_PARTY -> {
                    // Direct, purely informational, high-efficiency: fast, crisp
                    tts.setSpeechRate(1.28f)
                    tts.setPitch(1.05f)
                    val devVoice = availableVoices?.find {
                        it.name.contains("iob", ignoreCase = true) || it.name.contains("tpf", ignoreCase = true)
                    }
                    if (devVoice != null) tts.voice = devVoice
                }
                DevilProtocol.JARVIS -> {
                    // Formal, calm, British male accent
                    tts.setSpeechRate(1.02f)
                    tts.setPitch(0.92f)
                    val britishVoice = availableVoices?.find {
                        it.locale.country.equals("GB", ignoreCase = true) &&
                        (it.name.contains("rjs", ignoreCase = true) || it.name.contains("gbb", ignoreCase = true) || it.name.contains("male", ignoreCase = true))
                    } ?: availableVoices?.find { it.locale.country.equals("GB", ignoreCase = true) }
                    if (britishVoice != null) {
                        tts.voice = britishVoice
                        Log.i("MainActivity", "Selected British voice for J.A.R.V.I.S.: ${britishVoice.name}")
                    }
                }
                DevilProtocol.FRIDAY -> {
                    // Warm, witty female companion accent (EXPLICITLY NON-BRITISH to never collide with JARVIS)
                    tts.setSpeechRate(1.15f)
                    tts.setPitch(1.28f)
                    val fridayVoice = availableVoices?.find {
                        !it.locale.country.equals("GB", ignoreCase = true) &&
                        !it.name.contains("gb", ignoreCase = true) &&
                        (it.locale.country.equals("IE", ignoreCase = true) ||
                         it.name.contains("dfz", ignoreCase = true) ||
                         it.name.contains("iom", ignoreCase = true) ||
                         it.name.contains("iob", ignoreCase = true))
                    } ?: availableVoices?.find {
                        !it.locale.country.equals("GB", ignoreCase = true) &&
                        !it.name.contains("gb", ignoreCase = true) &&
                        (it.name.contains("female", ignoreCase = true) || it.features?.contains("female") == true)
                    }
                    if (fridayVoice != null) {
                        tts.voice = fridayVoice
                        Log.i("MainActivity", "Selected F.R.I.D.A.Y. companion voice: ${fridayVoice.name}")
                    }
                }
                DevilProtocol.EDITH -> {
                    // Crisp, analytical, tactical US female synth voice
                    tts.setSpeechRate(1.20f)
                    tts.setPitch(1.02f)
                    val edithVoice = availableVoices?.find {
                        !it.locale.country.equals("GB", ignoreCase = true) &&
                        !it.name.contains("gb", ignoreCase = true) &&
                        (it.name.contains("tpf", ignoreCase = true) ||
                         it.name.contains("sfg", ignoreCase = true) ||
                         (it.locale.country.equals("US", ignoreCase = true) && it.name.contains("female", ignoreCase = true)))
                    }
                    if (edithVoice != null) {
                        tts.voice = edithVoice
                        Log.i("MainActivity", "Selected E.D.I.T.H. tactical voice: ${edithVoice.name}")
                    }
                }
                DevilProtocol.BABY_MONITOR -> {
                    // Warm, informal female relaxation voice
                    tts.setSpeechRate(1.08f)
                    tts.setPitch(1.25f)
                    val warmFemale = availableVoices?.find {
                        !it.locale.country.equals("GB", ignoreCase = true) &&
                        !it.name.contains("gb", ignoreCase = true) &&
                        (it.name.contains("iom", ignoreCase = true) || it.name.contains("female", ignoreCase = true))
                    }
                    if (warmFemale != null) tts.voice = warmFemale
                }
                DevilProtocol.BOSS_MODE -> {
                    // Standard lively, energetic, confident master voice
                    tts.setSpeechRate(1.18f)
                    tts.setPitch(1.22f)
                    val masterVoice = availableVoices?.find {
                        it.name.contains("iob", ignoreCase = true) || it.name.contains("tpd", ignoreCase = true)
                    }
                    if (masterVoice != null) {
                        tts.voice = masterVoice
                        Log.i("MainActivity", "Selected Boss Mode master voice: ${masterVoice.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error applying protocol voice profile", e)
        }
    }

    private fun handleProtocolSwitch(protocol: DevilProtocol) {
        DevilProtocolManager.setProtocol(protocol, this)
        securityStatus = protocol.title
        applyProtocolVoiceProfile(protocol)

        val announcement = when (protocol) {
            DevilProtocol.BARN_DOOR ->
                "Barn Door Protocol engaged. Media, notifications, and standard ringtones silenced. Standing by in whispered mode, Boss."
            DevilProtocol.HOUSE_PARTY ->
                "House Party Protocol engaged. Strict technical notification filters active. High-efficiency focus mode initiated, Boss."
            DevilProtocol.JARVIS ->
                "J.A.R.V.I.S. Protocol online. At your service, Boss. Business communications prioritized, distraction applications shielded."
            DevilProtocol.FRIDAY ->
                "F.R.I.D.A.Y. Protocol engaged! Systems synchronized, Boss. How can I help you today?"
            DevilProtocol.EDITH ->
                "E.D.I.T.H. Protocol online. Tactical defense systems armed. Standing by, Boss."
            DevilProtocol.BABY_MONITOR ->
                "Baby Monitor Protocol activated, Boss. Work communication blocked, relaxation and entertainment unlocked. Have a wonderful evening."
            DevilProtocol.BOSS_MODE ->
                "All protocols revoked. Master Boss Mode restored. DEVIL is fully at your command."
        }
        speakResponse(announcement)
    }

    private fun speakResponse(text: String, continueConversation: Boolean = false) {
        Log.i("MainActivity", "DEVIL SPEAK: $text (continueConversation=$continueConversation, inSession=$inConversationSession)")
        transcript = text
        assistantState = AssistantState.SPEAKING
        if (continueConversation) {
            refreshConversationSession()
        }
        val utteranceId = "DEVIL_${System.currentTimeMillis()}"
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
                val now = System.currentTimeMillis()
                val sessionActive = inConversationSession && (now < conversationSessionEndTime)

                if (sessionActive && assistantState != AssistantState.SPEAKING && assistantState != AssistantState.THINKING) {
                    // Conversation session is active within the 40-second window!
                    // Do NOT exit or force the user to tap again.
                    assistantState = AssistantState.IDLE
                    val secondsLeft = ((conversationSessionEndTime - now) / 1000L).coerceAtLeast(0)
                    transcript = "Listening... (${secondsLeft}s)"
                    Log.i("MainActivity", "Recognizer error $error in active 40s session. Auto-re-listening (${secondsLeft}s remaining)...")
                    scope.launch(Dispatchers.Main) {
                        delay(250)
                        if (inConversationSession && System.currentTimeMillis() < conversationSessionEndTime && assistantState == AssistantState.IDLE && !isListening) {
                            startListening()
                        }
                    }
                } else {
                    assistantState = AssistantState.IDLE
                    inConversationSession = false
                    autoListenAfterSpeech = false
                    transcript = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "Standing by, Boss."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Standing by, Boss."
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording issue"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer busy, tap to retry"
                        else -> "Error: $error"
                    }
                    stopSco()
                }
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    transcript = text
                    assistantState = AssistantState.THINKING
                    refreshConversationSession() // Reset 40-second timer on user speech!
                    processCommand(text)
                } else {
                    if (inConversationSession && System.currentTimeMillis() < conversationSessionEndTime) {
                        scope.launch(Dispatchers.Main) {
                            delay(200)
                            if (!isListening && assistantState == AssistantState.IDLE) {
                                startListening()
                            }
                        }
                    } else {
                        assistantState = AssistantState.IDLE
                    }
                }
                stopSco()
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListening() {
        if (isListening || assistantState == AssistantState.SPEAKING) return
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
            // Natural speech endpointing: allow comfortable breathing pause without premature cutoff
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 400L)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
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
            "syam", "syaam", "shyam", "shyaam", "sham", "syam fincare", "shyam fincare" -> "Syam"
            else -> spoken
        }
    }

    private fun handlePersonIntroduction(input: String): Boolean {
        val lower = input.lowercase(Locale.getDefault()).trim()

        // Filter out non-introduction queries (calls, music, navigation, queries, negative statements, etc.)
        val nonPersonKeywords = listOf(
            "playlist", "song", "music", "audio", "track", "album", "video", "movie", "vlc",
            "not ", "isn't", "aren't", "wasn't", "phone", "app", "what", "why", "how", "when",
            "weather", "where", "temp", "call ", "play ", "open ", "directions"
        )
        if (nonPersonKeywords.any { lower.contains(it) }) {
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
        val regexes = listOf(
            Regex("(?i).*(?:his|her)\\s+name\\s+is\\s+([a-zA-Z]+(?:\\s+[a-zA-Z]+)?)"),
            Regex("(?i).*(?:say\\s+(?:hi|hello|hey)\\s+to|greet|welcome)\\s+(?:my\\s+\\w+\\s+|our\\s+\\w+\\s+)?([a-zA-Z]+(?:\\s+[a-zA-Z]+)?)"),
            Regex("(?i).*(?:this\\s+is|he\\s+is|she\\s+is|meet)\\s+(?:my\\s+\\w+\\s+|our\\s+\\w+\\s+)?([a-zA-Z]+(?:\\s+[a-zA-Z]+)?)"),
            Regex("(?i).*introduce\\s+(?:yourself\\s+to\\s+)?(?:my\\s+\\w+\\s+|our\\s+\\w+\\s+)?([a-zA-Z]+(?:\\s+[a-zA-Z]+)?)")
        )

        val fillerWords = setOf(
            "a", "an", "the", "my", "our", "him", "her", "them", "someone", "everyone", "devil", "assistant",
            "not", "no", "never", "good", "bad", "cool", "okay", "great", "fine", "boring", "awesome",
            "right", "wrong", "here", "there", "test", "real", "working", "done"
        )

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

    private fun handleMapsAndDistanceCommand(input: String): Boolean {
        val trimmed = input.trim()
        val lower = trimmed.lowercase(Locale.getDefault())

        val isMapsIntent = lower.contains("distance") ||
                lower.contains("how far") ||
                lower.contains("directions") ||
                lower.contains("route to") ||
                lower.contains("navigate") ||
                lower.contains("take me to") ||
                lower.contains("show way to") ||
                lower.contains("open maps") ||
                lower.contains("google maps") ||
                lower == "maps"

        if (!isMapsIntent) return false

        // 1. Two-point distance: "distance between A and B" or "distance from A to B"
        val twoPointRegex1 = Regex("(?i).*(?:distance|how\\s+far)\\s+(?:between|from)\\s+(.+?)\\s+(?:and|to)\\s+(.+)")
        val match1 = twoPointRegex1.find(trimmed)
        if (match1 != null) {
            val origin = match1.groupValues[1].trim()
            val destination = match1.groupValues[2].trim()
            openMapsRoute(origin, destination)
            return true
        }

        // 2. "How far is A from B"
        val twoPointRegex2 = Regex("(?i).*how\\s+far\\s+is\\s+(.+?)\\s+from\\s+(.+)")
        val match2 = twoPointRegex2.find(trimmed)
        if (match2 != null) {
            val destination = match2.groupValues[1].trim()
            val origin = match2.groupValues[2].trim()
            openMapsRoute(origin, destination)
            return true
        }

        // 3. Single destination distance:
        // "what is the distance to Chennai", "distance to Hyderabad", "distance of Mumbai", "how far is Bangalore"
        val singleDestRegex = Regex("(?i).*(?:distance\\s+(?:to|of|for)|how\\s+far\\s+is)\\s+(.+)")
        val match3 = singleDestRegex.find(trimmed)
        if (match3 != null) {
            val destination = match3.groupValues[1]
                .replace(Regex("(?i)^(?:the\\s+)?(?:city\\s+of\\s+)?"), "")
                .replace(Regex("(?i)\\b(?:in\\s+google\\s+maps|on\\s+maps|in\\s+maps)\\b"), "")
                .trim()
            if (destination.isNotBlank()) {
                openMapsRoute(origin = null, destination = destination)
                return true
            }
        }

        // 4. Navigation & Directions:
        // "directions to X", "route to X", "navigate to X", "take me to X", "show way to X"
        val navRegex = Regex("(?i).*(?:directions?|route|navigate|take\\s+me|show\\s+way)\\s+to\\s+(.+)")
        val matchNav = navRegex.find(trimmed)
        if (matchNav != null) {
            val destination = matchNav.groupValues[1]
                .replace(Regex("(?i)\\b(?:in\\s+google\\s+maps|on\\s+maps|in\\s+maps)\\b"), "")
                .trim()
            if (destination.isNotBlank()) {
                openMapsNavigation(destination)
                return true
            }
        }

        // 5. General "open maps" / "maps"
        if (lower.contains("maps")) {
            openApp("maps")
            speakResponse("Opening Google Maps.")
            return true
        }

        return false
    }

    private fun handleWeatherCommand(input: String): Boolean {
        val lower = input.lowercase(Locale.getDefault())
        val isWeather = lower.contains("weather") || lower.contains("temperature") || lower.contains("climate")
        if (!isWeather) return false

        // Extract city if present
        var city = "Bengaluru"
        val cityMatch = Regex("(?i)\\b(?:in|at|for|of)\\s+([a-zA-Z\\s]+)").find(input)
        if (cityMatch != null) {
            val candidate = cityMatch.groupValues[1].trim()
                .replace(Regex("(?i)\\b(?:today|tomorrow|now|right\\s+now|please)\\b"), "")
                .trim()
            if (candidate.isNotEmpty()) {
                city = candidate
            }
        }

        scope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                assistantState = AssistantState.THINKING
                transcript = "Checking weather for $city..."
            }
            try {
                val cleanKey = city.lowercase(Locale.getDefault())
                val knownCoords = mapOf(
                    "bangalore" to Pair(12.9716, 77.5946),
                    "bengaluru" to Pair(12.9716, 77.5946),
                    "mumbai" to Pair(19.0760, 72.8777),
                    "bombay" to Pair(19.0760, 72.8777),
                    "delhi" to Pair(28.6139, 77.2090),
                    "new delhi" to Pair(28.6139, 77.2090),
                    "hyderabad" to Pair(17.3850, 78.4867),
                    "chennai" to Pair(13.0827, 80.2707),
                    "madras" to Pair(13.0827, 80.2707),
                    "kolkata" to Pair(22.5726, 88.3639),
                    "pune" to Pair(18.5204, 73.8567)
                )

                val coords = knownCoords[cleanKey] ?: run {
                    val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=${Uri.encode(city)}&count=5"
                    val geoConn = java.net.URL(geoUrl).openConnection() as java.net.HttpURLConnection
                    geoConn.connectTimeout = 3000
                    geoConn.readTimeout = 3000
                    val jsonStr = geoConn.inputStream.bufferedReader().readText()
                    val latMatch = Regex("\"latitude\":([0-9.]+)").find(jsonStr)
                    val lonMatch = Regex("\"longitude\":([0-9.]+)").find(jsonStr)
                    if (latMatch != null && lonMatch != null) {
                        Pair(latMatch.groupValues[1].toDouble(), lonMatch.groupValues[1].toDouble())
                    } else {
                        Pair(12.9716, 77.5946)
                    }
                }

                val weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=${coords.first}&longitude=${coords.second}&current=temperature_2m,relative_humidity_2m,weather_code"
                val conn = java.net.URL(weatherUrl).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                val weatherJson = conn.inputStream.bufferedReader().readText()

                val tempMatch = Regex("\"temperature_2m\":([0-9.-]+)").find(weatherJson)
                val humMatch = Regex("\"relative_humidity_2m\":([0-9]+)").find(weatherJson)
                val codeMatch = Regex("\"weather_code\":([0-9]+)").find(weatherJson)

                val temp = tempMatch?.groupValues?.get(1)?.toDoubleOrNull()?.let { "%.1f".format(it) } ?: "23"
                val hum = humMatch?.groupValues?.get(1) ?: "75"
                val code = codeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

                val condition = when (code) {
                    0 -> "clear skies"
                    1, 2 -> "partly cloudy"
                    3 -> "overcast and cloudy"
                    45, 48 -> "foggy"
                    51, 53, 55 -> "light drizzle"
                    61, 63, 65 -> "rainy"
                    80, 81, 82 -> "rain showers"
                    95, 96, 99 -> "thunderstorms"
                    else -> "fair"
                }

                val displayCity = city.replaceFirstChar { it.uppercase() }
                val weatherResponse = "Currently in $displayCity, it is $temp degrees Celsius with $condition and $hum percent humidity."
                withContext(Dispatchers.Main) {
                    speakResponse(weatherResponse)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Weather fetch error", e)
                withContext(Dispatchers.Main) {
                    speakResponse("I couldn't fetch the current weather. Please check your internet connection.")
                }
            }
        }
        return true
    }

    private val vlcPlaylists = mapOf(
        "mood" to 66,
        "mass" to 158,
        "slow" to 49,
        "test" to 27,
        "m" to 330,
        "s" to 64,
        "o" to 97,
        "h" to 1
    )

    private fun normalizeSpokenCommand(command: String): String {
        var norm = command.trim()
        // Fix Darwinbox phonetic misrecognitions: "net box", "netbox", "down box", "dial box", "diamond box", "darling box", "darwin box", "darwin's box"
        norm = norm.replace(Regex("(?i)\\b(net\\s*box|netbox|down\\s*box|dial\\s*box|diamond\\s*box|darling\\s*box|darwin\\s+box|darwin's\\s*box)\\b"), "darwinbox")
        // Fix VLC phonetic misrecognitions: "vluc", "v l c", "v.l.c.", "velc", "vl c"
        norm = norm.replace(Regex("(?i)\\b(?:vluc|v\\.?\\s*l\\.?\\s*c\\.?|velc|vl\\s+c)\\b"), "vlc")
        // Fix "play list" / "play lists" / "play-list" -> "playlist" / "playlists"
        norm = norm.replace(Regex("(?i)\\bplay\\s+lists\\b"), "playlists")
        norm = norm.replace(Regex("(?i)\\bplay\\s+list\\b"), "playlist")
        norm = norm.replace(Regex("(?i)\\bplay-lists?\\b"), "playlist")
        // Fix WhatsApp misrecognitions
        norm = norm.replace(Regex("(?i)\\bwhat\\s*'?s?\\s*app\\b"), "whatsapp")
        // Fix phonetic / typo variations for "ask": "aks"
        norm = norm.replace(Regex("(?i)\\baks\\b"), "ask")
        // Fix phonetic misrecognition of "test player" / "test playlist" as "latest player", "latest ladies", etc.
        norm = norm.replace(Regex("(?i)\\b(?:latest|taste|tasted)\\s+(?:player|playlist|play\\s*list|music\\s*player|play\\s*store\\s*music\\s*player|play\\s*store)\\b"), "test playlist")
        norm = norm.replace(Regex("(?i)\\blatest\\s+ladies\\b"), "test playlist")
        return norm
    }

    private fun handleMediaAndVlcCommand(input: String): Boolean {
        val trimmed = normalizeSpokenCommand(input)
        val lower = trimmed.lowercase(Locale.getDefault())

        // 1. Direct App Launch for VLC
        if (lower in listOf("vlc", "vlc player", "open vlc", "open vlc player", "launch vlc", "start vlc", "start vlc player")) {
            openApp("vlc")
            speakResponse("Opening VLC player.")
            return true
        }

        // 2. Specific playlist track count inquiry (e.g., "how many songs in mood playlist")
        val specificPlaylistCountRegex = Regex("(?i).*(?:how\\s+many|count|number\\s+of)\\s+(?:songs?|tracks?)\\s+(?:are\\s+there\\s+)?(?:in|on|for)\\s+(?:the\\s+|my\\s+)?([a-zA-Z0-9_-]+)(?:\\s+playlist)?.*")
        if (specificPlaylistCountRegex.matches(lower)) {
            val name = specificPlaylistCountRegex.find(lower)?.groupValues?.get(1)?.trim()?.lowercase(Locale.getDefault())
            if (name != null && vlcPlaylists.containsKey(name)) {
                val count = vlcPlaylists[name]
                speakResponse("Playlist $name in VLC player has $count tracks. You can say 'play $name playlist' to listen.")
                return true
            }
        }

        // 3. General VLC playlist inquiry (e.g., "how many playlists are there in my vlc player", "what playlists do I have", "list playlists")
        val isPlaylistInquiry = (
            (lower.contains("playlist") || lower.contains("playlists")) && (
                lower.contains(Regex("\\b(?:what|show|which|tell|how\\s+many|count|number\\s+of|total|check|find|any|list|lists|all)\\b")) ||
                lower.contains(Regex("\\bhow\\s+much\\b"))
            )
        ) || lower in listOf("playlists", "vlc playlists", "my playlists", "playlist list", "all playlists", "playlist count", "vlc playlist count")

        if (isPlaylistInquiry) {
            val count = vlcPlaylists.size
            val listStr = vlcPlaylists.entries.joinToString(", ") { "${it.key} (${it.value} tracks)" }
            speakResponse("In VLC player you have $count playlists: $listStr. You can say 'play mood playlist' or 'play mass playlist' to start listening.")
            return true
        }

        // 3.5 Media playback controls (pause, resume, next, previous)
        val isPause = lower in listOf("pause", "pause music", "pause song", "stop music", "pause the music", "pause the song", "stop playback", "stop playing", "pause playback", "stop the music")
        val isResume = lower in listOf("resume", "resume music", "resume song", "continue music", "resume the music", "unpause", "resume playback")
        val isNext = lower in listOf("next song", "next track", "skip", "skip song", "next", "skip track", "play next song", "next song please")
        val isPrevious = lower in listOf("previous song", "previous track", "previous", "go back song", "last song", "play previous song")

        if (isPause) {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            speakResponse("Music paused, Boss.")
            return true
        }
        if (isResume) {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
            speakResponse("Resuming music, Boss.")
            return true
        }
        if (isNext) {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT))
            speakResponse("Skipping to next song, Boss.")
            return true
        }
        if (isPrevious) {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            speakResponse("Playing previous song, Boss.")
            return true
        }

        // 4. Media playback & shuffle
        val isMedia = lower.startsWith("play ") ||
                lower.startsWith("start playing ") ||
                lower.startsWith("shuffle ") ||
                lower.contains("play on shuffle") ||
                lower.contains("play random") ||
                lower.contains("play playlist") ||
                lower.contains("playlist") ||
                lower.contains("play album") ||
                lower.contains("play artist") ||
                lower.contains("play song") ||
                lower.contains("play music") ||
                lower.contains("in vlc") ||
                lower.contains("on vlc") ||
                lower == "play" ||
                lower == "play music" ||
                lower == "play songs" ||
                lower == "shuffle" ||
                lower == "shuffle songs" ||
                lower == "shuffle music"

        if (!isMedia) {
            return false
        }

        val isShuffle = lower.contains("shuffle") || lower.contains("random")

        var query = trimmed
            .replace(Regex("(?i)^(?:can\\s+you\\s+(?:please\\s+)?|please\\s+)?(?:play|start\\s+playing|shuffle(?:\\s+play)?|play\\s+on\\s+shuffle|play\\s+random)\\s*"), "")
            .replace(Regex("(?i)\\b(?:in|on|from|using|with)\\s+vlc(?:\\s+player)?\\b"), "")
            .replace(Regex("(?i)\\bvlc(?:\\s+player)?\\b"), "")
            .replace(Regex("(?i)\\bon\\s+shuffle\\b|\\bshuffle\\b|\\brandom\\b"), "")
            .trim()

        playMedia(query, shuffle = isShuffle)
        return true
    }

    private fun handleVoiceCalibration() {
        speakResponse("Boss voice calibration initiated. After the tone, please speak continuously for three seconds: 'I am the Boss, authorize DEVIL'.")
        scope.launch(Dispatchers.Main) {
            delay(4200)
            assistantState = AssistantState.LISTENING
            transcript = "Calibrating Boss Voiceprint... Speak now!"
            val pcmBytes = voiceRecorder.recordCalibration(3500L)
            assistantState = AssistantState.THINKING
            if (pcmBytes.size > VoiceBiometrics.FRAME_SIZE * 4) {
                val embedding = VoiceBiometrics.extractEmbedding(pcmBytes)
                bossSecurity.saveBossVoiceprint(embedding)
                securityStatus = "BOSS SECURED (ENROLLED)"
                speakResponse("Calibration successful! Your master acoustic voiceprint is now locked to DEVIL, Boss.")
                transcript = "Boss Voiceprint Enrolled Successfully"
            } else {
                speakResponse("Could not capture sufficient audio for calibration. Please try again.")
                transcript = "Calibration failed: insufficient audio"
            }
            assistantState = AssistantState.IDLE
        }
    }

    private fun processCommand(command: String) {
        val trimmed = normalizeSpokenCommand(command)
        Log.i("MainActivity", "COMMAND RECEIVED: '$command' -> TRIMMED: '$trimmed'")

        // ── Voice Calibration & Voiceprint Management ──
        if (trimmed.contains(Regex("(?i)\\b(calibrate\\s*(my\\s*)?voice|enroll\\s*(my\\s*)?voice|setup\\s*voiceprint|register\\s*(my\\s*)?voice|record\\s*voiceprint)\\b"))) {
            handleVoiceCalibration()
            return
        }
        if (trimmed.contains(Regex("(?i)\\b(reset\\s*voiceprint|clear\\s*voiceprint|delete\\s*voiceprint|unregister\\s*voice)\\b"))) {
            bossSecurity.clearBossVoiceprint()
            securityStatus = bossSecurity.getSecurityStatus()
            speakResponse("Boss voiceprint cleared. DEVIL is back in open mode.")
            return
        }

        // ── Conversation Memory & Session Control ──
        if (trimmed.contains(Regex("(?i)\\b(clear\\s*(?:chat|history|conversation|memory)|reset\\s*(?:chat|conversation|memory)|forget\\s*(?:that|everything|previous))\\b"))) {
            LlamaHelper.clearHistory()
            inConversationSession = true
            speakResponse("Conversation memory cleared, Boss. What's on your mind?", continueConversation = true)
            return
        }
        if (trimmed.contains(Regex("(?i)\\b(that's\\s*all|that\\s*is\\s*all|bye|goodbye|see\\s*you|stop\\s*listening|exit\\s*chat|never\\s*mind|nothing\\s*else|go\\s*to\\s*sleep)\\b"))) {
            endConversationSession("Standing by, Boss.")
            return
        }

        // ── Persistent Long-Term Memory (BossMemoryVault) ──
        // 1. Remember a fact
        val rememberRegex = Regex("(?i)^(?:devil\\s*,?\\s*)?(?:please\\s+)?(?:remember\\s+(?:that\\s+)?|note\\s+(?:down\\s+)?(?:that\\s+)?|commit\\s+to\\s+memory\\s+(?:that\\s+)?)(.+)")
        if (rememberRegex.containsMatchIn(trimmed) && !trimmed.contains("do you remember") && !trimmed.contains("what do you remember")) {
            val match = rememberRegex.find(trimmed)
            val fullStatement = match?.groupValues?.get(1)?.trim() ?: ""
            val splitRegex = Regex("(?i)\\s+(?:is|=|are|was)\\s+")
            val parts = fullStatement.split(splitRegex, limit = 2)
            val (key, fact) = if (parts.size == 2) {
                parts[0].replace(Regex("(?i)^(?:my|the|that)\\s+"), "").trim() to parts[1].trim()
            } else {
                fullStatement to fullStatement
            }
            memoryVault.remember(key, fact)
            inConversationSession = true
            speakResponse("Got it, Boss! I've stored in your memory vault that $key is $fact.", continueConversation = true)
            return
        }

        // 2. List all memories
        if (trimmed.contains(Regex("(?i)\\b(what\\s+(?:do\\s+you|did\\s+you)\\s+remember|list\\s+(?:my\\s+)?memories|show\\s+(?:my\\s+)?memories|what's\\s+in\\s+(?:my\\s+)?memory\\s+vault)\\b"))) {
            inConversationSession = true
            val summary = memoryVault.listMemoriesSummary()
            speakResponse(summary, continueConversation = true)
            return
        }

        // 3. Forget/Delete memory
        val forgetMemoryRegex = Regex("(?i)^(?:forget|delete\\s+(?:the\\s+)?memory\\s+(?:about|of)?|remove\\s+(?:the\\s+)?memory\\s+(?:about|of)?)\\s+(?:my\\s+)?(.+)")
        if (forgetMemoryRegex.matches(trimmed) && !trimmed.contains("chat") && !trimmed.contains("history")) {
            val target = forgetMemoryRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: ""
            inConversationSession = true
            val success = memoryVault.forget(target)
            if (success) {
                speakResponse("Memory regarding '$target' has been purged from your vault, Boss.", continueConversation = true)
            } else {
                speakResponse("I couldn't find any memory stored for '$target', Boss.", continueConversation = true)
            }
            return
        }

        // 4. Recall a specific fact
        val recallRegex = Regex("(?i)^(?:what(?:'s|\\s+is|\\s+was)|do\\s+you\\s+(?:know|remember)|recall)\\s+(?:my\\s+|about\\s+my\\s+|about\\s+)?(.+?)(?:\\s*\\?)?$")
        if (recallRegex.matches(trimmed) && !trimmed.contains("weather") && !trimmed.contains("time") && !trimmed.contains("date") && !trimmed.contains("name") && !trimmed.contains("holiday") && !trimmed.contains("shift") && !trimmed.contains("leave") && !trimmed.contains("calendar") && !trimmed.contains("protocol")) {
            val target = recallRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: ""
            val item = memoryVault.recall(target)
            if (item != null) {
                inConversationSession = true
                speakResponse("According to your memory vault, Boss: ${item.key} is ${item.fact}.", continueConversation = true)
                return
            }
        }

        val greetingRegex = Regex("(?i)^(?:hello|hi|hey|good\\s+(?:morning|afternoon|evening)|namaste|hola)\\b.*")
        val identityRegex = Regex("(?i).*(?:who\\s+are\\s+you|what(?:'s|\\s+is)\\s+your\\s+name|what\\s+are\\s+you|introduce\\s+yourself(?!(?:\\s+to|\\s+with))).*")
        val howAreYouRegex = Regex("(?i).*(?:how\\s+are\\s+you|how's\\s+it\\s+going|how\\s+are\\s+things).*")
        val thanksRegex = Regex("(?i).*(?:thank\\s+you|thanks|thx).*")
        val helpRegex = Regex("(?i).*(?:what\\s+can\\s+you\\s+do|help\\s+me|help).*")

        // Enhanced WhatsApp detection
        val isWhatsApp = trimmed.contains(Regex("(?i)\\bwhatsapp\\b"))
        // Enhanced SMS detection
        val isSms = trimmed.contains(Regex("(?i)\\b(?:texts?|sms|messages?)\\b")) && !isWhatsApp

        val tryVoiceNoteRegex = Regex("(?i).*(?:try|test)\\s+(?:the\\s+)?(?:whatsapp|voice)\\s+note.*")
        val voiceNoteRegex = Regex(
            "(?i)^(?:can\\s+you\\s+|please\\s+)?(?:send\\s+)?(?:a\\s+)?(?:whatsapp\\s+)?(?:voice\\s+note|audio\\s+note|voice\\s+message|audio\\s+message|note)\\s+(?:to\\s+)?(.+?)\\s+(?:saying|say|tell|telling|that|message)\\s+(.+)"
        )
        val voiceNoteSimpleRegex = Regex(
            "(?i)^(?:can\\s+you\\s+|please\\s+)?(?:send\\s+)?(?:a\\s+)?(?:whatsapp\\s+)?(?:voice\\s+note|audio\\s+note|voice\\s+message|audio\\s+message)\\s+(?:to\\s+)?([a-zA-Z0-9\\s]{1,25}?)\\s*[:,]?\\s+(.+)"
        )
        val sendWhatsAppRegex = Regex(
            "(?i)^(?:can\\s+you\\s+|please\\s+)?(?:send\\s+)?(?:a\\s+)?whatsapp(?:\\s+message)?\\s+(?:to\\s+)?(.+?)\\s+(?:saying|say|tell|telling|that)\\s+(.+)"
        )
        val tellOnWhatsAppRegex = Regex(
            "(?i)^(?:can\\s+you\\s+|please\\s+)?(?:tell|message|inform|convey\\s+to)\\s+(.+?)\\s+on\\s+whatsapp\\s+(?:saying|that|to)?\\s*(.+)"
        )
        val whatsAppVerbRegex = Regex(
            "(?i)^(?:can\\s+you\\s+|please\\s+)?whatsapp\\s+(.+?)\\s+(?:saying|say|tell|telling|that)\\s+(.+)"
        )

        val callAndSayRegex = Regex("(?i)^(?:can\\s+you\\s+|please\\s+)?call\\s+(.+?)\\s+(?:and\\s+|to\\s+)?(?:say(?:\\s+to\\s+(?:him|her|them))?|tell(?:\\s+(?:to\\s+)?(?:him|her|them))?|inform(?:\\s+(?:to\\s+)?(?:him|her|them))?|convey(?:\\s+(?:to\\s+)?(?:him|her|them))?|ask(?:\\s+(?:to\\s+)?(?:him|her|them))?)\\s+(?:that\\s+)?(.+)")
        val callRegex = Regex("(?i)call\\s+(.+)")
        val openAppRegex = Regex("(?i)(?:open|launch|start)\\s+(.+)")

        val isLockdown = trimmed.matches(Regex("(?i).*(?:lock\\s*down|cancel\\s+guest|revoke\\s+guest|boss\\s+only).*"))
        val isBossModeLogic = trimmed.matches(Regex("(?i).*(?:what\\s+is\\s+(?:the\\s+)?(?:logic\\s+of\\s+)?boss\\s+mode|explain\\s+boss\\s+mode|how\\s+does\\s+boss\\s+mode\\s+work).*"))
        val isBossCheck = trimmed.matches(Regex("(?i).*(?:who\\s+is\\s+(?:your|the)\\s+boss|do\\s+you\\s+have\\s+a\\s+boss|who\\s+is\\s+in\\s+charge|who\\s+runs\\s+(?:you|this)).*"))
        val isUserIdentityCheck = trimmed.matches(Regex("(?i).*(?:who\\s+am\\s+i|about\\s+me|tell\\s+me\\s+about\\s+me|what\\s+is\\s+my\\s+role|my\\s+identity|do\\s+you\\s+know\\s+who\\s+am\\s+i).*"))
        val isAuthCheck = trimmed.matches(Regex("(?i).*(?:who\\s+is\\s+authorized|authorization\\s+status|who\\s+can\\s+talk|who\\s+has\\s+access).*"))

        when {
            // ── 0. Instant Greetings Fast-Path ──
            trimmed.contains(Regex("(?i)\\b(hi|hello|hey|namaste|hola|good\\s+(?:morning|afternoon|evening))\\b")) && trimmed.length <= 25 -> {
                inConversationSession = true
                speakResponse("Hello Boss! DEVIL is online and fully at your service. What's on your mind?", continueConversation = true)
            }

            // ── 0. Boss Security Lockdown & Authorization ──
            isLockdown -> {
                val msg = bossSecurity.revokeGuest()
                securityStatus = bossSecurity.getSecurityStatus()
                speakResponse(msg)
            }
            isBossModeLogic -> {
                speakResponse("Boss Mode designates you as the sole authorized master of DEVIL with full clearance over all phone features. Guests can only interact when introduced by you, and can be revoked at any time with lockdown.")
            }
            isBossCheck -> {
                speakResponse("You are my Boss. You hold master voice clearance over DEVIL and all device subsystems.")
            }
            isUserIdentityCheck -> {
                if (bossSecurity.isGuestSessionActive()) {
                    speakResponse("You are the Boss with master clearance, currently hosting guest ${bossSecurity.activeGuest}. Your word is the ultimate command.")
                } else {
                    speakResponse("You are the Boss. You hold master clearance over DEVIL and this device. Your command is absolute.")
                }
            }
            isAuthCheck -> {
                if (bossSecurity.isGuestSessionActive()) {
                    speakResponse("You are the Boss with master voice clearance. Active guest ${bossSecurity.activeGuest} is currently authorized to speak with DEVIL.")
                } else {
                    speakResponse("Boss-only mode is active. Master voice clearance is required. No outside guests are authorized.")
                }
            }
            // ── 0.0 Protocol Specific Inquiries & Explanations ──
            // J.A.R.V.I.S. Specific Inquiry ("what is jarvis", "who is jarvis", "tell me about jarvis", "what is jarvis protocol")
            (trimmed.contains(Regex("(?i)\\b(what\\s+(is|are|does)|who\\s+is|tell\\s+me\\s+about|explain|describe|details\\s+(of|about)?|meaning\\s+of)\\b.*\\b(j\\.?a\\.?r\\.?v\\.?i\\.?s\\.?|jarvis|jarves|jervis)\\b")) ||
             trimmed.contains(Regex("(?i)\\b(j\\.?a\\.?r\\.?v\\.?i\\.?s\\.?|jarvis|jarves|jervis)\\b.*\\b(protocol|mode)\\s+(details|info|explanation)\\b"))) -> {
                inConversationSession = true
                speakResponse("J.A.R.V.I.S. is your formal British AI protocol, Boss. When activated, it adopts a formal British gentleman persona, prioritizes office and business communications, and shields you from leisure notifications.", continueConversation = true)
            }

            // F.R.I.D.A.Y. Specific Inquiry ("what is friday", "who is friday", "tell me about friday", "what is friday protocol")
            (trimmed.contains(Regex("(?i)\\b(what\\s+(is|are|does)|who\\s+is|tell\\s+me\\s+about|explain|describe|details\\s+(of|about)?|meaning\\s+of)\\b.*\\b(f\\.?r\\.?i\\.?d\\.?a\\.?y\\.?|friday|fryday)\\b")) ||
             trimmed.contains(Regex("(?i)\\b(f\\.?r\\.?i\\.?d\\.?a\\.?y\\.?|friday|fryday)\\b.*\\b(protocol|mode)\\s+(details|info|explanation)\\b"))) -> {
                inConversationSession = true
                speakResponse("F.R.I.D.A.Y. is your tactical companion AI protocol, Boss. When engaged, she speaks with a warm, energetic companion persona, synchronizes device subsystems, and provides agile day-to-day tactical assistance.", continueConversation = true)
            }

            // E.D.I.T.H. Specific Inquiry ("what is edith", "who is edith", "tell me about edith", "what is edith protocol")
            (trimmed.contains(Regex("(?i)\\b(what\\s+(is|are|does)|who\\s+is|tell\\s+me\\s+about|explain|describe|details\\s+(of|about)?|meaning\\s+of)\\b.*\\b(e\\.?d\\.?i\\.?t\\.?h\\.?|edith)\\b")) ||
             trimmed.contains(Regex("(?i)\\b(e\\.?d\\.?i\\.?t\\.?h\\.?|edith)\\b.*\\b(protocol|mode)\\s+(details|info|explanation)\\b"))) -> {
                inConversationSession = true
                speakResponse("E.D.I.T.H. stands for 'Even Dead, I'm The Hero', Boss. It is your tactical defense protocol that locks down entertainment apps, heightens security monitoring, and utilizes a precise, analytical voice profile.", continueConversation = true)
            }

            // Barn Door Specific Inquiry ("what is barn door", "tell me about barn door", "what is barn door protocol")
            trimmed.contains(Regex("(?i)\\b(what\\s+(is|are|does)|tell\\s+me\\s+about|explain|describe)\\b.*\\b(barn\\s*door|silent\\s*protocol|meeting\\s*mode)\\b")) -> {
                inConversationSession = true
                speakResponse("Barn Door Protocol is your silent meeting and stealth mode, Boss. It silences all media, standard ringtones, and notifications except for VIP contacts, keeping DEVIL operating in a quiet whispered voice.", continueConversation = true)
            }

            // House Party Specific Inquiry ("what is house party", "tell me about house party", "what is house party protocol")
            trimmed.contains(Regex("(?i)\\b(what\\s+(is|are|does)|tell\\s+me\\s+about|explain|describe)\\b.*\\b(house\\s*party|development\\s*mode|dev\\s*mode|coding\\s*mode)\\b")) -> {
                inConversationSession = true
                speakResponse("House Party Protocol is your high-focus development and coding mode, Boss. It filters out distractions and only permits critical technical notifications from GitHub, Termux, and Jira so you can code without interruption.", continueConversation = true)
            }

            // Baby Monitor / Chill Mode Specific Inquiry ("what is baby monitor", "tell me about baby monitor", "what is chill mode")
            trimmed.contains(Regex("(?i)\\b(what\\s+(is|are|does)|tell\\s+me\\s+about|explain|describe)\\b.*\\b(baby\\s*monitor|chill\\s*mode|sunset\\s*protocol|leisure\\s*mode)\\b")) -> {
                inConversationSession = true
                speakResponse("Baby Monitor Protocol is your leisure and relaxation mode, Boss. It blocks work communication from Teams, Slack, Outlook, and Darwinbox while unlocking entertainment and music for your evening downtime.", continueConversation = true)
            }

            // Sentry Mode Specific Inquiry ("what is sentry mode", "tell me about sentry mode", "explain sentry mode")
            trimmed.contains(Regex("(?i)\\b(what\\s+(is|are|does)|tell\\s+me\\s+about|explain|describe)\\b.*\\bsentry(\\s*mode)?\\b")) -> {
                inConversationSession = true
                speakResponse("Sentry Mode is DEVIL's hands-free perimeter monitoring protocol, Boss. It activates background audio listening so you can give commands completely hands-free by saying 'Hey DEVIL'.", continueConversation = true)
            }

            // General Protocols List Inquiry ("what are the protocols", "list protocols", "tell me about protocols")
            (!trimmed.contains(Regex("(?i)\\b(jarvis|friday|edith|barn|house|baby|chill|sentry|boss)\\b")) &&
             trimmed.contains(Regex("(?i)\\b(list\\s+(?:all\\s+)?protocols?|what\\s+(?:are\\s+the\\s+|kind\\s+of\\s+)?protocols?|which\\s+protocols?|tell\\s+me\\s+about\\s+(?:all\\s+)?protocols?|protocols\\s+list)\\b"))) -> {
                inConversationSession = true
                speakResponse("I have several operational protocols, Boss: 1. Boss Mode for master clearance, 2. J.A.R.V.I.S. Protocol for formal British assistance, 3. F.R.I.D.A.Y. Protocol for tactical companion mode, 4. E.D.I.T.H. Protocol for tactical defense, 5. Sentry Mode for hands-free perimeter monitoring, 6. Barn Door Protocol for silent meetings, and 7. House Party Protocol for focused development.", continueConversation = true)
            }

            // ── Sentry Mode Activation & Revocation (Hands-Free Wake-Word) ──
            DevilProtocolManager.isSentryModeTrigger(trimmed) -> {
                DevilProtocolManager.activateSentryMode(this)
                securityStatus = "SENTRY MODE // AUDIO ACTIVE"
                speakResponse("Sentry Mode engaged, Boss. Perimeter audio sensors online. Standing by for 'Hey DEVIL'.")
            }
            DevilProtocolManager.isSentryModeRevokeTrigger(trimmed) -> {
                DevilProtocolManager.revokeSentryMode(this)
                securityStatus = DevilProtocolManager.currentProtocol.title
                speakResponse("Sentry Mode revoked, Boss. Audio sensors powered down to battery-saver mode.")
            }

            // ── 0.1 DEVIL Operational Protocols & Boss Mode Revocation ──
            DevilProtocolManager.isBossModeRevocation(trimmed) -> {
                handleProtocolSwitch(DevilProtocol.BOSS_MODE)
            }
            DevilProtocolManager.isJarvisTrigger(trimmed) -> {
                handleProtocolSwitch(DevilProtocol.JARVIS)
            }
            DevilProtocolManager.isFridayTrigger(trimmed) -> {
                handleProtocolSwitch(DevilProtocol.FRIDAY)
            }
            DevilProtocolManager.isEdithTrigger(trimmed) -> {
                handleProtocolSwitch(DevilProtocol.EDITH)
            }
            DevilProtocolManager.isBarnDoorTrigger(trimmed) -> {
                handleProtocolSwitch(DevilProtocol.BARN_DOOR)
            }
            DevilProtocolManager.isHousePartyTrigger(trimmed) -> {
                handleProtocolSwitch(DevilProtocol.HOUSE_PARTY)
            }
            DevilProtocolManager.isBabyMonitorTrigger(trimmed) -> {
                handleProtocolSwitch(DevilProtocol.BABY_MONITOR)
            }

            // ── 1. Person Introduction & Greeting Fast-Path ──
            handlePersonIntroduction(trimmed) -> {
                // Handled directly inside handlePersonIntroduction with 0-latency
            }

            // ── 2. Conversational Fast-Path (0 latency) ──
            identityRegex.matches(trimmed) -> {
                inConversationSession = true
                speakResponse("I'm DEVIL, your AI companion. I'm here at your service, Boss.", continueConversation = true)
            }
            greetingRegex.matches(trimmed) -> {
                inConversationSession = true
                speakResponse("Hello Boss! DEVIL is ready. What can I do for you?", continueConversation = true)
            }
            trimmed.matches(Regex("(?i)^(?:hey\\s+|ok\\s+|hello\\s+)?devil\\b.*")) && trimmed.length <= 15 -> {
                inConversationSession = true
                speakResponse("Yes Boss, I'm listening.", continueConversation = true)
            }
            howAreYouRegex.matches(trimmed) -> {
                inConversationSession = true
                speakResponse("I'm running at peak performance, Boss! How are you doing today?", continueConversation = true)
            }
            thanksRegex.matches(trimmed) -> {
                endConversationSession("You're very welcome, Boss! Always at your command.")
            }
            helpRegex.matches(trimmed) -> {
                inConversationSession = true
                speakResponse("You can chat with me naturally, ask any question, or command me to check in on Darwinbox, navigate with Maps, send WhatsApp voice notes, and play music in VLC. What shall we do?", continueConversation = true)
            }
            trimmed.contains(Regex("(?i)\\b(bored|bore|getting\\s+bore|feel\\s+bored|boredom)\\b")) -> {
                inConversationSession = true
                speakResponse("Boredom detected, Boss! Let me fix that. I can play your music in VLC, or tell you a joke. Here's a good one: " + getNextJoke(), continueConversation = true)
            }
            trimmed.contains(Regex("(?i)\\b(jokes?|tell\\s+a\\s+joke|tell\\s+me\\s+a\\s+joke|funny|make\\s+me\\s+laugh|entertain\\s+me)\\b")) -> {
                inConversationSession = true
                speakResponse(getNextJoke(), continueConversation = true)
            }

            // ── 3. WhatsApp Voice Notes & Messages ──
            trimmed.contains(Regex("(?i)\\b(?:try|test)\\b.*\\b(?:whatsapp|voice)\\s+note\\b")) ||
            trimmed.contains(Regex("(?i)\\b(?:try|test)\\b.*\\bwhatsapp\\b")) -> {
                sendWhatsAppVoiceNote("Amma", "Boss tested the voice note system and it is working now.")
            }
            voiceNoteRegex.find(trimmed) != null -> {
                val match = voiceNoteRegex.find(trimmed)!!
                val targetName = match.groupValues[1].trim()
                val message = match.groupValues[2].trim()
                sendWhatsAppVoiceNote(normalizeContactName(targetName), message)
            }
            voiceNoteSimpleRegex.find(trimmed) != null -> {
                val match = voiceNoteSimpleRegex.find(trimmed)!!
                val targetName = match.groupValues[1].trim()
                val message = match.groupValues[2].trim()
                sendWhatsAppVoiceNote(normalizeContactName(targetName), message)
            }
            sendWhatsAppRegex.find(trimmed) != null -> {
                val match = sendWhatsAppRegex.find(trimmed)!!
                val targetName = match.groupValues[1].trim()
                val message = match.groupValues[2].trim()
                sendWhatsAppTextMessage(normalizeContactName(targetName), message)
            }
            tellOnWhatsAppRegex.find(trimmed) != null -> {
                val match = tellOnWhatsAppRegex.find(trimmed)!!
                val targetName = match.groupValues[1].trim()
                val message = match.groupValues[2].trim()
                sendWhatsAppTextMessage(normalizeContactName(targetName), message)
            }
            whatsAppVerbRegex.find(trimmed) != null -> {
                val match = whatsAppVerbRegex.find(trimmed)!!
                val targetName = match.groupValues[1].trim()
                val message = match.groupValues[2].trim()
                sendWhatsAppTextMessage(normalizeContactName(targetName), message)
            }
            isWhatsApp -> {
                val fromMatch = Regex("(?i)(?:from|by)\\s+(.+)").find(trimmed)
                val sender = fromMatch?.groupValues?.get(1)?.trim()
                readWhatsAppMessages(fromSender = sender)
            }

            // ── 4. SMS & Text Messages ──
            isSms && (trimmed.contains(Regex("(?i)\\b(?:read|check|get|any|latest|recent)\\b")) || trimmed.contains("unread", ignoreCase = true)) -> {
                val fromMatch = Regex("(?i)(?:from|by)\\s+(.+)").find(trimmed)
                val sender = fromMatch?.groupValues?.get(1)?.trim()
                val isUnread = trimmed.contains("unread", ignoreCase = true)
                readMessages(fromSender = sender, unreadOnly = isUnread)
            }

            // ── 5. Phone Calls ──
            callAndSayRegex.matches(trimmed) -> {
                val match = callAndSayRegex.find(trimmed)
                val spokenName = match?.groupValues?.get(1)?.trim() ?: return
                val message = match?.groupValues?.get(2)?.trim() ?: return
                val contactName = normalizeContactName(spokenName)
                speakResponse("Sending voice note to $contactName.")
                sendWhatsAppVoiceNote(contactName, message)
            }
            callRegex.matches(trimmed) -> {
                val spokenName = callRegex.find(trimmed)?.groupValues?.get(1) ?: return
                val contactName = normalizeContactName(spokenName)
                callContact(contactName)
            }

            // ── 6. Maps, Routes & Distance ──
            handleMapsAndDistanceCommand(trimmed) -> {
                // Handled directly inside handleMapsAndDistanceCommand
            }

            // ── 7. Weather (Real-time live weather) ──
            handleWeatherCommand(trimmed) -> {
                // Handled directly inside handleWeatherCommand
            }

            // ── 8. VLC Player & Media (Playlists, Albums, Artists, Songs, Shuffle) ──
            handleMediaAndVlcCommand(trimmed) -> {
                // Handled directly inside handleMediaAndVlcCommand
            }

            // ── 9. Darwinbox Attendance, 9-Hour Shift, Calendar & Holiday Intelligence ──
            // 9a-1. Time to leave & departure queries ("what is the time to leave", "when can i leave", "when to leave", "what time should i leave")
            trimmed.contains(Regex("(?i)\\b((what\\s*is\\s*the\\s*)?time\\s*to\\s*leave|when\\s*(can|should|do)\\s*i\\s*leave|when\\s*to\\s*leave|what\\s*time\\s*(can|should|do)\\s*i\\s*leave|time\\s*to\\s*go\\s*home|can\\s*i\\s*leave(\\s*now)?)\\b")) -> {
                val ans = DarwinboxShiftTracker.getTimeToLeave(this)
                speakResponse(ans, continueConversation = true)
            }

            // 9a-2. Time remaining queries ("how much time left", "how much time is left", "time left in shift", "shift time remaining")
            trimmed.contains(Regex("(?i)\\b(how\\s*much\\s*time\\s*(is\\s*)?left|time\\s*left\\s*in\\s*(my\\s*)?shift|how\\s*many\\s*hours\\s*left|shift\\s*time\\s*remaining|how\\s*long\\s*(until|till)\\s*(check\\s*out|checkout|leave))\\b")) -> {
                val ans = DarwinboxShiftTracker.getRemainingTimeOnly(this)
                speakResponse(ans, continueConversation = true)
            }

            // 9a-3. Shift status & checkout queries ("when is my checkout", "what time is checkout", "shift status", "darwinbox status")
            trimmed.contains(Regex("(?i)\\b(when\\s*(is|do\\s*i)?\\s*(my\\s*)?(check\\s*out|checkout|clock\\s*out)|what\\s*time\\s*(is|do\\s*i)?\\s*(my\\s*)?(check\\s*out|checkout|clock\\s*out|check\\s*in|checkin)|shift\\s*status|darwin\\s*box\\s*status|attendance\\s*status|did\\s*i\\s*(check\\s*in|check\\s*out|checkout)|when\\s*did\\s*i\\s*check\\s*in)\\b")) -> {
                val status = DarwinboxShiftTracker.getShiftStatus(this)
                speakResponse(status, continueConversation = true)
            }

            // ── 9b. Calendar & Saturday Intelligence ──
            // Date query ("what is today's date", "today's date", "what's the date", "what date is today")
            trimmed.contains(Regex("(?i)\\b(what\\s*(is|'s)?\\s*(the|today'?s?)?\\s*date|today'?s?\\s*date|what\\s*date\\s*(is\\s*it|is\\s*today)?)\\b")) -> {
                val ans = CorporateCalendarEngine.getTodayDateSpoken()
                speakResponse(ans, continueConversation = true)
            }

            // Current Time query ("what is the time", "what time is it", "current time", "time now")
            trimmed.contains(Regex("(?i)\\b(what\\s*(is|'s)?\\s*(the)?\\s*time|current\\s*time|time\\s*now|what\\s*time\\s*is\\s*it)\\b")) -> {
                val ans = CorporateCalendarEngine.getCurrentTimeSpoken()
                speakResponse(ans, continueConversation = true)
            }

            // Day of week query ("what day is today", "what day is it", "which day is today")
            trimmed.contains(Regex("(?i)\\b(what\\s*day\\s*is\\s*(it|today)|which\\s*day\\s*is\\s*(it|today))\\b")) -> {
                val ans = CorporateCalendarEngine.getTodayDaySpoken()
                speakResponse(ans, continueConversation = true)
            }

            // Saturday queries ("is coming saturday a second saturday", "is next saturday 2nd saturday")
            (trimmed.contains(Regex("(?i)\\b(second|2nd)\\s*saturday\\b")) &&
             trimmed.contains(Regex("(?i)\\b(is|coming|next|this|approaching)\\b"))) -> {
                val ans = CorporateCalendarEngine.checkComingSaturday(targetRank = 2)
                speakResponse(ans, continueConversation = true)
            }

            (trimmed.contains(Regex("(?i)\\b(fourth|4th)\\s*saturday\\b")) &&
             trimmed.contains(Regex("(?i)\\b(is|coming|next|this|approaching)\\b"))) -> {
                val ans = CorporateCalendarEngine.checkComingSaturday(targetRank = 4)
                speakResponse(ans, continueConversation = true)
            }

            // Generic coming Saturday query ("is coming saturday a holiday", "what saturday is coming", "is this saturday working")
            trimmed.contains(Regex("(?i)\\b(coming|next|this)\\s*saturday\\b")) -> {
                val ans = CorporateCalendarEngine.checkComingSaturday(null)
                speakResponse(ans, continueConversation = true)
            }

            // Total Saturdays query ("how many saturdays do we have", "how many saturdays in this month", "list saturdays")
            trimmed.contains(Regex("(?i)\\b(how\\s*many\\s*saturdays|saturdays\\s*(do\\s*we\\s*have|in\\s*this\\s*month|this\\s*month)|list\\s*(all\\s*)?saturdays)\\b")) -> {
                val ans = CorporateCalendarEngine.getMonthSaturdaysSummary()
                speakResponse(ans, continueConversation = true)
            }

            // Current week telemetry ("what is this week", "what week is this week", "which week is this", "what week are we in", "what week is it")
            (trimmed.contains(Regex("(?i)\\b(what|which)\\s*week\\b")) ||
             trimmed.contains(Regex("(?i)\\b(what|which)\\s*(is\\s*)?(this|the|current)\\s*week\\b")) ||
             trimmed.contains(Regex("(?i)\\b(current\\s*week|week\\s*number)\\b")) ||
             trimmed.contains(Regex("(?i)\\bweek\\s*of\\s*the\\s*(month|year)\\b"))) -> {
                val ans = CorporateCalendarEngine.getWeekInfo()
                speakResponse(ans, continueConversation = true)
            }

            // ── 9c. Darwinbox Leaves & Holiday Intelligence ──
            // 9c-0. Attendance necessity on holidays ("no need to check in right", "do I need to check in today", "if today is a holiday then no need to check in or check out")
            trimmed.contains(Regex("(?i)\\b(no\\s*need\\s*to\\s*(check\\s*in|checkout|check\\s*out)|do\\s*(i|we)\\s*(need|have)\\s*to\\s*(check\\s*in|check\\s*out)|is\\s*(check\\s*in|checkout|check\\s*out)\\s*(required|needed)|should\\s*(i|we)\\s*(check\\s*in|check\\s*out))\\b")) -> {
                val ans = CorporateCalendarEngine.isCheckInRequiredToday()
                speakResponse(ans, continueConversation = true)
            }

            // 9c-1. Remaining / Left holidays in this month ("what are the holidays left in this month", "holidays left this month", "remaining holidays in this month", "how many holidays left", "any holidays left")
            ((trimmed.contains(Regex("(?i)\\b(left|remaining|balance|pending)\\b")) &&
              trimmed.contains(Regex("(?i)\\b(holidays?|leaves?)\\b"))) ||
             trimmed.contains(Regex("(?i)\\b(holidays?\\s*after\\s*today|holidays?\\s*from\\s*today)\\b"))) -> {
                val targetMonth = CorporateCalendarEngine.extractMonthFromQuery(trimmed)
                val ans = CorporateCalendarEngine.getRemainingHolidaysForMonth(targetMonth)
                speakResponse(ans, continueConversation = true)
            }

            // 9c-2. Today's holiday status ("is today a holiday", "today is a holiday", "did you check darwinbox that today is a holiday", "why is today a holiday", "how do you know today is a holiday", "is today holiday", "check holiday today")
            (!trimmed.contains(Regex("(?i)\\b(left|remaining|after|tomorrow|next|balance|pending)\\b")) &&
             trimmed.contains(Regex("(?i)\\b(is\\s*today\\s*(a\\s*)?(holiday|off|working(\\s*day)?)|today\\s*(is\\s*)?(a\\s*)?(holiday|off|working(\\s*day)?)|why\\s*(is\\s*)?today\\s*(is\\s*)?(a\\s*)?holiday|how\\s*(do\\s*)?you\\s*know\\s*(that\\s*)?(it\\s*is\\s*a\\s*|today\\s*is\\s*(a\\s*)?)?holiday|today'?s?\\s*holiday|holiday\\s*today)\\b"))) -> {
                val ans = CorporateCalendarEngine.getTodayHolidayStatus()
                speakResponse(ans, continueConversation = true)
            }

            // 9c-3. Day after tomorrow holiday status ("is day after tomorrow a holiday", "is day after tomorrow off")
            trimmed.contains(Regex("(?i)\\b(day\\s*after\\s*tomorrow.*(holiday|off|working)|is\\s*day\\s*after\\s*tomorrow\\s*(is\\s*)?(a\\s*)?(holiday|off|working))\\b")) -> {
                val ans = CorporateCalendarEngine.getDayAfterTomorrowHolidayStatus()
                speakResponse(ans, continueConversation = true)
            }

            // 9c-4. Tomorrow's holiday status ("is tomorrow a holiday", "is tomorrow is a holiday", "tomorrow holiday", "is tomorrow off", "is tomorrow working")
            trimmed.contains(Regex("(?i)\\b(is\\s*tomorrow\\s*(is\\s*)?(a\\s*)?(holiday|off|working(\\s*day)?)|tomorrow\\s*(is\\s*)?(a\\s*)?(holiday|off|working(\\s*day)?)|check\\s*tomorrow.*(holiday|off|working)|tomorrow'?s?\\s*holiday|holiday\\s*tomorrow)\\b")) -> {
                val ans = CorporateCalendarEngine.getTomorrowHolidayStatus()
                speakResponse(ans, continueConversation = true)
            }

            // 9c-5. Next upcoming holiday queries ("when is the next holiday", "what is the next holiday", "what is the next coming holiday", "upcoming holiday", "coming holiday")
            trimmed.contains(Regex("(?i)\\b((when|what)\\s*is\\s*(the\\s*)?(next|upcoming|coming)\\s*(coming\\s*)?holiday|next\\s*(coming\\s*)?holiday|upcoming\\s*holiday|coming\\s*holiday)\\b")) -> {
                val ans = CorporateCalendarEngine.getNextHoliday()
                speakResponse(ans, continueConversation = true)
            }

            // 9c-6. Specific day holiday check ("is coming friday a holiday", "is monday a holiday", "is friday off", "is there a holiday on friday", "do we have a holiday on wednesday")
            (trimmed.contains(Regex("(?i)\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b")) &&
             trimmed.contains(Regex("(?i)\\b(holiday|holidays|off|working)\\b"))) -> {
                val match = Regex("(?i)(monday|tuesday|wednesday|thursday|friday|saturday|sunday)").find(trimmed)
                val day = match?.value ?: "friday"
                val ans = CorporateCalendarEngine.isUpcomingDayHoliday(day)
                speakResponse(ans, continueConversation = true)
            }

            // 9c-7. Specific date holiday query ("is 21st a holiday", "is 26 a holiday", "is october 21 a holiday", "is 26th holiday")
            trimmed.contains(Regex("(?i)\\b(is\\s*(the\\s*)?(\\d{1,2})(st|nd|rd|th)?(\\s*of\\s*([a-z]+)|\\s*([a-z]+))?\\s*(a\\s*)?(holiday|off|working)|is\\s*([a-z]+)\\s*(\\d{1,2})(st|nd|rd|th)?\\s*(a\\s*)?(holiday|off|working)|check\\s*(\\d{1,2})(st|nd|rd|th)?\\s*holiday)\\b")) -> {
                val ans = CorporateCalendarEngine.checkSpecificDateSpoken(trimmed)
                speakResponse(ans, continueConversation = true)
            }

            // 9c-8. Darwinbox & Monthly Holiday queries ("holiday in darwinbox", "darwinbox holiday", "what are the holidays in darwinbox", "leaves in darwinbox", "holidays this month", "list holidays", "leave section holiday")
            // Catches ANY sentence with 'holiday'/'holidays'/'leave'/'leaves' referencing darwinbox, month, or company
            ((trimmed.contains(Regex("(?i)\\b(holidays?|leaves?)\\b")) &&
              trimmed.contains(Regex("(?i)\\b(darwinbox|darwin\\s*box|month|calendar|office|company|section|list)\\b"))) ||
             trimmed.contains(Regex("(?i)\\b(list\\s*(all\\s*)?holidays?|what\\s*(are\\s*the\\s*)?holidays?\\b)"))) -> {
                val targetMonth = CorporateCalendarEngine.extractMonthFromQuery(trimmed)
                val monthHolidays = CorporateCalendarEngine.getHolidaysForMonth(targetMonth)
                speakResponse(monthHolidays, continueConversation = true)
            }

            // 9c-9. General holiday fallback ("any holiday", "tell me holiday", "check holiday")
            trimmed.contains(Regex("(?i)\\b(holidays?|leave\\s*section)\\b")) -> {
                val targetMonth = CorporateCalendarEngine.extractMonthFromQuery(trimmed)
                val ans = CorporateCalendarEngine.getRemainingHolidaysForMonth(targetMonth)
                speakResponse(ans, continueConversation = true)
            }

            // 9d. Manual check-in logging ("I checked in at 9:15 am", "we checked in at 9:15 am", "set check in time to 9:15 am", "record check in at 9:15 am")
            (trimmed.contains(Regex("(?i)\\b(checked\\s*in|chickened\\s*in|punched\\s*in|clocked\\s*in|check\\s*in\\s*time|set\\s*check\\s*in|record\\s*check\\s*in)\\b")) &&
             trimmed.contains(Regex("(?i)\\b(\\d{1,2}[:.\\s]?\\d{0,2}\\s*(am|pm)?|at\\s+\\d{1,2})\\b"))) -> {
                val info = DarwinboxShiftTracker.recordManualCheckInTime(this, trimmed)
                if (info != null) {
                    speakResponse(
                        "Understood Boss! Logged your check-in at ${info.checkInTimeStr}. Your 9-hour shift completes at ${info.checkoutTimeStr}. I will give you an alert at ${info.alertTimeStr} if you haven't checked out.",
                        continueConversation = true
                    )
                } else {
                    speakResponse("I couldn't detect the exact check-in time, Boss. Please say something like 'I checked in at 9:15 AM'.", continueConversation = true)
                }
            }

            // 9e. Darwinbox Attendance Automation (Check-In)
            // MUST explicitly ask for check-in or attendance, and NOT be asking about holidays, leaves, or calendar!
            (!trimmed.contains(Regex("(?i)\\b(holiday|holidays|leave|leaves|calendar|when|how\\s*much|time\\s*left|status)\\b")) &&
             trimmed.contains(Regex("(?i)\\b(check\\s*in|checkin|chicken\\s*in|chick\\s*in|cheque\\s*in|clock\\s*in|clockin|punch\\s*in|punchin|mark\\s*attendance|do\\s*attendance|attendance\\s*in)\\b"))) -> {
                handleDarwinboxAttendance(DevilAccessibilityService.DarwinboxAction.CHECK_IN)
            }

            // 9f. Darwinbox Attendance Automation (Check-Out)
            (!trimmed.contains(Regex("(?i)\\b(holiday|holidays|leave|leaves|calendar|when|how\\s*much|time\\s*left|status)\\b")) &&
             trimmed.contains(Regex("(?i)\\b(check\\s*out|checkout|chick\\s*out|cheque\\s*out|clock\\s*out|clockout|punch\\s*out|punchout|attendance\\s*out)\\b"))) -> {
                handleDarwinboxAttendance(DevilAccessibilityService.DarwinboxAction.CHECK_OUT)
            }

            // ── 10. General Apps ──
            openAppRegex.matches(trimmed) -> {
                val appName = openAppRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: return
                if (!openApp(appName)) {
                    queryBackend(trimmed)
                }
            }
            else -> {
                queryBackend(trimmed)
            }
        }
    }

    private fun handleDarwinboxAttendance(action: DevilAccessibilityService.DarwinboxAction) {
        val launchIntent = packageManager.getLaunchIntentForPackage("com.darwinbox.darwinbox")?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        } ?: Intent().apply {
            component = android.content.ComponentName("com.darwinbox.darwinbox", "com.darwinbox.splashscreen.ui.SplashScreenActivity")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        try {
            DevilAccessibilityService.triggerDarwinbox(action)
            startActivity(launchIntent)
            if (action == DevilAccessibilityService.DarwinboxAction.CHECK_IN) {
                val info = DarwinboxShiftTracker.recordCheckIn(this)
                speakResponse("Opening Darwinbox and clocking you in, Boss! Shift check-in logged at ${info.checkInTimeStr}. Your 9-hour shift completes at ${info.checkoutTimeStr}. I will give you an alert at ${info.alertTimeStr} if you haven't checked out.")
            } else {
                val checkoutStr = DarwinboxShiftTracker.recordCheckOut(this)
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                notificationManager?.cancel(DarwinboxAlarmReceiver.NOTIFICATION_ID)
                speakResponse("Opening Darwinbox and submitting your check out, Boss. Shift complete at $checkoutStr! Excellent work today.")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to launch Darwinbox", e)
            speakResponse("Could not open Darwinbox on your phone.")
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

    private fun formatPhoneNumberForWhatsApp(raw: String): String {
        val digits = raw.replace(Regex("[^0-9]"), "")
        return when {
            digits.length == 10 -> "91$digits"
            digits.length == 11 && digits.startsWith("0") -> "91" + digits.substring(1)
            else -> digits
        }
    }

    private fun sendWhatsAppVoiceNote(targetName: String, rawMessage: String) {
        val contact = findContactByName(targetName)
        if (contact == null) {
            speakResponse("Contact $targetName not found in your phone.")
            return
        }

        val cleanNumber = formatPhoneNumberForWhatsApp(contact.number)
        val cleanMessage = rawMessage.trim().replace(Regex("^that\\s+", RegexOption.IGNORE_CASE), "")

        val script = if (cleanMessage.startsWith("hello", ignoreCase = true) || cleanMessage.startsWith("hi", ignoreCase = true)) {
            cleanMessage
        } else {
            "Hello ${contact.displayName}, this is DEVIL. Boss asked me to convey: $cleanMessage"
        }

        Log.i("MainActivity", "Synthesizing voice note for ${contact.displayName} ($cleanNumber): script='$script'")
        transcript = "Synthesizing DEVIL voice note for ${contact.displayName}..."
        assistantState = AssistantState.THINKING

        scope.launch(Dispatchers.IO) {
            try {
                val voiceDir = File(cacheDir, "voice_notes").apply { mkdirs() }
                // Instantly purge prior voice note cache files to prevent storage bloat
                voiceDir.listFiles()?.forEach { it.delete() }

                val audioFile = File(voiceDir, "devil_voice_${System.currentTimeMillis()}.wav")
                val utteranceId = "VOICE_NOTE_${System.currentTimeMillis()}"

                voiceNoteSynthesisCallback = {
                    try {
                        Log.i("MainActivity", "Voice note written: ${audioFile.absolutePath}, bytes=${audioFile.length()}")
                        val uri = FileProvider.getUriForFile(
                            this@MainActivity,
                            "${packageName}.fileprovider",
                            audioFile
                        )

                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "audio/*"
                            setPackage("com.whatsapp")
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra("jid", "$cleanNumber@s.whatsapp.net")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }

                        DevilAccessibilityService.triggerAutoSend(contact.displayName)
                        startActivity(sendIntent)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Direct WhatsApp audio intent failed, using fallback chooser", e)
                        try {
                            val uri = FileProvider.getUriForFile(
                                this@MainActivity,
                                "${packageName}.fileprovider",
                                audioFile
                            )
                            val fallback = Intent(Intent.ACTION_SEND).apply {
                                type = "audio/*"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(Intent.createChooser(fallback, "Share Voice Note with ${contact.displayName}").apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        } catch (e2: Exception) {
                            Log.e("MainActivity", "Fallback sharing failed", e2)
                        }
                    }
                }

                val res = tts.synthesizeToFile(script, Bundle(), audioFile, utteranceId)
                if (res != TextToSpeech.SUCCESS) {
                    Log.e("MainActivity", "tts.synthesizeToFile failed: code=$res")
                    runOnUiThread {
                        voiceNoteSynthesisCallback = null
                        assistantState = AssistantState.IDLE
                        speakResponse("Failed to synthesize voice note.")
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error synthesizing voice note", e)
                runOnUiThread {
                    voiceNoteSynthesisCallback = null
                    assistantState = AssistantState.IDLE
                    speakResponse("Error preparing voice note.")
                }
            }
        }
    }

    private fun sendWhatsAppTextMessage(targetName: String, rawMessage: String) {
        val contact = findContactByName(targetName)
        if (contact == null) {
            speakResponse("Contact $targetName not found in your phone.")
            return
        }

        val cleanNumber = formatPhoneNumberForWhatsApp(contact.number)
        val cleanMessage = rawMessage.trim().replace(Regex("^that\\s+", RegexOption.IGNORE_CASE), "")
        val formattedText = "😈 *DEVIL Message from Boss:*\n$cleanMessage"

        speakResponse("Opening WhatsApp to send message to ${contact.displayName}.")

        try {
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber&text=${Uri.encode(formattedText)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            DevilAccessibilityService.triggerAutoSend(contact.displayName)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Direct WhatsApp text link failed", e)
            try {
                val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber&text=${Uri.encode(formattedText)}")
                startActivity(Intent(Intent.ACTION_VIEW, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            } catch (e2: Exception) {
                Log.e("MainActivity", "Generic WhatsApp text failed", e2)
            }
        }
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
                "No new unread WhatsApp messages from $fromSender, Boss."
            } else {
                "No new unread WhatsApp messages, Boss. All caught up."
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
                        // Mark spoken messages as read so DEVIL never repeats them
                        for (msg in prioritized.take(4)) {
                            WhatsAppNotificationService.markMessageAsSpokenAndRead(msg.sender, msg.message)
                        }
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
        val spokenList = mutableListOf<WhatsAppNotificationService.StoredMessage>()

        if (vip.isNotEmpty()) {
            sb.append("Important priority alert. ")
            for (msg in vip.take(2)) {
                val clean = if (msg.message.length > 90) msg.message.substring(0, 90) + "..." else msg.message
                sb.append("From ${msg.sender}: $clean. ")
                spokenList.add(msg)
            }
        }
        if (regular.isNotEmpty()) {
            if (vip.isNotEmpty()) sb.append("Other notifications: ")
            for (msg in regular.take(2)) {
                val clean = if (msg.message.length > 70) msg.message.substring(0, 70) + "..." else msg.message
                sb.append("${msg.sender} says: $clean. ")
                spokenList.add(msg)
            }
        }
        speakResponse(sb.toString().trim())
        // Mark all spoken messages as read so DEVIL never repeats them
        for (msg in spokenList) {
            WhatsAppNotificationService.markMessageAsSpokenAndRead(msg.sender, msg.message)
        }
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

    private fun openMapsRoute(origin: String?, destination: String) {
        try {
            val mapsUri = if (origin.isNullOrBlank()) {
                Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destination)}")
            } else {
                Uri.parse("https://www.google.com/maps/dir/?api=1&origin=${Uri.encode(origin)}&destination=${Uri.encode(destination)}")
            }

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
            if (origin.isNullOrBlank()) {
                speakResponse("Opening Google Maps to show distance and route to $destination.")
            } else {
                speakResponse("Showing distance and route from $origin to $destination in Google Maps.")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error opening Maps", e)
            speakResponse("Could not open Google Maps.")
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
            speakResponse("Starting navigation to $destination in Google Maps.")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error starting navigation", e)
            speakResponse("Could not open Google Maps.")
        }
    }

    private fun findContactByName(name: String): ContactInfo? {
        val cleanName = normalizeContactName(name).trim()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        val isSyam = cleanName.equals("syam", ignoreCase = true) ||
                     cleanName.equals("shyam", ignoreCase = true) ||
                     cleanName.equals("sham", ignoreCase = true)

        val selection = if (isSyam) {
            "(${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?)"
        } else {
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        }
        val selectionArgs = if (isSyam) {
            arrayOf("%syam%", "%shyam%")
        } else {
            arrayOf("%$cleanName%")
        }

        try {
            contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null

                data class ContactMatch(val displayName: String, val number: String, val score: Int)
                val matches = mutableListOf<ContactMatch>()
                do {
                    val displayName = cursor.getString(0) ?: continue
                    val number = cursor.getString(1) ?: continue

                    val score = when {
                        displayName.equals(cleanName, ignoreCase = true) -> 10
                        displayName.startsWith(cleanName, ignoreCase = true) -> 8
                        isSyam && (displayName.contains("syam", ignoreCase = true) || displayName.contains("shyam", ignoreCase = true)) -> 9
                        displayName.contains(cleanName, ignoreCase = true) -> 6
                        else -> 1
                    }
                    matches.add(ContactMatch(displayName, number, score))
                } while (cursor.moveToNext())

                val best = matches.maxByOrNull { it.score * 1000 - it.displayName.length }
                if (best != null) {
                    return ContactInfo(best.displayName, best.number)
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error finding contact: $name", e)
        }
        return null
    }

    private fun callContact(name: String) {
        val contact = findContactByName(name)
        if (contact != null) {
            speakResponse("Calling ${contact.displayName}.")
            val cleanNumber = contact.number.replace(Regex("[^0-9+]"), "")
            val uri = Uri.parse("tel:$cleanNumber")
            try {
                if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                    val callIntent = Intent(Intent.ACTION_CALL, uri).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(callIntent)
                } else {
                    val dialIntent = Intent(Intent.ACTION_DIAL, uri).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(dialIntent)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error placing call to ${contact.displayName}", e)
                speakResponse("Could not place call to ${contact.displayName}.")
            }
        } else {
            speakResponse("Contact $name not found in your phone.")
        }
    }

    /**
     * Find the playlist ID from MediaStore.Audio.Playlists by name.
     * Returns the playlist ID or null if not found.
     */
    @Suppress("DEPRECATION")
    private fun findPlaylistIdByName(playlistName: String): Long? {
        try {
            val playlistUri = MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Playlists._ID,
                MediaStore.Audio.Playlists.NAME
            )
            val cleanName = playlistName.trim()

            // First try exact match (case-insensitive)
            val exactSelection = "${MediaStore.Audio.Playlists.NAME} LIKE ?"
            val exactArgs = arrayOf(cleanName)
            contentResolver.query(playlistUri, projection, exactSelection, exactArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists._ID))
                }
            }

            // Then try partial match
            val partialSelection = "${MediaStore.Audio.Playlists.NAME} LIKE ?"
            val partialArgs = arrayOf("%$cleanName%")
            contentResolver.query(playlistUri, projection, partialSelection, partialArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists._ID))
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error finding playlist by name: $playlistName", e)
        }
        return null
    }

    /**
     * Get all track content URIs from a playlist by its ID.
     */
    @Suppress("DEPRECATION")
    private fun getPlaylistTrackUris(playlistId: Long): List<Uri> {
        val trackUris = mutableListOf<Uri>()
        try {
            val membersUri = MediaStore.Audio.Playlists.Members.getContentUri("external", playlistId)
            val projection = arrayOf(MediaStore.Audio.Playlists.Members.AUDIO_ID)
            contentResolver.query(membersUri, projection, null, null,
                MediaStore.Audio.Playlists.Members.PLAY_ORDER)?.use { cursor ->
                val audioIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists.Members.AUDIO_ID)
                while (cursor.moveToNext()) {
                    val audioId = cursor.getLong(audioIdCol)
                    trackUris.add(ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, audioId))
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error getting playlist tracks for ID: $playlistId", e)
        }
        return trackUris
    }

    data class MediaSearchResult(
        val trackUris: List<Uri>,
        val mediaType: String?
    )

    private fun findMatchingTracks(keyword: String, shuffle: Boolean = false, mediaType: String? = null): MediaSearchResult {
        val hasPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasPermission) return MediaSearchResult(emptyList(), mediaType)

        try {
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM
            )

            val isGeneric = keyword.isBlank() || keyword.lowercase(Locale.getDefault()) in listOf("song", "songs", "music", "audio", "something", "track", "tracks")
            val cleanKw = keyword.trim()

            val trackUris = mutableListOf<Uri>()

            if (isGeneric) {
                val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
                contentResolver.query(uri, projection, selection, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    while (cursor.moveToNext() && trackUris.size < 500) {
                        trackUris.add(ContentUris.withAppendedId(uri, cursor.getLong(idCol)))
                    }
                }
                if (shuffle) trackUris.shuffle()
                return MediaSearchResult(trackUris, mediaType)
            }

            when (mediaType) {
                "playlist" -> {
                    // Actually look up the real playlist from MediaStore.Audio.Playlists
                    val playlistId = findPlaylistIdByName(cleanKw)
                    if (playlistId != null) {
                        val playlistTracks = getPlaylistTrackUris(playlistId)
                        if (playlistTracks.isNotEmpty()) {
                            trackUris.addAll(playlistTracks)
                            if (shuffle) trackUris.shuffle()
                            return MediaSearchResult(trackUris, "playlist")
                        }
                    }
                    // If no system playlist found, return empty — let MEDIA_PLAY_FROM_SEARCH handle it
                    Log.d("MainActivity", "No system playlist found for: $cleanKw")
                }
                "album" -> {
                    val selection = "${MediaStore.Audio.Media.ALBUM} LIKE ?"
                    val selectionArgs = arrayOf("%$cleanKw%")
                    contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                        while (cursor.moveToNext()) {
                            trackUris.add(ContentUris.withAppendedId(uri, cursor.getLong(idCol)))
                        }
                    }
                }
                "artist" -> {
                    val selection = "${MediaStore.Audio.Media.ARTIST} LIKE ?"
                    val selectionArgs = arrayOf("%$cleanKw%")
                    contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                        while (cursor.moveToNext()) {
                            trackUris.add(ContentUris.withAppendedId(uri, cursor.getLong(idCol)))
                        }
                    }
                }
                else -> {
                    // For individual songs, try title match first
                    val selection = "${MediaStore.Audio.Media.TITLE} LIKE ?"
                    val selectionArgs = arrayOf("%$cleanKw%")
                    contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                        while (cursor.moveToNext()) {
                            trackUris.add(ContentUris.withAppendedId(uri, cursor.getLong(idCol)))
                        }
                    }
                }
            }

            // Fallback: broaden search if specific type yielded 0 results (not for playlist)
            if (trackUris.isEmpty() && mediaType != "playlist") {
                val fallbackSelection = "${MediaStore.Audio.Media.TITLE} LIKE ? OR ${MediaStore.Audio.Media.ALBUM} LIKE ? OR ${MediaStore.Audio.Media.ARTIST} LIKE ?"
                val fallbackArgs = arrayOf("%$cleanKw%", "%$cleanKw%", "%$cleanKw%")
                contentResolver.query(uri, projection, fallbackSelection, fallbackArgs, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    while (cursor.moveToNext()) {
                        trackUris.add(ContentUris.withAppendedId(uri, cursor.getLong(idCol)))
                    }
                }
            }

            if (shuffle) trackUris.shuffle()
            return MediaSearchResult(trackUris, mediaType)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error finding local tracks for: $keyword", e)
        }
        return MediaSearchResult(emptyList(), mediaType)
    }

    private fun playMedia(query: String, shuffle: Boolean = false) {
        val cleanQuery = normalizeSpokenCommand(query)
                              .replace(Regex("(?i)\\b(?:in|on|from|using|with)\\s+vlc(?:\\s+player)?\\b"), "")
                              .replace(Regex("(?i)\\bvlc(?:\\s+player)?\\b"), "")
                              .trim()
        val isGeneric = cleanQuery.isEmpty() || cleanQuery.lowercase(Locale.getDefault()) in listOf("song", "songs", "music", "audio", "something", "track", "tracks", "all", "anything")

        try {
            val actionPrefix = if (shuffle) "Shuffling" else "Playing"

            if (cleanQuery.lowercase(Locale.getDefault()) in listOf("playlist", "the playlist", "a playlist", "my playlist")) {
                val listStr = vlcPlaylists.keys.joinToString(", ")
                speakResponse("Which playlist would you like to play? In VLC you have: $listStr.")
                return
            }

            // ── A. Check if user is asking for a Playlist ──
            val playlistRegex = Regex("(?i)(?:the\\s+|my\\s+)?playlist\\s+(?:called\\s+|named\\s+)?(.+)|(.+?)\\s+playlist")
            var playlistTarget: String? = null

            if (playlistRegex.matches(cleanQuery)) {
                val match = playlistRegex.find(cleanQuery)
                val raw = (match?.groupValues?.get(1)?.ifEmpty { null } ?: match?.groupValues?.get(2) ?: cleanQuery).trim()
                playlistTarget = raw.replace(Regex("(?i)^(?:the|my|a|called|named)\\s+"), "").trim()
            } else {
                // If query matches a known VLC playlist name directly (e.g., "play mood", "play mass", "play slow")
                val candidate = cleanQuery.replace(Regex("(?i)^(?:the|my|a)\\s+"), "").trim().lowercase(Locale.getDefault())
                if (vlcPlaylists.containsKey(candidate)) {
                    playlistTarget = candidate
                }
            }

            if (playlistTarget != null) {
                val cleanPlaylistName = playlistTarget.replace(Regex("[^a-zA-Z0-9\\s_-]"), "").trim().lowercase(Locale.getDefault())
                val trackCount = vlcPlaylists[cleanPlaylistName]

                val vlcPlaylistIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(SearchManager.QUERY, cleanPlaylistName)
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/playlist")
                    putExtra("android.intent.extra.focus", "vnd.android.cursor.item/playlist")
                    putExtra(MediaStore.EXTRA_MEDIA_PLAYLIST, cleanPlaylistName)
                    putExtra("android.intent.extra.playlist", cleanPlaylistName)
                    putExtra("extra_playlist", cleanPlaylistName)
                    if (shuffle) {
                        putExtra("android.intent.extra.SHUFFLE", true)
                        putExtra("shuffle", true)
                        putExtra("EXTRA_SHUFFLE", true)
                    }
                    setPackage("org.videolan.vlc")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }

                if (vlcPlaylistIntent.resolveActivity(packageManager) != null) {
                    startActivity(vlcPlaylistIntent)
                    val countMsg = if (trackCount != null) " with $trackCount songs" else ""
                    speakResponse("$actionPrefix playlist $cleanPlaylistName in VLC$countMsg.")
                    return
                }
            }

            // ── B. Check for Albums and Artists ──
            val albumRegex = Regex("(?i)(?:the\\s+)?album\\s+(.+)|(.+?)\\s+album")
            val artistPattern1 = Regex("(?i)(?:all\\s+)?(?:songs?\\s+(?:of|by|from)|artist)\\s+(.+)")
            val artistPattern2 = Regex("(?i)(?:all\\s+)?(.+?)\\s+(?:songs?|hits|all\\s+songs)")

            var mediaFocus = "vnd.android.cursor.item/*"
            var targetExtraKey: String? = null
            var targetName = cleanQuery
            var isArtist = false
            var mediaType: String? = null

            when {
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

            val displayTitle = if (targetName.isBlank() || isGeneric) "music" else targetName

            // 1. Try finding matching local tracks
            val searchResult = findMatchingTracks(targetName, shuffle = shuffle, mediaType = mediaType)
            val matchedUris = searchResult.trackUris

            if (matchedUris.isNotEmpty()) {
                val speakText = when (mediaType) {
                    "album" -> "$actionPrefix album $displayTitle in VLC"
                    "artist" -> "$actionPrefix songs by $displayTitle in VLC"
                    else -> "$actionPrefix $displayTitle in VLC"
                }

                if (matchedUris.size == 1) {
                    val directPlayIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(matchedUris.first(), "audio/*")
                        setPackage("org.videolan.vlc")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    if (directPlayIntent.resolveActivity(packageManager) != null) {
                        startActivity(directPlayIntent)
                        speakResponse(speakText)
                        return
                    }
                } else {
                    val multiIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(matchedUris.first(), "audio/*")
                        setPackage("org.videolan.vlc")
                        val locationList = ArrayList<String>()
                        for (trackUri in matchedUris) {
                            locationList.add(trackUri.toString())
                        }
                        putStringArrayListExtra("extra_uri_list", ArrayList(locationList.drop(1).map { it }))
                        if (shuffle) {
                            putExtra("android.intent.extra.SHUFFLE", true)
                            putExtra("shuffle", true)
                            putExtra("EXTRA_SHUFFLE", true)
                        }
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    if (multiIntent.resolveActivity(packageManager) != null) {
                        startActivity(multiIntent)
                        speakResponse(speakText)
                        return
                    }
                }
            }

            // 2. Otherwise send MEDIA_PLAY_FROM_SEARCH to VLC
            val vlcSearchIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(SearchManager.QUERY, targetName)
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, mediaFocus)
                putExtra("android.intent.extra.focus", mediaFocus)
                if (targetExtraKey != null) {
                    putExtra(targetExtraKey, targetName)
                }
                if (isArtist) {
                    putExtra(MediaStore.EXTRA_MEDIA_ARTIST, targetName)
                    putExtra("android.intent.extra.artist", targetName)
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
                val speakText = when (mediaType) {
                    "album" -> "$actionPrefix album $displayTitle in VLC"
                    "artist" -> "$actionPrefix songs by $displayTitle in VLC"
                    else -> "$actionPrefix $displayTitle in VLC"
                }
                speakResponse(speakText)
                return
            }

            // 3. Fallback: Launch VLC directly
            val launchIntent = packageManager.getLaunchIntentForPackage("org.videolan.vlc")
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                speakResponse("Opening VLC player.")
                return
            }

            speakResponse("VLC player is not installed.")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error playing media", e)
            speakResponse("Could not open VLC player.")
        }
    }

    private fun queryBackend(query: String) {
        scope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                assistantState = AssistantState.THINKING
                transcript = "DEVIL is thinking..."
            }
            try {
                val answer = LlamaHelper.generateAnswer(query)
                withContext(Dispatchers.Main) {
                    inConversationSession = true
                    speakResponse(answer, continueConversation = true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    inConversationSession = false
                    speakResponse("I'm having trouble analyzing that query right now.")
                }
            }
        }
    }

    private fun clearAppCache() {
        scope.launch(Dispatchers.IO) {
            try {
                cacheDir?.deleteRecursively()
                externalCacheDir?.deleteRecursively()
                Log.i("MainActivity", "App cache purged successfully")
            } catch (e: Exception) {
                Log.w("MainActivity", "Cache clear warning", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clearAppCache()
        if (::speechRecognizer.isInitialized) speechRecognizer.destroy()
        if (::tts.isInitialized) tts.shutdown()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}
        LlamaHelper.release()
        scope.cancel()
    }
}
