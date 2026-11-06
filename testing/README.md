# Testing harness

A repeatable pass over performance, layout and design before a store release.
Four parts, run in this order:

1. **Screenshots**: the same fixed list of screens on every device and theme.
2. **Layout checks**: function only (readable, focus visible, nothing clipped
   or overlapping, overscan-safe on TV, large font on phone, no crashes).
3. **Performance**: Macrobenchmark start-up, scroll frame timing and memory.
4. **Jury**: blind design scores from several AI judges, **Simple theme only**.

Frosted, Extreme glass and Neon grid are loud on purpose. They get the
function checks in part 2 and are never scored for taste.

Generated output goes to `harness-results/` (ignored by git). The baseline
shots referenced from the release PR are in `testing/baseline/`.

This lives in `testing/` rather than `docs/` because `docs/` is the public
GitHub Pages site.

The scripts are Python, not PowerShell or bash: they parse hierarchy XML and
check pixels (Pillow), and the same files run from Git Bash, PowerShell or a
Mac/Linux shell.

## Baseline (6 Nov 2026, master `0abe53b`)

`testing/baseline/` holds the first pass, for later runs to compare against:

- `layout-report.md`: layout pass/fail for every device and theme (shot paths
  point into the run folder, which is not in git)
- `jury/`: the jury round card, the full tally and each judge's verdict JSON,
  exactly as returned (Simple theme only)
- a few screenshots: TV home (Simple and Neon), TV settings and phone show
  page with layout problems boxed, phone home, tablet show page

- `perf-results.md`: start-up, scroll frame timing and memory on the emulators

## What you need

- Android SDK with `adb` and the emulator on the PATH, Python 3.10+ with
  Pillow (`pip install pillow`), and the JDK the Gradle build uses.
- Devices. Any mix of:
  - Android TV emulator, 1080p (`tv_1080p` profile, Google TV API 34 image)
  - Android TV emulator, 4K (`tv_4k` profile)
  - Phone emulator (`medium_phone`, API 36)
  - Tablet emulator (`pixel_tablet`, API 36)
  - The Fire TV at `192.168.50.188:5555` (`harness.py devices --firetv 192.168.50.188:5555`)

  Keep emulator ports in adb's scan range (5554 to 5584, even numbers) or adb
  will not see them. If another session already has an AVD open, start your
  own AVD rather than sharing it.

  Creating the harness AVDs once (names are only a convention):

  ```bash
  avdmanager create avd -n GBTV_Harness_TV1080 -k "system-images;android-34;google-tv;x86" -d tv_1080p
  avdmanager create avd -n GBTV_Harness_TV4K   -k "system-images;android-34;google-tv;x86" -d tv_4k
  avdmanager create avd -n GBTV_Harness_Phone  -k "system-images;android-36.1;google_apis_playstore;x86_64" -d medium_phone
  avdmanager create avd -n GBTV_Harness_Tablet -k "system-images;android-36.1;google_apis_playstore;x86_64" -d pixel_tablet
  emulator -avd GBTV_Harness_TV1080 -port 5580 -no-audio -no-boot-anim
  ```

  Give each AVD `disk.dataPartition.size=8G` and `hw.ramSize=2048M` in its
  `config.ini`. Use `hw.gpu.mode=auto`; `host` hung the API 36 images here.

  Known snags on this PC (Nov 2026):
  - A fresh cold boot of the API 36.1 phone image crash-loops surfaceflinger
    (`RegionSampling` abort), with any GPU mode. The baseline used a copy of
    the existing `Medium_Phone_API_36.1` AVD, which boots from its saved
    snapshot (copy the `.avd` folder and `.ini`, then fix the paths inside).
  - Tablet: the baseline ran the phone AVD with `adb shell wm size 2560x1600`
    and `wm density 320` (800 dp wide, like a 10 inch tablet), then
    `wm size reset` / `wm density reset`. Pass `--device-label tablet`.
  - 4K TV: Google TV caps the app UI at 1920 wide (`config_maxUiWidth`), so
    the `tv_4k` AVD renders the same 1080p UI as real 4K Fire TVs and
    Chromecasts. Capture it with `--device-label tv4k` to prove that.
  - Android 13+ asks for notification permission on first playback; `setup`
    and `capture` grant it so the prompt never covers a screen.

- **An API key already saved on a device.** The harness never asks for one
  and never stores one. `setup --key-from <serial>` copies the key that is
  already saved on one device (debug build) to another, through an in-memory
  pipe. The setup screen shows the key, so the harness paints over every
  text field in screenshots and blanks it in hierarchy dumps before anything
  is written. Never commit a key.

## Commands

All from the repo root.

```bash
python scripts/harness/harness.py devices
```

