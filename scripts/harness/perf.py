"""Performance pass: Macrobenchmark from the benchmark/ module.

Per device:
  - cold and warm start (StartupTimingMetric, 5 runs each)
  - memory after browsing the home rows (MemoryUsageMetric, last value, 3 runs)
  - home scroll frame timing per theme (FrameTimingMetric, 5 runs):
    TV walks the Leanback rows with the D-pad, phone flings the home list

It installs the benchmarkRelease build (minified, profileable, test-signed)
over the debug build so the saved key stays, runs the tests with `am
instrument` (not Gradle, which would uninstall the app and lose the key) and
puts the debug build back at the end.

Writes <out>/perf/results.md and results.json, plus the raw benchmark JSON.
Emulator numbers are for comparing runs on the same machine only.
"""
from __future__ import annotations

import datetime as dt
import json
import statistics
import sys
from pathlib import Path

import adbutil as a

ROOT = Path(__file__).resolve().parents[2]
BENCH = "com.giantbomb.tv.benchmark.StartupAndScrollBenchmark"
REMOTE_JSON = f"/sdcard/Android/media/{a.TEST_APP}/{a.TEST_APP}-benchmarkData.json"
APP_DEBUG_APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
APP_BENCH_APK = ROOT / "app/build/outputs/apk/benchmarkRelease/app-benchmarkRelease.apk"
TEST_APK = ROOT / "benchmark/build/outputs/apk/benchmarkRelease/benchmark-benchmarkRelease.apk"


def log(msg: str) -> None:
    print(f"[{dt.datetime.now():%H:%M:%S}] {msg}", flush=True)


def run_bench(serial: str, test: str, raw_dir: Path, tag: str) -> dict:
    a.shell(serial, f"rm -f {REMOTE_JSON}", check=False)
    args = {"androidx.benchmark.suppressErrors": "EMULATOR,LOW-BATTERY,UNLOCKED"}
    ok, out = a.instrument(serial, f"{BENCH}#{test}", args, timeout=1800)
    raw = raw_dir / f"{tag}.json"
    try:
        a.pull(serial, REMOTE_JSON, str(raw))
        data = json.loads(raw.read_text())
    except (a.AdbError, ValueError):
        data = None
    if not ok or not data:
        err = "\n".join(l for l in out.splitlines() if "Exception" in l or "Error" in l or "error(" in l)
        return {"ok": False, "error": (err or out[-600:])[:1200]}
    return {"ok": True, "benchmark": data["benchmarks"][0], "context": data.get("context", {})}


def pct(values: list[float], p: float) -> float:
    s = sorted(values)
    if not s:
        return float("nan")
    k = (len(s) - 1) * p
    lo, hi = int(k), min(int(k) + 1, len(s) - 1)
    return s[lo] + (s[hi] - s[lo]) * (k - lo)


def summarise(test: str, r: dict) -> dict:
    if not r["ok"]:
        return {"ok": False, "error": r["error"]}
    b = r["benchmark"]
    m, sm = b.get("metrics", {}), b.get("sampledMetrics", {})
    if test in ("coldStartup", "warmStartup"):
        t = m.get("timeToInitialDisplayMs", {})
        full = m.get("timeToFullDisplayMs", {})
        return {"ok": True, "ttid_median_ms": t.get("median"), "ttid_min_ms": t.get("minimum"),
                "ttid_max_ms": t.get("maximum"), "ttfd_median_ms": full.get("median"),
                "runs": t.get("runs")}
    if test == "memoryAfterBrowse":
        out = {"ok": True}
        for k, v in m.items():
            if k.startswith("memory"):
                out[k] = v.get("median")
        return out
    # homeScroll
    cpu = [x for run in sm.get("frameDurationCpuMs", {}).get("runs", []) for x in run]
    over = [x for run in sm.get("frameOverrunMs", {}).get("runs", []) for x in run]
    res = {"ok": True, "frames": len(cpu)}
    if cpu:
        res.update({"cpu_p50_ms": round(pct(cpu, .5), 1), "cpu_p90_ms": round(pct(cpu, .9), 1),
                    "cpu_p99_ms": round(pct(cpu, .99), 1)})
    if over:
        res.update({"jank_pct": round(100 * sum(1 for x in over if x > 0) / len(over), 1),
                    "overrun_p90_ms": round(pct(over, .9), 1), "overrun_p99_ms": round(pct(over, .99), 1)})
    else:
        res["jank_pct"] = None  # frameOverrunMs needs API 31+
    return res


def fmt(v, unit="") -> str:
    if v is None:
        return "-"
    if isinstance(v, float):
        v = round(v, 1)
    return f"{v}{unit}"


