# KUN 0.5 Preview — real chat using your PC

The previous app disabled Send until a small experimental model.json was loaded. That character model is not an instruction-following chat model. This change adds an explicitly selected Ollama connection. No paid API key is required.

## Install and connect

1. Install KUN-0.5-Preview.apk on Android 12 or newer. It installs alongside your old KUN as **KUN Preview**. Old chats remain in the old app; they are not migrated. This is a development-signed preview, not a Play Store release.
2. On your Windows PC, install Ollama from https://ollama.com/download/windows . While Ollama is running, open PowerShell and run `ollama pull qwen2.5:1.5b`. This is approximately a 986 MB initial download. An 8 GB PC is a reasonable starting point for this small model, but speed and Urdu quality need device testing.
3. Find your PC's Wi-Fi IPv4 with `ipconfig`. Quit Ollama from its system-tray menu. In PowerShell set `$env:OLLAMA_HOST="192.168.1.10:11434"` (replace the example IP with your PC's actual Wi-Fi IP), then run `ollama serve`. Keep this window open. If Windows Firewall asks, allow only your trusted Private network. Do not port-forward this unauthenticated service to the internet.
4. Connect your phone to the same trusted Wi-Fi. In **KUN Preview**, tap **⋯ → Connect PC AI / Ollama**, enter `http://YOUR-PC-IP:11434`, tap Connect and select the downloaded model from the returned list.
5. Send a message. The question and up to four recent conversation pairs from the same endpoint/model are sent to your PC. After model download, this local-network mode does not require an external AI service. The PC must stay running; inference is NOT on the phone.

## What changed

- Send offers setup instead of silently staying disabled without a model.
- Server model discovery, real Ollama chat requests, bounded conversation context and network errors.
- Model.json experiment stays available in settings and is labelled experimental.
- Experimental responses are excluded from PC AI context.
- Failed requests retain the question for retry; chat/history/copy behavior is preserved.
- Private numeric-IP HTTP is allowed for local PCs. Public hosts require HTTPS. Redirects are not followed. This client does not currently support server authentication tokens.

## Tested / not tested

- Original Java model regression tests passed.
- 21 Java integration assertions passed against a local mock HTTP server: URL validation, context isolation, model discovery, Urdu UTF-8 request/response, empty output and HTTP 404.
- Gradle assembleDebug could not resolve the Android Gradle plugin in this environment. The delivered APK was built directly with the official SDK tools instead.
- All Android Java sources compiled against Android SDK 35; DEX/resources/APK assembled with official Android SDK tools. APK signature verified.
- No live Ollama model inference, Android emulator run or Redmi installation was performed in this environment. Connecting your PC and testing a real answer remain required. This is not a claim of production readiness.
- Preview build uses application ID app.kun.offline.preview. Release source retains app.kun.offline; updating the original app requires its original signing key.

## Build

JDK 17+, Android SDK 35, build tools 35.0.0: `./gradlew assembleDebug`.
The CI workflow builds the preview and runs model and client tests. No server credentials or model weights are embedded.

References: https://github.com/ollama/ollama/blob/main/docs/api.md ; https://docs.ollama.com/faq ; https://ollama.com/library/qwen2.5:1.5b
