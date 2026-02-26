# Pretty Private AI

An Android chat app for private AI inference. Your prompts and responses are
end-to-end encrypted and processed inside a hardware Trusted Execution
Environment (AMD SEV-SNP) — the server operator cannot read them.

## What makes it different

| | Pretty Private AI | Typical AI chat apps |
|---|---|---|
| **Prompt confidentiality** | HPKE field-level E2E encryption; server processes data inside TEE | TLS terminates at server; operator can read everything |
| **Attestation** | Client verifies AMD SEV-SNP report before sending data | Trust the operator's word |
| **Speech-to-text** | On-device via whisper.cpp — audio never leaves the phone | Cloud STT; audio uploaded to third party |
| **Web search** | Via Startpage (privacy-focused); results stay on device | Provider-dependent |

## Features

- **AI chat** with streaming responses, markdown rendering, file and image upload
- **Model selection**: GPT (120B), Gemma 3 (27B), Qwen3 Coder (30B) — all running
  in attested confidential VMs on NVIDIA H100 GPUs
- **On-device speech-to-text** using whisper.cpp (tiny 31 MB / small 105 MB models)
- **Security dashboard** showing attestation status, manifest hash, and TCB versions
- **Extended thinking** (reasoning mode) for supported models
- **Web search** integration via Startpage from local device
- **Local chat history** with search, rename, and date grouping

## Prerequisites

- Android Studio (Arctic Fox+) or Android Gradle Plugin 8.7+
- Android SDK API 35, NDK
- JDK 17
- Go 1.25+ (for cross-compiling the native proxy)
- A [Privatemode.ai](https://privatemode.ai) access key

## Building

### Debug APK (with TEE attestation)

```bash
# Set NDK path
export ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/<version>

# Cross-compile Go proxy for Android
./scripts/build-native.sh

# Build and install
./gradlew installDebug
```

### Debug APK (without native proxy)

Skip `build-native.sh`. The app falls back to direct HTTPS — still connects to
`api.privatemode.ai` over TLS, but without client-side attestation verification
or HPKE encryption.

### Release bundle (unsigned)

```bash
./gradlew bundleRelease
```

Output: `app/build/outputs/bundle/release/app-release.aab`

## Tests

```bash
# Unit tests
./gradlew testDebugUnitTest

# Whisper native end-to-end test (builds whisper-cli, downloads model, transcribes sample)
./tests/whisper-native-e2e.sh
```

## Architecture

The app embeds a Go shared library (`libprivatemode.so`) loaded via JNI.
On startup, the proxy performs remote attestation against the Privatemode backend
using the Contrast SDK, establishes HPKE keys, then listens on localhost.
All API traffic flows through this local proxy, which encrypts request fields
and decrypts responses transparently.

```
Android App (Kotlin/Compose)
  └─ OkHttp ──► localhost proxy (Go, JNI)
                   ├─ TEE attestation (AMD SEV-SNP via Contrast SDK)
                   ├─ HPKE E2E encryption
                   └─ HTTPS ──► api.privatemode.ai (confidential VM)
```

Whisper transcription runs entirely on-device via whisper.cpp (JNI).
No audio data is transmitted.

## License

See [LICENSE](LICENSE).
