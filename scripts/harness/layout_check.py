"""Function-only layout checks for a harness run. Never a taste score.

For every capture in <run>/manifest.json this reads the hierarchy dump (.xml)
and the screenshot (.png) and checks:

  crash       the screen was reached and the app did not crash
  focus       TV: one element has focus and is fully on screen
  overscan    TV: no text or focusable element sits inside the 5% edge band
              (48 x 27 dp, which is 96 x 54 px at 1080p). Items cut by the screen edge are rows
              that scroll and peek, and are skipped.
  overlap     no two pieces of text overlap each other
  truncated   no text looks cut short for its box (estimated text width
              against box width; a heuristic for ellipsis and clipping)
  small_text  no text box is shorter than the minimum line height
              (TV: 24 px at 1080p; phone/tablet: 12 sp)
  contrast    text stands out from what is behind it (luminance contrast
              of the darkest and lightest pixels in the text box, >= 3:1;
              a heuristic, weaker over photos)

Writes <run>/layout/report.md, report.json and an annotated copy of every
screenshot with problems boxed in red.
"""
from __future__ import annotations

import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from xml.etree import ElementTree as ET

from PIL import Image, ImageDraw

CHECKS = ["crash", "focus", "overscan", "overlap", "truncated", "small_text", "contrast"]
TV_ONLY = {"focus", "overscan"}
# Screens where the checks below do not apply, with the reason shown in the report.
SKIP = {
    ("tv_playback", "focus"): "player surface owns focus by design",
    ("tv_playback", "contrast"): "text sits over live video",
}


@dataclass
class Node:
    cls: str
    rid: str
    text: str
    desc: str
    bounds: tuple[int, int, int, int]
    focused: bool
    focusable: bool
    depth: int
    path: tuple[int, ...]
    scroll_ancestor: bool = False
    children: list = field(default_factory=list)

    @property
    def w(self) -> int:
        return self.bounds[2] - self.bounds[0]

    @property
    def h(self) -> int:
        return self.bounds[3] - self.bounds[1]

    def label(self) -> str:
        rid = self.rid.split("/")[-1] if self.rid else self.cls.split(".")[-1]
        t = (self.text or self.desc or "").replace("\n", " ")
        return f"{rid} '{t[:40]}'" if t else rid


_B = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")
SCROLLERS = ("RecyclerView", "ScrollView", "GridView", "ListView", "ViewPager")


def parse(xml_path: Path) -> list[Node]:
    root = ET.parse(xml_path).getroot()
    out: list[Node] = []

    def walk(el, depth, path, in_scroll):
        for i, ch in enumerate(el.findall("node")):
            m = _B.match(ch.get("bounds", "[0,0][0,0]"))
            b = tuple(int(v) for v in m.groups()) if m else (0, 0, 0, 0)
            cls = ch.get("class", "")
            n = Node(cls, ch.get("resource-id", ""), ch.get("text", ""), ch.get("content-desc", ""),
                     b, ch.get("focused") == "true", ch.get("focusable") == "true",
                     depth, path + (i,), in_scroll)
            if ch.get("package", "") in ("com.giantbomb.tv", ""):
                out.append(n)
            walk(ch, depth + 1, path + (i,), in_scroll or any(s in cls for s in SCROLLERS))

    walk(root, 0, (), False)
    return out


def is_ancestor(a: Node, b: Node) -> bool:
    return len(a.path) < len(b.path) and b.path[:len(a.path)] == a.path


def inter(a, b) -> int:
    x = max(0, min(a[2], b[2]) - max(a[0], b[0]))
    y = max(0, min(a[3], b[3]) - max(a[1], b[1]))
    return x * y


_LIN = [((v / 255) / 12.92 if v / 255 <= 0.03928 else ((v / 255 + 0.055) / 1.055) ** 2.4) for v in range(256)]


def contrast_of(img: Image.Image, b) -> float | None:
    """Contrast between the background (median) and the strongest ink in the box."""
    x0, y0, x1, y1 = b
    if x1 - x0 < 4 or y1 - y0 < 4:
        return None
    hist = img.crop(b).convert("L").histogram()
    total = sum(hist)

    def at(q):
        acc = 0
        for v, n in enumerate(hist):
            acc += n
            if acc >= q * total:
                return _LIN[v]
        return _LIN[255]
    lo, med, hi = at(0.005), at(0.5), at(0.995)
    return max((hi + 0.05) / (med + 0.05), (med + 0.05) / (lo + 0.05))


