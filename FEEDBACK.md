# User Feedback

Captured feedback from users (Reddit, in-app, beta testers, etc.) grouped by area. Each item lists the source, what the user said, any technical notes for implementation, and rough priority.

## Browse / Navigation

### Show-by-show menu, including legacy shows
- **Source:** Reddit /r/giantbomb, users `u/ShevanelWozzeck` and `u/nicolauz`, 2026-05-08
- **What they said:**
  - "I wish there were more options on the Android app, like selecting shows from a drop down menu including legacy shows… It is pretty bare bones right now."
  - Reference to the old "video bomb" app — they want a menu structured like the website: **current running shows** + **legacy shows** as separate groups, with sub-categories (Quick Looks, Bombing in the AM, Unprofessional Fridays, etc.).
- **Surface:** Mobile (per `clinteastman`'s clarifying question and `nicolauz`'s reply implying yes).
- **Implementation notes:**
  - `Show.active` already distinguishes current from legacy; `getShows()` returns all.
  - `BrowseFragment` / `MobileBrowseFragment` currently splits "active" into individual rows but doesn't expose legacy shows at all (only `activeShows = shows.filter { it.active }`).
  - Possible designs: a dedicated "Shows" tab/screen with sectioned list (Active / Legacy / Search), or a top-level dropdown filter.
- **Priority:** Medium — quality-of-life navigation; a long-running ask given users remember the old app.

## Watchlist

### Watchlist sits too far down the browse page
- **Source:** Reddit /r/giantbomb, anonymous user, 2026-05-08
- **What they said:** "It's wayyyyy down the page, so it's a bit of a scroll" — they use the watchlist as a primary entry point and want it higher in the browse stack.
- **Surface:** Mobile.
- **Implementation notes:**
  - Mobile (`MobileBrowseFragment.loadContent`) builds rows in this order: Upcoming/Live → Continue Watching → Recent (vertical) → Recent (horizontal) → Premium → All Shows grid → per-show rows → **Watchlist** → Settings. Watchlist is near the bottom.
  - TV (`BrowseFragment`) likely has a similar order.
  - Cheap fix: hoist Watchlist nearer the top (e.g. just after Continue Watching) for users who actively use it. Possibly only when non-empty.
  - Bigger fix: a "Pin row to top" preference, or a settings toggle for row order.
- **Related dev note (from clinteastman):** A favourite/pin system existed previously to bubble things up, but the toggle was unreliable and got removed; revisiting on a future weekend. Worth coordinating any solution here with that work.
- **Priority:** High — primary-flow friction for active users.

### Watchlist sort order: oldest-first option
- **Source:** Reddit /r/giantbomb, same anonymous user, 2026-05-08
- **What they said:** "Ideally I'd love for it to order by oldest. Since I watch blightclubs in bulk, it being by latest is a bit annoying."
- **Surface:** Mobile (and presumably TV).
- **Implementation notes:**
  - `GiantBombApi.getWatchlist()` calls `/api/public/watchlist?…limit=100&images=true` and returns videos in the order the API gave them (newest first).
  - Two options:
    - Client-side toggle: add a sort affordance on the Watchlist row/screen with persistence in `PrefsManager`.
    - Default the watchlist order to oldest-first if that matches the predominant use case (bulk-watching show series).
  - Toggle is the safer default — keeps newest-first for users who treat the watchlist as a queue.
- **Priority:** Medium — small UX improvement for a defined user segment.

## Playback / Player UI

### Player control focus highlight too faint on TV
- **Source:** Anonymous user, sideloaded onto Android TV, 2026-05-08
- **What they said:** "It's hard to tell what's selected on the video playback controls since the background highlight that shows where you are is pretty faint. I wonder if that's just a settings thing on my TV."
- **Surface:** TV (Android TV).
- **Implementation notes:**
  - Playback uses media3 `PlayerView` with default controller (`useController = true`). The default ExoPlayer controller's focus drawable is a low-alpha overlay that can wash out on TVs with weak contrast or HDR-mapped UI.
  - Options: provide a custom controller layout (`R.layout.exo_player_control_view` override) with stronger focus state — brighter ring, scale-on-focus, or a colored fill — matching the rest of the app's TV focus treatment in `CardPresenter` / `ShowCardPresenter`.
  - Worth checking on a few TV makes/models before committing to a contrast level — the user's observation about TV settings is a real factor.
- **Priority:** Medium — affects every TV playback session.

## Casting

