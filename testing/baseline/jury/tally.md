# Jury: gbtv-simple-baseline-20261106, round 1 of 5
Target: every judge 9.0 or more. Verdict: **CONTINUE**

| Judge | Model | Score | Change | Gap |
|---|---|---|---|---|
| claude-opus | Claude Opus 5.5 | 5.6 |  | 3.4 |
| codex | gpt-6.1-sol | 6.1 |  | 2.9 |
| gemini-4 | gemini-4-argon | 5.5 |  | 3.5 |

Lowest 5.5, average 5.7.
Criteria average: sofa_readability 6.2, focus_clarity 7.3, hierarchy 6.7, spacing_alignment 5.2, consistency 6.0, image_text_contrast 6.1, information_density 6.0, polish 4.3.

## Scores by page (weakest page first in the card)

| Judge | tv-home | tv-show-grid | tv-detail | tv-playback | tv-search | tv-downloads | tv-settings | tv-theme-picker | phone-home | phone-show-page | phone-player | phone-downloads |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| claude-opus | 6.5 | 5.5 | 8 | 7 | 6.5 | 7 | 4.5 | 8.5 | 7 | 2.5 | 7 | 7 |
| codex | 6.3 | 6.2 | 7.3 | 6.8 | 6.5 | 7.8 | 4.8 | 7.5 | 6.5 | 1.8 | 7.2 | 7.8 |
| gemini-4 | 6.5 | 6.0 | 8.5 | 7.0 | 7.0 | 6.5 | 4.5 | 8.5 | 6.5 | 1.5 | 7.0 | 7.0 |
| **average** | **6.4** | **5.9** | **7.9** | **6.9** | **6.7** | **7.1** | **4.6** | **8.2** | **6.7** | **1.9** | **7.1** | **7.3** |

Open fixes by page (votes x impact): phone-show-page 9, tv-settings 9, tv-home 9, tv-search 4, tv-playback 4, tv-show-grid 2, phone-home 2.

## Judge summaries
- **claude-opus**: The TV detail, theme picker and phone home/player are solid, but a broken phone show page, heavily truncated TV settings cards and app-wide descender clipping on card metadata keep this well short of shippable.
- **codex**: The main viewing screens are usable, but the broken phone show page, truncated settings and weak secondary-text contrast prevent this baseline from being ready to ship.
- **gemini-4**: While the TV detail screen and theme picker are clean and clear, severe layout bugs on the phone show page, heavily truncated settings cards on TV, and clipped text descenders across video cards hold the app back from shipping quality.

