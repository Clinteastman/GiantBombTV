# Changelog

All notable changes to Giant Bomb TV are documented here. Versions follow the
app's release tags; dates are in YYYY-MM-DD.

## [1.2.1] - 2026-06-02

### Fixed
- **Android TV: app crashed on launch.** The main screen inflated the mobile
  bottom navigation bar (`BottomNavigationView`) on every device. That Material
  widget requires a Material-based theme, but Android TV runs on `Theme.Leanback`,
  so it threw at view-inflation time before the home screen could appear —
  crashing on startup on all TV devices and blocking Android TV review. TV now
  loads a navigation-bar-free layout (`res/layout-television/activity_main.xml`)
  and starts normally. Phone and tablet behaviour is unchanged.

## [1.2.0] - 2026-05-31

### Added
- Offline video downloads with a background download service and a Downloads
  screen for watching saved videos offline.
- Mobile bottom navigation (Home / Shows / Podcasts / Downloads) and a podcast
  hero section with show favouriting.
- Twitch chat alongside live streams, plus a Settings toggle to show/hide it.
- Customize Browse: reorder, hide, and restore home rows.

### Fixed
- Autoplay of the next episode no longer shows a black screen with audio only.

## [1.1.0] - 2026-05-11

### Added
- Upcoming / Live row and background (audio) playback.
- Plex sync and assorted Android TV polish.

[1.2.1]: https://github.com/Clinteastman/GiantBombTV/releases/tag/v1.2.1
[1.2.0]: https://github.com/Clinteastman/GiantBombTV/releases/tag/v1.2.0
[1.1.0]: https://github.com/Clinteastman/GiantBombTV/releases/tag/v1.1.0