### Cast stops when the phone screen locks
- **Source:** Anonymous user, Pixel 6 Pro, 2026-05-08
- **What they said:** "When casting from my phone, the cast stops if I lock my phone. Not the worst thing in the world, but does place more of a load on the battery than it probably would otherwise."
- **Surface:** Mobile (Chromecast sender, Pixel 6 Pro).
- **Implementation notes:**
  - `PlaybackActivity.onStop()` calls `releaseCastPlayer()` on every stop, including the lock-screen path. The intent (per the existing comment) is that "Cast session lives on the receiver regardless of this CastPlayer instance, so releasing is safe" — but the user's report contradicts that.
  - Likely culprit: when the activity stops we also tear down our `MediaController` to the local `PlaybackService`. If the local service was the active source of truth and we hadn't fully handed off to the receiver, locking the screen drops the bridge.
  - The receiver itself usually does keep playing once it's authoritatively in charge of an HLS URL. Worth verifying with `dumpsys` of the cast framework whether the session is "remote-controlled by app" vs "free-running on receiver" at lock time.
  - Workaround for now: keep the screen on while casting (FLAG_KEEP_SCREEN_ON is already set, but a locked screen cancels that).
- **Priority:** Medium — battery + UX; common casting use case.

### Casting livestreams shows a blank screen (VOD casts fine)
- **Source:** Anonymous user, phone + tablet, 2026-05-08
- **What they said:** "Watching stuff on my phone and tablet (and pushing it to my Chromecast too!) works without issue — except for livestreams. I can watch livestreams on the phone or tablet no problem, but if I try to cast a livestream it just gives me a blank screen and refuses to work."
- **Surface:** Chromecast receiver (phone/tablet sender).
- **Implementation notes:**
  - VOD path: GB-hosted HLS URLs work end-to-end on the cast receiver.
  - Live path: HLS URL is built by `TwitchExtractor` from `usher.ttvnw.net` with a per-client signed token. The token is granted to the *requesting* client (anonymous web headers). When the Chromecast receiver fetches the manifest from its own origin/UA, Twitch's CDN may reject it (403/410) — explaining the blank receiver.
  - Verification: pull the receiver's debug log (chrome://inspect on the same network) to confirm the failed request.
  - Possible fixes:
    - Embed the manifest content into the cast load request as inline data (some receivers support this).
    - Use a custom receiver app that re-extracts the token on the receiver side using the same anonymous-web flow.
    - Switch to a different live HLS source if the GB stream is mirrored elsewhere.
    - Proxy the manifest through a small worker that re-signs/relays for the cast receiver — heavyweight.
- **Priority:** Medium — affects the use case the dev cares about (cast-to-TV) for a defined content type. Bug, not polish.

### Positive: "This rips."
- **Source:** Anonymous user (Pixel 6 Pro caster), 2026-05-08
- **What they said:** "One: This rips."
- **Notes:** Filed for morale.

## Onboarding

### API key entry on TV is tedious
- **Source:** Anonymous user (Android TV sideloader), 2026-05-08
- **What they said:** "Entering the API key was tedious, but what can you do." (Noted as resigned acceptance, not an active complaint.)
- **Surface:** TV.
- **Implementation notes:**
  - Current flow: 40-char alphanumeric key entered via TV remote D-pad on a software keyboard. Brutal.
  - The `giantbombtv://setup?key=…` deep link already exists (`SetupActivity.kt`) — a `PrefsManager.apiKey` write happens with no validation when followed.
  - Better UX options:
    - Show a QR code on the TV linking to `giantbombtv://setup?key=…` + instructions to scan with the phone app, which would auto-fill the key on the same network.
    - Pairing-code flow: TV displays a 6-digit code and a giantbomb.com URL; user enters the code on the web; backend pushes the key down. Heavier infra.
    - "Type the key on your phone, send to TV" via local mDNS/Bonjour pairing.
  - Lightest-weight win: print the user's phone-app `?key=` deep link as a QR on the TV setup screen — only useful if they've already paired the phone, but at least one device is already done.
- **Priority:** Medium — first-run friction, unlikely to recur per user but a key first impression on TV.

## Discovery

### Random video button
- **Source:** Reddit /r/giantbomb, `u/nicolauz`, 2026-05-08
- **What they said:** The old "video bomb" Android app had a "random video" button; they want it back.
- **Implementation notes:**
  - Could be a toolbar action or a dedicated card on the browse screen.
  - GB API: `/api/public/videos?api_key=…&limit=1&sort=random` may not be supported; might need to fetch a page and pick random client-side, or use a known total-count to pick a random offset.
  - Should respect the user's premium status (don't roll a premium video for free users — or handle the paywall gracefully).
- **Priority:** Low–medium — nice fun feature, low scope.
