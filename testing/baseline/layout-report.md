# Layout checks: 20261106-1738-0abe53b-baseline

Function only (no taste). pass / **FAIL** / n/a. Details and annotated shots below.

## Summary

| Device | Theme | Screens | crash | focus | overscan | overlap | truncated | small_text | contrast |
|---|---|---|---|---|---|---|---|---|---|
| tv | simple | 9 | pass | pass | **FAIL 4** | pass | **FAIL 2** | pass | **FAIL 4** |
| tv | frosted | 9 | pass | pass | **FAIL 4** | pass | **FAIL 2** | pass | **FAIL 4** |
| tv | extreme | 9 | pass | pass | **FAIL 4** | pass | **FAIL 2** | pass | **FAIL 4** |
| tv | neon | 9 | pass | pass | **FAIL 4** | pass | **FAIL 3** | pass | **FAIL 1** |
| phone | simple | 4 | pass | n/a | n/a | pass | **FAIL 1** | **FAIL 1** | **FAIL 1** |
| tv4k | simple | 9 | pass | pass | **FAIL 4** | pass | **FAIL 2** | pass | **FAIL 4** |
| phone | frosted | 4 | pass | n/a | n/a | pass | **FAIL 1** | **FAIL 1** | **FAIL 2** |
| phone | extreme | 4 | pass | n/a | n/a | pass | **FAIL 1** | **FAIL 1** | **FAIL 2** |
| phone | neon | 4 | pass | n/a | n/a | pass | **FAIL 1** | **FAIL 1** | **FAIL 1** |
| phone-font1.3 | simple | 4 | pass | n/a | n/a | pass | **FAIL 1** | pass | **FAIL 1** |
| phone-font1.3 | frosted | 4 | pass | n/a | n/a | pass | **FAIL 1** | pass | **FAIL 1** |
| phone-font1.3 | extreme | 4 | pass | n/a | n/a | pass | **FAIL 1** | pass | **FAIL 1** |
| phone-font1.3 | neon | 4 | pass | n/a | n/a | pass | **FAIL 1** | pass | **FAIL 1** |
| tablet | simple | 4 | pass | n/a | n/a | pass | **FAIL 1** | **FAIL 1** | **FAIL 1** |
| tablet | frosted | 4 | pass | n/a | n/a | pass | **FAIL 1** | **FAIL 1** | **FAIL 1** |
| tablet | extreme | 4 | pass | n/a | n/a | pass | **FAIL 1** | **FAIL 1** | **FAIL 1** |
| tablet | neon | 4 | pass | n/a | n/a | pass | **FAIL 1** | **FAIL 1** | **FAIL 1** |

## Per screen

### tv / simple

| Screen | crash | focus | overscan | overlap | truncated | small_text | contrast | Shot |
|---|---|---|---|---|---|---|---|---|
| tv_home | pass | pass | pass | pass | **FAIL** | pass | **FAIL** | `layout/tv/simple/tv_home_issues.png` |
| tv_show_grid | pass | pass | pass | pass | pass | pass | pass | `tv/simple/tv_show_grid.png` |
| tv_detail | pass | pass | pass | pass | pass | pass | **FAIL** | `layout/tv/simple/tv_detail_issues.png` |
| tv_playback | pass | n/a | **FAIL** | pass | pass | pass | n/a | `layout/tv/simple/tv_playback_issues.png` |
| tv_search | pass | pass | **FAIL** | pass | pass | pass | pass | `layout/tv/simple/tv_search_issues.png` |
| tv_downloads | pass | pass | **FAIL** | pass | pass | pass | pass | `layout/tv/simple/tv_downloads_issues.png` |
| tv_settings | pass | pass | **FAIL** | pass | **FAIL** | pass | **FAIL** | `layout/tv/simple/tv_settings_issues.png` |
| tv_theme_picker | pass | pass | pass | pass | pass | pass | **FAIL** | `layout/tv/simple/tv_theme_picker_issues.png` |
| tv_setup | pass | pass | pass | pass | pass | pass | pass | `tv/simple/tv_setup.png` |

- **tv_home**
  - truncated: card_meta 'Nov 6, 2026 • Quick Looks' cut off: only 1px of the text is visible @[137, 848, 759, 849]
  - contrast: row_header 'RECENT VIDEOS' contrast about 2.7:1 @[112, 286, 309, 334]
  - contrast: row_header 'BROWSE SHOWS' contrast about 2.7:1 @[112, 934, 311, 982]
- **tv_detail**
  - contrast: TextView 'QUICK LOOKS' contrast about 2.3:1 @[96, 60, 1520, 108]
- **tv_playback**
  - overscan: exo_position '00:05' inside the overscan band @[20, 1001, 108, 1039]
  - overscan: TextView '·' inside the overscan band @[108, 1001, 132, 1039]
  - overscan: exo_duration '3:22:50' inside the overscan band @[132, 1001, 244, 1039]
  - overscan: TextView 'The Legend of Zelda: Ocarina of Time Rem' inside the overscan band @[80, 851, 1056, 900]
  - overscan: exo_settings 'Settings' inside the overscan band @[1820, 972, 1916, 1068]
- **tv_search**
  - overscan: card_title 'Phantom Blade Zero Quick Look' inside the overscan band @[94, 754, 802, 800]
  - overscan: card_meta 'Oct 29, 2026 • Quick Looks' inside the overscan band @[94, 800, 802, 841]
  - overscan: FrameLayout 'Phantom Blade Zero Quick Look' inside the overscan band @[65, 294, 831, 874]
- **tv_downloads**
  - overscan: TextView 'Downloads' inside the overscan band @[96, 48, 1824, 156]
