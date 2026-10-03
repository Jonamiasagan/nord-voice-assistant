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

### 5. Architecture Documentation & Git Management
- **Kotlin vs. Flutter Architectural Decision**: Documented the engineering rationale in `ARCHITECTURE_DECISION_KOTLIN_VS_FLUTTER.md` and `.adoc` explaining why Native Android (Kotlin + Compose) was chosen over Flutter for low-level OS listeners, NDK zero-copy LLM inference, and OxygenOS process-killer resilience.
- **Clean Repository Structure**: Configured `.gitignore` to prevent large binary models (>100 MB) from blocking GitHub pushes, committed all 51 project files, and synced to GitHub branch `nord-voice-assistant_Phase1`.

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