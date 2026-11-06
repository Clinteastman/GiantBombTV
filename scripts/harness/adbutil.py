"""Small ADB helpers for the Giant Bomb TV test harness.

Nothing here writes the API key to disk. Prefs are read from one device and
written to another through pipes held in memory only.
"""
from __future__ import annotations

import os
import re
import subprocess
import time
from xml.sax.saxutils import escape

APP = "com.giantbomb.tv"
TEST_APP = "com.giantbomb.tv.benchmark"
RUNNER = f"{TEST_APP}/androidx.test.runner.AndroidJUnitRunner"
PREFS = "shared_prefs/giantbomb_tv.xml"
DEVICE_OUT = f"/sdcard/Android/media/{TEST_APP}/harness"
THEMES = ["simple", "frosted", "extreme", "neon"]

# Git Bash on Windows rewrites /sdcard/... arguments into C:/Program Files/...
os.environ.setdefault("MSYS_NO_PATHCONV", "1")


class AdbError(RuntimeError):
    pass


def adb(serial: str | None, *args: str, input: bytes | None = None,
        check: bool = True, timeout: float = 600) -> str:
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    p = subprocess.run(cmd, input=input, capture_output=True, timeout=timeout)
    out = p.stdout.decode("utf-8", "replace")
    if check and p.returncode != 0:
        err = p.stderr.decode("utf-8", "replace").strip()
        raise AdbError(f"{' '.join(cmd[:6])} failed: {err or out}")
    return out


def adb_bytes(serial: str, *args: str) -> bytes:
    p = subprocess.run(["adb", "-s", serial] + list(args), capture_output=True, timeout=120)
    if p.returncode != 0:
        raise AdbError(p.stderr.decode("utf-8", "replace"))
    return p.stdout


def shell(serial: str, cmd: str, check: bool = True) -> str:
    return adb(serial, "shell", cmd, check=check)


def devices() -> list[str]:
    out = adb(None, "devices")
    return [l.split()[0] for l in out.splitlines()[1:] if l.strip().endswith("device")]


def connect(host: str, timeout: float = 8) -> bool:
    try:
        out = adb(None, "connect", host, check=False, timeout=timeout)
    except subprocess.TimeoutExpired:
        return False
    return "connected" in out and "failed" not in out and "cannot" not in out


def wait_boot(serial: str, timeout: float = 300) -> None:
    end = time.time() + timeout
    while time.time() < end:
        try:
            if shell(serial, "getprop sys.boot_completed", check=False).strip() == "1":
                return
        except (AdbError, subprocess.TimeoutExpired):
            pass
        time.sleep(3)
    raise AdbError(f"{serial} did not finish booting")


def device_info(serial: str) -> dict:
    feats = shell(serial, "pm list features")
    size = shell(serial, "wm size").strip().splitlines()
    dens = shell(serial, "wm density").strip().splitlines()
    model = shell(serial, "getprop ro.product.model").strip()
    sdk = shell(serial, "getprop ro.build.version.sdk").strip()
    is_tv = "android.software.leanback" in feats
    size_now = size[-1].split(":")[-1].strip()
    w, h = (int(v) for v in size_now.split("x"))
    density = int(dens[-1].split(":")[-1].strip())
    sw_dp = min(w, h) * 160 // density
    if is_tv:
        form = "tv4k" if max(w, h) >= 3000 else "tv"
    else:
        form = "tablet" if sw_dp >= 600 else "phone"
    return {
        "serial": serial, "model": model, "sdk": sdk, "is_tv": is_tv,
        "size": size_now, "density": density, "sw_dp": sw_dp, "form": form,
        "emulator": serial.startswith("emulator-"),
    }


def is_debuggable(serial: str) -> bool:
    out = shell(serial, f"dumpsys package {APP}", check=False)
    return "DEBUGGABLE" in out


def installed(serial: str, pkg: str = APP) -> bool:
    return f"package:{pkg}" in shell(serial, f"pm list packages {pkg}", check=False)