- **tv_settings**
  - overscan: title_text '8-4 Play' inside the overscan band @[1670, 141, 1866, 179]
  - truncated: title_text 'Dispatch: Grubbtale Games' needs ~365px, box is 196px (likely ellipsized) @[134, 141, 330, 179]
  - truncated: title_text 'Giant Bomb Gaming Minute' needs ~350px, box is 196px (likely ellipsized) @[390, 141, 586, 179]
  - truncated: title_text 'JeffJeff's Bizarre Adventure' needs ~409px, box is 196px (likely ellipsized) @[646, 141, 842, 179]
  - truncated: content_text 'Reload the video feed' needs ~266px, box is 116px (likely ellipsized) @[134, 580, 250, 613]
  - truncated: title_text 'Stream Quality' needs ~204px, box is 116px (likely ellipsized) @[310, 540, 426, 578]
  - truncated: content_text 'Default: Auto (HLS)' needs ~241px, box is 116px (likely ellipsized) @[310, 580, 426, 613]
  - truncated: ... and 8 more
  - contrast: row_header 'SETTINGS' contrast about 2.7:1 @[112, 286, 235, 334]
- **tv_theme_picker**
  - contrast: TextView '✓' contrast about 1.4:1 @[1347, 344, 1404, 399]

### tv / frosted

| Screen | crash | focus | overscan | overlap | truncated | small_text | contrast | Shot |
|---|---|---|---|---|---|---|---|---|
| tv_home | pass | pass | pass | pass | **FAIL** | pass | **FAIL** | `layout/tv/frosted/tv_home_issues.png` |
| tv_show_grid | pass | pass | pass | pass | pass | pass | pass | `tv/frosted/tv_show_grid.png` |
| tv_detail | pass | pass | pass | pass | pass | pass | **FAIL** | `layout/tv/frosted/tv_detail_issues.png` |
| tv_playback | pass | n/a | **FAIL** | pass | pass | pass | n/a | `layout/tv/frosted/tv_playback_issues.png` |
| tv_search | pass | pass | **FAIL** | pass | pass | pass | pass | `layout/tv/frosted/tv_search_issues.png` |
| tv_downloads | pass | pass | **FAIL** | pass | pass | pass | pass | `layout/tv/frosted/tv_downloads_issues.png` |
| tv_settings | pass | pass | **FAIL** | pass | **FAIL** | pass | **FAIL** | `layout/tv/frosted/tv_settings_issues.png` |
| tv_theme_picker | pass | pass | pass | pass | pass | pass | **FAIL** | `layout/tv/frosted/tv_theme_picker_issues.png` |
| tv_setup | pass | pass | pass | pass | pass | pass | pass | `tv/frosted/tv_setup.png` |

- **tv_home**
  - truncated: card_meta 'Nov 6, 2026 • Quick Looks' cut off: only 1px of the text is visible @[137, 848, 759, 849]
  - contrast: row_header 'RECENT VIDEOS' contrast about 2.7:1 @[112, 286, 309, 334]
  - contrast: row_header 'BROWSE SHOWS' contrast about 2.7:1 @[112, 934, 311, 982]
- **tv_detail**
  - contrast: TextView 'QUICK LOOKS' contrast about 2.3:1 @[96, 60, 1520, 108]
- **tv_playback**
  - overscan: exo_position '00:05' inside the overscan band @[20, 1001, 108, 1039]
  - overscan: TextView '·' inside the overscan band @[108, 1001, 132, 1039]
  - overscan: exo_duration '3:22:50' inside the overscan band @[132, 1001, 244, 1039]
  - overscan: TextView 'The Legend of Zelda: Ocarina of Time Rem' inside the overscan band @[80, 851, 1056, 900]
  - overscan: exo_settings 'Settings' inside the overscan band @[1820, 972, 1916, 1068]
- **tv_search**
  - overscan: card_title 'Phantom Blade Zero Quick Look' inside the overscan band @[94, 754, 802, 800]
  - overscan: card_meta 'Oct 29, 2026 • Quick Looks' inside the overscan band @[94, 800, 802, 841]
  - overscan: FrameLayout 'Phantom Blade Zero Quick Look' inside the overscan band @[65, 294, 831, 874]
- **tv_downloads**
  - overscan: TextView 'Downloads' inside the overscan band @[96, 48, 1824, 156]
- **tv_settings**
  - overscan: title_text '8-4 Play' inside the overscan band @[1670, 141, 1866, 179]
  - truncated: title_text 'Dispatch: Grubbtale Games' needs ~365px, box is 196px (likely ellipsized) @[134, 141, 330, 179]
  - truncated: title_text 'Giant Bomb Gaming Minute' needs ~350px, box is 196px (likely ellipsized) @[390, 141, 586, 179]
  - truncated: title_text 'JeffJeff's Bizarre Adventure' needs ~409px, box is 196px (likely ellipsized) @[646, 141, 842, 179]
  - truncated: content_text 'Reload the video feed' needs ~266px, box is 116px (likely ellipsized) @[134, 580, 250, 613]
  - truncated: title_text 'Stream Quality' needs ~204px, box is 116px (likely ellipsized) @[310, 540, 426, 578]
  - truncated: content_text 'Default: Auto (HLS)' needs ~241px, box is 116px (likely ellipsized) @[310, 580, 426, 613]
  - truncated: ... and 8 more
  - contrast: row_header 'SETTINGS' contrast about 2.7:1 @[112, 286, 235, 334]
- **tv_theme_picker**
  - contrast: TextView '✓' contrast about 1.4:1 @[1347, 482, 1404, 537]

### tv / extreme

