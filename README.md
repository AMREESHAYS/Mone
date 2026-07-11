# Mone 🐵

A simple Android app to download videos from Instagram, Pinterest, X, and 1000+ other sites — saved straight to your gallery. Built on [yt-dlp](https://github.com/yt-dlp/yt-dlp).

> [!WARNING]
> **For personal use only.** Downloading content may violate the terms of service of Instagram, YouTube, Pinterest, and other platforms, and redistributing others' content may infringe copyright. You are responsible for how you use this app. It is not affiliated with, endorsed by, or connected to any of these platforms. Not available on Google Play by design.

## Features

- **Paste a link → download.** Reels, posts, and videos from 1000+ sites yt-dlp supports.
- **Share to Mone.** Share a reel from Instagram straight into the app — no copy-paste.
- **In-app Instagram login.** Sign in with your own account so login-gated reels work. Your session stays on your device, in private app storage — never shared.
- **Best quality.** Grabs the best single-file stream, or downloads video + audio separately and merges them with ffmpeg.
- **Saves to your gallery** in a dedicated `Mone` folder.
- **Download notifications** and a **history** list of everything you've saved.

## Install

Grab the latest `app-release.apk` from the [Releases](../../releases) page and install it (you'll need to allow installing from unknown sources). Sideload only — this app is not on Google Play.

On first launch it asks for **All files access** (to save into the `Mone` folder) and **notification** permission.

## How it works

Mone is a thin Kotlin front-end. The actual downloading is done by **yt-dlp** (embedded via [yt-dlp-android](https://github.com/ffmpegkit-maintained/yt-dlp-android), which bundles Python), and merging is done by **ffmpeg-kit**. Instagram authentication uses a WebView login that captures your cookies locally for yt-dlp.

## Build from source

Requirements: Android Studio, JDK 17+, an Android device or emulator (arm64-v8a or x86_64).

```bash
git clone https://github.com/AMREESHAYS/Mone.git
cd Mone
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Limitations (honest)

- Instagram reels require logging in with your own account (cookies).
- ffmpeg is the free/LGPL build — some codecs and best-quality merges may be limited.
- yt-dlp is bundled at a fixed version; sites change often, so extractors can break until the library updates.
- Depends on a young third-party library; treat it as experimental.

## Credits

- [yt-dlp](https://github.com/yt-dlp/yt-dlp) — the download engine
- [yt-dlp-android](https://github.com/ffmpegkit-maintained/yt-dlp-android) — Android bindings
- [ffmpeg-kit](https://github.com/ffmpegkit-maintained/ffmpeg-kit) — media muxing

## License

[GPL-3.0](LICENSE) © AMREESHAYS
