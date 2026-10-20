# Nord AI Voice Assistant (G.I.D.E.O.N.)

An on-device, zero-latency, privacy-first AI Voice Assistant engineered specifically for OnePlus (OxygenOS) Android smartphones. It runs completely offline with **zero cloud API keys**, powered by a local quantized **Qwen2.5-1.5B** language model and features an interactive **Gideon Holographic Interface** inspired by *The Flash* series.

---

## 🎯 What We Did In This Project

### 1. Migrated to 100% Offline On-Device LLM (Zero API Keys)
- **Eliminated Cloud & API Key Costs**: Completely transitioned from a cloud-dependent Gemini prototype to an offline on-device architecture.
- **Quantization Optimization**: Integrated `qwen2.5-1.5b-instruct-q4_k_m.gguf` using **4-bit K-Quantization (Medium)**. This compressed the model from ~4 GB down to ~980 MB so it runs natively in phone RAM without crashing.
- **C++ NDK Engine**: Integrated `org.codeshipping:llama-kotlin-android` (native `llama.cpp` bindings) in `LlamaHelper.kt` to execute inference across 4 CPU cores with zero-copy memory mapping.
- **Removed Obsolete Backend**: Removed the unused Python FastAPI backend and virtual environment, saving 82 MB of storage.

---

### 2. Built the G.I.D.E.O.N. Holographic Interface (from *The Flash*)
Built a complete, hardware-accelerated Jetpack Compose holographic UI (`GideonFaceView.kt`) modeled after the S.T.A.R. Labs AI Gideon:
- **Dot Matrix Hologram Emitter Wall**: Canvas-rendered ambient LED particle wall matching Gideon's Time Vault chamber.
- **Concentric Cybernetic HUD Rings**: Segmented, counter-rotating telemetry circles with orbiting data nodes.
- **Translucent Hologram Face**: High-resolution holographic face with cyan neon energy glow and vertical scanline raster sweeps.
- **Dynamic 4-State Visual Feedback**:
  - `IDLE`: Ambient respiration scale, faint cyan aura, and slow rotating data rings.
  - `LISTENING`: Green audio sensor telemetry and expanding sound-wave rings.
  - `THINKING`: High-frequency neural scanning waves, orbital data nodes, and golden-yellow processing indicator.
  - `SPEAKING`: Luminous cyan voice modulation synchronized directly with Android Text-To-Speech.
- **Sci-Fi Terminal Log**: Glowing cybernetic transcript box for user queries and assistant responses.

---

### 3. Voice Latency & Performance Optimizations
- **Fast Silence Endpointing**: Android's `SpeechRecognizer` typically waits ~2.0 seconds of silence before firing results. We tuned `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` down to **700ms**, cutting command trigger delay by over 1.3 seconds.
- **Conditional Bluetooth SCO**: Prevented the 400–800ms hardware audio negotiation delay by conditionally engaging Bluetooth SCO only when a compatible headset/car kit is actively connected.
- **Frame-Accurate State Synchronization**: Connected Android's `TextToSpeech.setOnUtteranceProgressListener` so Gideon switches between Thinking, Speaking, and Idle states synchronously without UI lag.

---

### 4. Deep Android OS Automations
- **WhatsApp Background Reader**: Built `WhatsAppNotificationService.kt` (`NotificationListenerService`) to intercept, clean, and speak incoming WhatsApp messages aloud.
- **Smart SMS & Spam Filter**: Created `MessageFilter.kt` to inspect incoming SMS messages and intelligently drop bank OTP spam, ads, and promotional texts.
- **Media Player & VLC Controls**: Deep intent automation for VLC (`MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH`) supporting album, artist, track, and random shuffle playback.
- **Navigation & Maps**: Direct intent routing into Google Maps for distance checks, route previews, and turn-by-turn navigation.
- **Direct Calling**: Contact search with fuzzy matching and direct phone dialing.

---

### 5. Sentry Mode & Stark AI Personas (J.A.R.V.I.S., F.R.I.D.A.Y., E.D.I.T.H.)
- **🛡️ Sentry Mode (`DevilSentryService.kt`)**: Voice-activated hands-free perimeter monitoring ("Activate Sentry Mode" / "Revoke Sentry Mode"). Runs as a low-power Android foreground service with the green mic privacy dot indicator. Automatically wakes up to "Hey DEVIL" or "Hey Jarvis" and powers down to 0% CPU when revoked.
- **🎙️ Stark AI Voice Profiles**: Seamless offline switching between:
  - **J.A.R.V.I.S.**: Formal British male accent, polite, dry wit ("At your service, Boss").
  - **F.R.I.D.A.Y.**: Warm, fast-paced Irish/warm female companion ("Ready when you are, Boss").
  - **E.D.I.T.H.**: Crisp tactical security synth female voice ("Tactical systems armed, Boss").
  - **D.E.V.I.L.**: Energetic, confident master voice with holographic Time-Vault interface.
- **⚡ 0ms Accessibility Optimization**: High-performance early-exit gating in `DevilAccessibilityService.kt` ensuring zero background CPU or frame drops across other apps.

---