| Screen | crash | focus | overscan | overlap | truncated | small_text | contrast | Shot |
|---|---|---|---|---|---|---|---|---|
| tv_home | pass | pass | pass | pass | **FAIL** | pass | **FAIL** | `layout/tv/extreme/tv_home_issues.png` |
| tv_show_grid | pass | pass | pass | pass | pass | pass | pass | `tv/extreme/tv_show_grid.png` |
| tv_detail | pass | pass | pass | pass | pass | pass | **FAIL** | `layout/tv/extreme/tv_detail_issues.png` |
| tv_playback | pass | n/a | **FAIL** | pass | pass | pass | n/a | `layout/tv/extreme/tv_playback_issues.png` |
| tv_search | pass | pass | **FAIL** | pass | pass | pass | pass | `layout/tv/extreme/tv_search_issues.png` |
| tv_downloads | pass | pass | **FAIL** | pass | pass | pass | pass | `layout/tv/extreme/tv_downloads_issues.png` |
| tv_settings | pass | pass | **FAIL** | pass | **FAIL** | pass | **FAIL** | `layout/tv/extreme/tv_settings_issues.png` |
| tv_theme_picker | pass | pass | pass | pass | pass | pass | **FAIL** | `layout/tv/extreme/tv_theme_picker_issues.png` |
| tv_setup | pass | pass | pass | pass | pass | pass | pass | `tv/extreme/tv_setup.png` |

- **tv_home**
  - truncated: card_meta 'Nov 6, 2026 • Quick Looks' cut off: only 1px of the text is visible @[137, 848, 759, 849]
  - contrast: row_header 'RECENT VIDEOS' contrast about 2.7:1 @[112, 286, 309, 334]
  - contrast: row_header 'BROWSE SHOWS' contrast about 2.7:1 @[112, 934, 311, 982]
- **tv_detail**
  - contrast: TextView 'QUICK LOOKS' contrast about 2.3:1 @[96, 60, 1520, 108]
- **tv_playback**
  - overscan: exo_position '00:05' inside the overscan band @[20, 1001, 108, 1039]
  - overscan: TextView '·' inside the overscan band @[108, 1001, 132, 1039]
  - overscan: exo_duration '3:22:50' inside the overscan band @[132, 1001, 244, 1039]
  - overscan: TextView 'The Legend of Zelda: Ocarina of Time Rem' inside the overscan band @[80, 851, 1056, 900]
  - overscan: exo_settings 'Settings' inside the overscan band @[1820, 972, 1916, 1068]
- **tv_search**
  - overscan: card_title 'Phantom Blade Zero Quick Look' inside the overscan band @[94, 754, 802, 800]
  - overscan: card_meta 'Oct 29, 2026 • Quick Looks' inside the overscan band @[94, 800, 802, 841]
  - overscan: FrameLayout 'Phantom Blade Zero Quick Look' inside the overscan band @[65, 294, 831, 874]
- **tv_downloads**
  - overscan: TextView 'Downloads' inside the overscan band @[96, 48, 1824, 156]
- **tv_settings**
  - overscan: title_text '8-4 Play' inside the overscan band @[1670, 141, 1866, 179]
  - truncated: title_text 'Dispatch: Grubbtale Games' needs ~365px, box is 196px (likely ellipsized) @[134, 141, 330, 179]
  - truncated: title_text 'Giant Bomb Gaming Minute' needs ~350px, box is 196px (likely ellipsized) @[390, 141, 586, 179]
  - truncated: title_text 'JeffJeff's Bizarre Adventure' needs ~409px, box is 196px (likely ellipsized) @[646, 141, 842, 179]
  - truncated: content_text 'Reload the video feed' needs ~274px, box is 122px (likely ellipsized) @[131, 587, 253, 621]
  - truncated: title_text 'Stream Quality' needs ~204px, box is 116px (likely ellipsized) @[310, 540, 426, 578]
  - truncated: content_text 'Default: Auto (HLS)' needs ~241px, box is 116px (likely ellipsized) @[310, 580, 426, 613]
  - truncated: ... and 8 more
  - contrast: row_header 'SETTINGS' contrast about 2.7:1 @[112, 286, 235, 334]
- **tv_theme_picker**
  - contrast: TextView '✓' contrast about 1.4:1 @[1347, 620, 1404, 675]

### tv / neon

| Screen | crash | focus | overscan | overlap | truncated | small_text | contrast | Shot |
|---|---|---|---|---|---|---|---|---|
| tv_home | pass | pass | pass | pass | **FAIL** | pass | pass | `layout/tv/neon/tv_home_issues.png` |
| tv_show_grid | pass | pass | pass | pass | pass | pass | pass | `tv/neon/tv_show_grid.png` |
| tv_detail | pass | pass | pass | pass | pass | pass | **FAIL** | `layout/tv/neon/tv_detail_issues.png` |
| tv_playback | pass | n/a | **FAIL** | pass | pass | pass | n/a | `layout/tv/neon/tv_playback_issues.png` |
| tv_search | pass | pass | **FAIL** | pass | **FAIL** | pass | pass | `layout/tv/neon/tv_search_issues.png` |
| tv_downloads | pass | pass | **FAIL** | pass | pass | pass | pass | `layout/tv/neon/tv_downloads_issues.png` |
| tv_settings | pass | pass | **FAIL** | pass | **FAIL** | pass | pass | `layout/tv/neon/tv_settings_issues.png` |
| tv_theme_picker | pass | pass | pass | pass | pass | pass | pass | `tv/neon/tv_theme_picker.png` |
| tv_setup | pass | pass | pass | pass | pass | pass | pass | `tv/neon/tv_setup.png` |

- **tv_home**
  - truncated: card_meta 'Nov 6, 2026 • Quick Looks' cut off: only 1px of the text is visible @[137, 848, 759, 849]