def check_capture(cap: dict, run: Path) -> dict:
    res = {c: {"pass": True, "items": []} for c in CHECKS}
    screen = cap["screen"]
    if not cap.get("ok") or cap.get("crash"):
        res["crash"] = {"pass": False, "items": [cap.get("error") or "crash: " + "; ".join(cap.get("crash", [])[:3])]}
    if not cap.get("xml") or not cap.get("png") or not (run / cap["xml"]).exists():
        for c in CHECKS[1:]:
            res[c] = {"pass": None, "items": ["no capture"]}
        return res
    nodes = parse(run / cap["xml"])
    img = Image.open(run / cap["png"])
    W, H = img.size
    is_tv = cap.get("is_tv", False)
    scale = H / 1080 if is_tv else 1.0
    texts = [n for n in nodes if n.text.strip() and n.w > 0 and n.h > 0
             and "EditText" not in n.cls]
    boxes: list[tuple[str, tuple]] = []

    for c in CHECKS:
        if (not is_tv and c in TV_ONLY) or (screen, c) in SKIP:
            res[c] = {"pass": None, "items": [SKIP.get((screen, c), "phone/tablet: not applicable")]}

    def fail(check, node: Node, why: str):
        if res[check]["pass"] is None:
            return
        res[check]["pass"] = False
        res[check]["items"].append(f"{node.label()} {why} @{list(node.bounds)}")
        boxes.append((check, node.bounds))

    def touches_edge(b) -> bool:
        return b[0] <= 0 or b[1] <= 0 or b[2] >= W or b[3] >= H

    # focus
    if res["focus"]["pass"] is not None and is_tv:
        foc = [n for n in nodes if n.focused]
        if not foc:
            res["focus"] = {"pass": False, "items": ["nothing has focus"]}
        else:
            f = foc[-1]
            if f.w <= 0 or f.h <= 0 or touches_edge(f.bounds):
                fail("focus", f, "focused element is cut off or off screen")
            else:
                res["focus"]["items"].append(f"focused: {f.label()} @{list(f.bounds)}")

    # overscan: 5% band. Rows that scroll and peek off the edge are skipped.
    if res["overscan"]["pass"] is not None and is_tv:
        mx, my = int(W * 0.05), int(H * 0.05)  # 48 x 27 dp: 96 x 54 px at 1080p
        items = [n for n in nodes if n.focusable and n.w > 0
                 and not any(k in n.cls for k in SCROLLERS)]  # lists are containers, not items
        for n in texts + items:
            b = n.bounds
            if touches_edge(b) and n.scroll_ancestor:
                continue
            if touches_edge(b) and not n.text:
                continue  # full-bleed containers and backdrops
            if b[0] < mx or b[1] < my or b[2] > W - mx or b[3] > H - my:
                fail("overscan", n, "inside the overscan band")

    # overlap: text on text, not parent/child
    for i, x in enumerate(texts):
        for y in texts[i + 1:]:
            if is_ancestor(x, y) or is_ancestor(y, x):
                continue
            ov = inter(x.bounds, y.bounds)
            if ov and ov > 0.2 * min(x.w * x.h, y.w * y.h):
                fail("overlap", x, f"overlaps {y.label()}")

    # small text and truncation
    # 12 sp at font scale 1.0 (24 px on a 1080p TV, 2x density).
    min_line = 24 * scale if is_tv else 12 * (cap.get("density") or 420) / 160
    for n in texts:
        if touches_edge(n.bounds) and n.scroll_ancestor:
            continue  # partly scrolled off: height is not the real line height
        line = n.h
        if line < min_line * 0.5:
            # Only a sliver of the text box is visible: its container cuts it off.
            fail("truncated", n, f"cut off: only {line}px of the text is visible")
            continue
        if line < min_line * 0.95:
            fail("small_text", n, f"text box {line}px high (min {min_line:.0f}px)")
        # A single line of text is roughly 0.5 x its height wide per character.
        lines = max(1, round(n.h / (min_line * 1.6))) if n.h > min_line * 2.2 else 1
        est = len(n.text.strip()) * 0.48 * (n.h / lines) / 1.25
        if lines == 1 and est > n.w * 1.5 and len(n.text.strip()) > 4:
            fail("truncated", n, f"needs ~{est:.0f}px, box is {n.w}px (likely ellipsized)")

    # contrast
    if res["contrast"]["pass"] is not None:
        for n in texts:
            if touches_edge(n.bounds):
                continue
            cr = contrast_of(img, n.bounds)
            if cr is not None and cr < 3.0:
                fail("contrast", n, f"contrast about {cr:.1f}:1")

    if boxes:
        ann = img.convert("RGB").copy()
        dr = ImageDraw.Draw(ann)
        for check, b in boxes:
            dr.rectangle(b, outline=(255, 0, 0), width=max(2, int(3 * scale)))
            dr.text((b[0] + 3, max(0, b[1] - 14)), check, fill=(255, 40, 40))
        out = run / "layout" / Path(cap["png"]).with_name(Path(cap["png"]).stem + "_issues.png")
        out.parent.mkdir(parents=True, exist_ok=True)
        ann.save(out)
        res["annotated"] = str(out.relative_to(run)).replace("\\", "/")
    return res


