# Mural for Android

Native Kotlin and Jetpack Compose client for Android 8.0 or later. It offers voice and written conversation, eight learning languages, 24 themes, meanings, vocabulary and local history, using the owner's DeepSeek API key and Android speech services. Mandarin captions link each word and show optional pinyin on Android 10 or later; Android 8 and 9 keep word links without pinyin.

Word taps in all eight languages open a contextual meaning sheet, matching the iOS flow. Mandarin uses a bundled offline phrase dictionary with an ICU fallback; unresolved common ambiguous readings retain their source characters.

Interface copy lives in `res/values` (English) and `res/values-es` (Spanish); Android picks the translation from the phone's language.

See [install and build](../../docs/run-on-android.md), [design](../../docs/android/design.md) and [verification](../../verification/android-validation.md).

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

## DeepSeek backend

The Android client sends text to `https://api.deepseek.com/chat/completions` using `deepseek-chat`, Bearer authentication and non-streaming Chat Completions. Evaluations use JSON mode with client-side schema validation. Voice alternates Android speech recognition, a DeepSeek text reply, and Android text-to-speech; no audio is sent to DeepSeek. The device speech service can use its own network servers. Device voices and recognition language support are required.

Enter a new DeepSeek key in Settings after upgrading and accept the updated processing consent. The old provider key is never reused. History and vocabulary remain local. The web-search topic is hidden because this API has no built-in web search. This Android variant disables the legacy managed origin, Google account configuration and minute purchases; it does not require a server deployment. Historical hosted conversations remain available locally but do not issue hosted AI requests.

Voice is turn-based, with no simultaneous listening during playback. Bluetooth routing follows the device speech engine and requires device testing. Billing is by DeepSeek tokens; the obsolete per-minute voice estimate has been removed.

Requirements: Java 17, Android SDK 36 and Build Tools 35.0.0. The API key is entered inside the app and must never be configured in Gradle. `local.properties`, private signing keys and build outputs are excluded from Git.
