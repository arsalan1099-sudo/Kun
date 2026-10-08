# KUN Hybrid 0.2 — Android + self-hosted AI gateway

KUN now has two explicitly selected modes:

- **Local (default on every launch):** MediaPipe runs the imported model on the user's phone. No chat request leaves the phone. No provider token quota; device memory, context length, heat and battery still limit use.
- **Server (opt-in):** Sends the current question and up to eight recent messages to the owner's HTTPS KUN gateway. The gateway calls a locally hosted Ollama model. Users have separate revocable bearer tokens. There is no automatic cloud fallback.

This is an updated source package, NOT an APK or a running public service. The server is a small-pilot implementation, not a large-scale production platform. No model weights, access tokens, API keys or signing keys are included. No Replit dependency.

## فون پر استعمال

1. Android Studio یا شامل GitHub Actions workflow سے APK بنائیں۔
2. Local mode: موزوں `gemma3-1b-it-int4.task` الگ حاصل کرکے **Model لوڈ کریں** دبائیں۔ شرائط قبول کرنا ضروری ہو سکتا ہے۔
3. Server mode: **Server settings** میں اپنا `https://your-domain` اور administrator سے ملا الگ user token درج کریں، پھر Server mode فعال کریں۔
4. Server mode میں سوال اور حالیہ گفتگو server کو جاتی ہے؛ Local گفتگو بھی حالیہ history میں شامل ہو سکتی ہے۔ حساس پرانی گفتگو بھیجنے سے پہلے **نئی گفتگو** کریں۔
5. Token صرف app session کی memory میں ہے؛ app process بند ہونے کے بعد دوبارہ درج کریں۔ Server address محفوظ رہتا ہے۔ ہر launch پر Local mode منتخب ہوتا ہے۔
6. Server بند یا مصروف ہو تو سوال input میں برقرار رہتا ہے۔ App خود سے دوسرے provider کو نہیں بھیجتا۔

## Build an APK

Project root is the **contents of KUN/**. Upload those contents to your GitHub repository root, including `.github/`, `gradle/` and `gradlew`.

GitHub → Actions → **Build KUN debug APK** → Run workflow. After success, download the **KUN-debug-apk** artifact, unzip it and install `app-debug.apk` on your Android 12+ ARM64 phone. This workflow has been provided but has not been run in this session. GitHub Actions availability/charges depend on your account and repository.

Or use Android Studio with JDK 17+, SDK 35, Gradle wrapper 8.9 / Android Gradle Plugin 8.7.3:

```bash
chmod +x gradlew
./gradlew --no-daemon assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. Debug signing is for testing; retain your own release signing identity for distribution. If an older installation was signed with another key, an update will fail: use the same signing key or export anything needed before uninstalling (uninstall deletes app history/model).

Package `app.kun.offline`; min SDK 31; target SDK 34; compile SDK 35; ARM64. Review target SDK/store requirements before publishing. MediaPipe dependency remains exactly `com.google.mediapipe:tasks-genai:0.10.27`.

## Local model

Expected import file: `gemma3-1b-it-int4.task`.
Model source: https://huggingface.co/litert-community/Gemma3-1B-IT/tree/main
Imported private filename: `model.task`. `.gguf` and `.litertlm` are not supported by this Android implementation. Max import 4 GiB. CPU backend; 2,048-token context setting and 1,400-token prompt budget retained from the existing app. Quality and device compatibility need actual phone testing. The phone model and Ollama model are separate formats and separate downloads.

## Backend / users

See [server/README.md](server/README.md) for provisioning and Docker HTTPS setup. Each user can run local inference independently; Server mode shares the owner's compute. No total monthly token budget is enforced by this gateway, but there are deliberate per-request and traffic limits. This is not infinite capacity or free hosted compute.

FreeLLMAPI is not bundled or connected: its provider quotas do not become unlimited, and the inspected project is intended for single-user use. For this multi-user pilot the supplied backend uses Ollama directly. A future provider fallback needs separate terms, privacy, billing and capacity decisions.

## Verification

- 9 backend tests passed, including real local HTTP roundtrip to a mock upstream, authentication, immediate revocation, invalid inputs, body/context limits, rate limiting, concurrency release, and upstream failure redaction.
- No JDK compiler or Android SDK is available in the editing environment: Android 0.2 compilation has NOT been performed.
- Docker/Caddy startup, HTTPS issuance, real Ollama inference, Android UI, physical-device inference and load tests have NOT been run. No APK is included.
- The original export's old build success referred only to version 0.1 and must not be interpreted as validation of 0.2.

## Acceptance checks before sharing

1. Build APK, launch with increased text size and verify Urdu/RTL and keyboard layout.
2. Import compatible model, ask in airplane mode, restart and check history.
3. Configure deployed HTTPS gateway with a user token. Test Urdu multi-turn replies.
4. Try bad token and unavailable server; question must remain and no local/cloud fallback should occur.
5. Revoke a token, verify 401; test two users and busy/rate-limit handling.
6. Clear token and restart app: no token retained and Local selected.
7. Run sustained device/GPU tests and evaluate Urdu quality before widening the pilot.

History is app-private and retained up to 100 messages; Android backup disabled. It is not separately encrypted. No telemetry or gateway chat persistence is implemented. Keep the app in foreground while generating/importing. No streaming, cancellation, account signup, billing, attachments, web browsing or public app-store release is included.

References checked during implementation:
- https://github.com/ollama/ollama/blob/main/docs/api/openai-compatibility.mdx
- https://developers.google.com/edge/api/mediapipe/java/com/google/mediapipe/tasks/genai/llminference/LlmInference