def mark(v) -> str:
    return {True: "pass", False: "**FAIL**", None: "n/a"}[v]


def main(run: Path) -> None:
    m = json.loads((run / "manifest.json").read_text())
    results = []
    for cap in m["captures"]:
        dev = m["devices"].get(cap["device"], {})
        cap = dict(cap, density=dev.get("density"))
        r = check_capture(cap, run)
        results.append({"device": cap["device"], "theme": cap["theme"], "screen": cap["screen"],
                        "png": cap.get("png"), **r})
    (run / "layout").mkdir(exist_ok=True)
    (run / "layout" / "report.json").write_text(json.dumps(results, indent=2))

    lines = [f"# Layout checks: {run.name}", "",
             "Function only (no taste). pass / **FAIL** / n/a. Details and annotated shots below.", ""]
    # Summary per device and theme
    lines += ["## Summary", "", "| Device | Theme | Screens | " + " | ".join(CHECKS) + " |",
              "|---|---|---|" + "---|" * len(CHECKS)]
    groups: dict[tuple, list] = {}
    for r in results:
        groups.setdefault((r["device"], r["theme"]), []).append(r)
    for (dev, theme), rs in groups.items():
        cells = []
        for c in CHECKS:
            vals = [r[c]["pass"] for r in rs]
            fails = sum(1 for v in vals if v is False)
            if fails:
                cells.append(f"**FAIL {fails}**")
            elif all(v is None for v in vals):
                cells.append("n/a")
            else:
                cells.append("pass")
        lines.append(f"| {dev} | {theme} | {len(rs)} | " + " | ".join(cells) + " |")
    lines += ["", "## Per screen", ""]
    for (dev, theme), rs in groups.items():
        lines += [f"### {dev} / {theme}", "", "| Screen | " + " | ".join(CHECKS) + " | Shot |",
                  "|---|" + "---|" * len(CHECKS) + "---|"]
        for r in rs:
            shot = (r.get("annotated") or r.get("png") or "").replace("\\", "/")
            lines.append(f"| {r['screen']} | " + " | ".join(mark(r[c]['pass']) for c in CHECKS)
                         + f" | `{shot}` |")
        lines.append("")
        for r in rs:
            fails = [(c, r[c]["items"]) for c in CHECKS if r[c]["pass"] is False]
            if fails:
                lines.append(f"- **{r['screen']}**")
                for c, items in fails:
                    for it in items[:6]:
                        lines.append(f"  - {c}: {it}")
                    if len(items) > 6:
                        lines.append(f"  - {c}: ... and {len(items) - 6} more")
        lines.append("")
    (run / "layout" / "report.md").write_text("\n".join(lines), encoding="utf-8")
    total = sum(1 for r in results for c in CHECKS if r[c]["pass"] is False)
    print(f"Layout checks: {len(results)} screens, {total} failed checks. "
          f"Report: {run / 'layout' / 'report.md'}")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
