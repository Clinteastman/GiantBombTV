# Giant Bomb TV

An unofficial Android TV and mobile app for watching [Giant Bomb](https://www.giantbomb.com) videos. Glassmorphism-inspired TV UI and a YouTube-style mobile layout.

> **Disclaimer:** This is a fan-made, unofficial app. It is not affiliated with or endorsed by Giant Bomb. All Giant Bomb content is property of its respective owners.

**Giant Bomb is an independent video game site.** A free account gets you access to some content, but for the full experience (exclusive shows, ad-free videos, and access to the full back catalog) [**join Premium**](https://giantbomb.com/get-premium). It's worth it.

## Features

- **Browse** recent videos, shows, and categories (Premium / Free)
- **Continue Watching** with server-synced progress tracking and resume
- **Watchlist** management (add/remove from detail screen)
- **Search** across all Giant Bomb videos
- **Show browsing** with infinite scroll through episodes
- **Stream quality selection** - Auto (HLS), 1080p, 720p, 480p, 360p (per-session and default preference)
- **Watched indicators** (green checkmark) and red progress bars on all video cards
- **Chromecast** - cast videos to any Cast-compatible device from the mobile app
- **Picture-in-Picture** - keep watching in a floating window while using other apps (Android 8.0+)
- **Upcoming shows and live streams** - see what's coming up and jump into live Twitch streams
- **Self-update** - checks GitHub releases for new versions and prompts to install
- **Direct playback** - tap a video on mobile to start playing immediately
- **Blurred backdrop** with smooth crossfade transitions as you navigate (TV)
- **D-pad optimized** navigation for TV remotes
- **Mobile phone layout** - YouTube-style vertical feed with horizontal category rows
- **Portrait playback** - video at top with "Up Next" episodes below (mobile)
- **Responsive** - adapts between 1-column (portrait) and 2-column (landscape) grid on phones

## Screenshots

### Android TV

| Browse | Detail |
|--------|--------|
| ![Browse](screenshots/browse.png) | ![Detail](screenshots/detail.png) |

| Playback | Quality Picker |
|----------|---------------|
| ![Playback](screenshots/playback.png) | ![Quality](screenshots/quality.png) |

### Mobile

| Browse | Detail | Playback | Search |
|--------|--------|----------|--------|
| ![Browse](screenshots/mobile_browse.png) | ![Detail](screenshots/mobile_detail.png) | ![Playback](screenshots/mobile_playback.png) | ![Search](screenshots/mobile_search.png) |

## Installation

Download the latest APK from the [Releases](https://github.com/Clinteastman/GiantBombTV/releases) page, or use one of the methods below.

### Obtainium (recommended for auto-updates)

[Obtainium](https://github.com/ImranR98/Obtainium) tracks GitHub releases and notifies you when updates are available. You can sideload Obtainium onto your TV via ADB, then manage updates directly from the TV.

1. Install Obtainium on your Android TV device
2. Add a new app with the URL: `https://github.com/Clinteastman/GiantBombTV`
3. Obtainium will install the latest release and notify you of future updates

### Downloader (Fire TV / Android TV)

[Downloader](https://www.aftvnews.com/downloader/) is a popular sideloading app available on the Amazon Appstore and Google Play for TV.

1. Install Downloader on your Fire TV or Android TV
2. Open Downloader and enter the URL for the latest release APK from the [Releases](https://github.com/Clinteastman/GiantBombTV/releases) page
3. Download and install when prompted

### ADB (from a computer)

If you have a computer on the same network as your TV:

```bash
# Enable developer options and ADB debugging on your TV
# Connect via network: adb connect <tv-ip-address>
# Then install the APK:
adb install -r app-release.apk
```

### Setup

1. Log in to [giantbomb.com](https://www.giantbomb.com) and find your API key in your profile settings
2. Install the app using any method above
3. Launch the app and enter your API key in the setup screen

## Building

### Prerequisites

- [Android Studio](https://developer.android.com/studio) (Ladybug or later) **or** the Android SDK command-line tools
- JDK 17+
- Android SDK with:
  - Compile SDK 35
  - Build Tools (latest)
  - Android TV system image (optional, for emulator testing)
- Supports devices running Android 5.0+ (SDK 21)

### Build & Install

```bash
# Clone the repo
git clone https://github.com/Clinteastman/GiantBombTV.git
cd GiantBombTV

# Run tests
./gradlew testDebugUnitTest

# Build debug APK
./gradlew assembleDebug

# Install on a connected Android TV device or emulator
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch
adb shell am start -n com.giantbomb.tv/.MainActivity
```

### Build Configuration

The app has a build flag for inline YouTube playback in `app/build.gradle.kts`:

```kotlin
buildConfigField("boolean", "ENABLE_INLINE_YOUTUBE", "false")
```

By default this is **off**. When a video only has a YouTube source, the app opens it in the YouTube app or browser.

If you are building for sideloading or personal use, you can set this to `"true"` to enable inline YouTube playback using YouTube's internal API. This is not compliant with app store policies and should not be enabled for store builds.

### CI/CD

This project uses GitHub Actions for automated builds and releases:

- **Build & Test** - runs unit tests and builds on every push and PR to `master`
- **Release** - push a version tag (e.g. `git tag v0.3.0 && git push origin v0.3.0`) to automatically run tests, build a signed release APK, and create a GitHub Release

### Emulator Setup

To run on an Android TV emulator:

1. In Android Studio, open **Device Manager**
2. Create a new virtual device, select **Television**, then any TV profile
3. Select a system image (API 34 recommended)
4. Start the emulator, then run the build & install commands above

## Architecture

```
app/src/main/java/com/giantbomb/tv/
├── MainActivity.kt              # Host activity (loads TV or mobile fragment)
├── BrowseFragment.kt            # TV browse screen (Leanback BrowseSupportFragment)
├── CastOptionsProvider.kt       # Google Cast SDK configuration
├── DetailActivity.kt            # Video detail screen (responsive TV/mobile)
├── PlaybackActivity.kt          # ExoPlayer playback with Cast and PiP support
├── SearchActivity.kt            # Search screen host (loads TV or mobile search)
├── GiantBombSearchFragment.kt   # Leanback search with debounced API queries (TV)
├── ShowActivity.kt              # Show episode browser
├── ShowBrowseFragment.kt        # Infinite-scroll episode list for a show
├── SetupActivity.kt             # API key entry screen (responsive)
├── data/
│   ├── GiantBombApi.kt          # Giant Bomb public API client (OkHttp)
│   ├── TwitchExtractor.kt       # Twitch GQL stream extraction (live streams)
│   ├── UpdateChecker.kt         # GitHub release checker and APK installer
│   ├── YouTubeExtractor.kt      # YouTube stream extraction (optional, see build config)
│   └── PrefsManager.kt          # SharedPreferences wrapper
├── model/
│   ├── Video.kt                 # Video, Show, PlaybackInfo, ProgressEntry, Upcoming models
│   └── SettingsItem.kt          # Settings row item model
├── mobile/
│   ├── MobileBrowseFragment.kt  # YouTube-style vertical feed with horizontal rows
│   └── MobileSearchFragment.kt  # Mobile search with text input and list results
├── ui/
│   ├── VideoCardView.kt         # Custom glassmorphism video card (TV)
│   ├── CardPresenter.kt         # Leanback presenter for video cards
│   ├── ShowCardPresenter.kt     # Leanback presenter for show cards
│   ├── SettingsCardPresenter.kt # Leanback presenter for settings cards
│   ├── UpcomingCardPresenter.kt # Presenter for upcoming/live stream cards
│   ├── UpcomingCardView.kt      # Card view for upcoming shows and live streams
│   └── BlurTransformation.kt    # Glide transform for blurred backdrops
└── util/
    └── DeviceUtil.kt            # Runtime TV/phone detection
```

## Tech Stack

- **Kotlin** with coroutines
- **AndroidX Leanback** for TV UI framework
- **ExoPlayer (Media3)** for HLS and MP4 playback
- **Google Cast SDK** for Chromecast support
- **Glide** for image loading
- **OkHttp** for networking
- **Giant Bomb Public API** for all content
- **Twitch GQL API** for live stream extraction
- **JUnit + MockWebServer** for unit testing
- **GitHub Actions** for CI/CD

## Remote Controls

| Button | Action (Browse) | Action (Playback) |
|--------|-----------------|-------------------|
| D-pad | Navigate cards | Seek / show controls |
| Select/Enter | Open video detail | Play/pause |
| Back | Navigate back | Enter PiP (mobile) / save progress & exit (TV) |
| Menu | - | Open quality picker |
| Search orb | Open search | - |
| Play/Pause | - | Toggle playback |
| FF/Rewind | - | Skip +/-10 seconds |

## AI Tools Used

This project uses AI-assisted tooling for specific parts of the development workflow:

- **Claude Code** - unit test generation, commit messages, PR review prep, mock reviews for app store readiness, and as a sounding board during planning
- **GitHub Copilot** - pull request code reviews

These tools handle the repetitive parts of solo development (writing tests, reviewing your own PRs, preparing store submissions) so more time goes into the actual app. All architecture, features, and code are written by a human developer with 26 years of professional experience. Android/Kotlin is a new stack for me, so AI review serves as a useful second opinion when learning a new platform.

## Known Issues

- Audio crackle and video stutter on emulators due to software decoding - does not affect real hardware (Fire TV, etc.)
- Cloudflare may intermittently block API requests, causing 403 errors or missing thumbnails
- This is an early preview - expect rough edges

## License

This project is provided as-is for personal, non-commercial use only.
