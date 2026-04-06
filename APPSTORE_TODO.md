# App Store Submission - Remaining Items

Items that require manual/external action before submitting to the Google Play Store and Amazon Appstore.

## Required

### Trademark / Branding Authorization
- The app uses Giant Bomb's name, logo, and branding throughout
- Both stores will likely require written permission from Giant Bomb to publish
- Options: get explicit permission from GB staff, or rebrand as a generic "video client" with GB as one source
- Without this, the app will almost certainly be rejected

### Content Rating Questionnaire
- Both stores require completing their content rating questionnaire
- The app streams user-generated/editorial video content
- Will need to accurately describe content types (language, etc.)
- Google Play uses the IARC rating system
- Amazon has its own content rating form

### App Store Listing Assets
- Store assets exist in `store-assets/` but review them before submission:
  - `app_icon_512x512.png` - app icon
  - `small_icon_114x114.png` - small icon (Amazon)
  - `firetv_banner_1280x720.png` - Fire TV banner
- Google Play requires:
  - Hi-res icon (512x512)
  - Feature graphic (1024x500)
  - 2-8 phone screenshots
  - 7-inch and 10-inch tablet screenshots (if supporting tablets)
  - TV screenshots and banner (for Android TV)
- Amazon requires:
  - 3-10 screenshots (1920x1080 or 1080x1920)
  - Small icon (114x114)
  - Fire TV banner (1280x720)
- Both need short description (max 80 chars) and long description (max 4000 chars)

### Google Play Data Safety Form
- Google Play requires declaring what data is collected, shared, and how it's used
- Data transmitted: API key, viewing progress, watchlist, search queries (all to giantbomb.com)
- Data stored locally: API key, quality preference, favourite shows
- No analytics, no advertising, no crash reporting, no third-party data sharing

### Testing on Physical Devices
- Test the release (minified) APK on a real Fire TV before Amazon submission
- Test on a real phone before Play Store submission
- Verify all features work with R8 enabled (minification can break reflection-based code)
- Confirm MediaSession works with Fire TV remote and Alexa voice commands

## Completed

### Privacy Policy
- Created at `docs/privacy.html`, hosted via GitHub Pages
- URL: https://clinteastman.github.io/GiantBombTV/privacy
- Linked in app settings (both TV and mobile)
- Covers API key, progress/watchlist data, Twitch, GitHub update checks, and Chromecast

### Restrict Cleartext Traffic
- `network_security_config.xml` updated to disallow cleartext (HTTP) traffic
- All API endpoints use HTTPS

### Store-Compliant Build Configuration
- `ENABLE_INLINE_YOUTUBE` build flag (default false) - inline YouTube playback via internal API, disabled for store builds
- `ENABLE_SELF_UPDATE` build flag (default false) - GitHub release self-update, disabled for store builds
- `REQUEST_INSTALL_PACKAGES` permission only included in debug builds (via debug manifest overlay)
- Sideload builders can enable both flags in `app/build.gradle.kts`
