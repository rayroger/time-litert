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
    with open(path / "labels.csv", newline="") as f:
        for row in csv.DictReader(f):
            img = cv2.imread(str(path / "images" / row["file"]))
            if img is None:
                continue
            img = cv2.cvtColor(cv2.resize(img, (IMG, IMG), interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2RGB)
            split = "validation" if row["split"] == "validation" else "train"
            xs[split].append(img)
            ys[split].append(float(row["minutes_12h"]))
    return ({k: np.array(v, dtype=np.uint8) for k, v in xs.items()},
            {k: np.array(v, dtype=np.float64) for k, v in ys.items()})


def build_models(pretrained):
    import tensorflow as tf
    from tensorflow.keras import layers

    base = tf.keras.applications.MobileNetV3Small(
        input_shape=(IMG, IMG, 3), include_top=False, pooling="avg",
        weights="imagenet" if pretrained else None, include_preprocessing=True)
    dense = layers.Dense(128, activation="relu")
    drop = layers.Dropout(0.2)
    out = layers.Dense(4)
    aug = tf.keras.Sequential([
        layers.RandomTranslation(0.04, 0.04),
        layers.RandomZoom(0.08),
        layers.RandomBrightness(0.25, value_range=(0, 255)),
        layers.RandomContrast(0.25),
    ], name="augment")  # no rotation/flip: both would change the time shown

    inp = tf.keras.Input((IMG, IMG, 3), name="dial")
    train_model = tf.keras.Model(inp, out(drop(dense(base(aug(inp))))), name="clock_train")
    inp2 = tf.keras.Input((IMG, IMG, 3), name="dial")
    infer_model = tf.keras.Model(inp2, out(dense(base(inp2))), name="clock_infer")
    return train_model, infer_model, base


def score(infer_model, x, minutes):
    pred = infer_model.predict(x.astype("float32"), batch_size=64, verbose=0)
    err = circular_error_minutes(targets_to_time(pred), minutes)
    return {"n": int(len(err)), "mean_abs_error_min": float(err.mean()), "median_error_min": float(np.median(err)),
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
        data_info = {"source": "synthetic"}
    else:
        if not args.data:
            sys.exit("pass --data or --synthetic")
        xs, ys = load_folder(args.data)
        xt, tt, xv, tv = xs["train"], ys["train"], xs["validation"], ys["validation"]
        data_info = {"source": args.data}
    if args.synthetic_extra:
        xe, te = make_synthetic(args.synthetic_extra, seed=3)
        xt, tt = np.concatenate([xt, xe]), np.concatenate([tt, te])
    if len(xt) == 0 or len(xv) == 0:
        sys.exit(f"need train and validation images (got {len(xt)} / {len(xv)})")
    data_info.update({"train": int(len(xt)), "validation": int(len(xv))})
    print("data:", data_info, flush=True)

    train_model, infer_model, base = build_models(pretrained=not args.no_pretrained)
    if args.init_weights:
        infer_model.load_weights(args.init_weights)  # layers are shared with train_model
        print("continuing from", args.init_weights, flush=True)
    yt = time_to_targets(tt).astype("float32")
    yv = time_to_targets(tv).astype("float32")
    history = {}

    def fit(epochs, lr, trainable):
        base.trainable = trainable
        train_model.compile(tf.keras.optimizers.Adam(lr), loss="mse")
        h = train_model.fit(xt.astype("float32"), yt, validation_data=(xv.astype("float32"), yv),
                            epochs=epochs, batch_size=args.batch, verbose=2)
        for k, v in h.history.items():
            history.setdefault(k, []).extend(float(a) for a in v)

    frozen = min(3, max(1, args.epochs // 4))
    fit(frozen, 1e-3, False)
    if args.epochs > frozen:
        fit(args.epochs - frozen, 2e-4, True)

    metrics = {"data": data_info, "epochs": args.epochs,
               "validation": score(infer_model, xv, tv), "train": score(infer_model, xt[:1000], tt[:1000])}

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
