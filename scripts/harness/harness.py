#!/usr/bin/env python3
"""Giant Bomb TV test harness: screenshots, layout checks and performance.

Run from the repo root. See docs/testing.md for the full guide.

  python scripts/harness/harness.py devices
  python scripts/harness/harness.py setup   --serial emulator-5586 [--key-from emulator-5554]
  python scripts/harness/harness.py capture --serial emulator-5586 [--themes all] [--font-scale 1.3]
  python scripts/harness/harness.py layout  --run harness-results/<run>
  python scripts/harness/harness.py perf    --serial emulator-5586 [--themes simple,frosted]
  python scripts/harness/harness.py dump    --serial emulator-5586 --name here

Generated files go to harness-results/ (ignored by git).
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import adbutil as a  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
RESULTS = ROOT / "harness-results"
TOUR = "com.giantbomb.tv.benchmark.harness.ScreenTour#capture"
DUMP = "com.giantbomb.tv.benchmark.harness.HarnessDump"

# The fixed screen list. Same names, same order, every run.
TV_SCREENS = [
    "tv_home", "tv_show_grid", "tv_detail", "tv_playback", "tv_search",
    "tv_downloads", "tv_settings", "tv_theme_picker", "tv_setup",
]
# The phone app has no detail screen (a tap plays the video), so it gets playback.
MOBILE_SCREENS = ["phone_home", "phone_show_grid", "phone_playback", "phone_downloads"]

APP_DEBUG_APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
APP_BENCH_APK = ROOT / "app/build/outputs/apk/benchmarkRelease/app-benchmarkRelease.apk"
TEST_APK = ROOT / "benchmark/build/outputs/apk/benchmarkRelease/benchmark-benchmarkRelease.apk"


def log(msg: str) -> None:
    print(f"[{dt.datetime.now():%H:%M:%S}] {msg}", flush=True)


def git_head() -> str:
    try:
        return subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT,
                              capture_output=True, text=True).stdout.strip() or "nogit"
    except OSError:
        return "nogit"


def gradle(*tasks: str) -> None:
    exe = "gradlew.bat" if os.name == "nt" else "./gradlew"
    log(f"gradle {' '.join(tasks)}")
    subprocess.run([str(ROOT / exe) if os.name == "nt" else exe, *tasks, "--console=plain", "-q"],
                   cwd=ROOT, check=True)


def parse_themes(value: str) -> list[str]:
    if value == "all":
        return list(a.THEMES)
    themes = [t.strip() for t in value.split(",") if t.strip()]
    for t in themes:
        if t not in a.THEMES:
            sys.exit(f"Unknown theme '{t}'. Use: {', '.join(a.THEMES)} or 'all'")
    return themes


def resolve_serials(values: list[str] | None) -> list[str]:
    if values:
        return values
    found = a.devices()
    if not found:
        sys.exit("No devices. Start an emulator or `adb connect <ip>:5555` first.")
    return found


# ---------------------------------------------------------------- devices ---

def cmd_devices(args) -> None:
    if args.firetv:
        log(f"adb connect {args.firetv}: {'ok' if a.connect(args.firetv) else 'not reachable'}")
    for s in a.devices():
        info = a.device_info(s)
        dbg = a.installed(s) and a.is_debuggable(s)
        key = a.key_fingerprint(s) if dbg else ("app missing" if not a.installed(s) else "release build")
        print(f"{s:22} {info['form']:7} {info['size']:>10} dpi={info['density']} "
              f"sw={info['sw_dp']}dp sdk={info['sdk']} debug_app={'yes' if dbg else 'no'} key={key}")


# ------------------------------------------------------------------ setup ---

def cmd_setup(args) -> None:
    if not args.no_build:
        gradle(":app:assembleDebug", ":benchmark:assembleBenchmarkRelease")
    for s in resolve_serials(args.serial):
        log(f"{s}: installing debug app + harness test app")
        a.adb(s, "install", "-r", str(APP_DEBUG_APK))
        a.adb(s, "install", "-r", "-t", str(TEST_APK))
        a.disable_animations(s)
        a.grant_notifications(s)
        if args.key_from:
            a.seed_key(args.key_from, s, "simple")
            log(f"{s}: key copied from {args.key_from} (fingerprint {a.key_fingerprint(s)})")
        elif a.has_key(s):
            a.set_theme(s, "simple")
            log(f"{s}: using the key already saved (fingerprint {a.key_fingerprint(s)})")
        else:
            log(f"{s}: NO API KEY. Enter one in the app, or pass --key-from <serial>.")


# ---------------------------------------------------------------- capture ---

def new_run_dir(label: str | None) -> Path:
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M")
    d = RESULTS / f"{stamp}-{git_head()}{'-' + label if label else ''}"
    d.mkdir(parents=True, exist_ok=True)
    return d


def load_manifest(run: Path) -> dict:
    f = run / "manifest.json"
    return json.loads(f.read_text()) if f.exists() else {"captures": [], "devices": {}}


def save_manifest(run: Path, m: dict) -> None:
    (run / "manifest.json").write_text(json.dumps(m, indent=2))


def capture_one(serial: str, screen: str, dest: Path, retries: int) -> dict:
    """Runs one ScreenTour step and pulls its png/xml. Retries a flaky step."""
    name = dest.stem
    since = a.logcat_now(serial)
    started = time.time()
    ok, out, attempt = False, "", 0
    for attempt in range(1, retries + 2):
        ok, out = a.instrument(serial, TOUR, {"screen": screen, "name": name})
        if ok:
            break
        log(f"  {screen}: attempt {attempt} failed, retrying")
    crashes = a.crashed_since(serial, since)
    if not ok:
        # Keep whatever is on screen, so a failure can be looked at.
        a.instrument(serial, DUMP, {"name": name})
    rec = {"screen": screen, "ok": ok, "attempts": attempt, "seconds": round(time.time() - started, 1),
           "crash": crashes[:20]}
    if not ok:
        rec["error"] = "\n".join(l for l in out.splitlines()
                                 if "Exception" in l or "Error" in l or "Timed out" in l)[:1500]
    for ext in ("png", "xml"):
        try:
            a.pull(serial, f"{a.DEVICE_OUT}/{name}.{ext}", str(dest.with_suffix("." + ext)))
            rec[ext] = dest.with_suffix("." + ext).relative_to(dest.parents[2]).as_posix()
        except a.AdbError:
            rec[ext] = None
    return rec


def cmd_capture(args) -> None:
    run = Path(args.run) if args.run else new_run_dir(args.label)
    run.mkdir(parents=True, exist_ok=True)
    m = load_manifest(run)
    m.setdefault("git", git_head())
    themes = parse_themes(args.themes)
    for s in resolve_serials(args.serial):
        info = a.device_info(s)
        if not (a.installed(s) and a.is_debuggable(s)):
            sys.exit(f"{s}: needs the debug app (run `setup` first)")
        if not a.has_key(s):
            sys.exit(f"{s}: no API key saved (run `setup --key-from <serial>` or enter one)")
        form = (args.device_label or info["form"]) + (f"-font{args.font_scale}" if args.font_scale != 1.0 else "")
        info["key_fingerprint"] = a.key_fingerprint(s)
        m["devices"][form] = info
        save_manifest(run, m)
        screens = TV_SCREENS if info["is_tv"] else MOBILE_SCREENS
        if args.screens:
            screens = [x for x in screens if x in args.screens.split(",")]
        a.disable_animations(s)
        a.grant_notifications(s)
        a.set_font_scale(s, args.font_scale)
        try:
            for theme in themes:
                a.set_theme(s, theme)
                for screen in screens:
                    dest = run / form / theme / f"{screen}.png"
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    log(f"{s} {form} {theme} {screen}")
                    rec = capture_one(s, screen, dest, args.retries)
                    rec.update({"device": form, "serial": s, "theme": theme,
                                "font_scale": args.font_scale, "is_tv": info["is_tv"]})
                    # Re-read first: another capture may be adding a device to this run.
                    fresh = load_manifest(run)
                    fresh.setdefault("git", m["git"])
                    fresh["devices"][form] = info
                    fresh["captures"] = [c for c in fresh["captures"]
                                         if not (c["device"] == form and c["theme"] == theme
                                                 and c["screen"] == screen)] + [rec]
                    save_manifest(run, fresh)
                    if not rec["ok"]:
                        log(f"  FAILED: {rec.get('error', '')[:300]}")
        finally:
            if args.font_scale != 1.0:
                a.set_font_scale(s, 1.0)
            a.set_theme(s, "simple")
    log(f"Done. Run folder: {run}")


# ------------------------------------------------------------------- dump ---

def cmd_dump(args) -> None:
    s = resolve_serials(args.serial)[0]
    ok, out = a.instrument(s, DUMP, {"name": args.name})
    if not ok:
        sys.exit(out)
    dest = RESULTS / "dumps"
    dest.mkdir(parents=True, exist_ok=True)
    for ext in ("png", "xml"):
        a.pull(s, f"{a.DEVICE_OUT}/{args.name}.{ext}", str(dest / f"{args.name}.{ext}"))
    log(f"Wrote {dest / args.name}.png/.xml")


# ------------------------------------------------------------------ cli ---

def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    d = sub.add_parser("devices", help="list devices, form factor, key status")
    d.add_argument("--firetv", help="also try `adb connect` to this host:port first")
    d.set_defaults(func=cmd_devices)

    st = sub.add_parser("setup", help="install debug app + harness, seed the key")
    st.add_argument("--serial", action="append")
    st.add_argument("--key-from", help="copy the key already saved on this device (debug build)")
    st.add_argument("--no-build", action="store_true")
    st.set_defaults(func=cmd_setup)

    c = sub.add_parser("capture", help="capture the fixed screen list")
    c.add_argument("--serial", action="append")
    c.add_argument("--themes", default="all", help="all, or e.g. simple,neon")
    c.add_argument("--screens", help="limit to these screen names (comma list)")
    c.add_argument("--font-scale", type=float, default=1.0)
    c.add_argument("--run", help="add to an existing run folder")
    c.add_argument("--label", help="suffix for a new run folder")
    c.add_argument("--retries", type=int, default=1)
    c.add_argument("--device-label", help="folder name for this device instead of the detected "
                   "form (tv, phone, tablet), e.g. tv4k for a 4K TV that renders a 1080p UI")
    c.set_defaults(func=cmd_capture)

    lc = sub.add_parser("layout", help="function-only layout checks on a run")
    lc.add_argument("--run", required=True)
    lc.set_defaults(func=lambda args: __import__("layout_check").main(Path(args.run)))

    pf = sub.add_parser("perf", help="Macrobenchmark startup, scroll and memory")
    pf.add_argument("--serial", action="append")
    pf.add_argument("--themes", default="simple,frosted", help="themes for the scroll test")
    pf.add_argument("--no-build", action="store_true")
    pf.add_argument("--out", help="results folder (default: new run folder)")
    pf.add_argument("--iterations-note", default="")
    pf.set_defaults(func=lambda args: __import__("perf").main(args))

    du = sub.add_parser("dump", help="screenshot + hierarchy of whatever is on screen")
    du.add_argument("--serial", action="append")
    du.add_argument("--name", default="dump")
    du.set_defaults(func=cmd_dump)

    args = p.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