def read_prefs(serial: str) -> str:
    """Prefs XML of a debuggable install, kept in memory only."""
    return adb_bytes(serial, "exec-out", f"run-as {APP} cat {PREFS}").decode("utf-8", "replace")


def write_prefs(serial: str, xml: str) -> None:
    shell(serial, "am force-stop " + APP)
    shell(serial, f"run-as {APP} mkdir -p shared_prefs")
    adb(serial, "shell", f"run-as {APP} sh -c 'cat > {PREFS}'", input=xml.encode("utf-8"))


_KEY_RE = re.compile(r'<string name="api_key">([^<]*)</string>')
_PREMIUM_RE = re.compile(r'<boolean name="is_premium" value="(true|false)" />')


def has_key(serial: str) -> bool:
    try:
        return bool(_KEY_RE.search(read_prefs(serial)))
    except AdbError:
        return False


def key_fingerprint(serial: str) -> str:
    """Short hash so runs can say which key was used without showing it."""
    import hashlib
    m = _KEY_RE.search(read_prefs(serial))
    return hashlib.sha256(m.group(1).encode()).hexdigest()[:8] if m else "none"


def clean_prefs(source_xml: str, theme: str) -> str:
    """A fixed prefs file: the saved key and premium flag, a chosen theme and
    defaults for everything else (row order, pins, hidden rows), so every run
    shows the same layout."""
    key = _KEY_RE.search(source_xml)
    if not key:
        raise AdbError("No API key saved on the source device")
    premium = _PREMIUM_RE.search(source_xml)
    lines = [
        "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>",
        "<map>",
        f'    <string name="api_key">{key.group(1)}</string>',
        f'    <boolean name="is_premium" value="{premium.group(1) if premium else "false"}" />',
        f'    <string name="visual_theme">{escape(theme)}</string>',
        '    <boolean name="neon_particles_enabled" value="true" />',
        '    <string name="preferred_quality">auto</string>',
        "</map>",
    ]
    return "\n".join(lines) + "\n"


def set_theme(serial: str, theme: str) -> None:
    if theme not in THEMES:
        raise ValueError(f"Unknown theme {theme}; use one of {THEMES}")
    write_prefs(serial, clean_prefs(read_prefs(serial), theme))


def seed_key(source: str, target: str, theme: str = "simple") -> None:
    """Copies the key already saved on `source` to `target` (both debuggable)."""
    xml = read_prefs(source)
    if not installed(target):
        raise AdbError(f"Install the debug app on {target} first")
    # Start once so the data dir exists, then replace prefs.
    shell(target, f"monkey -p {APP} -c android.intent.category.LAUNCHER 1", check=False)
    time.sleep(3)
    write_prefs(target, clean_prefs(xml, theme))


def disable_animations(serial: str) -> None:
    for k in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        shell(serial, f"settings put global {k} 0")


def set_font_scale(serial: str, scale: float) -> None:
    shell(serial, f"settings put system font_scale {scale}")


def instrument(serial: str, cls: str, args: dict[str, str], timeout: float = 240) -> tuple[bool, str]:
    cmd = ["shell", "am", "instrument", "-w", "-e", "class", cls]
    for k, v in args.items():
        cmd += ["-e", k, v]
    cmd.append(RUNNER)
    try:
        out = adb(serial, *cmd, check=False, timeout=timeout)
    except subprocess.TimeoutExpired:
        return False, "timed out"
    ok = "OK (1 test)" in out or re.search(r"OK \(\d+ tests\)", out) is not None
    return ok, out


def pull(serial: str, remote: str, local: str) -> None:
    adb(serial, "pull", remote, local)


def crashed_since(serial: str, since: str) -> list[str]:
    """Crash buffer lines for the app since a logcat timestamp."""
    out = adb(serial, "logcat", "-d", "-b", "crash", "-T", since, check=False)
    lines = [l for l in out.splitlines() if APP in l or "FATAL" in l]
    return lines


def logcat_now(serial: str) -> str:
    return shell(serial, "date '+%m-%d %H:%M:%S.000'").strip()
