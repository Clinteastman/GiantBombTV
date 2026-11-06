# Performance results

| Device | What | Size | Model |
|---|---|---|---|
| tv | emulator, API 34 | 1920x1080 @ 320dpi | AOSP TV on x86 |
| phone | emulator, API 36 | 1080x2400 @ 420dpi | sdk_gphone64_x86_64 |

## Startup (ms, median of 5; min-max)

| Device | Theme | Cold TTID | Warm TTID |
|---|---|---|---|
| tv | frosted | 453.7 (436.7-664.4) | 83.7 (82.6-214.7) |
| phone | frosted | 1089.4 (1047.7-1134.2) | 399.6 (343.8-836.9) |

## Home scroll frame timing

| Device | Theme | Frames | Jank % | CPU P50 | CPU P90 | CPU P99 | Overrun P90 | Overrun P99 |
|---|---|---|---|---|---|---|---|---|
| tv | frosted | 3211 | 99.8% | 34.2 | 48.7 | 53.2 | 49.7 | 58.7 |
| tv | simple | 1817 | 77.3% | 21.5 | 33.9 | 39.8 | 27.2 | 39.0 |
| tv | extreme | 3198 | 99.9% | 34.0 | 48.9 | 53.6 | 49.7 | 58.7 |
| tv | neon | 876 | 100.0% | 230.3 | 272.4 | 289.6 | 379.4 | 404.7 |
| phone | frosted | 292 | 97.9% | 70.0 | 97.3 | 120.4 | 104.2 | 145.4 |
| phone | simple | 329 | 97.0% | 67.1 | 84.1 | 100.7 | 91.1 | 114.1 |
| phone | extreme | 241 | 98.8% | 84.6 | 126.9 | 172.4 | 144.8 | 205.7 |
| phone | neon | 245 | 100.0% | 115.0 | 148.6 | 213.0 | 193.9 | 297.3 |

Jank % = frames whose frameOverrunMs > 0 (missed their deadline). Times in ms.

## Memory after browsing (dumpsys meminfo, KB, median of 3)

| Device | Theme | java_heap_kb | native_heap_kb | graphics_kb | code_kb | total_pss_kb |
|---|---|---|---|---|---|---|
| tv | frosted | 6012 | 85952 | 0 | 9960 | 115030 |
| phone | frosted | 8776 | 80392 | 0 | 22036 | 131098 |