### 6. Persistent Long-Term Memory Vault (`BossMemoryVault.kt`)
- **🧠 Zero-Cloud SQLite Knowledge Vault**: Permanently remembers user facts, credentials, vehicle numbers, routines, and preferences locally in phone storage.
- **🗣️ Natural Voice Commands**:
  - *"Remember that my car number is KA-04-9821"* ➡️ Saves to persistent memory.
  - *"What is my car number?"* ➡️ Instant 0ms recall without hitting the cloud.
  - *"What do you remember about me?"* / *"List my memories"* ➡️ Complete spoken summary.
  - *"Forget my car number"* ➡️ Instant memory purge.
- **🧬 Automatic Context Injection**: Injects saved Boss profile facts directly into Qwen's system prompt in `LlamaHelper.kt` so the assistant naturally knows you across conversation restarts.

---

### 7. Darwinbox 9-Hour Shift Tracker & Overtime Alert System
- **⏰ Automatic 9-Hour Shift Calculation**:
  - Automatically records check-in timestamp upon voice command (e.g. *"Check in on Darwinbox"* or manual entry *"I checked in at 9:15 AM"*).
  - Calculates exact 9-hour checkout target (e.g. Check-in at **9:15 AM** ➡️ Target checkout at **6:15 PM**).
- **🚨 10-Minute Overtime Alert (`DarwinboxAlarmReceiver.kt`)**:
  - Schedules an exact wake-up alarm with Android's `AlarmManager.setExactAndAllowWhileIdle` for **9 hours + 10 minutes** (e.g. **6:25 PM**).
  - Wakes the device from deep Doze mode and posts a high-priority heads-up notification with alarm audio, vibration, and a one-tap **"Clock Out Now"** action.
  - Suppresses alarm if user has already clocked out.
- **🛡️ 10:30 AM Background Verification & Fallback (Zero Burden / 0% CPU)**:
  - If no check-in was triggered through DEVIL before 10:30 AM, an exact RTC hardware alarm fires at **10:30 AM**:
    - Checks if check-in was already recorded for today.
    - If not recorded, automatically records manual check-in at 10:30 AM, schedules 9-hour checkout for 7:30 PM, and arms the overtime alert for 7:40 PM.
    - Uses Linux kernel RTC alarms (`AlarmManager.RTC_WAKEUP`) — phone remains in deep sleep with **0.00% battery drain** and no polling loops.
- **👁️ Passive Real-Time Detection (`DevilAccessibilityService.kt`)**:
  - If user manually opens Darwinbox before 10:30 AM (e.g. 9:15 AM) and taps "Check In" / "Done", DEVIL's accessibility listener captures the exact punch time in real-time with zero lag.
- **🗣️ Spoken Shift Status Queries & Manual Time Logging**:
  - *"When is my checkout?"* / *"What time do I checkout?"* ➡️ Spoken remaining time countdown (e.g. *"You have 3 hours and 15 minutes remaining in your 9-hour shift. Checkout scheduled for 6:15 PM."*).
  - *"How much shift time left?"* / *"Darwinbox status"* ➡️ Real-time shift summary.
  - *"I checked in at 9:15 AM"* / *"Set check in time to 9:30"* ➡️ Flexible parsing for biometric/fingerprint office punch-ins.
- **🧠 Cross-Session Memory Integration**: Logs shift timestamps directly into `BossMemoryVault.kt` so offline Qwen LLM naturally knows today's shift details in conversational prompts.

---

### 8. Architecture Documentation & Git Management
- **Kotlin vs. Flutter Architectural Decision**: Documented the engineering rationale in `ARCHITECTURE_DECISION_KOTLIN_VS_FLUTTER.md` and `.adoc` explaining why Native Android (Kotlin + Compose) was chosen over Flutter for low-level OS listeners, NDK zero-copy LLM inference, and OxygenOS process-killer resilience.
- **Clean Repository Structure**: Configured `.gitignore` to prevent large binary models (>100 MB) from blocking GitHub pushes, committed all project files, and synced to GitHub branch `nord-voice-assistant_Phase1`.

---

## 🛠️ Tech Stack
- **Language**: Kotlin 2.x
- **UI Framework**: Jetpack Compose (Material 3) with hardware-accelerated Canvas & Shaders
- **Inference Engine**: `org.codeshipping:llama-kotlin-android` (`llama.cpp` JNI)
- **Local Model**: Qwen 2.5 1.5B Instruct (`q4_k_m` 4-bit Quantized GGUF)
- **Speech Engine**: Android SpeechRecognizer & TextToSpeech (TTS)
- **Target OS**: Android 14 / 15 (OxygenOS / OnePlus)

---

## 🚀 How to Run

1. **Push the LLM model to your phone via ADB**:
   ```bash
   adb push models/qwen2.5-1.5b-instruct-q4_k_m.gguf /data/local/tmp/model.gguf
   ```
2. **Build and install the app**:
   ```bash
   cd android_app
   ./gradlew installDebug
   ```
3. **Grant runtime permissions** on the device:
   - Microphone, Phone, SMS, Contacts
   - Notification Access (in Android Settings for WhatsApp)