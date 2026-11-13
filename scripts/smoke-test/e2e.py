#!/usr/bin/env python3
"""End-to-end smoke test for the resolver and download flow, driven through adb.

Every scenario follows the same path through the app:

  1. the link arrives through the app's VIEW intent (the deep-link / share-sheet path)
  2. the resolver picks a tier: the Instagram v1 API, its `?__a=1` web payload, the
     embed page, or the generic extractor
  3. candidates are listed, tapped, and DownloadManager writes them into
     Movies/FastSave (video) or Pictures/FastSave (image)
  4. the harness request log proves which endpoint family was really used

The Instagram scenarios run against `scripts/smoke-test/server.py`. Debug builds accept
a mock base for the Instagram hosts (`Prefs.instagramMockBase`, applied in `Http.mirror`),
so the real request construction, fallback order and JSON parsing all execute; only the
host address changes. Release builds never read that setting.

Prerequisites: a running emulator (scripts/emulator-run.sh) and a built debug APK.
Pass --keep-harness if scripts/smoke-test/server.py is already running.
"""

import argparse
import json
import os
import re
import socket
import subprocess
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
MEDIA = os.path.join(HERE, "media", "flower.mp4")
MEDIA_URL = "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4"
PACKAGE = "io.anyvideo.downloader"
IG_APP_ID = "936619743392459"

# (expected file name or None, served media, extension). None matches any name.
SCENARIOS = [
    {
        "name": "api-tunnel",
        "url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        "note": "Resolved via the resolver API",
        "items": 1,
        "button": "Download all",
        "files": [("YouTube-___dQw4w9WgXcQ___-.mp4", "clip.mp4", ".mp4")],
        "api": True,
        "requests": [],
    },
    {
        "name": "api-picker",
        "url": "https://x.com/anyvideo/status/1794225406119000000?picker=1",
        "note": "Resolved via the resolver API",
        "items": 2,
        "button": "Download all",
        "files": [
            ("X-___1794225406119000000___-.mp4", "flower.mp4", ".mp4"),
            ("X-___1794225406119000000_1___-.jpg", "square.jpg", ".jpg"),
        ],
        "api": True,
        "requests": [],
    },
    {
        "name": "instagram-v1",
        "url": "https://www.instagram.com/p/Cv1Carousel1/",
        "note": "Resolved from Instagram",
        "items": 2,
        "button": "Download all",
        "files": [
            ("mockuser-___3344556677___-.mp4", "flower.mp4", ".mp4"),
            ("mockuser-___3344556678_1___-.jpg", "square.jpg", ".jpg"),
        ],
        "requests": ["/ig/i/api/v1/media/"],
        "check_ig_headers": True,
    },
    {
        "name": "instagram-web-fallback",
        "url": "https://www.instagram.com/p/CwebFallback1/",
        "note": "Resolved from Instagram",
        "items": 1,
        "button": "Download all",
        "files": [("mockowner-___4455667788___-.mp4", "clip.mp4", ".mp4")],
        # v1 misses, then the web payload answers: the order is the assertion.
        "requests": ["/ig/i/api/v1/media/", "/ig/www/p/CwebFallback1/"],
    },
    {
        "name": "instagram-embed-fallback",
        "url": "https://www.instagram.com/p/CembedImage1/",
        "note": "Resolved from Instagram",
        "items": 1,
        "button": "Save",
        "files": [("embeduser-___CembedImage1___-.jpg", "square.jpg", ".jpg")],
        "requests": [
            "/ig/i/api/v1/media/",
            "/ig/www/p/CembedImage1/",
            "/embed/captioned/",
        ],
    },
    {
        "name": "extractor",
        "url": "https://www.tiktok.com/@anyvideo/video/7300000000000000000",
        "note": "Resolved with the generic extractor",
        "items": 1,
        "button": "Save",
        "files": [(None, "flower.mp4", ".mp4")],
        "requests": ["/action.php"],
    },
]


def log(message):
    print(f"[e2e] {message}", flush=True)


