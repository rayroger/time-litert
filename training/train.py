#!/usr/bin/env python3
"""Train a small CNN that reads the time from a cropped analog watch dial and export LiteRT.

The model predicts [sin, cos] of the hour-hand and minute-hand angles (4 numbers). Time is
decoded from those angles and scored as the circular error in minutes on a 12 h dial.

Data: a folder (or .zip) with images/ and labels.csv (columns: file, split, minutes_12h), as
produced by training/build_dataset.py. Or --synthetic N for a pipeline smoke test.

Outputs in --out: model.tflite, metrics.json, history.json
"""
import argparse
import csv
import json
import os
import sys
import zipfile
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from clock_common import circular_error_minutes, targets_to_time, time_to_targets  # noqa: E402
from synthetic import make_synthetic  # noqa: E402

IMG = 224
CONSISTENCY_WEIGHT = 0.0  # default off: 0.3 made training diverge (the x12 amplifies gradients); see --consistency


def load_folder(path):
    path = Path(path)
    if path.suffix == ".zip":
        out = Path("/tmp/dataset_unzipped")
        out.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path) as z:
            z.extractall(out)
        path = out
    xs = {"train": [], "validation": []}
    ys = {"train": [], "validation": []}
    dials = {"train": [], "validation": []}
    with open(path / "labels.csv", newline="") as f:
        for row in csv.DictReader(f):
            img = cv2.imread(str(path / "images" / row["file"]))
            if img is None:
                continue
            img = cv2.cvtColor(cv2.resize(img, (IMG, IMG), interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2RGB)
            split = "validation" if row["split"] == "validation" else "train"
            xs[split].append(img)
            ys[split].append(float(row["minutes_12h"]))
            # watch id is "<session>_d<N>": N is the left-to-right position in the frame
            dials[split].append(int(row.get("watch", "_d0").rsplit("_d", 1)[-1] or 0))
    return ({k: np.array(v, dtype=np.uint8) for k, v in xs.items()},
            {k: np.array(v, dtype=np.float64) for k, v in ys.items()},
            {k: np.array(v, dtype=int) for k, v in dials.items()})


def build_models(pretrained, cond_hour=False):
    import tensorflow as tf
    from tensorflow.keras import layers

    base = tf.keras.applications.MobileNetV3Small(
        input_shape=(IMG, IMG, 3), include_top=False, pooling="avg",
        weights="imagenet" if pretrained else None, include_preprocessing=True)
    dense = layers.Dense(128, activation="relu")
    drop = layers.Dropout(0.2)
    if cond_hour:
        # Read the minute hand first, then predict the hour hand knowing that reading: the hour
        # hand's position is the hour mark plus minute/2 degrees, so the minute narrows it down.
        out_m, out_h = layers.Dense(2), layers.Dense(2)

        def head(f):
            m = out_m(f)
            h = out_h(layers.Concatenate()([f, m]))
            return layers.Concatenate()([h, m])  # [sin h, cos h, sin m, cos m]
    else:
        out = layers.Dense(4)

        def head(f):
            return out(f)
    aug = tf.keras.Sequential([
        layers.RandomTranslation(0.04, 0.04),
        layers.RandomZoom(0.08),
        layers.RandomBrightness(0.25, value_range=(0, 255)),
        layers.RandomContrast(0.25),
    ], name="augment")  # no rotation/flip: the targets are hand angles in the image frame (12 at the top), so either would make the labels wrong

    inp = tf.keras.Input((IMG, IMG, 3), name="dial")
    train_model = tf.keras.Model(inp, head(drop(dense(base(aug(inp))))), name="clock_train")
    inp2 = tf.keras.Input((IMG, IMG, 3), name="dial")
    infer_model = tf.keras.Model(inp2, head(dense(base(inp2))), name="clock_infer")
    return train_model, infer_model, base


def clock_loss(tf, weight):
    """MSE on [sin h, cos h, sin m, cos m] plus a consistency term.

    The hour hand's angle is 1/12 of a turn per minute-hand turn, so a valid reading satisfies
    (hour direction)^12 == minute direction. A model that swaps the two hands, or mistakes the
    long hand for the short one, breaks this and is penalised; the plain MSE alone treats the
    two hands independently."""
    def unit(a, b):  # (re, im) of a 2-vector, normalised
        n = tf.stop_gradient(tf.sqrt(tf.square(a) + tf.square(b)) + 1e-3)  # no gradient through the norm: it explodes near 0
        return a / n, b / n

    def cmul(p, q):
        return p[0] * q[0] - p[1] * q[1], p[0] * q[1] + p[1] * q[0]

    def loss(y_true, y_pred):
        mse = tf.reduce_mean(tf.square(y_true - y_pred), axis=-1)
        zh = unit(y_pred[:, 1], y_pred[:, 0])
        z2 = cmul(zh, zh)
        z4 = cmul(z2, z2)
        z12 = cmul(cmul(z4, z4), z4)
        zm = unit(y_pred[:, 3], y_pred[:, 2])
        cons = tf.square(z12[0] - zm[0]) + tf.square(z12[1] - zm[1])
        return mse + weight * cons
    return loss


def hand_diagnostics(pred, minutes):
    """How the big misses (> 30 min) look: is the hour-hand output following the true minute hand?"""
    err = circular_error_minutes(targets_to_time(pred), minutes)
    big = err > 30
    true = time_to_targets(minutes)
    hour_pred = np.rad2deg(np.arctan2(pred[:, 0], pred[:, 1]))
    min_true = np.rad2deg(np.arctan2(true[:, 2], true[:, 3]))
    d = np.abs((hour_pred - min_true + 180) % 360 - 180)
    return {"big_miss_fraction": float(big.mean()),
            "big_misses_hour_output_follows_minute_hand": float((d[big] < 25).mean()) if big.any() else 0.0}


def swap_fix(pred):
    """Inference-time check: keep the hand assignment (as predicted, or hour/minute outputs swapped)
    whose hour and minute angles agree better with the 12:1 relation. Returns the chosen predictions."""
    def resid(p):
        h = np.arctan2(p[:, 0], p[:, 1])
        m = np.arctan2(p[:, 2], p[:, 3])
        return np.abs((12 * h - m + np.pi) % (2 * np.pi) - np.pi)
    sw = pred[:, [2, 3, 0, 1]]
    use = resid(sw) < resid(pred)
    return np.where(use[:, None], sw, pred), float(use.mean())


def breakdown(pred, minutes, dials):
    """Where the errors are: per frame position, per minute-of-hour zone, per hour, and what kind of
    big miss it is (hour wrong but minute right, versus the minute reading itself wrong)."""
    err = circular_error_minutes(targets_to_time(pred), minutes)
    minute_err = circular_error_minutes(targets_to_time(pred) % 60.0, minutes % 60.0)
    mod = np.asarray(minutes) % 60.0

    def stats(mask):
        n = int(mask.sum())
        if n == 0:
            return {"n": 0}
        e = err[mask]
        return {"n": n, "mean": round(float(e.mean()), 1), "median": round(float(np.median(e)), 2),
                "big": round(float((e > 30).mean()), 3)}

    big = err > 30
    out = {"by_dial_position": {str(d): stats(dials == d) for d in sorted(set(dials.tolist()))},
           "by_minute_zone": {"near_hour_mark(<3|>57)": stats((mod < 3) | (mod > 57)),
                              "near_half(27-33)": stats((mod > 27) & (mod < 33)),
                              "elsewhere": stats(~((mod < 3) | (mod > 57)) & ~((mod > 27) & (mod < 33)))},
           "by_hour": {str(h): stats((np.asarray(minutes) // 60).astype(int) == h) for h in range(12)}}
    if big.any():
        out["big_miss_kinds"] = {
            "hour_wrong_minute_right": round(float((minute_err[big] < 5).mean()), 3),
            "minute_reading_wrong": round(float((minute_err[big] >= 5).mean()), 3)}
    return out


def score(infer_model, x, minutes, dials=None):
    pred = infer_model.predict(x.astype("float32"), batch_size=64, verbose=0)
    err = circular_error_minutes(targets_to_time(pred), minutes)
    extra = {"breakdown": breakdown(pred, minutes, dials)} if dials is not None else {}
    fixed, swapped = swap_fix(pred)
    ferr = circular_error_minutes(targets_to_time(fixed), minutes)
    extra["swap_fix"] = {"swapped_fraction": swapped, "mean_abs_error_min": float(ferr.mean()),
                         "median_error_min": float(np.median(ferr)), "within_5_min": float((ferr <= 5).mean()),
                         "big_miss_fraction": float((ferr > 30).mean())}
    return {**extra, **hand_diagnostics(pred, minutes), "n": int(len(err)), "mean_abs_error_min": float(err.mean()), "median_error_min": float(np.median(err)),
            "p90_error_min": float(np.percentile(err, 90)), "within_5_min": float((err <= 5).mean()),
            "within_1_min": float((err <= 1).mean())}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", help="folder or zip from build_dataset.py")
    ap.add_argument("--synthetic", type=int, default=0, help="use N synthetic clocks instead of --data")
    ap.add_argument("--synthetic-extra", type=int, default=0, help="add N synthetic clocks to the training set")
    ap.add_argument("--epochs", type=int, default=20)
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--no-pretrained", action="store_true")
    ap.add_argument("--consistency", type=float, default=CONSISTENCY_WEIGHT,
                    help="weight of the hour/minute hand consistency loss (0 = plain MSE)")
    ap.add_argument("--cond-hour", action="store_true",
                    help="predict the hour hand conditioned on the minute-hand reading")
    ap.add_argument("--init-weights", help="clock.weights.h5 from an earlier run: continue training from it "
                    "(fine-tune on new data) instead of starting from ImageNet")
    ap.add_argument("--out", default="out")
    args = ap.parse_args()

    import tensorflow as tf
    tf.keras.utils.set_random_seed(0)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    if args.synthetic:
        n_val = max(50, args.synthetic // 5)
        xt, tt = make_synthetic(args.synthetic, seed=1)
        xv, tv = make_synthetic(n_val, seed=2)
        dv = np.zeros(len(xv), dtype=int)
        data_info = {"source": "synthetic"}
    else:
        if not args.data:
            sys.exit("pass --data or --synthetic")
        xs, ys, dials = load_folder(args.data)
        xt, tt, xv, tv = xs["train"], ys["train"], xs["validation"], ys["validation"]
        dv = dials["validation"]
        data_info = {"source": args.data}
    if args.synthetic_extra:
        xe, te = make_synthetic(args.synthetic_extra, seed=3)
        xt, tt = np.concatenate([xt, xe]), np.concatenate([tt, te])
    if len(xt) == 0 or len(xv) == 0:
        sys.exit(f"need train and validation images (got {len(xt)} / {len(xv)})")
    data_info.update({"train": int(len(xt)), "validation": int(len(xv))})
    print("data:", data_info, flush=True)

    train_model, infer_model, base = build_models(pretrained=not args.no_pretrained, cond_hour=args.cond_hour)
    if args.init_weights:
        infer_model.load_weights(args.init_weights)  # layers are shared with train_model
        print("continuing from", args.init_weights, flush=True)
    yt = time_to_targets(tt).astype("float32")
    yv = time_to_targets(tv).astype("float32")
    history = {}

    def fit(epochs, lr, trainable):
        base.trainable = trainable
        train_model.compile(tf.keras.optimizers.Adam(lr), loss=clock_loss(tf, args.consistency))
        h = train_model.fit(xt.astype("float32"), yt, validation_data=(xv.astype("float32"), yv),
                            epochs=epochs, batch_size=args.batch, verbose=2)
        for k, v in h.history.items():
            history.setdefault(k, []).extend(float(a) for a in v)

    frozen = min(3, max(1, args.epochs // 4))
    fit(frozen, 1e-3, False)
    if args.epochs > frozen:
        fit(args.epochs - frozen, 2e-4, True)

    metrics = {"data": data_info, "epochs": args.epochs,
               "validation": score(infer_model, xv, tv, dv), "train": score(infer_model, xt[:1000], tt[:1000])}

    converter = tf.lite.TFLiteConverter.from_keras_model(infer_model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]
    tflite = converter.convert()
    (out / "model.tflite").write_bytes(tflite)
    infer_model.save_weights(out / "clock.weights.h5")

    # Sanity check: the exported model must agree with the Keras model on validation images.
    interp = tf.lite.Interpreter(model_content=tflite)
    interp.allocate_tensors()
    i_in, i_out = interp.get_input_details()[0], interp.get_output_details()[0]
    k = min(50, len(xv))
    preds = []
    for img in xv[:k]:
        interp.set_tensor(i_in["index"], img[None].astype("float32"))
        interp.invoke()
        preds.append(interp.get_tensor(i_out["index"])[0])
    tfl_err = circular_error_minutes(targets_to_time(np.array(preds)), tv[:k])
    metrics["tflite"] = {"bytes": len(tflite), "checked": k, "mean_abs_error_min": float(tfl_err.mean()),
                         "input": [int(d) for d in i_in["shape"]], "output": [int(d) for d in i_out["shape"]]}

    (out / "metrics.json").write_text(json.dumps(metrics, indent=2))
    (out / "history.json").write_text(json.dumps(history, indent=2))
    print("METRICS " + json.dumps(metrics), flush=True)


if __name__ == "__main__":
    main()