## Changes to make (merged, most agreed first)
Numbers are what `round --reply N=...` refers to.
1. [high, 3 judge(s): claude-opus, codex, gemini-4] **phone-show-page / Whole show page layout: show title/description text and the video card list**: Apply status-bar and navigation-bar insets (WindowInsets.systemBars) so the title starts below the status bar; put the title and description on a solid or scrimmed header panel (at least 70% opaque dark) instead of raw text over the backdrop; make the card list match_parent width with 16 dp side margins so cards are not cut off at about 60% of the screen width; keep the bottom nav or add a back arrow in a top bar. (Right now the title collides with the clock and status icons, the cards are cropped mid-title and mid-date, and there is no visible way back; the page is close to unusable.)
2. [high, 3 judge(s): claude-opus, codex, gemini-4] **tv-settings / Settings row cards (Stream..., Visual T..., Downloa..., Customi..., Twitch C..., Privacy ...) and their subtitle lines**: Widen the settings cards to about 260-280 dp or allow the title to wrap to 2 lines and the subtitle to 2 lines at 14 sp, so full labels like Stream Quality, Visual Theme, Downloads, Customise Shows, Twitch Chat and Privacy Policy show without ellipsis; use one icon colour (all white or all red) and a distinct icon for Twitch rather than a second gear. (Almost every setting is unreadable as a word, so the viewer has to open each card to learn what it does; the duplicate gear and mixed red/white icons add confusion.)
3. [high, 2 judge(s): claude-opus, gemini-4] **tv-home / Video card subtitle line (date and show name, e.g. 'May 8, 2026 - Game Mess Mornings') on home, show grid and search cards**: Stop clipping descenders: give the subtitle TextView includeFontPadding or about 4 dp more bottom padding/line height so g, y, p and commas render fully; and keep the date/show line visible on the focused card (currently a 2-line title pushes it out) by reserving a fixed 2-line title plus 1-line subtitle area. (Dates read as 'Mav 8' and 'Sep 17.' and show names lose their tails on every card, and the focused card, the one the viewer is deciding on, hides its date and show.)
4. [medium, 2 judge(s): claude-opus, codex] **tv-search / Focused search result card backing shadow and the search text field**: Remove the square-cornered dark rectangle drawn behind the rounded focused card (clip the shadow to the card's corner radius), keep the focused card's scaled edge at least 96 px from the left screen edge, and raise the query text in the search field to white or about 90% white on the field background. (The stray square box reads as a rendering glitch, the focused card pokes past the TV safe margin, and the grey query text is hard to read from the sofa.)
5. [high, 1 judge(s): codex] **tv-home / Recent Videos and Browse Shows headers, plus video-card text panels**: Raise row-header contrast to approximately #B8B8B8 or brighter and use opaque dark card footers so backdrop lettering cannot show through titles. (Dim section labels are difficult to read from a sofa, and background artwork competes with the focused card's title.)
6. [medium, 1 judge(s): claude-opus] **tv-show-grid / Show title header and the card grid below it**: Use the same bold title style as tv-detail (Roboto Medium/Bold about 48 sp) instead of the thin light weight, allow it to wrap to 2 lines rather than ellipsizing, add a dark scrim behind the header, and left-align the grid to the title's 96-128 px start margin instead of floating the cards in the centre. (The show name is cut off and the cards float off the page grid, so the screen feels unanchored and inconsistent with the detail screen.)
7. [medium, 1 judge(s): codex] **tv-playback / Transport-control focus state and bottom seek controls**: Give the remote-focused control a distinct outer ring or coloured background independent of its icon; inset the seek track and settings button at least 48 px from screen edges. (The white Play icon does not clearly establish remote focus, and the seek thumb touches the left edge with little protection against TV overscan.)
8. [medium, 1 judge(s): gemini-4] **phone-home / PREMIUM badge and status bar icons**: Give the 'PREMIUM' label on Continue Watching cards a solid dark or gold pill background (matching tv-show-grid), and set the Android status bar icon appearance to light (white icons). (Plain white 'PREMIUM' text vanishes against light thumbnails like Grubbsnax, and black status bar icons are nearly invisible against the dark background.)
9. [medium, 1 judge(s): gemini-4] **tv-playback / Bottom seekbar and transport controls overlay (also affects phone-player)**: Add 48dp horizontal safe-margin to the seekbar container so the red scrubber thumb is not flush against the left screen edge, and add a dark gradient scrim behind the bottom controls and center buttons. (The scrubber thumb touches the screen edge (risking overscan clipping on TVs), and thin white button outlines get lost over bright or busy video frames.)

## Already good (keep)
- Downloads empty states explain how to save a video.
- Empty Downloads states on TV and phone explain what to do in plain words
- Focused video cards generally stand out through an outline and enlargement.
- Focused video cards on tv-home and tv-search clearly scale up and add a bright white border, making remote position easy to spot.
- Phone player correctly places readable details and Up Next below the video.
- TV card focus treatment: white outline plus scale-up makes the focused card obvious within a second on home, search and settings
- TV detail has a clear title and a prominent, visibly focused Watch Now action.
- The theme picker uses large rows, readable descriptions and an explicit selection checkmark.
- phone-player: details under the video with a bold title, show and date line, and a clean Up Next list with thumbnails and dates
- tv-detail has strong hierarchy, readable typography, good backdrop scrimming, and clear focus states on the action buttons.
- tv-detail: clear hierarchy with the red QUICK LOOKS eyebrow, large bold title, duration and date, then a strongly focused red Watch Now button ahead of Watchlist and Download
- tv-theme-picker modal is well-centered, legible from 10 feet, and uses a high-contrast red border and checkmark to indicate selection and focus.

**Next:** Fix the changes above, then open the next round with the same pages and views. Say what you did with `round --reply N=...` so judges do not flip-flop.