class Adb:
    def __init__(self, android_home):
        self.exe = os.path.join(android_home, "platform-tools", "adb")
        self.serial = None

    def __call__(self, *args, timeout=300, check=False):
        command = [self.exe] + (["-s", self.serial] if self.serial else []) + list(args)
        result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
        if check and result.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} failed: {result.stderr or result.stdout}")
        return result.stdout

    def dump(self):
        # Remove the previous dump first: when uiautomator fails under load it leaves the old
        # file behind, and reading that would silently report a stale screen as the current one.
        self("shell", "rm", "-f", "/sdcard/ui.xml")
        out = self("shell", "uiautomator", "dump", "/sdcard/ui.xml", timeout=180)
        if "dumped to" not in out:
            return ""
        return self("shell", "cat", "/sdcard/ui.xml", timeout=120)

    def tap_text(self, xml, text):
        """Tap the centre of the node whose text matches, via its bounds."""
        for match in re.finditer(r"<node[^>]*>", xml):
            node = match.group(0)
            if f'text="{text}"' not in node:
                continue
            bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
            if not bounds:
                continue
            x1, y1, x2, y2 = (int(v) for v in bounds.groups())
            self("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
            return True
        return False


def port_open(port):
    with socket.socket() as sock:
        sock.settimeout(0.5)
        return sock.connect_ex(("127.0.0.1", port)) == 0


def start_harness(port, log_path):
    handle = open(log_path, "ab")
    proc = subprocess.Popen(
        [sys.executable, "-u", os.path.join(HERE, "server.py"), "--port", str(port)],
        stdout=handle,
        stderr=subprocess.STDOUT,
    )
    for _ in range(40):
        if port_open(port):
            return proc
        time.sleep(0.25)
    proc.terminate()
    raise RuntimeError(f"harness did not start on port {port}")


def mock(port, path, method="GET"):
    request = urllib.request.Request(f"http://127.0.0.1:{port}{path}", method=method)
    with urllib.request.urlopen(request, timeout=15) as response:
        return response.read()


def media_sizes(port):
    """Ask the harness how big each served file is, so the test compares like with like."""
    sizes = {}
    for name in ("flower.mp4", "clip.mp4", "square.jpg"):
        request = urllib.request.Request(f"http://127.0.0.1:{port}/media/{name}", method="HEAD")
        with urllib.request.urlopen(request, timeout=15) as response:
            sizes[name] = int(response.headers["Content-Length"])
    return sizes


def ensure_media():
    """The sample clip is not committed; fetch it on first run."""
    if os.path.isfile(MEDIA) and os.path.getsize(MEDIA) > 0:
        return
    os.makedirs(os.path.dirname(MEDIA), exist_ok=True)
    log(f"fetching sample media from {MEDIA_URL}")
    urllib.request.urlretrieve(MEDIA_URL, MEDIA)


def wait_until(predicate, timeout, interval=3.0, description=""):
    deadline = time.time() + timeout
    while time.time() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(interval)
    raise TimeoutError(f"timed out after {timeout}s waiting for {description}")


def parse_ls(output):
    files = {}
    for line in output.splitlines():
        parts = line.split()
        if len(parts) >= 8 and not line.startswith("total") and "No such file" not in line:
            try:
                files[parts[-1]] = int(parts[4])
            except ValueError:
                continue
    return files


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--android-home", default=os.environ.get("ANDROID_HOME", os.path.expanduser("~/.local/android-sdk")))
    parser.add_argument("--apk", default=os.path.join(ROOT, "app/build/outputs/apk/debug/app-debug.apk"))
    parser.add_argument("--serial", default=None)
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--timeout", type=float, default=240.0, help="per UI stage")
    parser.add_argument("--only", default="all", help="all, or one of: " + ", ".join(s["name"] for s in SCENARIOS))
    parser.add_argument("--keep-harness", action="store_true")
    args = parser.parse_args()

    if not os.path.isfile(args.apk):
        sys.exit(f"APK not found: {args.apk}")
    ensure_media()

    selected = SCENARIOS if args.only == "all" else [s for s in SCENARIOS if s["name"] == args.only]
    if not selected:
        sys.exit(f"unknown scenario {args.only!r}")

    adb = Adb(args.android_home)
    if args.serial:
        adb.serial = args.serial
    if "device" not in adb("devices"):
        sys.exit("no emulator attached; start one with scripts/emulator-run.sh")

    harness = None
    out_dir = os.path.join(ROOT, "artifacts", "smoke-test")
    os.makedirs(out_dir, exist_ok=True)
    if not args.keep_harness and not port_open(args.port):
        harness = start_harness(args.port, os.path.join(out_dir, "harness.log"))
        log(f"harness listening on :{args.port}")

    results = []
    try:
        log("installing APK")
        adb("install", "-r", "-t", args.apk, check=True)

        local_prefs = os.path.join(out_dir, "prefs.xml")
        adb("shell", "run-as", PACKAGE, "mkdir", "-p", "shared_prefs")

        def seed_prefs(api: bool):
            """Point every scenario at the harness; only API scenarios configure the API."""
            entries = [
                f'    <string name="extractor">http://10.0.2.2:{args.port}/action.php</string>',
                f'    <string name="instagram_mock_base">http://10.0.2.2:{args.port}</string>',
            ]
            if api:
                entries.append(f'    <string name="resolver_api">http://10.0.2.2:{args.port}</string>')
            with open(local_prefs, "w", encoding="utf-8") as handle:
                handle.write(
                    "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                    "<map>\n" + "\n".join(entries) + "\n</map>\n"
                )
            adb("push", local_prefs, "/data/local/tmp/prefs.xml")
            adb("shell", "run-as", PACKAGE, "cp", "/data/local/tmp/prefs.xml", "shared_prefs/any_video_prefs.xml")

        sizes = media_sizes(args.port)

        for scenario in selected:
            log(f"--- {scenario['name']}: {scenario['url']}")
            mock(args.port, "/_clear", method="POST")
            seed_prefs(api=scenario.get("api", False))

            # Clean both the current folder and the pre-rebrand one: a leftover file makes
            # DownloadManager suffix the new name (…-1.mp4) and the assertion would fail.
            for folder in ("Movies/anyvideo", "Pictures/anyvideo", "Movies/FastSave", "Pictures/FastSave"):
                adb("shell", "rm", "-rf", f"/sdcard/{folder}")
            # Deleting the folders behind MediaStore's back leaves stale rows; drop those too.
            for collection, folder in (
                ("video", "Movies/anyvideo"),
                ("images", "Pictures/anyvideo"),
                ("video", "Movies/FastSave"),
                ("images", "Pictures/FastSave"),
            ):
                adb(
                    "shell",
                    "content delete --uri content://media/external/" + collection + "/media "
                    "--where \"relative_path='" + folder + "/'\"",
                )
            adb("shell", "am", "force-stop", PACKAGE)

            adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", scenario["url"], PACKAGE, timeout=180)

            named = [name for name, _, _ in scenario["files"] if name]

            def resolved():
                xml = adb.dump()
                if not xml or f'text="{scenario["note"]}"' not in xml:
                    return None
                if not re.search(rf'text="Found {scenario["items"]} item', xml):
                    return None
                # Filenames carry the post id, so they prove this link was resolved rather
                # than an earlier scenario's list still being on screen.
                if any(f'text="{name}"' not in xml for name in named):
                    return None
                return xml

            found = wait_until(
                resolved,
                args.timeout,
                description=f'{scenario["items"]} candidate(s) from "{scenario["note"]}"',
            )
            log(f'resolver: "{scenario["note"]}", {scenario["items"]} candidate(s)')

            def current_files():
                have = parse_ls(adb("shell", "ls", "-l", "/sdcard/Movies/anyvideo/", timeout=120))
                have.update(parse_ls(adb("shell", "ls", "-l", "/sdcard/Pictures/anyvideo/", timeout=120)))
                return have

            def satisfied():
                have = current_files()
                for name, media, suffix in scenario["files"]:
                    if name is None:
                        if not any(n.endswith(suffix) and s == sizes[media] for n, s in have.items()):
                            return None
                    elif have.get(name) != sizes[media]:
                        return None
                return have

            # The emulator renders slowly enough that a dump can go stale between the
            # read and the tap, so retry with a fresh hierarchy instead of trusting one.
            deadline = time.time() + args.timeout
            landed = None
            for attempt in range(1, 4):
                log(f'tapping "{scenario["button"]}" (attempt {attempt})')
                xml = adb.dump()
                if not xml:
                    log("no UI dump on this attempt")
                    continue
                if not adb.tap_text(xml, scenario["button"]):
                    raise RuntimeError(f'no "{scenario["button"]}" node')
                try:
                    landed = wait_until(
                        satisfied,
                        max(10.0, min(120.0, deadline - time.time())),
                        interval=4.0,
                        description="the downloads",
                    )
                    break
                except TimeoutError:
                    log("nothing landed yet; taking a fresh dump")
                    if time.time() >= deadline:
                        raise

            if landed is None:
                raise TimeoutError(f'{scenario["name"]}: the downloads never completed')

            # Prove the app walked the tiers in the documented order.
            served = json.loads(mock(args.port, "/_log"))
            paths = [entry["path"] for entry in served]
            cursor = 0
            for needle in scenario["requests"]:
                while cursor < len(paths) and needle not in paths[cursor]:
                    cursor += 1
                if cursor == len(paths):
                    raise AssertionError(
                        f'{scenario["name"]}: never requested {needle}; log was {paths}'
                    )
                cursor += 1
            if scenario.get("api"):
                posted = [e for e in served if e["method"] == "POST" and e["path"] == "/"]
                if not posted:
                    raise AssertionError(f'{scenario["name"]}: the resolver API was never called; log {paths}')
                if (posted[0].get("body_url") or "") != scenario["url"]:
                    raise AssertionError(
                        f'{scenario["name"]}: API received {posted[0].get("body_url")!r}, '
                        f'expected {scenario["url"]!r}'
                    )
                if any("/action.php" in p for p in paths):
                    raise AssertionError(f'{scenario["name"]}: fell through to the extractor; log {paths}')
            if scenario.get("check_ig_headers"):
                v1 = next(e for e in served if "/ig/i/api/v1/media/" in e["path"])
                if v1["ig_app_id"] != IG_APP_ID:
                    raise AssertionError(f"v1 request missing X-IG-App-ID: {v1['ig_app_id']!r}")
                if not (v1["user_agent"] or "").startswith("Instagram "):
                    raise AssertionError(f"v1 request UA was {v1['user_agent']!r}")

            for name, media, suffix in scenario["files"]:
                picked = [n for n in landed if (n == name if name else n.endswith(suffix))]
                log(f"  {picked[0]}: {landed[picked[0]]} bytes (= {media})")
            results.append((scenario["name"], sorted(landed)))

        expects_video = any(suffix == ".mp4" for s in selected for _, _, suffix in s["files"])
        tab = "Videos" if expects_video else "Photos"
        log(f"checking the Downloads gallery sees the downloads ({tab} tab)")
        adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity", timeout=180)
        time.sleep(20)
        adb.tap_text(adb.dump(), "Downloads")
        time.sleep(20)
        gallery = adb.dump()
        adb.tap_text(gallery, tab)
        time.sleep(20)
        gallery = adb.dump()
        count_text = re.search(r'text="(\d+) item\(s\) in anyvideo"', gallery)
        if not count_text:
            raise AssertionError("Downloads screen did not report an item count")
        count = int(count_text.group(1))
        if count < 1:
            raise AssertionError(f"gallery lists no items on the {tab} tab (count {count})")

        print("")
        for name, files in results:
            print(f"  {name:<26} {', '.join(files)}")
        print(f"\nPASS  {len(results)} scenario(s), gallery lists {count} {tab.lower()} item(s)", flush=True)
        return 0
    finally:
        if harness is not None:
            harness.terminate()


if __name__ == "__main__":
    sys.exit(main())
