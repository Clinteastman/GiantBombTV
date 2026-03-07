# Giant Bomb TV

An unofficial Android TV app for watching [Giant Bomb](https://www.giantbomb.com) videos with a glassmorphism-inspired UI.

> **Disclaimer:** This is a fan-made, unofficial app. It is not affiliated with or endorsed by Giant Bomb. All Giant Bomb content is property of its respective owners.

**Giant Bomb is an independent video game site.** A free account gets you access to some content, but for the full experience — including exclusive shows, ad-free videos, and access to the full back catalog — [**join Premium**](https://giantbomb.com/get-premium). It's worth it.

## Features

- **Browse** recent videos, shows, and categories (Premium / Free)
- **Continue Watching** with server-synced progress tracking and resume
- **Watchlist** management (add/remove from detail screen)
- **Search** across all Giant Bomb videos
- **Show browsing** with infinite scroll through episodes
- **Stream quality selection** — Auto (HLS), 1080p, 720p, 480p, 360p (per-session and default preference)
- **Watched indicators** (green checkmark) and red progress bars on all video cards
- **Blurred backdrop** with smooth crossfade transitions as you navigate
- **D-pad optimized** navigation for TV remotes

## Screenshots

*Coming soon*

## Setup

1. Get your Giant Bomb API key from [giantbomb.com/app/](https://www.giantbomb.com/app/)
2. Build and install the app (see below)
3. Launch the app and enter your API key in the setup screen

## Building

### Prerequisites

- [Android Studio](https://developer.android.com/studio) (Ladybug or later) **or** the Android SDK command-line tools
- JDK 17+
- Android SDK with:
  - Compile SDK 34
  - Build Tools (latest)
  - Android TV system image (optional, for emulator testing)

### Build & Install

```bash
# Clone the repo
git clone https://github.com/Clinteastman/GiantBombTV.git
cd GiantBombTV

# Build debug APK
./gradlew assembleDebug

# Install on a connected Android TV device or emulator
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch
adb shell am start -n com.giantbomb.tv/.MainActivity
```

### Emulator Setup

To run on an Android TV emulator:

1. In Android Studio, open **Device Manager**
2. Create a new virtual device → select **Television** → any TV profile
3. Select a system image (API 34 recommended)
4. Start the emulator, then run the build & install commands above

## Architecture

```
app/src/main/java/com/giantbomb/tv/
├── MainActivity.kt              # Host activity for BrowseFragment
├── BrowseFragment.kt            # Main browse screen (Leanback BrowseSupportFragment)
├── DetailActivity.kt            # Video detail screen with cinematic backdrop
├── PlaybackActivity.kt          # ExoPlayer video playback with quality picker
├── SearchActivity.kt            # Search screen host
├── GiantBombSearchFragment.kt   # Leanback search with debounced API queries
├── ShowActivity.kt              # Show episode browser
├── ShowBrowseFragment.kt        # Infinite-scroll episode list for a show
├── SetupActivity.kt             # API key entry screen
├── data/
│   ├── GiantBombApi.kt          # Giant Bomb public API client (OkHttp)
│   └── PrefsManager.kt          # SharedPreferences wrapper
├── model/
│   ├── Video.kt                 # Video, Show, PlaybackInfo, ProgressEntry models
│   └── SettingsItem.kt          # Settings row item model
└── ui/
    ├── VideoCardView.kt         # Custom glassmorphism video card
    ├── CardPresenter.kt         # Leanback presenter for video cards
    ├── ShowCardPresenter.kt     # Leanback presenter for show cards
    └── SettingsCardPresenter.kt # Leanback presenter for settings cards
```

## Tech Stack

- **Kotlin** with coroutines
- **AndroidX Leanback** for TV UI framework
- **ExoPlayer (Media3)** for HLS and MP4 playback
- **Glide** for image loading
- **OkHttp** for networking
- **Giant Bomb Public API** for all content

## Remote Controls

| Button | Action (Browse) | Action (Playback) |
|--------|-----------------|-------------------|
| D-pad | Navigate cards | Seek / show controls |
| Select/Enter | Open video detail | Play/pause |
| Back | Navigate back | Save progress & exit |
| Menu | — | Open quality picker |
| Search orb | Open search | — |
| Play/Pause | — | Toggle playback |
| FF/Rewind | — | Skip ±10 seconds |

## License

This project is provided as-is for personal, non-commercial use only.
