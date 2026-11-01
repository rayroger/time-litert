#!/usr/bin/env python3
"""Turn capture sessions (crop .jpg + .json from the app) into a training dataset.

Output folder (and optional zip): images/<file>.jpg and labels.csv with columns
  file, split, minutes_12h, session, watch, timestamp, confidence, aspect, blur

Label = the capture's local time of day on a 12 h dial, continuous (seconds included), plus an
optional per-session offset in minutes (offsets.json: {"20261101_171030": 0.0}). It is only
as correct as the watches were set, so only sessions from --since on (watches known to be
set) are used.

Split: by default the last part of the timeline is held out (times the model has never seen),
with a gap, because watch positions are only stable inside one session and the same physical
watch would otherwise land in both splits. --split watch holds out whole groups instead.
"""
import argparse
import csv
import json
import shutil
import zipfile
from datetime import datetime
from pathlib import Path

import cv2


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True, help="folder holding the session folders (Rebex data folder)")
    ap.add_argument("--out", required=True, help="output folder; a .zip is written next to it with --zip")
    ap.add_argument("--since", default="20261101_165000", help="first session folder name to use")
    ap.add_argument("--offsets", help="JSON file {session: minutes the watch is ahead (+)/behind (-)}")
    ap.add_argument("--min-conf", type=float, default=0.6)
    ap.add_argument("--max-aspect", type=float, default=1.3)
    ap.add_argument("--min-side", type=int, default=160)
    ap.add_argument("--min-blur", type=float, default=50.0)
    ap.add_argument("--val-fraction", type=float, default=0.2)
    ap.add_argument("--gap-minutes", type=float, default=3.0)
    ap.add_argument("--split", choices=["time", "watch"], default="time",
                    help="time: hold out the end of the timeline (default); watch: hold out whole watch groups "
                         "(only meaningful once watches have stable ids across sessions)")
    ap.add_argument("--zip", action="store_true")
    args = ap.parse_args()

    src, out = Path(args.src), Path(args.out)
    offsets = json.loads(Path(args.offsets).read_text()) if args.offsets else {}
    if out.exists():
        shutil.rmtree(out)
    (out / "images").mkdir(parents=True)

    rows, skipped = [], {"no_image": 0, "low_conf": 0, "aspect": 0, "small": 0, "blur": 0}
    for sdir in sorted(p for p in src.iterdir() if p.is_dir() and p.name >= args.since):
        for jf in sorted(sdir.glob("*_watch_*.json")):
            jpg = jf.with_suffix(".jpg")
            img = cv2.imread(str(jpg)) if jpg.exists() else None
            if img is None:
                skipped["no_image"] += 1
                continue
            meta = json.loads(jf.read_text(encoding="utf-8"))
            h, w = img.shape[:2]
            conf = (meta.get("model") or {}).get("detection_confidence") or 0.0
            blur = cv2.Laplacian(cv2.cvtColor(img, cv2.COLOR_BGR2GRAY), cv2.CV_64F).var()
            aspect = max(w, h) / min(w, h)
            if conf < args.min_conf:
                skipped["low_conf"] += 1
            elif aspect > args.max_aspect:
                skipped["aspect"] += 1
            elif min(w, h) < args.min_side:
                skipped["small"] += 1
            elif blur < args.min_blur:
                skipped["blur"] += 1
            else:
                cap = meta["capture"]
                dt = datetime.fromisoformat(cap["timestamp_iso"])
                minutes = (dt.hour % 12) * 60 + dt.minute + dt.second / 60.0 + dt.microsecond / 6e7
                minutes = (minutes + float(offsets.get(cap["session"], 0.0))) % 720.0
                name = f"{cap['session']}_{jpg.name}"
                shutil.copy2(jpg, out / "images" / name)
                rows.append({"file": name, "minutes_12h": round(minutes, 4), "session": cap["session"],
                             "watch": f"{cap['session']}_d{cap['dial_index']}", "timestamp": dt.timestamp(),
                             "confidence": round(conf, 3), "aspect": round(aspect, 3), "blur": round(blur, 1)})

    if not rows:
        raise SystemExit(f"no usable crops found (skipped: {skipped})")

    watches = sorted({r["watch"] for r in rows})
    if args.split == "watch" and len(watches) >= 5:
        held = {w for i, w in enumerate(watches) if i % 5 == 4}
        for r in rows:
            r["split"] = "validation" if r["watch"] in held else "train"
        mode = f"held out {len(held)} of {len(watches)} watch groups"
    else:
        times = sorted({r["timestamp"] for r in rows})
        cut = times[int(len(times) * (1 - args.val_fraction))] if len(times) > 1 else times[0] + 1
        gap = args.gap_minutes * 60
        for r in rows:
            r["split"] = "validation" if r["timestamp"] >= cut else ("gap" if r["timestamp"] >= cut - gap else "train")
        rows = [r for r in rows if r["split"] != "gap"]
        mode = f"held out the last {args.val_fraction:.0%} of the timeline ({len(watches)} watch groups)"

    with open(out / "labels.csv", "w", newline="") as f:
        wr = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        wr.writeheader()
        wr.writerows(rows)

    n_val = sum(r["split"] == "validation" for r in rows)
    distinct = len({int(r["minutes_12h"]) for r in rows})
    print(f"crops kept: {len(rows)} (train {len(rows) - n_val}, validation {n_val}); skipped: {skipped}")
    print(f"split: {mode}; distinct minutes covered: {distinct} of 720; watch groups: {len(watches)}")
    if args.zip:
        zpath = out.with_suffix(".zip")
        with zipfile.ZipFile(zpath, "w", zipfile.ZIP_STORED) as z:
            z.write(out / "labels.csv", "labels.csv")
            for p in sorted((out / "images").iterdir()):
                z.write(p, f"images/{p.name}")
        print(f"wrote {zpath} ({zpath.stat().st_size / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