- **tv_detail**
  - contrast: TextView 'QUICK LOOKS' contrast about 2.3:1 @[96, 60, 1520, 108]
- **tv_playback**
  - overscan: exo_position '00:04' inside the overscan band @[20, 1001, 108, 1039]
  - overscan: TextView '·' inside the overscan band @[108, 1001, 132, 1039]
  - overscan: exo_duration '3:22:50' inside the overscan band @[132, 1001, 244, 1039]
  - overscan: TextView 'The Legend of Zelda: Ocarina of Time Rem' inside the overscan band @[80, 851, 1056, 900]
- **tv_search**
  - overscan: card_title 'Phantom Blade Zero Quick Look' inside the overscan band @[94, 754, 802, 800]
  - overscan: card_meta 'Oct 29, 2026 • Quick Looks' inside the overscan band @[94, 800, 802, 841]
  - overscan: FrameLayout 'Phantom Blade Zero Quick Look' inside the overscan band @[65, 294, 831, 874]
  - truncated: card_meta 'Sep 17, 2026 • Quick Looks' cut off: only 1px of the text is visible @[840, 797, 1432, 798]
- **tv_downloads**
  - overscan: TextView 'Downloads' inside the overscan band @[96, 48, 1824, 156]
- **tv_settings**
  - overscan: title_text '8-4 Play' inside the overscan band @[1670, 119, 1866, 157]
  - truncated: title_text 'Dispatch: Grubbtale Games' needs ~365px, box is 196px (likely ellipsized) @[134, 119, 330, 157]
  - truncated: title_text 'Giant Bomb Gaming Minute' needs ~350px, box is 196px (likely ellipsized) @[390, 119, 586, 157]
  - truncated: title_text 'JeffJeff's Bizarre Adventure' needs ~409px, box is 196px (likely ellipsized) @[646, 119, 842, 157]
  - truncated: content_text 'Reload the video feed' needs ~266px, box is 116px (likely ellipsized) @[134, 580, 250, 613]
  - truncated: title_text 'Stream Quality' needs ~204px, box is 116px (likely ellipsized) @[310, 540, 426, 578]
  - truncated: content_text 'Default: Auto (HLS)' needs ~241px, box is 116px (likely ellipsized) @[310, 580, 426, 613]
  - truncated: ... and 10 more

### phone / simple

| Screen | crash | focus | overscan | overlap | truncated | small_text | contrast | Shot |
|---|---|---|---|---|---|---|---|---|
| phone_home | pass | n/a | n/a | pass | pass | **FAIL** | **FAIL** | `layout/phone/simple/phone_home_issues.png` |
| phone_show_grid | pass | n/a | n/a | pass | **FAIL** | pass | pass | `layout/phone/simple/phone_show_grid_issues.png` |
| phone_playback | pass | n/a | n/a | pass | pass | pass | pass | `phone/simple/phone_playback.png` |
| phone_downloads | pass | n/a | n/a | pass | pass | pass | pass | `phone/simple/phone_downloads.png` |

- **phone_home**
  - small_text: TextView 'HR' text box 25px high (min 32px) @[817, 641, 843, 666]
  - small_text: TextView 'MIN' text box 25px high (min 32px) @[901, 641, 939, 666]
  - small_text: TextView 'SEC' text box 25px high (min 32px) @[991, 641, 1028, 666]
  - contrast: small_premium_badge 'PREMIUM' contrast about 1.8:1 @[37, 1138, 155, 1171]
  - contrast: navigation_bar_item_large_label_view 'Home' contrast about 2.1:1 @[133, 2250, 232, 2301]
- **phone_show_grid**
  - truncated: card_title 'GIANT BOMB KUMITE | 02 | 11/02/2026' needs ~685px, box is 399px (likely ellipsized) @[362, 306, 761, 357]
  - truncated: card_meta 'Nov 3, 2026 • Premium 64 KUMITE (Office ' needs ~954px, box is 399px (likely ellipsized) @[362, 357, 761, 411]
  - truncated: card_title 'GIANT BOMB KUMITE | 01 | 10/19/2026' needs ~685px, box is 399px (likely ellipsized) @[362, 853, 761, 904]
  - truncated: card_meta 'Oct 19, 2026 • Premium 64 KUMITE (Office' needs ~975px, box is 399px (likely ellipsized) @[362, 904, 761, 958]

### tv4k / simple

| Screen | crash | focus | overscan | overlap | truncated | small_text | contrast | Shot |
|---|---|---|---|---|---|---|---|---|
| tv_home | pass | pass | pass | pass | **FAIL** | pass | **FAIL** | `layout/tv4k/simple/tv_home_issues.png` |
| tv_show_grid | pass | pass | pass | pass | pass | pass | pass | `tv4k/simple/tv_show_grid.png` |
| tv_detail | pass | pass | pass | pass | pass | pass | **FAIL** | `layout/tv4k/simple/tv_detail_issues.png` |
| tv_playback | pass | n/a | **FAIL** | pass | pass | pass | n/a | `layout/tv4k/simple/tv_playback_issues.png` |
| tv_search | pass | pass | **FAIL** | pass | pass | pass | pass | `layout/tv4k/simple/tv_search_issues.png` |
| tv_downloads | pass | pass | **FAIL** | pass | pass | pass | pass | `layout/tv4k/simple/tv_downloads_issues.png` |
| tv_settings | pass | pass | **FAIL** | pass | **FAIL** | pass | **FAIL** | `layout/tv4k/simple/tv_settings_issues.png` |
| tv_theme_picker | pass | pass | pass | pass | pass | pass | **FAIL** | `layout/tv4k/simple/tv_theme_picker_issues.png` |
| tv_setup | pass | pass | pass | pass | pass | pass | pass | `tv4k/simple/tv_setup.png` |

