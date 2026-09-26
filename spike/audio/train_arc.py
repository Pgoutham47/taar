"""
Train Taar's arc model on the phone's own recordings and export it to TFLite.

Recordings are named  <label>_<session>_<anything>.wav  -- arc_s1_10cm.wav,
notarc_s2_30cm.wav, real_s1_room.wav. `arc` is the positive class; `notarc` and
`real` are both negative.

The number that matters is the held-out one. Clips from one session share a room,
a speaker position and a background, so a random split leaks all three and
reports an accuracy the demo will not reproduce. Here the model is trained on
every session but one and tested on the one it has never heard, and the rule the
app already runs is scored on exactly the same captures beside it -- the model has
to beat that to be worth shipping. The exported model is then retrained on every
session.

Run:  .venv-train/bin/python spike/audio/train_arc.py --data ~/Downloads/taar_recordings
"""

import argparse
import csv
import json
import os
import re
from pathlib import Path

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

import numpy as np
import tensorflow as tf
from scipy.io import wavfile

import arcfeatures as F

OUT = Path(__file__).parent / "model"
TRIM_S = 2.0          # taps on the record button at either end
TRAIN_HOP_S = 1.0     # overlapping 3 s captures for training
TEST_HOP_S = 3.0      # disjoint 3 s captures for scoring, like pressing Measure
RULE_FALSE_ALARM = 0.05
NAME = re.compile(r"^(arc|notarc|real)_(s\d+)_.*\.wav$")


def load(data_dir):
    recs = []
    for p in sorted(Path(data_dir).expanduser().glob("*.wav")):
        m = NAME.match(p.name)
        if not m:
            print(f"  skipped {p.name}: name is not <label>_<session>_...")
            continue
        sr, x = wavfile.read(p)
        if x.ndim > 1:
            x = x.mean(axis=1)
        x = x.astype(np.float64) / (32768.0 if x.dtype == np.int16 else 1.0)
        trim = int(TRIM_S * sr)
        recs.append(dict(file=p.name, kind=m.group(1), session=m.group(2),
                         label=int(m.group(1) == "arc"), sr=sr, audio=x[trim:-trim]))
    return recs


def windows(rec, hop_s):
    n, hop = int(F.CAPTURE_S * rec["sr"]), int(hop_s * rec["sr"])
    return [rec["audio"][i:i + n] for i in range(0, len(rec["audio"]) - n + 1, hop)]


def dataset(recs, hop_s):
    X, y, src = [], [], []
    for r in recs:
        for w in windows(r, hop_s):
            X.append(F.features(w, r["sr"]))
            y.append(r["label"])
            src.append(r["file"])
    return np.array(X), np.array(y, dtype=np.float32), np.array(src)


def build(X_train, seed):
    tf.keras.utils.set_random_seed(seed)
    norm = tf.keras.layers.Normalization()
    norm.adapt(X_train)  # stored in the model: the phone passes raw features
    model = tf.keras.Sequential([
        tf.keras.Input((F.N_FEATURES,)),
        norm,
        tf.keras.layers.Dense(32, activation="relu"),
        tf.keras.layers.Dropout(0.2),
        tf.keras.layers.Dense(16, activation="relu"),
        tf.keras.layers.Dense(1, activation="sigmoid"),
    ])
    model.compile(optimizer=tf.keras.optimizers.Adam(1e-3), loss="binary_crossentropy")
    return model


def fit(X, y, seed, epochs):
    model = build(X, seed)
    pos = max(y.mean(), 1e-6)
    model.fit(X, y, epochs=epochs, batch_size=32, verbose=0,
              class_weight={0: 0.5 / (1 - pos), 1: 0.5 / pos})
    return model


def rule_threshold(X, y):
    """The app's rule: MI above the room's own baseline at a 5% false-alarm point."""
    mi = 10 ** X[y == 0, -1]
    return float(np.quantile(mi, 1 - RULE_FALSE_ALARM))


def score(pred, y):
    tp = int(((pred == 1) & (y == 1)).sum()); fn = int(((pred == 0) & (y == 1)).sum())
    fp = int(((pred == 1) & (y == 0)).sum()); tn = int(((pred == 0) & (y == 0)).sum())
    return dict(accuracy=(tp + tn) / max(len(y), 1), detection=tp / max(tp + fn, 1),
                false_alarm=fp / max(fp + tn, 1), tp=tp, fn=fn, fp=fp, tn=tn)


def evaluate(recs, seed, epochs):
    sessions = sorted({r["session"] for r in recs})
    if len(sessions) < 2:
        print("only one session: no held-out score is possible\n")
        return []
    results = []
    for held in sessions:
        train = [r for r in recs if r["session"] != held]
        test = [r for r in recs if r["session"] == held]
        if len({r["label"] for r in test}) < 2 or len({r["label"] for r in train}) < 2:
            continue
        Xtr, ytr, _ = dataset(train, TRAIN_HOP_S)
        Xte, yte, src = dataset(test, TEST_HOP_S)
        model = fit(Xtr, ytr, seed, epochs)
        p_model = (model.predict(Xte, verbose=0)[:, 0] >= 0.5).astype(int)
        # The rule's threshold comes from the training sessions' negatives only.
        p_rule = (10 ** Xte[:, -1] > rule_threshold(Xtr, ytr)).astype(int)
        m, r = score(p_model, yte), score(p_rule, yte)
        print(f"held-out {held}: trained on {', '.join(s for s in sessions if s != held)}, "
              f"{len(yte)} captures of 3 s")
        print(f"  {'':<22}{'model':>8}{'rule':>8}")
        for k in ("accuracy", "detection", "false_alarm"):
            print(f"  {k:<22}{m[k]:>8.1%}{r[k]:>8.1%}")
        print(f"  {'per file':<22}{'model':>8}{'rule':>8}   (share called arc)")
        for f in sorted(set(src)):
            sel = src == f
            print(f"    {f:<20}{p_model[sel].mean():>8.0%}{p_rule[sel].mean():>8.0%}")
        print()
        results.append(dict(held_out=held, model=m, rule=r))
    return results


