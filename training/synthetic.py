"""Procedurally drawn analog clock faces with exact labels (pipeline smoke test / pretraining)."""
import cv2
import numpy as np


def draw_clock(rng, minutes_of_12h, size=224):
    s = size
    bg = int(rng.integers(20, 235))
    img = np.full((s, s, 3), bg, np.uint8)
    img += rng.integers(0, 12, img.shape, dtype=np.uint8)
    c = (s // 2 + int(rng.integers(-6, 7)), s // 2 + int(rng.integers(-6, 7)))
    r = int(s * rng.uniform(0.38, 0.47))
    face = int(rng.integers(0, 256, dtype=np.int32))
    ink = 255 - face if abs(face - 128) > 40 else int(rng.choice([0, 255]))
    cv2.circle(img, c, r, (face, face, face), -1)
    cv2.circle(img, c, r, (ink, ink, ink), int(rng.integers(2, 6)))
    for k in range(12):
        a = np.deg2rad(k * 30)
        p1 = (int(c[0] + np.sin(a) * r * 0.86), int(c[1] - np.cos(a) * r * 0.86))
        p2 = (int(c[0] + np.sin(a) * r * 0.96), int(c[1] - np.cos(a) * r * 0.96))
        cv2.line(img, p1, p2, (ink, ink, ink), 2)
    t = minutes_of_12h % 720
    ha = np.deg2rad(t / 720 * 360)
    ma = np.deg2rad((t % 60) / 60 * 360)
    hl, ml = r * rng.uniform(0.45, 0.55), r * rng.uniform(0.7, 0.85)
    for ang, ln, w in ((ha, hl, int(rng.integers(4, 8))), (ma, ml, int(rng.integers(2, 5)))):
        end = (int(c[0] + np.sin(ang) * ln), int(c[1] - np.cos(ang) * ln))
        cv2.line(img, c, end, (ink, ink, ink), w)
    img = cv2.GaussianBlur(img, (3, 3), float(rng.uniform(0.1, 1.2)))
    return img


def make_synthetic(n, seed=0, size=224):
    rng = np.random.default_rng(seed)
    times = rng.uniform(0, 720, n)
    imgs = np.stack([draw_clock(rng, t, size) for t in times])
    return imgs, times
