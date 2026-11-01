"""Shared helpers: time <-> hand angles, decoding, and error in minutes."""
import numpy as np


def time_to_targets(minutes_of_12h):
    """Continuous time in minutes on a 12 h dial (0..720) -> [sin h, cos h, sin m, cos m]."""
    t = np.asarray(minutes_of_12h, dtype=np.float64) % 720.0
    hour_ang = np.deg2rad(t / 720.0 * 360.0)          # hour hand: one turn per 12 h
    min_ang = np.deg2rad((t % 60.0) / 60.0 * 360.0)   # minute hand: one turn per hour
    return np.stack([np.sin(hour_ang), np.cos(hour_ang), np.sin(min_ang), np.cos(min_ang)], axis=-1)


def targets_to_time(y):
    """Inverse of time_to_targets for (possibly noisy) predictions. Returns minutes in 0..720."""
    y = np.asarray(y, dtype=np.float64)
    hour_deg = np.rad2deg(np.arctan2(y[..., 0], y[..., 1])) % 360.0
    min_deg = np.rad2deg(np.arctan2(y[..., 2], y[..., 3])) % 360.0
    minute = min_deg / 6.0                      # 0..60 from the minute hand
    hour_est = hour_deg / 30.0                  # 0..12 from the hour hand
    hour = np.round(hour_est - minute / 60.0) % 12.0  # whole hour that agrees with the minute hand
    return hour * 60.0 + minute


def circular_error_minutes(pred_minutes, true_minutes):
    d = np.abs(np.asarray(pred_minutes) - np.asarray(true_minutes)) % 720.0
    return np.minimum(d, 720.0 - d)
