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
         logcat, pull_session, app_state, swipe, key, publish_release_asset

publish_release_asset uploads one file from <bridge>/files to a GitHub release of REPO (tag
dataset-* or model-*), replacing an asset of the same name. It needs a fine-grained token
limited to this repo (Contents: read/write) in the GITHUB_TOKEN environment variable, set
before starting the bridge:   set GITHUB_TOKEN=github_pat_...   then   python agent_bridge.py
The token is never written to results or logs.

Optional param delete_sessions (list of session folder names like 20261101_192858): after the
upload succeeded, those folders are removed from the FTP data folder given with --data. Sessions
touched in the last 15 minutes (still receiving uploads) are skipped.
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
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
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
LOG_MARKERS = ("TrainingCapture", "UploadWorker", "WorkManager", "AndroidRuntime", "FATAL", PKG)
ASSET_RE = re.compile(r"^[A-Za-z0-9._-]{1,100}$")
TAG_RE = re.compile(r"^(dataset|model)-[A-Za-z0-9._-]{1,40}$")
ASSET_SUFFIXES = (".zip", ".json", ".csv", ".tflite")
KEYS = {"BACK": "KEYCODE_BACK", "HOME": "KEYCODE_HOME", "WAKEUP": "KEYCODE_WAKEUP"}
VERSION = "7"


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
        self.finished = set()  # job files already processed but not yet moved to done/
        self.data_dir = None  # FTP server data folder (--data), needed for delete_sessions
        self.api_base = "https://api.github.com"
        self.upload_base = "https://uploads.github.com"
        self.jobs = self.root / "jobs"
        self.results = self.root / "results"
        self.done = self.root / "done"
        self.files = self.root / "files"
        for d in (self.jobs, self.results, self.done, self.files):
            d.mkdir(parents=True, exist_ok=True)

    # ---- helpers -------------------------------------------------------------------------
    GLOBAL_ADB = {"devices", "connect", "disconnect", "start-server", "kill-server", "pair"}

    def pick_serial(self):
        """Which adb transport to use. USB and Wi-Fi may both be present: prefer USB (faster, more
        reliable), else Wi-Fi. If nothing is attached but a Wi-Fi address was saved earlier, reconnect to it."""
        cached = getattr(self, "_serial_cache", None)
        if cached and time.time() - cached[0] < 8:
            return cached[1]
        def attached():
            out = subprocess.run([self.adb, "devices"], capture_output=True, timeout=30).stdout.decode("utf-8", "replace")
            return [ln.split()[0] for ln in out.splitlines()[1:] if ln.strip().endswith("device")]
        devs = attached()
        wifi_file = self.root / "wifi_target.txt"
        if not devs and wifi_file.exists():
            target = wifi_file.read_text().strip()
            if target:
                subprocess.run([self.adb, "connect", target], capture_output=True, timeout=20)
                devs = attached()
        usb = [d for d in devs if ":" not in d]
        wifi = [d for d in devs if ":" in d]
        serial = (usb or wifi or [None])[0]
        self._serial_cache = (time.time(), serial)
        return serial

    def adb_run(self, *args, timeout=60, binary=False):
        cmd = [self.adb]
        if args and args[0] not in self.GLOBAL_ADB:
            serial = self.pick_serial()
            if serial:
                cmd += ["-s", serial]
        proc = subprocess.run([*cmd, *args], capture_output=True, timeout=timeout)
        if binary:
            return proc.returncode, proc.stdout
        text = (proc.stdout + proc.stderr).decode("utf-8", errors="replace").strip()
        return proc.returncode, text

    def app_log(self, lines, everything=False):
        """Recent logcat lines; filtered in Python (adb tag filters returned nothing on this device)."""
        rc, out = self.adb_run("logcat", "-d", "-t", str(lines if everything else lines * 8), timeout=60)
        rows = out.splitlines()
        if not everything:
            rows = [ln for ln in rows if any(m in ln for m in LOG_MARKERS)]
        return "\n".join(rows[-lines:])

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

    def write_json(self, path, data, attempts=10):
        """Atomic write. On Windows os.replace raises PermissionError while another process (the
        agent reading the file, antivirus, the indexer) has the target open, so retry for a while."""
        tmp = path.with_suffix(path.suffix + ".tmp")
        tmp.write_text(json.dumps(data, indent=2), encoding="utf-8")
        for i in range(attempts):
            try:
                os.replace(tmp, path)
                return
            except PermissionError:
                if i == attempts - 1:
                    raise
                time.sleep(0.2 * (i + 1))

    def heartbeat(self, state="idle", job=None):
        # The heartbeat is a convenience: never let a failed write take the bridge down.
        try:
            self.write_json(self.root / "heartbeat.json",
                            {"time": now(), "state": state, "job": job, "bridge_version": VERSION})
        except OSError as e:
            print(f"heartbeat not written ({e}); continuing")

    # ---- actions -------------------------------------------------------------------------
    def a_ping(self, params, job_id):
        return {"time": now(), "adb": self.adb, "bridge_version": VERSION, "python": sys.version.split()[0],
                "github_token_set": bool(self.github_token())}

    def a_devices(self, params, job_id):
        self._serial_cache = None
        return {"devices": self.adb_run("devices", "-l")[1], "using": self.pick_serial()}

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
        samples, gaps = [], []
        last_tick, next_sample = time.time(), 0.0
        while time.time() < deadline:
            tick = time.time()
            if tick - last_tick > 30:  # the loop normally ticks every 5 s: a long gap means the PC slept or stalled
                gaps.append({"at": now(), "seconds": int(tick - last_tick)})
            last_tick = tick
            self.heartbeat("run_session", job_id)
            if tick >= next_sample:
                next_sample = tick + 300
                samples.append(self.status_sample())
            try:
                self.write_json(self.results / f"{job_id}.running.json",
                                {"started": started, "ends_about": datetime.fromtimestamp(deadline).astimezone().isoformat(timespec="seconds"),
                                 "last_sample": samples[-1] if samples else None, "pc_gaps": gaps})
            except OSError:
                pass
            time.sleep(min(5, max(0.0, deadline - time.time())))
        samples.append(self.status_sample())
        (self.results / f"{job_id}.running.json").unlink(missing_ok=True)
        rc, listing = self.adb_run("shell", "ls", PHONE_PICTURES)
        sessions = sorted(s for s in listing.split() if SESSION_RE.match(s))
        return {"started": started, "interval_s": interval, "duration_s": duration,
                "newest_session_on_phone": sessions[-1] if sessions else None,
                "status_samples": samples, "pc_gaps": gaps,
                "log_tail": self.app_log(60)[-3000:]}

    def status_sample(self):
        """Phone battery, whether the app process is alive and which window has focus (never raises)."""
        sample = {"time": now()}
        try:
            rc, out = self.adb_run("shell", "dumpsys", "battery", timeout=20)
            for ln in out.splitlines():
                key, _, val = ln.strip().partition(": ")
                if key in ("level", "status", "USB powered", "AC powered"):
                    sample[key] = val
            rc, out = self.adb_run("shell", "pidof", PKG, timeout=20)
            sample["app_running"] = bool(out.strip()) and rc == 0
            rc, out = self.adb_run("shell", "dumpsys", "window", "|", "grep", "mCurrentFocus", timeout=20)
            sample["focus"] = out.strip()[-120:]
        except Exception as e:  # noqa: BLE001
            sample["error"] = str(e)[:120]
        return sample

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
        everything = params.get("all", False) is True
        self.require_device()
        return {"log": self.app_log(lines, everything)[-20000:]}

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

    def a_app_state(self, params, job_id):
        """Upload settings (secrets redacted) and how many files wait in the phone's upload queue."""
        self.require_device()
        rc, text = self.adb_run("shell", "run-as", PKG, "cat", "shared_prefs/upload_settings.xml")
        settings = {}
        try:
            for el in ET.fromstring(text.replace("\r", "")):
                name = el.get("name", "")
                value = el.get("value") if el.get("value") is not None else (el.text or "")
                settings[name] = "<set>" if ("password" in name or "key" in name) and value else value
        except ET.ParseError:
            settings = {"error": text[:300]}
        rc, out = self.adb_run("shell", "run-as", PKG, "find", "files/upload_queue", "-type", "f")
        queued = [ln for ln in out.splitlines() if ln.strip().endswith((".jpg", ".json"))]
        return {"upload_settings": settings, "queued_files": len(queued),
                "queue_sample": queued[:5], "recent_log": self.app_log(25)}

    def a_swipe(self, params, job_id):
        x1, y1, x2, y2 = (self.int_param(params, k, 0, 0, 5000) for k in ("x1", "y1", "x2", "y2"))
        ms = self.int_param(params, "duration_ms", 300, 50, 3000)
        self.require_device()
        return {"output": self.adb_run("shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(ms))[1]}

    def a_key(self, params, job_id):
        name = params.get("name")
        if name not in KEYS:
            raise BridgeError(f"param name must be one of {sorted(KEYS)}")
        self.require_device()
        return {"output": self.adb_run("shell", "input", "keyevent", KEYS[name])[1]}

    @staticmethod
    def github_token():
        return os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")

    def github_json(self, method, path, payload=None, allow_404=False):
        data = json.dumps(payload).encode() if payload is not None else None
        req = urllib.request.Request(self.api_base + path, data=data, method=method, headers={
            "Authorization": f"Bearer {self.github_token()}", "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28", "Content-Type": "application/json", "User-Agent": "agent-bridge"})
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                body = resp.read()
                return json.loads(body) if body else {}
        except urllib.error.HTTPError as e:
            if e.code == 404 and allow_404:
                return None
            raise BridgeError(f"GitHub {method} {path} failed: HTTP {e.code} {e.read()[:200].decode('utf-8', 'replace')}")

    def a_publish_release_asset(self, params, job_id):
        if not self.github_token():
            raise BridgeError("GITHUB_TOKEN is not set for the bridge: set it, then restart the bridge")
        rel, tag = params.get("file"), params.get("tag", "dataset-latest")
        if not isinstance(rel, str) or not isinstance(tag, str) or not TAG_RE.match(tag):
            raise BridgeError("params: file (path inside bridge/files) and tag (dataset-* or model-*)")
        path = (self.files / rel).resolve()
        if self.files.resolve() not in path.parents or not path.is_file():
            raise BridgeError("file must be an existing file inside the bridge files folder")
        if not ASSET_RE.match(path.name) or path.suffix.lower() not in ASSET_SUFFIXES:
            raise BridgeError(f"asset name must match {ASSET_RE.pattern} and end in {ASSET_SUFFIXES}")
        release = self.github_json("GET", f"/repos/{REPO}/releases/tags/{tag}", allow_404=True)
        if release is None:
            release = self.github_json("POST", f"/repos/{REPO}/releases", {
                "tag_name": tag, "name": tag, "prerelease": True, "target_commitish": "main",
                "body": "Uploaded by the PC agent bridge."})
        for asset in self.github_json("GET", f"/repos/{REPO}/releases/{release['id']}/assets?per_page=100") or []:
            if asset.get("name") == path.name:
                self.github_json("DELETE", f"/repos/{REPO}/releases/assets/{asset['id']}")
        size = path.stat().st_size
        with open(path, "rb") as fh:
            req = urllib.request.Request(
                f"{self.upload_base}/repos/{REPO}/releases/{release['id']}/assets?name={urllib.parse.quote(path.name)}",
                data=fh, method="POST", headers={
                    "Authorization": f"Bearer {self.github_token()}", "Accept": "application/vnd.github+json",
                    "Content-Type": "application/octet-stream", "Content-Length": str(size), "User-Agent": "agent-bridge"})
            try:
                with urllib.request.urlopen(req, timeout=1800) as resp:
                    resp.read()
            except urllib.error.HTTPError as e:
                raise BridgeError(f"upload failed: HTTP {e.code} {e.read()[:200].decode('utf-8', 'replace')}")
        result = {"url": f"https://github.com/{REPO}/releases/download/{tag}/{path.name}", "bytes": size}
        if params.get("delete_sessions"):
            result.update(self.delete_sessions(params["delete_sessions"]))
        return result

    def delete_sessions(self, sessions):
        """Removes uploaded session folders from the FTP data folder. Only called after a publish."""
        if self.data_dir is None:
            raise BridgeError("upload succeeded, but nothing was deleted: start the bridge with --data <ftp data folder>")
        if not isinstance(sessions, list) or not all(isinstance(x, str) and SESSION_RE.match(x) for x in sessions):
            raise BridgeError("upload succeeded, but delete_sessions must be a list of names like 20261101_192858")
        base = self.data_dir.resolve()
        deleted, skipped = [], []
        for name in sessions:
            folder = (base / name).resolve()
            if folder.parent != base or not folder.is_dir():
                skipped.append(name)
                continue
            newest = max([folder.stat().st_mtime] + [p.stat().st_mtime for p in folder.rglob("*")])
            if time.time() - newest < 15 * 60:
                skipped.append(name)
                continue
            shutil.rmtree(folder)
            deleted.append(name)
        return {"deleted_sessions": deleted, "skipped_sessions": skipped}

    def a_wifi_adb(self, params, job_id):
        """Switch the phone to adb over Wi-Fi (needs the USB connection once). After this, unplug the
        USB data cable and use a wall charger. params: {"port": 5555, "ip": optional}, or {"disconnect": true}."""
        port = str(params.get("port", 5555))
        if params.get("disconnect"):
            wf = self.root / "wifi_target.txt"
            if wf.exists():
                wf.write_text("")
            self._serial_cache = None
            return {"disconnect": self.adb_run("disconnect")[1], "devices": self.adb_run("devices", "-l")[1]}
        ip = params.get("ip")
        if not ip:
            self.require_device()
            out = self.adb_run("shell", "ip", "-f", "inet", "addr", "show", "wlan0")[1]
            import re
            m = re.search(r"inet (\d+\.\d+\.\d+\.\d+)", out)
            if not m:
                raise BridgeError(f"phone has no Wi-Fi address: {out[:200]}")
            ip = m.group(1)
            tcp = self.adb_run("tcpip", port)[1]
            time.sleep(4)
        else:
            tcp = "(ip given, tcpip skipped)"
        conn = self.adb_run("connect", f"{ip}:{port}")[1]
        if "connected" in conn:
            (self.root / "wifi_target.txt").write_text(f"{ip}:{port}")
        self._serial_cache = None
        return {"ip": ip, "tcpip": tcp, "connect": conn, "devices": self.adb_run("devices", "-l")[1],
                "next": "USB can stay or go: the bridge uses USB when attached, Wi-Fi otherwise"}

    ACTIONS = {"wifi_adb", "ping", "devices", "phone_info", "unlock", "install_latest", "run_session",
               "screenshot", "logcat", "pull_session", "app_state", "swipe", "key", "publish_release_asset"}

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
        try:
            self.write_json(self.results / f"{job_id}.json", result, attempts=30)
        except OSError as e:
            print(f"could not write result of {job_id}: {e}")
        return job_path

    def archive_finished(self):
        """Moves processed job files to done/; a locked file is retried on the next loop."""
        for name in sorted(self.finished):
            src = self.jobs / name
            if not src.exists():
                self.finished.discard(name)
                continue
            try:
                shutil.move(str(src), str(self.done / name))
                self.finished.discard(name)
            except OSError as e:
                print(f"could not move {name} to done ({e}); will retry")

    def run(self, once=False):
        print(f"agent bridge running. folder: {self.root}  adb: {self.adb}")
        while True:
            try:
                self.heartbeat()
                self.archive_finished()
                pending = sorted(p for p in self.jobs.glob("*.json")
                                 if p.name not in self.finished and time.time() - p.stat().st_mtime > 2)
                for job_path in pending:
                    self.finished.add(self.process(job_path).name)  # never run a job twice
                    self.archive_finished()
                if once and not pending:
                    return
            except OSError as e:  # a file held open by another program: wait and try again
                print(f"file access problem ({e}); retrying")
            time.sleep(2)


def keep_awake():
    """Ask Windows not to sleep while the bridge runs (a sleeping PC cuts USB power and the FTP server)."""
    if os.name != "nt":
        return
    try:
        import ctypes
        ctypes.windll.kernel32.SetThreadExecutionState(0x80000000 | 0x00000001)  # ES_CONTINUOUS | ES_SYSTEM_REQUIRED
    except Exception as e:  # noqa: BLE001 - best effort only
        print(f"could not set keep-awake: {e}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--bridge", default=str(Path.home() / "agent-bridge"))
    ap.add_argument("--adb")
    ap.add_argument("--once", action="store_true", help="process waiting jobs, then exit (for testing)")
    ap.add_argument("--data", help="FTP server data folder holding the session folders (enables delete_sessions)")
    args = ap.parse_args()
    keep_awake()
    bridge = Bridge(args.bridge, find_adb(args.adb))
    if args.data:
        bridge.data_dir = Path(args.data)
    bridge.run(once=args.once)


if __name__ == "__main__":
    main()