- **tv_home**
  - truncated: card_meta 'Nov 6, 2026 • Quick Looks' cut off: only 1px of the text is visible @[137, 848, 759, 849]
  - contrast: row_header 'RECENT VIDEOS' contrast about 2.7:1 @[112, 286, 309, 334]
  - contrast: row_header 'BROWSE SHOWS' contrast about 2.7:1 @[112, 934, 311, 982]
- **tv_detail**
  - contrast: TextView 'QUICK LOOKS' contrast about 2.3:1 @[96, 60, 1520, 108]
- **tv_playback**
  - overscan: exo_position '00:05' inside the overscan band @[20, 1001, 108, 1039]
  - overscan: TextView '·' inside the overscan band @[108, 1001, 132, 1039]
  - overscan: exo_duration '3:22:50' inside the overscan band @[132, 1001, 244, 1039]
  - overscan: TextView 'The Legend of Zelda: Ocarina of Time Rem' inside the overscan band @[80, 851, 1056, 900]
  - overscan: exo_settings 'Settings' inside the overscan band @[1820, 972, 1916, 1068]
- **tv_search**
  - overscan: card_title 'Phantom Blade Zero Quick Look' inside the overscan band @[94, 754, 802, 800]
  - overscan: card_meta 'Oct 29, 2026 • Quick Looks' inside the overscan band @[94, 800, 802, 841]
  - overscan: FrameLayout 'Phantom Blade Zero Quick Look' inside the overscan band @[65, 294, 831, 874]
- **tv_downloads**
  - overscan: TextView 'Downloads' inside the overscan band @[96, 48, 1824, 156]
- **tv_settings**
  - overscan: title_text '8-4 Play' inside the overscan band @[1670, 141, 1866, 179]
  - truncated: title_text 'Dispatch: Grubbtale Games' needs ~365px, box is 196px (likely ellipsized) @[134, 141, 330, 179]
  - truncated: title_text 'Giant Bomb Gaming Minute' needs ~350px, box is 196px (likely ellipsized) @[390, 141, 586, 179]
  - truncated: title_text 'JeffJeff's Bizarre Adventure' needs ~409px, box is 196px (likely ellipsized) @[646, 141, 842, 179]
  - truncated: content_text 'Reload the video feed' needs ~274px, box is 122px (likely ellipsized) @[131, 587, 253, 621]
  - truncated: title_text 'Stream Quality' needs ~204px, box is 116px (likely ellipsized) @[310, 540, 426, 578]
  - truncated: content_text 'Default: Auto (HLS)' needs ~241px, box is 116px (likely ellipsized) @[310, 580, 426, 613]
  - truncated: ... and 8 more
  - contrast: row_header 'SETTINGS' contrast about 2.7:1 @[112, 286, 235, 334]
- **tv_theme_picker**
  - contrast: TextView '✓' contrast about 1.4:1 @[1347, 344, 1404, 399]

### phone / frosted

| Screen | crash | focus | overscan | overlap | truncated | small_text | contrast | Shot |
|---|---|---|---|---|---|---|---|---|
| phone_home | pass | n/a | n/a | pass | pass | **FAIL** | **FAIL** | `layout/phone/frosted/phone_home_issues.png` |
| phone_show_grid | pass | n/a | n/a | pass | **FAIL** | pass | pass | `layout/phone/frosted/phone_show_grid_issues.png` |
| phone_playback | pass | n/a | n/a | pass | pass | pass | pass | `phone/frosted/phone_playback.png` |
| phone_downloads | pass | n/a | n/a | pass | pass | pass | **FAIL** | `layout/phone/frosted/phone_downloads_issues.png` |

- **phone_home**
  - small_text: TextView 'HR' text box 25px high (min 32px) @[817, 641, 843, 666]
  - small_text: TextView 'MIN' text box 25px high (min 32px) @[901, 641, 939, 666]
  - small_text: TextView 'SEC' text box 25px high (min 32px) @[991, 641, 1028, 666]
  - contrast: small_premium_badge 'PREMIUM' contrast about 1.8:1 @[37, 1138, 155, 1171]
- **phone_show_grid**
  - truncated: card_title 'GIANT BOMB KUMITE | 02 | 11/02/2026' needs ~685px, box is 399px (likely ellipsized) @[362, 306, 761, 357]
  - truncated: card_meta 'Nov 3, 2026 • Premium 64 KUMITE (Office ' needs ~954px, box is 399px (likely ellipsized) @[362, 357, 761, 411]
  - truncated: card_title 'GIANT BOMB KUMITE | 01 | 10/19/2026' needs ~685px, box is 399px (likely ellipsized) @[362, 853, 761, 904]
  - truncated: card_meta 'Oct 19, 2026 • Premium 64 KUMITE (Office' needs ~975px, box is 399px (likely ellipsized) @[362, 904, 761, 958]
- **phone_downloads**
  - contrast: navigation_bar_item_small_label_view 'Home' contrast about 2.3:1 @[139, 2256, 225, 2299]
  - contrast: navigation_bar_item_small_label_view 'Shows' contrast about 2.3:1 @[372, 2256, 468, 2299]
  - contrast: navigation_bar_item_small_label_view 'Podcasts' contrast about 2.3:1 @[592, 2256, 726, 2299]

### phone / extreme

