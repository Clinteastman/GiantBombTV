# Amazon Appstore Submission - Remaining Items

Items that require manual/external action before submitting to the Amazon Appstore.

## Required

### Trademark / Branding Authorization
- The app uses Giant Bomb's name, logo, and branding throughout
- Amazon will likely require written permission from Giant Bomb to publish
- Options: get explicit permission from GB staff, or rebrand as a generic "video client" with GB as one source
- Without this, the app will almost certainly be rejected

### Privacy Policy
- Amazon requires a privacy policy URL for all apps
- The app collects/transmits the user's API key and viewing progress data
- Need to create a privacy policy page (can be a GitHub Pages site or similar) and add the URL to the app listing
- Also add the URL in the app's settings screen

### Content Rating Questionnaire
- Amazon requires completing their content rating questionnaire
- The app streams user-generated/editorial video content
- Will need to accurately describe content types (language, etc.)

## Recommended

### Restrict Cleartext Traffic
- The app currently allows cleartext (HTTP) traffic via `android:usesCleartextTraffic="true"` in the manifest
- Should add a `network_security_config.xml` to only allow cleartext for specific domains if needed, or remove the flag entirely if all API endpoints use HTTPS

### App Store Listing Assets
- Store assets exist in `store-assets/` but review them before submission:
  - `app_icon_512x512.png` - app icon
  - `small_icon_114x114.png` - small icon
  - `firetv_banner_1280x720.png` - Fire TV banner
- Need 3-10 screenshots (1920x1080 or 1080x1920)
- Short description (max 80 chars) and long description (max 4000 chars)

### Testing on Physical Device
- Test the release (minified) APK on a real Fire TV before submission
- Verify all features work with R8 enabled (minification can break reflection-based code)
- Confirm MediaSession works with Fire TV remote and Alexa voice commands