def export(model, X_sample):
    try:
        tflite = tf.lite.TFLiteConverter.from_keras_model(model).convert()
    except Exception:
        fn = tf.function(lambda x: model(x, training=False)).get_concrete_function(
            tf.TensorSpec([1, F.N_FEATURES], tf.float32))
        tflite = tf.lite.TFLiteConverter.from_concrete_functions([fn], model).convert()

    # The file must agree with the Keras model it came from before it goes anywhere.
    interp = tf.lite.Interpreter(model_content=tflite)
    interp.allocate_tensors()
    i, o = interp.get_input_details()[0], interp.get_output_details()[0]
    got = []
    for x in X_sample:
        interp.set_tensor(i["index"], x[None, :].astype(np.float32))
        interp.invoke()
        got.append(float(interp.get_tensor(o["index"])[0, 0]))
    want = model.predict(X_sample, verbose=0)[:, 0]
    worst = float(np.max(np.abs(np.array(got) - want)))
    if worst > 1e-4:
        raise SystemExit(f"TFLite disagrees with Keras by {worst:.2e}")
    return tflite, np.array(got), worst


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="~/Downloads/taar_recordings")
    ap.add_argument("--epochs", type=int, default=60)
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()

    recs = load(args.data)
    print(f"{len(recs)} recordings, sessions {sorted({r['session'] for r in recs})}\n")
    results = evaluate(recs, args.seed, args.epochs)

    X, y, src = dataset(recs, TRAIN_HOP_S)
    model = fit(X, y, args.seed, args.epochs)
    OUT.mkdir(exist_ok=True)

    # Golden captures for the Kotlin port: features and the expected output, so a
    # test on the phone side can prove both the feature code and the model load.
    Xg, yg, srcg = dataset(recs, TEST_HOP_S)
    pick = np.concatenate([np.where(srcg == f)[0][:2] for f in sorted(set(srcg))])
    tflite, p_golden, worst = export(model, Xg[pick])
    (OUT / "taar_arc.tflite").write_bytes(tflite)

    with open(OUT / "golden_features.csv", "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["file", "label", "p_arc"] + [f"f{k}" for k in range(F.N_FEATURES)])
        for k, idx in enumerate(pick):
            w.writerow([srcg[idx], int(yg[idx]), f"{p_golden[k]:.6f}"] +
                       [f"{v:.7g}" for v in Xg[idx]])

    card = dict(
        model="taar_arc.tflite", input=[1, F.N_FEATURES], output="p(arc), threshold 0.5",
        features=dict(capture_s=F.CAPTURE_S, sample_rate=F.SR, order="ENV(76) SHAPE(12) MI(1)",
                      transform="log10(fraction + 1e-6)", source="spike/audio/arcfeatures.py"),
        trained_on=sorted({r["file"] for r in recs}), train_captures=int(len(y)),
        held_out=results, tflite_vs_keras_max_abs=worst,
        caveat="Arc class is synthetic (make_playback.py) played through a speaker "
               "and recorded on the phone. Not-arc is the same speaker plus real rooms.")
    (OUT / "model_card.json").write_text(json.dumps(card, indent=2))

    export_to_app(recs, tflite, OUT / "golden_features.csv")

    print(f"exported {OUT / 'taar_arc.tflite'}  ({len(tflite) / 1024:.1f} KB), "
          f"trained on all {len(y)} captures")
    print(f"TFLite matches Keras to {worst:.1e} on {len(pick)} golden captures")
    print(f"wrote golden_features.csv and model_card.json")
    print(f"copied the model and fixtures into {ANDROID}")


ANDROID = Path(__file__).resolve().parents[1] / "android"


def export_to_app(recs, tflite, golden_csv):
    """
    Puts the model in the app, and gives the Kotlin port something to be checked
    against: one real 3 s capture per kind as a WAV, with the features this file
    computes from it. The JVM test proves ArcFeatures.kt reproduces them; the
    phone's self-check proves the runtime reproduces the model's outputs.
    """
    assets = ANDROID / "app" / "src" / "main" / "assets"
    golden = ANDROID / "golden" / "arc"
    assets.mkdir(parents=True, exist_ok=True)
    golden.mkdir(parents=True, exist_ok=True)
    (assets / "taar_arc.tflite").write_bytes(tflite)
    (assets / "arc_selfcheck.csv").write_text(golden_csv.read_text())

    rows = []
    for kind in ("arc", "notarc", "real"):
        rec = next(r for r in recs if r["kind"] == kind)
        clip = windows(rec, TEST_HOP_S)[0]
        name = f"{kind}.wav"
        # The recordings are 16-bit, so this round-trips the capture exactly.
        wavfile.write(golden / name, rec["sr"], np.round(clip * 32768).astype(np.int16))
        rows.append([name, rec["file"]] + [f"{v:.9g}" for v in F.features(clip, rec["sr"])])
    with open(golden / "arc_features.csv", "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["clip", "source"] + [f"f{k}" for k in range(F.N_FEATURES)])
        w.writerows(rows)


if __name__ == "__main__":
    main()
