# Giant Bomb TV

[![Build & Test](https://img.shields.io/github/actions/workflow/status/Clinteastman/GiantBombTV/build.yml?branch=master&label=build%20%26%20test)](https://github.com/Clinteastman/GiantBombTV/actions/workflows/build.yml)
[![Latest release](https://img.shields.io/github/v/release/Clinteastman/GiantBombTV?label=release&color=blue)](https://github.com/Clinteastman/GiantBombTV/releases/latest)
[![Android 5.0+](https://img.shields.io/badge/min%20SDK-21-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions/lollipop)
[![Google Play](https://img.shields.io/badge/Google%20Play-Available-414141?logo=googleplay&logoColor=white)](https://play.google.com/store/apps/details?id=com.giantbomb.tv)
[![Amazon Appstore](https://img.shields.io/badge/Amazon%20Appstore-Available-FF9900?logo=amazon&logoColor=white)](https://www.amazon.co.uk/dp/B0H1DSRF54)

An unofficial Android TV and mobile app for watching [Giant Bomb](https://www.giantbomb.com) videos. Glassmorphism-inspired TV UI and a YouTube-style mobile layout.

> **Disclaimer:** This is a fan-made, unofficial app. It is not affiliated with or endorsed by Giant Bomb. All Giant Bomb content is property of its respective owners.

**Giant Bomb is an independent video game site.** A free account gets you access to some content, but for the full experience (exclusive shows, ad-free videos, and access to the full back catalog) [**join Premium**](https://giantbomb.com/get-premium). It's worth it.

## Features

- **Browse** recent videos, shows, and categories (Premium / Free)
- **Customize Browse** - reorder or hide sections to taste; pin shows to the top
- **Continue Watching** with server-synced progress tracking and resume
- **Watchlist** management (add/remove from detail screen, tap-to-toggle bookmark on any mobile card)
- **Background playback** - foreground media service keeps audio playing when you leave the app or lock the phone, with transport-control notification
- **Search** across all Giant Bomb videos
- **Show browsing** with infinite scroll through episodes (TV shows a vertical episode grid with marquee show titles)
- **Autoplay next episode** - automatically advances to the next episode when the current one finishes, swapping in place so playback stays smooth
- **Now-playing title** - the video title is shown under the TV playback controls
- **Stream quality selection** - Auto (HLS), 1080p, 720p, 480p, 360p (per-session and default preference)
- **Watched indicators** (green checkmark) and red progress bars on all video cards
- **Chromecast** - cast videos to any Cast-compatible device from the mobile app
- **Picture-in-Picture** - keep watching in a floating window while using other apps (Android 8.0+); the Home button always enters PiP, and an optional Settings toggle makes Back enter PiP too
- **Upcoming shows and live streams** - see what's coming up and jump into live Twitch streams; live cards refresh their preview every minute
- **Read-only Twitch chat** - follow the live chat alongside the stream on TV and in mobile landscape; toggle it in Settings
- **Self-update** - checks GitHub releases for new versions and prompts to install
- **Direct playback** - tap a video on mobile to start playing immediately
- **Blurred backdrop** with smooth crossfade transitions as you navigate (TV)
- **D-pad optimized** navigation for TV remotes, with white focus halos on every playback control and a per-row context menu reachable by holding Select on a header
- **Mobile phone layout** - YouTube-style vertical feed with horizontal category rows
- **Bottom navigation** (mobile) - Home, Shows, and Podcasts tabs
- **Podcasts tab** - a full-width featured hero for the newest podcast (tap to play) with an "Up Next" row, above the grid of podcast shows
- **Favourite shows** - long-press a show card (mobile) to pin it; pinned shows sort to the front and appear in your Home "Pinned Shows" section
- **Chip-bar quick-jump** - tap a section name (mobile) to jump straight to that section
- **Portrait playback** - video at top with "Up Next" episodes below (mobile)
- **Responsive** - adapts between 1-column (portrait) and 2-column (landscape) grid on phones
- **Plex Sync** - optional standalone script (`scripts/plex-sync.py`) generates `.strm` / `.nfo` / thumb triples so a Plex server can index your Giant Bomb subscription as a TV library

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

The app is published on **Google Play** (Android phones and Android TV) and the **Amazon Appstore** (Fire TV). You can also sideload the latest APK from the [Releases](https://github.com/Clinteastman/GiantBombTV/releases) page if you prefer to manage updates yourself.

### Google Play (Android phones and Android TV, recommended)

[![Get it on Google Play](https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png)](https://play.google.com/store/apps/details?id=com.giantbomb.tv)

[Giant Bomb TV on Google Play](https://play.google.com/store/apps/details?id=com.giantbomb.tv). Install directly on your Android phone or Android TV. Updates are delivered automatically through Google Play.

### Amazon Appstore (Fire TV, recommended)

[![Available on Amazon Fire TV](screenshots/amazon-appstore-badge.png)](https://www.amazon.co.uk/dp/B0H1DSRF54)

[Giant Bomb TV on the Amazon Appstore](https://www.amazon.co.uk/dp/B0H1DSRF54). Install directly from your Fire TV's appstore search ("Giant Bomb TV") or from the link on a phone/computer to push to your Fire TV. Updates are delivered automatically through the appstore.

### Obtainium (sideloading with auto-updates)

If you'd rather track GitHub releases than install from a store, [Obtainium](https://github.com/ImranR98/Obtainium) notifies you when new versions are available. Useful for Android TV devices without Google Play, or if you want the raw GitHub build. You can sideload Obtainium onto your TV via ADB, then manage updates directly from the TV.

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
├── MainActivity.kt              # Host activity (TV browse, or mobile bottom-nav tabs)
├── BrowseFragment.kt            # TV browse screen (Leanback BrowseSupportFragment)
├── CastOptionsProvider.kt       # Google Cast SDK configuration
├── CustomizeBrowseActivity.kt   # Drag-to-reorder + hide-section settings screen
├── DetailActivity.kt            # Video detail screen (responsive TV/mobile)
├── PlaybackActivity.kt          # Playback UI (controls, Cast, PiP, title overlay, Twitch chat, autoplay-next); player itself lives in the service
├── SearchActivity.kt            # Search screen host (loads TV or mobile search)
├── GiantBombSearchFragment.kt   # Leanback search with debounced API queries (TV)
├── ShowActivity.kt              # Show episode browser
├── ShowBrowseFragment.kt        # Infinite-scroll episode list for a show
├── SetupActivity.kt             # API key entry screen (responsive)
├── data/
│   ├── GiantBombApi.kt          # Giant Bomb public API client (OkHttp)
│   ├── TwitchExtractor.kt       # Twitch GQL live-status + HLS extraction
│   ├── TwitchChatPrefs.kt       # Twitch chat opt-out + cookie/DOM cleanup
│   ├── UpcomingResolver.kt      # Pure helpers reconciling GB feed + Twitch live signal
│   ├── UpdateChecker.kt         # GitHub release checker and APK installer
│   ├── YouTubeExtractor.kt      # YouTube stream extraction (optional, see build config)
│   └── PrefsManager.kt          # SharedPreferences wrapper (section order, pins, etc.)
├── model/
│   ├── Video.kt                 # Video, Show, PlaybackInfo, ProgressEntry, Upcoming models
│   └── SettingsItem.kt          # Settings row item model
├── playback/
│   └── PlaybackService.kt       # Foreground MediaSessionService; owns the ExoPlayer instance
├── mobile/
│   ├── MobileBrowseFragment.kt   # YouTube-style vertical feed; mobile bottom-nav Home tab
│   ├── MobileShowGridFragment.kt # Shows / Podcasts tabs (grid + podcast hero + Up Next)
│   ├── MobileGridAdapter.kt      # Multi-type grid adapter (hero / episode / show cards)
│   └── MobileSearchFragment.kt   # Mobile search with text input and list results
├── ui/
│   ├── VideoCardView.kt         # Custom glassmorphism video card (TV)
│   ├── CardPresenter.kt         # Leanback presenter for video cards
│   ├── ShowCardPresenter.kt     # Leanback presenter for show cards
│   ├── SettingsCardPresenter.kt # Leanback presenter for settings cards
│   ├── UpcomingCardPresenter.kt # Presenter for upcoming/live stream cards
│   ├── UpcomingCardView.kt      # Card view for upcoming shows and live streams
│   └── BlurTransformation.kt    # Glide transform for blurred backdrops
└── util/
    ├── DateFormat.kt            # Shared publish-date formatting (mobile + TV)
    └── DeviceUtil.kt            # Runtime TV/phone detection

scripts/
├── install-latest.sh            # Sideload the latest CI APK to phone + TV in one go
├── plex-sync.py                 # Generate a Plex-indexable local library from your subscription
└── Dockerfile.plex-sync         # Containerised plex-sync runner
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
| D-pad | Navigate cards | Seek / show controls (progressive scrub speed when held) |
| Select/Enter | Open video detail | Play/pause |
| Select/Enter (hold) | Open context menu on the focused side-menu header (pin show, reorder section), or pin/unpin a show card | - |
| Back | Slide the side menu back in / exit confirmation (TV); on mobile, return to the previous screen (or to the Home tab from Shows/Podcasts) | Save progress & exit (TV); on mobile, return to the previous screen — or enter PiP if enabled in Settings |
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