def main(args) -> None:
    import harness  # for gradle/new_run_dir/parse_themes
    themes = harness.parse_themes(args.themes)
    if not args.no_build:
        harness.gradle(":app:assembleDebug", ":app:assembleBenchmarkRelease",
                       ":benchmark:assembleBenchmarkRelease")
    out = Path(args.out) if args.out else harness.new_run_dir("perf")
    pdir = out / "perf"
    raw_dir = pdir / "raw"
    raw_dir.mkdir(parents=True, exist_ok=True)
    resf = pdir / "results.json"
    results = json.loads(resf.read_text()) if resf.exists() else {"devices": {}, "rows": []}

    for s in harness.resolve_serials(args.serial):
        info = a.device_info(s)
        form = info["form"]
        results["devices"][form] = info
        if not (a.installed(s) and a.is_debuggable(s) and a.has_key(s)):
            sys.exit(f"{s}: needs the debug app with a key first (harness.py setup)")
        a.adb(s, "install", "-r", "-t", str(TEST_APK))
        try:
            plan = [(themes[0], t) for t in ("coldStartup", "warmStartup", "memoryAfterBrowse")]
            plan += [(th, "homeScroll") for th in themes]
            current = None
            for theme, test in plan:
                if theme != current:
                    a.adb(s, "install", "-r", str(APP_DEBUG_APK))
                    a.set_theme(s, theme)
                    a.adb(s, "install", "-r", str(APP_BENCH_APK))
                    current = theme
                log(f"{s} {form} {theme} {test}")
                r = run_bench(s, test, raw_dir, f"{form}-{theme}-{test}")
                row = {"device": form, "serial": s, "theme": theme, "test": test, **summarise(test, r)}
                results["rows"] = [x for x in results["rows"]
                                   if not (x["device"] == form and x["theme"] == theme and x["test"] == test)]
                results["rows"].append(row)
                resf.write_text(json.dumps(results, indent=2))
                if not row["ok"]:
                    log(f"  FAILED: {row['error'][:300]}")
        finally:
            a.adb(s, "install", "-r", str(APP_DEBUG_APK), check=False)
            try:
                a.set_theme(s, "simple")
            except a.AdbError:
                pass
    write_md(results, pdir / "results.md")
    log(f"Done: {pdir / 'results.md'}")


def write_md(results: dict, path: Path) -> None:
    L = ["# Performance results", ""]
    L.append("| Device | What | Size | Model |")
    L.append("|---|---|---|---|")
    for form, d in results["devices"].items():
        kind = "emulator" if d.get("emulator") else "real device"
        L.append(f"| {form} | {kind}, API {d['sdk']} | {d['size']} @ {d['density']}dpi | {d['model']} |")
    L += ["", "## Startup (ms, median of 5; min-max)", "",
          "| Device | Theme | Cold TTID | Warm TTID |", "|---|---|---|---|"]
    by = {(r["device"], r["theme"], r["test"]): r for r in results["rows"]}
    for (dev, theme, test), r in by.items():
        if test != "coldStartup":
            continue
        w = by.get((dev, theme, "warmStartup"), {})

        def cell(x):
            if not x.get("ok"):
                return "failed"
            return f"{fmt(x.get('ttid_median_ms'))} ({fmt(x.get('ttid_min_ms'))}-{fmt(x.get('ttid_max_ms'))})"
        L.append(f"| {dev} | {theme} | {cell(r)} | {cell(w) if w else '-'} |")
    L += ["", "## Home scroll frame timing", "",
          "| Device | Theme | Frames | Jank % | CPU P50 | CPU P90 | CPU P99 | Overrun P90 | Overrun P99 |",
          "|---|---|---|---|---|---|---|---|---|"]
    for (dev, theme, test), r in by.items():
        if test != "homeScroll":
            continue
        if not r.get("ok"):
            L.append(f"| {dev} | {theme} | failed | | | | | | |")
            continue
        L.append(f"| {dev} | {theme} | {r.get('frames')} | {fmt(r.get('jank_pct'), '%')} | "
                 f"{fmt(r.get('cpu_p50_ms'))} | {fmt(r.get('cpu_p90_ms'))} | {fmt(r.get('cpu_p99_ms'))} | "
                 f"{fmt(r.get('overrun_p90_ms'))} | {fmt(r.get('overrun_p99_ms'))} |")
    L += ["", "Jank % = frames whose frameOverrunMs > 0 (missed their deadline). Times in ms.", "",
          "## Memory after browsing (KB, median of 3)", ""]
    mem_rows = [r for r in results["rows"] if r["test"] == "memoryAfterBrowse"]
    keys = sorted({k for r in mem_rows for k in r if k.startswith("memory")})
    if mem_rows:
        L.append("| Device | Theme | " + " | ".join(keys) + " |")
        L.append("|---|---|" + "---|" * len(keys))
        for r in mem_rows:
            if not r.get("ok"):
                L.append(f"| {r['device']} | {r['theme']} | failed |")
                continue
            L.append(f"| {r['device']} | {r['theme']} | " + " | ".join(fmt(r.get(k)) for k in keys) + " |")
    fails = [r for r in results["rows"] if not r.get("ok")]
    if fails:
        L += ["", "## Failures", ""]
        for r in fails:
            L.append(f"- {r['device']} {r['theme']} {r['test']}: `{r['error'][:300]}`")
    path.write_text("\n".join(L) + "\n", encoding="utf-8")