| Screen | crash | focus | overscan | overlap | truncated | small_text | contrast | Shot |
|---|---|---|---|---|---|---|---|---|
| phone_home | pass | n/a | n/a | pass | pass | **FAIL** | **FAIL** | `layout/phone/extreme/phone_home_issues.png` |
| phone_show_grid | pass | n/a | n/a | pass | **FAIL** | pass | pass | `layout/phone/extreme/phone_show_grid_issues.png` |
| phone_playback | pass | n/a | n/a | pass | pass | pass | pass | `phone/extreme/phone_playback.png` |
| phone_downloads | pass | n/a | n/a | pass | pass | pass | **FAIL** | `layout/phone/extreme/phone_downloads_issues.png` |

- **phone_home**
  - small_text: TextView 'HR' text box 25px high (min 32px) @[817, 641, 843, 666]
  - small_text: TextView 'MIN' text box 25px high (min 32px) @[901, 641, 939, 666]
  - small_text: TextView 'SEC' text box 25px high (min 32px) @[991, 641, 1028, 666]
  - contrast: small_premium_badge 'PREMIUM' contrast about 1.8:1 @[37, 1138, 155, 1171]
- **phone_show_grid**
  - truncated: card_title 'GIANT BOMB KUMITE | 02 | 11/02/2026' needs ~685px, box is 399px (likely ellipsized) @[362, 306, 761, 357]
  - truncated: card_meta 'Nov 3, 2026 • Premium 64 KUMITE (Office ' needs ~954px, box is 399px (likely ellipsized) @[362, 357, 761, 411]
  - truncated: card_title 'GIANT BOMB KUMITE | 01 | 10/19/2026' needs ~685px, box is 399px (likely ellipsized) @[362, 853, 761, 904]
  - truncated: card_meta 'Oct 19, 2026 • Premium 64 KUMITE (Office' needs ~975px, box is 399px (likely ellipsized) @[362, 904, 761, 958]
- **phone_downloads**
  - contrast: navigation_bar_item_small_label_view 'Home' contrast about 2.5:1 @[139, 2256, 225, 2299]
  - contrast: navigation_bar_item_small_label_view 'Shows' contrast about 2.5:1 @[372, 2256, 468, 2299]
  - contrast: navigation_bar_item_small_label_view 'Podcasts' contrast about 2.5:1 @[592, 2256, 726, 2299]

### phone / neon

| Screen | crash | focus | overscan | overlap | truncated | small_text | contrast | Shot |
|---|---|---|---|---|---|---|---|---|
| phone_home | pass | n/a | n/a | pass | pass | **FAIL** | **FAIL** | `layout/phone/neon/phone_home_issues.png` |
| phone_show_grid | pass | n/a | n/a | pass | **FAIL** | pass | pass | `layout/phone/neon/phone_show_grid_issues.png` |
| phone_playback | pass | n/a | n/a | pass | pass | pass | pass | `phone/neon/phone_playback.png` |
| phone_downloads | pass | n/a | n/a | pass | pass | pass | pass | `phone/neon/phone_downloads.png` |

- **phone_home**
  - small_text: TextView 'HR' text box 25px high (min 32px) @[817, 641, 843, 666]
  - small_text: TextView 'MIN' text box 25px high (min 32px) @[901, 641, 939, 666]
  - small_text: TextView 'SEC' text box 25px high (min 32px) @[991, 641, 1028, 666]
  - contrast: small_premium_badge 'PREMIUM' contrast about 1.6:1 @[37, 1178, 155, 1211]
- **phone_show_grid**
  - truncated: card_title 'GIANT BOMB KUMITE | 02 | 11/02/2026' needs ~685px, box is 399px (likely ellipsized) @[362, 306, 761, 357]
  - truncated: card_meta 'Nov 3, 2026 • Premium 64 KUMITE (Office ' needs ~954px, box is 399px (likely ellipsized) @[362, 357, 761, 411]
  - truncated: card_title 'GIANT BOMB KUMITE | 01 | 10/19/2026' needs ~685px, box is 399px (likely ellipsized) @[362, 853, 761, 904]
  - truncated: card_meta 'Oct 19, 2026 • Premium 64 KUMITE (Office' needs ~975px, box is 399px (likely ellipsized) @[362, 904, 761, 958]

### phone-font1.3 / simple

| Screen | crash | focus | overscan | overlap | truncated | small_text | contrast | Shot |
|---|---|---|---|---|---|---|---|---|
| phone_home | pass | n/a | n/a | pass | pass | pass | **FAIL** | `layout/phone-font1.3/simple/phone_home_issues.png` |
| phone_show_grid | pass | n/a | n/a | pass | **FAIL** | pass | pass | `layout/phone-font1.3/simple/phone_show_grid_issues.png` |
| phone_playback | pass | n/a | n/a | pass | pass | pass | pass | `phone-font1.3/simple/phone_playback.png` |
| phone_downloads | pass | n/a | n/a | pass | pass | pass | pass | `phone-font1.3/simple/phone_downloads.png` |

- **phone_home**
  - contrast: small_premium_badge 'PREMIUM' contrast about 1.8:1 @[37, 1261, 189, 1303]
- **phone_show_grid**
  - truncated: card_meta 'Nov 3, 2026 • Premium 64 KUMITE (Office ' needs ~1183px, box is 399px (likely ellipsized) @[362, 429, 761, 496]
  - truncated: card_meta 'Oct 19, 2026 • Premium 64 KUMITE (Office' needs ~1209px, box is 399px (likely ellipsized) @[362, 976, 761, 1043]

### phone-font1.3 / frosted