Lists devices with form factor (tv, tv4k, phone, tablet), size, density, and
whether a debug build with a key is installed (shown as a short fingerprint,
never the key).

```bash
python scripts/harness/harness.py setup --serial emulator-5580 --key-from emulator-5554
```

Builds and installs the debug app plus the harness test app
(`benchmark` module), turns off system animations and writes a clean prefs
file: the saved key and premium flag, Simple theme, default rows (no pins,
no custom order). Leave out `--key-from` if the device already has a key.

The first time the harness changes a device's prefs it keeps a copy inside
the app's private folder on that device (never on the PC, as it holds the
key). On a real device, put the owner's pins, row order and settings back
when you are done:

```bash
python scripts/harness/harness.py restore --serial 192.168.50.188:5555
```

```bash
python scripts/harness/harness.py capture --serial emulator-5580 --themes all --label baseline
python scripts/harness/harness.py capture --serial emulator-5570 --run harness-results/<run> --themes all
python scripts/harness/harness.py capture --serial emulator-5570 --run harness-results/<run> --font-scale 1.3
```

Captures the fixed screen list for each theme into
`harness-results/<date>-<sha>-<label>/<device>/<theme>/<screen>.png` (plus a
`.xml` hierarchy dump) and writes `manifest.json`. Add more devices to the
same run with `--run`. `--font-scale 1.3` makes a separate `phone-font1.3`
set and resets the font scale after.

```bash
python scripts/harness/harness.py layout --run harness-results/<run>
```

Runs the layout checks on every capture. Writes `layout/report.md` (pass or
FAIL per screen and theme, with the reason and the screenshot path),
`layout/report.json` and a copy of each failing screenshot with the problems
boxed in red.

```bash
python scripts/harness/harness.py perf --serial emulator-5580 --themes simple,frosted,extreme,neon --out harness-results/<run>
```

Runs the Macrobenchmarks and writes `perf/results.md` and `results.json`.
See "Performance" below.

```bash
python scripts/harness/harness.py dump --serial emulator-5580 --name here
```

Screenshot and hierarchy of whatever is on screen now, for debugging.

## The fixed screen list

The tour lives in `benchmark/src/main/java/com/giantbomb/tv/benchmark/harness/ScreenTour.kt`.
Every screen starts from a cold app start and walks there with the D-pad
(TV) or taps (phone), so focus and scroll are the same each run.

| Screen | How it gets there | Focus |
|---|---|---|
| `tv_home` | side menu to Recent Videos, into the row | first Recent Videos card |
| `tv_show_grid` | Browse Shows row, first show | first episode |
| `tv_detail` | first Recent Videos card, OK | Watch Now / Resume |
| `tv_playback` | detail, Watch, 7 s of play, pause, controls shown | player |
| `tv_search` | search orb, types "quick look", submit | first result |
| `tv_downloads` | Settings row, Downloads | page |
| `tv_settings` | Settings row | Refresh card |
| `tv_theme_picker` | Settings row, Visual Theme, OK | current theme |
| `tv_setup` | Settings row, API Key, OK, keyboard closed | key field (hidden) |
| `phone_home` | cold start, top of the list | none |
| `phone_show_grid` | Shows tab, first show (the show page) | none |
| `phone_playback` | first Recent video card, 7 s of play, pause, tap for controls | none |
| `phone_downloads` | Downloads tab | none |

The phone app has no separate detail screen: tapping a video opens the
player, with the details under the video. So the phone list has
`phone_playback` where the TV list has `tv_detail`.

The tablet uses the phone list. Content comes from the live API, so new
videos change what is on screen between days; layout, focus and scroll stay
fixed. Rows the user can hide or reorder are reset to defaults by `setup` and
before each theme.

**Side effect:** `tv_playback` and `phone_playback` play the first Recent
video for about 7 seconds, which nudges that video's saved progress on the account whose key
is used. Skip it with `--screens` if that matters.

Why the tour is UiAutomator, not `adb shell uiautomator dump`: the countdown
cards tick every second, so the UI is never idle and the stock dump tool
gives up. The harness test app sets a short idle timeout instead.

## Layout checks (function only)

`scripts/harness/layout_check.py` reads each hierarchy dump and screenshot:

| Check | Rule | Applies to |
|---|---|---|
| crash | the screen was reached, nothing in the crash log | all |
| focus | one element has focus and it is fully on screen | TV |
| overscan | no text or focusable item inside the 5% edge band (48 x 27 dp); rows that scroll off the edge are skipped | TV |
| overlap | no two text boxes overlap | all |
| truncated | text cut off by its container, or too long for its box (likely ellipsized) | all |
| small_text | no text box shorter than 12 sp at font scale 1.0 (24 px on a 1080p TV) | all |
| contrast | at least 3:1 between the text and what is behind it | all, not over live video |

