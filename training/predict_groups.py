#!/usr/bin/env python3
"""Run a trained model.tflite over every crop of a dataset zip and report the error per physical watch.

Watch identity is not stored in the data (only session + left-to-right position, and the layout may
change between sessions), so each session/position group is clustered by dial colour (HSV histogram)
into --clusters watch identities. Output: predictions.csv (one row per crop) and summary.json with
mean/median error and big-miss rate per cluster, split and calendar day. Prints a GROUPS line.
"""
import argparse
import csv
import json
import sys
import zipfile
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from clock_common import circular_error_minutes, targets_to_time  # noqa: E402

IMG = 224


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--model", required=True)
    ap.add_argument("--clusters", type=int, default=6)
    ap.add_argument("--out", default="out_groups")
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    root = Path("/tmp/ds")
    root.mkdir(exist_ok=True)
    with zipfile.ZipFile(a.data) as z:
        z.extractall(root)
    rows = list(csv.DictReader(open(root / "labels.csv", newline="")))

    import tensorflow as tf
    interp = tf.lite.Interpreter(model_path=a.model)
    interp.allocate_tensors()
    inp, outp = interp.get_input_details()[0], interp.get_output_details()[0]

    feats = defaultdict(list)
    preds = np.zeros((len(rows), 4), dtype=np.float64)
    for i, r in enumerate(rows):
        im = cv2.imread(str(root / "images" / r["file"]))
        im = cv2.resize(im, (IMG, IMG), interpolation=cv2.INTER_AREA)
        x = cv2.cvtColor(im, cv2.COLOR_BGR2RGB).astype("float32")[None]
        interp.set_tensor(inp["index"], x)
        interp.invoke()
        preds[i] = interp.get_tensor(outp["index"])[0][:4]
        hsv = cv2.cvtColor(im[40:184, 40:184], cv2.COLOR_BGR2HSV)
        h = cv2.calcHist([hsv], [0, 1, 2], None, [12, 4, 4], [0, 180, 0, 256, 0, 256]).flatten()
        feats[r["watch"]].append(h / h.sum())
    minutes = np.array([float(r["minutes_12h"]) for r in rows])
    err = circular_error_minutes(targets_to_time(preds), minutes)

    groups = sorted(feats)
    G = np.array([np.mean(feats[g], axis=0) for g in groups], dtype=np.float32)
    G = np.sqrt(G)  # Hellinger-style, makes histogram distances better behaved
    crit = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 100, 1e-4)
    _, lab, cen = cv2.kmeans(G, a.clusters, None, crit, 10, cv2.KMEANS_PP_CENTERS)
    cluster = {g: int(l) for g, l in zip(groups, lab.flatten())}

    with open(out / "predictions.csv", "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["file", "watch", "cluster", "split", "timestamp", "minutes_12h", "err_min"])
        for r, e in zip(rows, err):
            w.writerow([r["file"], r["watch"], cluster[r["watch"]], r["split"], r["timestamp"], r["minutes_12h"], round(float(e), 3)])

    def stats(mask):
        e = err[mask]
        if not len(e):
            return None
        return {"n": int(len(e)), "mean": round(float(e.mean()), 2), "median": round(float(np.median(e)), 2),
                "big": round(float((e > 30).mean()), 3)}

    cl = np.array([cluster[r["watch"]] for r in rows])
    split = np.array([r["split"] for r in rows])
    day = np.array([datetime.fromtimestamp(float(r["timestamp"]), timezone.utc).strftime("%m-%d") for r in rows])
    summary = {"clusters": a.clusters, "groups_per_cluster": {str(c): int((np.array(list(cluster.values())) == c).sum()) for c in range(a.clusters)},
               "by_cluster_split": {}, "by_cluster_day_validation": {}}
    for c in range(a.clusters):
        summary["by_cluster_split"][str(c)] = {s: stats((cl == c) & (split == s)) for s in ("train", "validation")}
        summary["by_cluster_day_validation"][str(c)] = {d: stats((cl == c) & (day == d) & (split == "validation"))
                                                        for d in sorted(set(day.tolist()))}
    # first appearance (day) of every cluster, to spot the late-added watch
    summary["first_day"] = {str(c): min(day[cl == c].tolist()) for c in range(a.clusters) if (cl == c).any()}
    # signed error per session/position group: a constant offset means the watch was not set right (label noise)
    pm = targets_to_time(preds)
    signed = (pm - minutes + 360.0) % 720.0 - 360.0
    byg = defaultdict(list)
    for r, e in zip(rows, signed):
        byg[r["watch"]].append(float(e))
    for g in groups:
        v = np.array(byg[g])
        print("GROUPERR %s c%d n=%d mean_abs=%.1f median_signed=%.1f frac_gt5=%.2f" % (
            g, cluster[g], len(v), np.abs(v).mean(), np.median(v), (np.abs(v) > 5).mean()))
    flagged = {g for g in groups if (np.abs(np.array(byg[g])) > 5).mean() > 0.3}
    for r, e in zip(rows, signed):
        if r["watch"] in flagged:
            print("CROP %s %s %.0f %.1f" % (r["file"], r["watch"], float(r["timestamp"]), e))
    (out / "summary.json").write_text(json.dumps(summary, indent=1))
    print("GROUPS " + json.dumps(summary))


if __name__ == "__main__":
    main()