| Screen | crash | focus | overscan | overlap | truncated | small_text | contrast | Shot |
|---|---|---|---|---|---|---|---|---|
| phone_home | pass | n/a | n/a | pass | pass | pass | **FAIL** | `layout/phone-font1.3/frosted/phone_home_issues.png` |
| phone_show_grid | pass | n/a | n/a | pass | **FAIL** | pass | pass | `layout/phone-font1.3/frosted/phone_show_grid_issues.png` |
| phone_playback | pass | n/a | n/a | pass | pass | pass | pass | `phone-font1.3/frosted/phone_playback.png` |
| phone_downloads | pass | n/a | n/a | pass | pass | pass | pass | `phone-font1.3/frosted/phone_downloads.png` |

- **phone_home**
  - contrast: small_premium_badge 'PREMIUM' contrast about 1.8:1 @[37, 1261, 189, 1303]
- **phone_show_grid**
  - truncated: card_meta 'Nov 3, 2026 • Premium 64 KUMITE (Office ' needs ~1183px, box is 399px (likely ellipsized) @[362, 429, 761, 496]
  - truncated: card_meta 'Oct 19, 2026 • Premium 64 KUMITE (Office' needs ~1209px, box is 399px (likely ellipsized) @[362, 976, 761, 1043]

### phone-font1.3 / extreme

| Screen | crash | focus | overscan | overlap | truncated | small_text | contrast | Shot |
|---|---|---|---|---|---|---|---|---|
| phone_home | pass | n/a | n/a | pass | pass | pass | **FAIL** | `layout/phone-font1.3/extreme/phone_home_issues.png` |
| phone_show_grid | pass | n/a | n/a | pass | **FAIL** | pass | pass | `layout/phone-font1.3/extreme/phone_show_grid_issues.png` |
| phone_playback | pass | n/a | n/a | pass | pass | pass | pass | `phone-font1.3/extreme/phone_playback.png` |
| phone_downloads | pass | n/a | n/a | pass | pass | pass | pass | `phone-font1.3/extreme/phone_downloads.png` |

- **phone_home**
  - contrast: small_premium_badge 'PREMIUM' contrast about 1.8:1 @[37, 1261, 189, 1303]
- **phone_show_grid**
  - truncated: card_meta 'Nov 3, 2026 • Premium 64 KUMITE (Office ' needs ~1183px, box is 399px (likely ellipsized) @[362, 429, 761, 496]
  - truncated: card_meta 'Oct 19, 2026 • Premium 64 KUMITE (Office' needs ~1209px, box is 399px (likely ellipsized) @[362, 976, 761, 1043]

### phone-font1.3 / neon

| Screen | crash | focus | overscan | overlap | truncated | small_text | contrast | Shot |
|---|---|---|---|---|---|---|---|---|
| phone_home | pass | n/a | n/a | pass | pass | pass | **FAIL** | `layout/phone-font1.3/neon/phone_home_issues.png` |
| phone_show_grid | pass | n/a | n/a | pass | **FAIL** | pass | pass | `layout/phone-font1.3/neon/phone_show_grid_issues.png` |
| phone_playback | pass | n/a | n/a | pass | pass | pass | pass | `phone-font1.3/neon/phone_playback.png` |
| phone_downloads | pass | n/a | n/a | pass | pass | pass | pass | `phone-font1.3/neon/phone_downloads.png` |

- **phone_home**
  - contrast: small_premium_badge 'PREMIUM' contrast about 1.6:1 @[37, 1261, 189, 1303]
- **phone_show_grid**
  - truncated: card_meta 'Nov 3, 2026 • Premium 64 KUMITE (Office ' needs ~1183px, box is 399px (likely ellipsized) @[362, 429, 761, 496]
  - truncated: card_meta 'Oct 19, 2026 • Premium 64 KUMITE (Office' needs ~1209px, box is 399px (likely ellipsized) @[362, 976, 761, 1043]

### tablet / simple

| Screen | crash | focus | overscan | overlap | truncated | small_text | contrast | Shot |
|---|---|---|---|---|---|---|---|---|
| phone_home | pass | n/a | n/a | pass | pass | **FAIL** | **FAIL** | `layout/tablet/simple/phone_home_issues.png` |
| phone_show_grid | pass | n/a | n/a | pass | **FAIL** | pass | pass | `layout/tablet/simple/phone_show_grid_issues.png` |
| phone_playback | pass | n/a | n/a | pass | pass | pass | pass | `tablet/simple/phone_playback.png` |
| phone_downloads | pass | n/a | n/a | pass | pass | pass | pass | `tablet/simple/phone_downloads.png` |

- **phone_home**
  - small_text: TextView 'HR' text box 19px high (min 24px) @[620, 398, 641, 417]
  - small_text: TextView 'MIN' text box 19px high (min 24px) @[685, 398, 714, 417]
  - small_text: TextView 'SEC' text box 19px high (min 24px) @[755, 398, 783, 417]
  - contrast: small_premium_badge 'PREMIUM' contrast about 1.8:1 @[28, 778, 118, 803]
- **phone_show_grid**
  - truncated: card_title 'GIANT BOMB KUMITE | 02 | 11/02/2026' needs ~511px, box is 304px (likely ellipsized) @[976, 232, 1280, 270]
  - truncated: card_meta 'Nov 3, 2026 • Premium 64 KUMITE (Office ' needs ~724px, box is 304px (likely ellipsized) @[976, 270, 1280, 311]
  - truncated: card_title 'GIANT BOMB KUMITE | 01 | 10/19/2026' needs ~511px, box is 304px (likely ellipsized) @[1312, 232, 1616, 270]
  - truncated: card_meta 'Oct 19, 2026 • Premium 64 KUMITE (Office' needs ~740px, box is 304px (likely ellipsized) @[1312, 270, 1616, 311]

### tablet / frosted

