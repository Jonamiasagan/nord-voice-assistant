# Architectural Decision Record (ADR): Native Android (Kotlin + Jetpack Compose) vs. Flutter

**Project:** Nord AI Voice Assistant (OnePlus)  
**Status:** Accepted / Implemented  
**Date:** March 2026  

---

## Context & Problem Statement

When designing an AI Voice Assistant for OnePlus smartphones featuring on-device LLM reasoning, deep OS integration, background listeners, and rich holographic user interface animations (Gideon Hologram), a fundamental technology stack decision had to be made:

**Should the application be built with Flutter (Dart) or Native Android (Kotlin + Jetpack Compose)?**

The decision to choose **Native Android (Kotlin + Jetpack Compose)** was a deliberate architectural choice based on performance, OS integration depth, and hardware optimization.

---

## Decision Drivers & Technical Evaluation

### 1. Direct Access to Low-Level Android System APIs
An ambient, high-utility voice assistant requires low-level Android OS hooks that cross-platform frameworks cannot execute without writing custom native bridge code:

* **WhatsApp Notification Interception**: 
  Subclasses Android's native `NotificationListenerService` (`WhatsAppNotificationService.kt`). In Flutter, background notification services are complex, require boilerplate MethodChannels, and are frequently killed by Android's memory manager.
* **SMS & Contacts Content Providers**: 
  Direct, high-performance SQL-like queries via `ContentResolver` into `Telephony.Sms.Inbox` and `ContactsContract.PhoneLookup` with custom spam and marketing filters.
* **Bluetooth SCO Audio Routing**: 
  Native `AudioManager.startBluetoothSco()` directly routes microphone input through Bluetooth earbuds and car head units.
* **Deep System Media Intents**: 
  Launches VLC and media players with targeted search queries, album/artist focus flags, and shuffle parameters (`MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH`).

---

### 2. Native C++ / NDK On-Device LLM (`llama.cpp`)
Running an offline LLM (`qwen2.5-1.5b-instruct-q4_k_m.gguf`) requires high memory bandwidth and native CPU execution:

* **Direct JNI POSIX Memory Mapping**: 
  In Kotlin, `org.codeshipping:llama-kotlin-android` links directly to native C++ `llama.cpp` binaries using the Android NDK (JNI). Memory is mapped with zero serialization overhead.
* **Flutter Limitations**: 
  Running on-device LLMs in Flutter requires complex Dart FFI bridges, packaging multi-architecture `.so` files, and coping with Dart isolate memory copying, adding noticeable latency and RAM pressure when loading a 1 GB model into memory.

---

### 3. OnePlus (OxygenOS) Background & Memory Management
* **RAM Footprint**: 
  A Flutter application must initialize the entire Flutter Engine (Dart VM runtime + Skia/Impeller graphics engine), adding 40–80 MB of idle overhead.
* **OxygenOS Process Killer Resistance**: 
  OnePlus OxygenOS features aggressive battery and RAM optimization. Native Android services running directly on the Android Runtime (ART) have a minimal idle footprint, drastically reducing the likelihood of background termination.

---

### 4. Zero-Latency SpeechRecognizer & TTS Synchronization
* **Immediate Event Dispatch**: 
  Android's native `SpeechRecognizer` and `TextToSpeech.setOnUtteranceProgressListener` fire synchronously within the main process.
* **Hologram State Transition**: 
  The instant speech input stops, `onEndOfSpeech` immediately triggers the **THINKING** animation on the Gideon hologram without asynchronous message passing or bridging delays inherent in Flutter's `MethodChannel`.

---

## Architectural Comparison Matrix

| Feature / Requirement | Native Android (Kotlin + Compose) | Flutter (Dart) |
| :--- | :--- | :--- |
| **On-Device LLM (`llama.cpp`)** | Direct native JNI bindings; zero-copy memory | Requires custom Dart FFI wrapper & isolate bridges |
| **Notification Listener** | Native `NotificationListenerService` | Requires custom Kotlin platform channels |
| **Bluetooth SCO Routing** | Direct `AudioManager` calls | Limited third-party plugin reliability |
| **Engine RAM Overhead** | ~0 MB (Native ART runtime) | +40–80 MB (Dart VM & Flutter engine) |
| **Audio-Reactive HUD / Hologram** | Hardware-accelerated Compose Canvas & Shaders | Canvas rendering inside Flutter Skia/Impeller |
| **Assistant Trigger (`ACTION_VOICE_COMMAND`)** | Native Android Activity intent filter | Requires custom platform channel bridge |

---

## Conclusion

Flutter is well-suited for standard cross-platform UI applications (iOS + Android). However, for a **deeply integrated OnePlus system voice assistant** requiring raw hardware acceleration, offline LLM computation, and background OS services, **Native Kotlin with Jetpack Compose** provides the lowest latency, lowest memory overhead, and highest reliability.
