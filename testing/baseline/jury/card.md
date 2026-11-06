**Jury: gbtv-simple-baseline-20261106** · round 1 of 5 · target 9 · **KEEP GOING**

| Judge | Score | | Rounds so far | To go |
|---|---|---|---|---|
| claude-opus | **5.6** | `█████▌░░░░` | 5.6 | 3.4 |
| codex | **6.1** | `██████░░░░` | 6.1 | 2.9 |
| gemini-4 | **5.5** | `█████▌░░░░` | 5.5 | 3.5 |

**Pages** (average, weakest first): phone-show-page **1.9** · tv-settings **4.6** · tv-show-grid **5.9** · tv-home **6.4** · tv-search **6.7** · phone-home **6.7** · tv-playback **6.9** · tv-downloads **7.1** · phone-player **7.1** · phone-downloads **7.3** · tv-detail **7.9** · tv-theme-picker **8.2**

**Top fixes**
1. **Whole show page layout** (3/3 judges, high): Apply status-bar and navigation-bar insets (WindowInsets.systemBars) so the t...
2. **Settings row cards** (3/3 judges, high): Widen the settings cards to about 260-280 dp or allow the title to wrap to 2...
3. **Video card subtitle line** (2/3 judges, high): Stop clipping descenders: give the subtitle TextView includeFontPadding or ab...
4. **Focused search result card backing shadow and t...** (2/3 judges, medium): Remove the square-cornered dark rectangle drawn behind the rounded focused ca...
5. **Recent Videos and Browse Shows headers, plus vi...** (1/3 judges, high): Raise row-header contrast to approximately #B8B8B8 or brighter and use opaque...

Full detail: `_tmp/jury/gbtv-simple-baseline-20261106/round-1/tally.md`
