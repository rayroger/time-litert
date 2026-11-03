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
    # Hour hand is clearly shorter than the minute hand, but by a varying ratio (real watches differ);
    # width varies too, and sometimes the hour hand is not the thicker one.
    ml = r * rng.uniform(0.68, 0.9)
    hl = ml * rng.uniform(0.5, 0.78)
    hw, mw = int(rng.integers(3, 9)), int(rng.integers(2, 6))
    for ang, ln, w in ((ha, hl, hw), (ma, ml, mw)):
        end = (int(c[0] + np.sin(ang) * ln), int(c[1] - np.cos(ang) * ln))
        cv2.line(img, c, end, (ink, ink, ink), w)
    # Distractor hands that real watches have: a thin long seconds hand and sometimes a coloured
    # GMT-style hand with a triangle tip. They must not be mistaken for the hour/minute hands.
    if rng.random() < 0.7:
        sa = rng.uniform(0, 2 * np.pi)
        sl = r * rng.uniform(0.75, 0.95)
        end = (int(c[0] + np.sin(sa) * sl), int(c[1] - np.cos(sa) * sl))
        cv2.line(img, c, end, (40, 40, 220), 1)
    if rng.random() < 0.25:
        ga = rng.uniform(0, 2 * np.pi)
        gl = r * rng.uniform(0.55, 0.7)
        end = (int(c[0] + np.sin(ga) * gl), int(c[1] - np.cos(ga) * gl))
        cv2.line(img, c, end, (30, 30, 200), 2)
        cv2.circle(img, end, int(r * 0.06), (30, 30, 200), -1)
    img = cv2.GaussianBlur(img, (3, 3), float(rng.uniform(0.1, 1.2)))
    return img


def make_synthetic(n, seed=0, size=224):
    rng = np.random.default_rng(seed)
    times = rng.uniform(0, 720, n)
    imgs = np.stack([draw_clock(rng, t, size) for t in times])
    return imgs, times
