#!/usr/bin/env python3
"""PC-side bridge: lets an agent drive the phone through adb without shell access to this PC.

The agent drops small JSON job files into <bridge>/jobs; this script runs the matching
whitelisted action and writes <bridge>/results/<id>.json. It never runs arbitrary commands:
only the actions below, with validated parameters, and only through the configured adb.

Folders (created on start, default C:\\Users\\<you>\\agent-bridge):
  jobs/     incoming job files        results/  one result file per job
  done/     processed job files       files/    downloaded APK, screenshots, pulled sessions
  heartbeat.json   updated every few seconds so the agent can see the bridge is alive

Job file example (jobs/job-001.json):
  {"id": "job-001", "action": "run_session", "params": {"interval_s": 15, "duration_s": 120}}

Actions: ping, devices, phone_info, unlock, install_latest, run_session, screenshot,
         logcat, pull_session
Only the Python standard library is used. Keep this folder OUT of any FTP server root.

Usage:  python agent_bridge.py [--bridge DIR] [--adb PATH] [--once]
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request
from datetime import datetime
from pathlib import Path

REPO = "rayroger/time-litert"
PKG = "com.yourname.watchreader"
ACTIVITY = ".TrainingCaptureActivity"
RELEASE_BASE = f"https://github.com/{REPO}/releases/download/latest-build"
PHONE_PICTURES = "/sdcard/Pictures/WatchReaderTrainingData"
PHONE_DOCUMENTS = "/sdcard/Documents/WatchReaderTrainingData"
ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
SESSION_RE = re.compile(r"^\d{8}_\d{6}$")
VERSION = "1"


class BridgeError(Exception):
    pass


def now():
    return datetime.now().astimezone().isoformat(timespec="seconds")


def find_adb(explicit):
    candidates = [explicit, os.environ.get("ADB"), shutil.which("adb")]
    candidates.append(str(Path.home() / "Downloads" /
                          "Tiny_ADB_and_Fastboot_v1.4.0_Portable" / "adb.exe"))
    for c in candidates:
        if c and Path(c).exists():
            return str(c)
    raise SystemExit("adb not found: pass --adb PATH or set the ADB environment variable")


class Bridge:
    def __init__(self, root, adb):
        self.root = Path(root)
        self.adb = adb
        self.jobs = self.root / "jobs"
        self.results = self.root / "results"
        self.done = self.root / "done"
        self.files = self.root / "files"
        for d in (self.jobs, self.results, self.done, self.files):
            d.mkdir(parents=True, exist_ok=True)

    # ---- helpers -------------------------------------------------------------------------
    def adb_run(self, *args, timeout=60, binary=False):
        proc = subprocess.run([self.adb, *args], capture_output=True, timeout=timeout)
        if binary:
            return proc.returncode, proc.stdout
        text = (proc.stdout + proc.stderr).decode("utf-8", errors="replace").strip()
        return proc.returncode, text

    @staticmethod
    def int_param(params, name, default, lo, hi):
        value = params.get(name, default)
        if isinstance(value, bool) or not isinstance(value, int) or not lo <= value <= hi:
            raise BridgeError(f"param {name} must be an integer in [{lo}, {hi}]")
        return value

    def require_device(self):
        rc, out = self.adb_run("get-state")
        if rc != 0 or "device" not in out:
            raise BridgeError(f"no phone available over adb: {out}")

    def write_json(self, path, data):
        tmp = path.with_suffix(path.suffix + ".tmp")
        tmp.write_text(json.dumps(data, indent=2), encoding="utf-8")
        os.replace(tmp, path)

    def heartbeat(self, state="idle", job=None):
        self.write_json(self.root / "heartbeat.json",
                        {"time": now(), "state": state, "job": job, "bridge_version": VERSION})

    # ---- actions -------------------------------------------------------------------------
    def a_ping(self, params, job_id):
        return {"time": now(), "adb": self.adb, "bridge_version": VERSION, "python": sys.version.split()[0]}

    def a_devices(self, params, job_id):
        return {"devices": self.adb_run("devices", "-l")[1]}

    def a_phone_info(self, params, job_id):
        self.require_device()
        info = {}
        info["sdk"] = self.adb_run("shell", "getprop", "ro.build.version.sdk")[1]
        info["model"] = self.adb_run("shell", "getprop", "ro.product.model")[1]
        info["battery"] = self.adb_run("shell", "dumpsys", "battery")[1][:600]
        power = self.adb_run("shell", "dumpsys", "power")[1]
        info["screen"] = [ln.strip() for ln in power.splitlines()
                          if "mWakefulness=" in ln or "Display Power" in ln][:3]
        rc, out = self.adb_run("shell", "pm", "list", "packages", PKG)
        info["app_installed"] = PKG in out
        rc, out = self.adb_run("shell", "dumpsys", "package", PKG)
        m = re.search(r"versionName=(\S+)", out)
        info["app_version"] = m.group(1) if m else None
        return info

    def a_unlock(self, params, job_id):
        self.require_device()
        out = [self.adb_run("shell", "input", "keyevent", "KEYCODE_WAKEUP")[1],
               self.adb_run("shell", "wm", "dismiss-keyguard")[1]]
        return {"output": [o for o in out if o]}

    def a_install_latest(self, params, job_id):
        self.require_device()
        allow_uninstall = params.get("allow_uninstall", False) is True
        apk = self.files / "app-debug.apk"
        urllib.request.urlretrieve(f"{RELEASE_BASE}/app-debug.apk", apk)
        expected = urllib.request.urlopen(f"{RELEASE_BASE}/app-debug.apk.sha256", timeout=60) \
            .read().decode().strip().lower()
        actual = hashlib.sha256(apk.read_bytes()).hexdigest()
        if expected != actual:
            raise BridgeError(f"checksum mismatch: expected {expected}, got {actual}")
        try:
            build = urllib.request.urlopen(f"{RELEASE_BASE}/version.txt", timeout=60).read().decode().strip()
        except Exception:
            build = None
        rc, out = self.adb_run("install", "-r", str(apk), timeout=180)
        uninstalled = False
        if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in out and allow_uninstall:
            self.adb_run("uninstall", PKG)
            uninstalled = True
            rc, out = self.adb_run("install", str(apk), timeout=180)
        if rc != 0 or "Success" not in out:
            raise BridgeError(f"install failed: {out}")
        self.adb_run("shell", "pm", "grant", PKG, "android.permission.CAMERA")
        return {"installed_build": build, "sha256": actual, "uninstalled_first": uninstalled,
                "note": "app settings (FTP upload config) are lost if uninstalled_first is true"}

    def a_run_session(self, params, job_id):
        interval = self.int_param(params, "interval_s", 15, 1, 300)
        duration = self.int_param(params, "duration_s", 120, 10, 6 * 3600)
        settle = self.int_param(params, "upload_wait_s", 60, 0, 600)
        self.require_device()
        self.adb_run("shell", "pm", "grant", PKG, "android.permission.CAMERA")
        self.adb_run("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        self.adb_run("shell", "wm", "dismiss-keyguard")
        self.adb_run("shell", "am", "force-stop", PKG)
        rc, out = self.adb_run(
            "shell", "am", "start", "-n", f"{PKG}/{ACTIVITY}",
            "--ez", "auto_start", "true",
            "--el", "interval_seconds", str(interval),
            "--el", "duration_seconds", str(duration))
        if rc != 0 or "Error" in out:
            raise BridgeError(f"could not start the app: {out}")
        started = now()
        deadline = time.time() + duration + settle
        while time.time() < deadline:
            self.heartbeat("run_session", job_id)
            self.write_json(self.results / f"{job_id}.running.json",
                            {"started": started, "ends_about": datetime.fromtimestamp(deadline).astimezone().isoformat(timespec="seconds")})
            time.sleep(min(5, max(0.0, deadline - time.time())))
        (self.results / f"{job_id}.running.json").unlink(missing_ok=True)
        rc, listing = self.adb_run("shell", "ls", PHONE_PICTURES)
        sessions = sorted(s for s in listing.split() if SESSION_RE.match(s))
        return {"started": started, "interval_s": interval, "duration_s": duration,
                "newest_session_on_phone": sessions[-1] if sessions else None,
                "log_tail": self.adb_run("logcat", "-d", "-t", "60", "TrainingCapture:V",
                                         "UploadWorker:V", "AndroidRuntime:E", "*:S")[1][-3000:]}

    def a_screenshot(self, params, job_id):
        self.require_device()
        rc, data = self.adb_run("exec-out", "screencap", "-p", binary=True, timeout=30)
        if rc != 0 or not data.startswith(b"\x89PNG"):
            raise BridgeError("screencap failed")
        out = self.files / f"screen_{job_id}.png"
        out.write_bytes(data)
        return {"file": str(out), "bytes": len(data)}

    def a_logcat(self, params, job_id):
        lines = self.int_param(params, "lines", 200, 1, 3000)
        self.require_device()
        rc, out = self.adb_run("logcat", "-d", "-t", str(lines), "TrainingCapture:V", "UploadWorker:V",
                               "AndroidRuntime:E", "*:S", timeout=60)
        return {"log": out[-20000:]}

    def a_pull_session(self, params, job_id):
        session = params.get("session")
        if not isinstance(session, str) or not SESSION_RE.match(session):
            raise BridgeError("param session must look like 20261101_141500")
        self.require_device()
        dest = self.files / "pull" / session
        dest.mkdir(parents=True, exist_ok=True)
        outputs = []
        for remote in (f"{PHONE_PICTURES}/{session}/.", f"{PHONE_DOCUMENTS}/{session}/."):
            outputs.append(self.adb_run("pull", remote, str(dest), timeout=600)[1][-300:])
        return {"dest": str(dest), "files": len(list(dest.iterdir())), "output": outputs}

    ACTIONS = {"ping", "devices", "phone_info", "unlock", "install_latest", "run_session",
               "screenshot", "logcat", "pull_session"}

    # ---- loop ----------------------------------------------------------------------------
    def process(self, job_path):
        started = now()
        try:
            job = json.loads(job_path.read_text(encoding="utf-8"))
            job_id, action = job.get("id"), job.get("action")
            params = job.get("params") or {}
            if not isinstance(job_id, str) or not ID_RE.match(job_id):
                raise BridgeError("job id must match [A-Za-z0-9_-]{1,64}")
            if action not in self.ACTIONS or not isinstance(params, dict):
                raise BridgeError(f"unknown action or bad params: {action}")
        except (ValueError, BridgeError) as e:
            job_id = re.sub(r"[^A-Za-z0-9_-]", "_", job_path.stem)[:64] or "bad-job"
            self.write_json(self.results / f"{job_id}.json",
                            {"status": "error", "error": str(e), "started": started, "finished": now()})
            return job_path
        self.heartbeat("working", job_id)
        try:
            data = getattr(self, f"a_{action}")(params, job_id)
            result = {"status": "ok", "action": action, "data": data}
        except subprocess.TimeoutExpired:
            result = {"status": "error", "action": action, "error": "command timed out"}
        except Exception as e:  # report any failure to the agent instead of crashing the bridge
            result = {"status": "error", "action": action, "error": f"{type(e).__name__}: {e}"}
        result.update({"id": job_id, "started": started, "finished": now()})
        self.write_json(self.results / f"{job_id}.json", result)
        return job_path

    def run(self, once=False):
        print(f"agent bridge running. folder: {self.root}  adb: {self.adb}")
        while True:
            self.heartbeat()
            pending = sorted(p for p in self.jobs.glob("*.json") if time.time() - p.stat().st_mtime > 2)
            for job_path in pending:
                done = self.process(job_path)
                shutil.move(str(done), str(self.done / done.name))
            if once and not pending:
                return
            time.sleep(2)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--bridge", default=str(Path.home() / "agent-bridge"))
    ap.add_argument("--adb")
    ap.add_argument("--once", action="store_true", help="process waiting jobs, then exit (for testing)")
    args = ap.parse_args()
    Bridge(args.bridge, find_adb(args.adb)).run(once=args.once)


if __name__ == "__main__":
    main()