| Screen | crash | focus | overscan | overlap | truncated | small_text | contrast | Shot |
|---|---|---|---|---|---|---|---|---|
| phone_home | pass | n/a | n/a | pass | pass | **FAIL** | **FAIL** | `layout/tablet/frosted/phone_home_issues.png` |
| phone_show_grid | pass | n/a | n/a | pass | **FAIL** | pass | pass | `layout/tablet/frosted/phone_show_grid_issues.png` |
| phone_playback | pass | n/a | n/a | pass | pass | pass | pass | `tablet/frosted/phone_playback.png` |
| phone_downloads | pass | n/a | n/a | pass | pass | pass | pass | `tablet/frosted/phone_downloads.png` |

- **phone_home**
  - small_text: TextView 'HR' text box 19px high (min 24px) @[620, 398, 641, 417]
  - small_text: TextView 'MIN' text box 19px high (min 24px) @[685, 398, 714, 417]
  - small_text: TextView 'SEC' text box 19px high (min 24px) @[755, 398, 783, 417]
  - contrast: small_premium_badge 'PREMIUM' contrast about 1.7:1 @[28, 778, 118, 803]
- **phone_show_grid**
  - truncated: card_title 'GIANT BOMB KUMITE | 02 | 11/02/2026' needs ~511px, box is 304px (likely ellipsized) @[976, 232, 1280, 270]
  - truncated: card_meta 'Nov 3, 2026 • Premium 64 KUMITE (Office ' needs ~724px, box is 304px (likely ellipsized) @[976, 270, 1280, 311]
  - truncated: card_title 'GIANT BOMB KUMITE | 01 | 10/19/2026' needs ~511px, box is 304px (likely ellipsized) @[1312, 232, 1616, 270]
  - truncated: card_meta 'Oct 19, 2026 • Premium 64 KUMITE (Office' needs ~740px, box is 304px (likely ellipsized) @[1312, 270, 1616, 311]

### tablet / extreme

| Screen | crash | focus | overscan | overlap | truncated | small_text | contrast | Shot |
|---|---|---|---|---|---|---|---|---|
| phone_home | pass | n/a | n/a | pass | pass | **FAIL** | **FAIL** | `layout/tablet/extreme/phone_home_issues.png` |
| phone_show_grid | pass | n/a | n/a | pass | **FAIL** | pass | pass | `layout/tablet/extreme/phone_show_grid_issues.png` |
| phone_playback | pass | n/a | n/a | pass | pass | pass | pass | `tablet/extreme/phone_playback.png` |
| phone_downloads | pass | n/a | n/a | pass | pass | pass | pass | `tablet/extreme/phone_downloads.png` |

- **phone_home**
  - small_text: TextView 'HR' text box 19px high (min 24px) @[620, 398, 641, 417]
  - small_text: TextView 'MIN' text box 19px high (min 24px) @[685, 398, 714, 417]
  - small_text: TextView 'SEC' text box 19px high (min 24px) @[755, 398, 783, 417]
  - contrast: small_premium_badge 'PREMIUM' contrast about 1.7:1 @[28, 778, 118, 803]
- **phone_show_grid**
  - truncated: card_title 'GIANT BOMB KUMITE | 02 | 11/02/2026' needs ~511px, box is 304px (likely ellipsized) @[976, 232, 1280, 270]
  - truncated: card_meta 'Nov 3, 2026 • Premium 64 KUMITE (Office ' needs ~724px, box is 304px (likely ellipsized) @[976, 270, 1280, 311]
  - truncated: card_title 'GIANT BOMB KUMITE | 01 | 10/19/2026' needs ~511px, box is 304px (likely ellipsized) @[1312, 232, 1616, 270]
  - truncated: card_meta 'Oct 19, 2026 • Premium 64 KUMITE (Office' needs ~740px, box is 304px (likely ellipsized) @[1312, 270, 1616, 311]

### tablet / neon

| Screen | crash | focus | overscan | overlap | truncated | small_text | contrast | Shot |
|---|---|---|---|---|---|---|---|---|
| phone_home | pass | n/a | n/a | pass | pass | **FAIL** | **FAIL** | `layout/tablet/neon/phone_home_issues.png` |
| phone_show_grid | pass | n/a | n/a | pass | **FAIL** | pass | pass | `layout/tablet/neon/phone_show_grid_issues.png` |
| phone_playback | pass | n/a | n/a | pass | pass | pass | pass | `tablet/neon/phone_playback.png` |
| phone_downloads | pass | n/a | n/a | pass | pass | pass | pass | `tablet/neon/phone_downloads.png` |

- **phone_home**
  - small_text: TextView 'HR' text box 19px high (min 24px) @[620, 398, 641, 417]
  - small_text: TextView 'MIN' text box 19px high (min 24px) @[685, 398, 714, 417]
  - small_text: TextView 'SEC' text box 19px high (min 24px) @[755, 398, 783, 417]
  - contrast: small_premium_badge 'PREMIUM' contrast about 1.6:1 @[28, 808, 118, 833]
- **phone_show_grid**
  - truncated: card_title 'GIANT BOMB KUMITE | 02 | 11/02/2026' needs ~511px, box is 304px (likely ellipsized) @[976, 232, 1280, 270]
  - truncated: card_meta 'Nov 3, 2026 • Premium 64 KUMITE (Office ' needs ~724px, box is 304px (likely ellipsized) @[976, 270, 1280, 311]
  - truncated: card_title 'GIANT BOMB KUMITE | 01 | 10/19/2026' needs ~511px, box is 304px (likely ellipsized) @[1312, 232, 1616, 270]
  - truncated: card_meta 'Oct 19, 2026 • Premium 64 KUMITE (Office' needs ~740px, box is 304px (likely ellipsized) @[1312, 270, 1616, 311]