`truncated` and `contrast` are heuristics from pixels and box sizes, so read
the annotated shot before acting on one. A FAIL means "look at this", not
proof of a bug.

## Performance

`scripts/harness/perf.py` uses the tests in
`benchmark/src/main/java/com/giantbomb/tv/benchmark/BaselineProfileGenerator.kt`:

- `coldStartup`, `warmStartup`: time to initial display, 5 runs each
- memory after browsing: the harness `ScreenTour#browse` step walks the
  home rows like the scroll test, then saves `dumpsys meminfo` (median of 3).
  A Macrobenchmark memory trace (`MemoryUsageMetric`) was tried first: the
  on-device trace processor needs more RAM than a TV emulator has and it
  thrashed a 1 GB AVD for 10 minutes
- `homeScroll`: frame timing while scrolling home, 5 runs, once per theme
  (TV walks the rows with the D-pad, phone flings the list)

It installs the `benchmarkRelease` build (minified, profileable) over the
debug build so the key stays, runs each test with `am instrument` (Gradle's
connected task uninstalls the app at the end, which would lose the key), then
puts the debug build back.

The countdown cards tick every second, so the UI is never idle. The
benchmark class sets a 500 ms idle wait; with the default 10 s every D-pad
step in the scroll test would wait the full 10 s.

Results table: cold and warm start medians, jank % (frames that missed their
deadline, from `frameOverrunMs`), CPU frame time P50/P90/P99, overrun
P90/P99 and memory. Emulator numbers only compare runs on the same machine;
use a real device (the Fire TV) for numbers to quote.

The Gradle route still works for a quick run on a physical device:

```bash
./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest
```

## Jury (Simple theme only)

The jury tool lives in the gscontent repo. Follow its skill
(`C:\Users\cmoss\github\gscontent\.claude\skills\jury\SKILL.md`) exactly:
blind judges, frozen pages, the caller is the clerk, not a judge. Run it from
the gscontent folder; do not edit anything there.

Rubric: `tools/jury/tv-ui.json` (readability from a sofa, focus clarity,
hierarchy, spacing and alignment, consistency, image and text contrast,
information density, polish).

The baseline run is `gbtv-simple-baseline-20261106` (12 pages, default
panel `claude-opus,codex,gemini-4`). Its exact setup, so later runs match it:

```bash
cd C:\Users\cmoss\github\gscontent
python tools/jury.py init --name gbtv-simple-<date> --kind url \
  --target "Giant Bomb TV app, Simple theme: Android TV 1080p screens and the phone app" \
  --context "Android TV / Fire TV client for giantbomb.com videos, used from a sofa with a remote; the same app on Android phones. Screenshots come from emulators: TV at 1920x1080, phone at 1080x2400. On TV, focus starts on a fixed card; the focused item is the one the remote is on." \
  --rubric-file <repo>\tools\jury\tv-ui.json \
  --page tv-home --page tv-show-grid --page tv-detail --page tv-playback --page tv-search \
  --page tv-downloads --page tv-settings --page tv-theme-picker \
  --page phone-home --page phone-show-page --page phone-player --page phone-downloads \
  --constraint "Simple theme only: flat, solid cards by design. Do not ask for glass, blur or neon effects." \
  --constraint "Video thumbnails, show artwork, backdrops and the frame inside the player are website content, not app design." \
  --constraint "The phone app has no separate detail screen: tapping a video opens the player, which shows the details below the video."
python tools/jury.py round --run gbtv-simple-<date>
# one per page: TV shots as view desktop 1920x1080, phone shots as view phone 1080x2400
python tools/jury.py evidence --run gbtv-simple-<date> --page tv-home --view desktop --viewport 1920x1080 --file <run>\tv\simple\tv_home.png
python tools/jury.py evidence --run gbtv-simple-<date> --page phone-show-page --view phone --viewport 1080x2400 --file <run>\phone\simple\phone_show_grid.png
python tools/jury.py brief --run gbtv-simple-<date> --full
```

Page to file: `tv-<x>` is `tv/simple/tv_<x>.png` (dashes to underscores),
`phone-home` and `phone-downloads` are `phone_home` and `phone_downloads`,
`phone-show-page` is `phone_show_grid`, `phone-player` is `phone_playback`.
The baseline also added a `--note` on playback, search, settings, theme
picker and the phone pages saying how each screen was reached.

Then start the agent judges and `jury.py judge` together, wait, and
`jury.py tally`. Post the round card as printed.

Privacy: the setup screen is left out of jury evidence (it holds the key
field, even though the harness hides it). Playback shows a frame of the
video. Check every shot for names, emails or keys before it goes to the
outside judges.

Use the same pages, same order and same devices every round. To compare a
later round with the baseline, use `round` on the same run with `--reply`
for each fix.
