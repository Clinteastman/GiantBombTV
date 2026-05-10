#!/usr/bin/env python3
"""
plex-sync.py — Generate a Plex-compatible local media library from your
Giant Bomb subscription. Each show becomes a "TV series" folder of .strm /
.nfo / -thumb.jpg triples that Plex's local-media agent picks up natively.

Usage:
    python3 plex-sync.py --api-key <KEY> --output /plex/giantbomb
    GIANTBOMB_API_KEY=<KEY> python3 plex-sync.py --output /plex/giantbomb

Add it to cron / systemd / your NAS scheduler to keep things fresh. The
script is idempotent: re-running only writes new episodes.

Requires: Python 3.8+, no third-party dependencies.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from html import escape
from pathlib import Path
from typing import Any, Iterable

API_BASE = "https://www.giantbomb.com/api"
USER_AGENT = "GiantBombTV-PlexSync/1.0 (+https://github.com/Clinteastman/GiantBombTV)"

# Plex / filesystem-friendly filename rules — strip Windows/Mac/Linux unsafe
# characters and clamp length so deeply-nested syncs don't trip path limits.
_UNSAFE = re.compile(r'[<>:"/\\|?*\x00-\x1f]')


def slug(name: str) -> str:
    cleaned = _UNSAFE.sub("", name).strip()
    cleaned = re.sub(r"\s+", " ", cleaned)
    return cleaned[:180] or "untitled"


def http_get_json(path: str, params: dict[str, Any], *, retries: int = 3) -> dict:
    params = {**params, "format": "json"}
    url = f"{API_BASE}{path}?{urllib.parse.urlencode(params)}"
    last_err: Exception | None = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=30) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except Exception as exc:  # urllib raises a small zoo; treat them all
            last_err = exc
            if attempt < retries - 1:
                time.sleep(2 ** attempt)
    raise RuntimeError(f"{path}: {last_err}")


def http_download(url: str, dest: Path) -> bool:
    if dest.exists():
        return False
    try:
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=60) as resp:
            data = resp.read()
        dest.write_bytes(data)
        return True
    except Exception as exc:
        print(f"    ! download failed ({url}): {exc}", file=sys.stderr)
        return False


def pick_video_url(video: dict, prefer: str) -> str | None:
    # Public API video object: hd_url (premium), high_url, low_url, url.
    # Pick the highest the user's tier can play.
    candidates = {
        "hd": ["hd_url", "high_url", "low_url", "url"],
        "high": ["high_url", "low_url", "url"],
        "low": ["low_url", "url", "high_url"],
    }[prefer]
    for key in candidates:
        u = video.get(key)
        if u:
            return u
    return None


def pick_image(images: dict | None, *keys: str) -> str | None:
    if not images:
        return None
    for k in keys:
        v = images.get(k)
        if v:
            return v
    return None


def write_show_nfo(show_dir: Path, show: dict) -> None:
    nfo = show_dir / "tvshow.nfo"
    if nfo.exists():
        return
    nfo.write_text(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        "<tvshow>\n"
        f"  <title>{escape(show.get('title') or 'Unknown')}</title>\n"
        f"  <plot>{escape(show.get('deck') or '')}</plot>\n"
        f"  <studio>Giant Bomb</studio>\n"
        f"  <genre>Video Games</genre>\n"
        "</tvshow>\n",
        encoding="utf-8",
    )


def write_episode(
    show_dir: Path,
    video: dict,
    *,
    show_title: str,
    prefer_quality: str,
    include_premium: bool,
) -> bool:
    publish = (video.get("publish_date") or "")[:10] or "1970-01-01"
    title = video.get("name") or f"video-{video.get('id')}"
    base = f"{slug(show_title)} - {publish} - {slug(title)}"

    strm = show_dir / f"{base}.strm"
    nfo = show_dir / f"{base}.nfo"
    thumb = show_dir / f"{base}-thumb.jpg"

    if strm.exists() and nfo.exists() and thumb.exists():
        return False

    if video.get("premium") and not include_premium:
        print(f"    - skipping premium: {title}")
        return False

    url = pick_video_url(video, prefer_quality)
    if not url:
        print(f"    ! no usable URL for: {title}", file=sys.stderr)
        return False

    strm.write_text(url + "\n", encoding="utf-8")

    runtime_seconds = video.get("length_seconds") or 0
    runtime_minutes = max(1, runtime_seconds // 60) if runtime_seconds else 0

    nfo.write_text(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        "<episodedetails>\n"
        f"  <title>{escape(title)}</title>\n"
        f"  <showtitle>{escape(show_title)}</showtitle>\n"
        f"  <plot>{escape(video.get('deck') or '')}</plot>\n"
        f"  <aired>{publish}</aired>\n"
        + (f"  <runtime>{runtime_minutes}</runtime>\n" if runtime_minutes else "")
        + "</episodedetails>\n",
        encoding="utf-8",
    )

    thumb_url = pick_image(
        video.get("image"),
        "super_url", "screen_large_url", "medium_url", "small_url",
    )
    if thumb_url:
        http_download(thumb_url, thumb)

    return True


def sync(args: argparse.Namespace) -> int:
    output = Path(args.output).expanduser().resolve()
    output.mkdir(parents=True, exist_ok=True)
    print(f"Syncing to {output}")

    shows_resp = http_get_json(
        "/shows/",
        {
            "api_key": args.api_key,
            "limit": args.shows_limit,
            "sort": "latest_video:desc",
        },
    )
    shows: Iterable[dict] = shows_resp.get("results") or []
    if args.active_only:
        shows = [s for s in shows if s.get("active")]
    shows = list(shows)
    print(f"Found {len(shows)} show(s)")

    written = 0
    for show in shows:
        show_title = show.get("title") or "Unknown"
        print(f"\n• {show_title}")
        show_dir = output / slug(show_title)
        show_dir.mkdir(exist_ok=True)
        write_show_nfo(show_dir, show)

        poster_url = pick_image(
            show.get("image"), "super_url", "screen_large_url", "medium_url"
        ) or pick_image(show.get("logo"), "super_url", "medium_url")
        if poster_url:
            http_download(poster_url, show_dir / "poster.jpg")

        try:
            videos_resp = http_get_json(
                "/videos/",
                {
                    "api_key": args.api_key,
                    "limit": args.per_show,
                    "filter": f"video_show:{show['id']}",
                    "sort": "publish_date:desc",
                },
            )
        except Exception as exc:
            print(f"  ! list failed: {exc}", file=sys.stderr)
            continue

        videos = videos_resp.get("results") or []
        for video in videos:
            try:
                if write_episode(
                    show_dir,
                    video,
                    show_title=show_title,
                    prefer_quality=args.quality,
                    include_premium=args.include_premium,
                ):
                    written += 1
                    print(f"  + {video.get('name')}")
            except Exception as exc:
                print(f"  ! {video.get('name')}: {exc}", file=sys.stderr)
            time.sleep(args.delay_ms / 1000.0)

    print(f"\nDone. {written} new episode(s) under {output}")
    print("Tell Plex to refresh / scan the library to pick them up.")
    return 0


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="Sync Giant Bomb videos into a Plex-friendly local library."
    )
    p.add_argument(
        "--api-key",
        default=os.environ.get("GIANTBOMB_API_KEY"),
        help="Giant Bomb API key. Or set GIANTBOMB_API_KEY in the environment.",
    )
    p.add_argument(
        "--output",
        required=True,
        help="Output directory. Point Plex's TV-show library at this path.",
    )
    p.add_argument(
        "--per-show",
        type=int,
        default=50,
        help="Max episodes to sync per show (default: 50).",
    )
    p.add_argument(
        "--shows-limit",
        type=int,
        default=100,
        help="Max shows to sync (default: 100).",
    )
    p.add_argument(
        "--quality",
        choices=["hd", "high", "low"],
        default="high",
        help="Preferred video quality. 'hd' requires a premium API key.",
    )
    p.add_argument(
        "--include-premium",
        action="store_true",
        help="Include premium-locked videos. Only useful with a premium API key.",
    )
    p.add_argument(
        "--active-only",
        action="store_true",
        help="Skip legacy / inactive shows.",
    )
    p.add_argument(
        "--delay-ms",
        type=int,
        default=100,
        help="Pause between API calls in milliseconds (default: 100). "
             "Bump if you start seeing 429s.",
    )
    args = p.parse_args(argv)
    if not args.api_key:
        p.error("missing API key — pass --api-key or set GIANTBOMB_API_KEY")
    return args


if __name__ == "__main__":
    sys.exit(sync(parse_args()))
