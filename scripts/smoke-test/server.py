#!/usr/bin/env python3
"""Local stand-in for the endpoints the app talks to, used by the emulator smoke test.

Two families of route:

* the generic extractor (snapsave-style). The real app POSTs a link, rewrites the
  answered HTML so `decodeURIComponent(escape(r))` logs to the console instead of
  writing the DOM, loads it in a hidden WebView and harvests URLs from
  `console.log`. This server answers with exactly that shape.
* Instagram, mirroring the three tiers the resolver tries in order: the private
  v1 media info API, the web `?__a=1` payload and the embed page. The debug build
  can be pointed here through `Prefs.instagramMockBase` (see Http.mirror), which
  rewrites `https://i.instagram.com/x` to `<base>/ig/i/x` and
  `https://www.instagram.com/x` to `<base>/ig/www/x`.

`GET /_log` returns every request it served (path, Instagram headers) so the test
can assert which tier the app actually chose; `POST /_clear` resets that log.

Usage:  python3 scripts/smoke-test/server.py [--port 8000] [--media-base URL]

The media base is what the guest has to fetch the file from. In the Android
emulator the host loopback is reachable as 10.0.2.2.
"""

import argparse
import base64
import http.server
import json
import os
import re
import urllib.parse

ROOT = os.path.dirname(os.path.abspath(__file__))
MEDIA_DIR = os.path.join(ROOT, "media")

PAGE = """<!doctype html>
<html><head><meta charset="utf-8"><title>extractor</title></head>
<body>
<div id="result"></div>
<script>
  // Same decoder contract as the extractor the app rewrites: the resolver
  // replaces decodeURIComponent(escape(r)) with a console.log variant and reads
  // the printed URL back out of the WebView console.
  var r = "%(payload)s";
  console.log("Hello" + decodeURIComponent(escape(r)));
</script>
</body></html>
"""

# 1x1 JPEG, so the image branch of the parser gets a real (scannable) picture.
SQUARE_JPEG = base64.b64decode(
    "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0a"
    "HBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAA"
    "AAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q=="
)

# Shortcode -> media id uses Instagram's URL-safe base64 alphabet, exactly like
# Instagram.mediaId in the app; the v1 endpoint only receives the decoded id.
ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"


def media_id(shortcode: str) -> str:
    value = 0
    for char in shortcode:
        index = ALPHABET.find(char)
        if index < 0:
            return shortcode
        value = value * 64 + index
    return str(value)


CAROUSEL_CODE = "Cv1Carousel1"
WEB_CODE = "CwebFallback1"
EMBED_CODE = "CembedImage1"


def v1_payload(media_base: str):
    """Private-API shape: one carousel post with a video and an image child."""
    return {
        "items": [
            {
                "id": "3344556600",
                "user": {"username": "mockuser"},
                "carousel_media": [
                    {
                        "id": "3344556677",
                        "media_type": 2,
                        "video_versions": [{"url": f"{media_base}/media/flower.mp4"}],
                        "image_versions2": {"candidates": [{"url": f"{media_base}/media/square.jpg"}]},
                    },
                    {
                        "id": "3344556678",
                        "media_type": 1,
                        "display_url": f"{media_base}/media/square.jpg",
                        "image_versions2": {"candidates": [{"url": f"{media_base}/media/square.jpg"}]},
                    },
                ],
            }
        ]
    }


def web_payload(code: str, media_base: str):
    """`?__a=1` shape: graphql.shortcode_media instead of items[]."""
    return {
        "graphql": {
            "shortcode_media": {
                "id": "4455667788",
                "shortcode": code,
                "is_video": True,
                "video_url": f"{media_base}/media/clip.mp4",
                "display_url": f"{media_base}/media/square.jpg",
                "owner": {"username": "mockowner"},
            }
        }
    }


def embed_page(code: str, media_base: str):
    escaped = f"{media_base}/media/square.jpg".replace("/", "\\/")
    return (
        "<!doctype html><html><body><script>\n"
        f'window.__additionalDataLoaded("extra", {{"owner":{{"username":"embeduser"}},'
        f'"display_url":"{escaped}"}});\n'
        "</script></body></html>\n"
    )


PRIVATE_USER = "mockprivate"
PRIVATE_ID = "9911223344"


def profile_payload(name: str):
    """`web_profile_info` shape, carrying the `is_private` flag the feed path reads."""
    return {
        "data": {
            "user": {"id": PRIVATE_ID, "username": name or PRIVATE_USER, "is_private": True}
        }
    }


def feed_payload(media_base: str):
    """A private profile's timeline: a photo and a reel that save, plus one refused file.

    `wall.jpg` answers 200 with an HTML login page, the way a CDN refuses a private file
    without an error status; the app must drop it instead of indexing a broken picture.
    """
    return {
        "items": [
            {
                "id": "5501",
                "media_type": 1,
                "user": {"username": PRIVATE_USER},
                "display_url": f"{media_base}/media/square.jpg",
                "image_versions2": {"candidates": [{"url": f"{media_base}/media/square.jpg"}]},
            },
            {
                "id": "5502",
                "media_type": 2,
                "user": {"username": PRIVATE_USER},
                "video_versions": [{"url": f"{media_base}/media/flower.mp4"}],
                "image_versions2": {"candidates": [{"url": f"{media_base}/media/square.jpg"}]},
            },
            {
                "id": "5503",
                "media_type": 1,
                "user": {"username": PRIVATE_USER},
                "display_url": f"{media_base}/media/wall.jpg",
                "image_versions2": {"candidates": [{"url": f"{media_base}/media/wall.jpg"}]},
            },
        ]
    }


V1_BY_ID = {media_id(CAROUSEL_CODE): CAROUSEL_CODE}
WEB_BY_CODE = {WEB_CODE: WEB_CODE}
EMBED_BY_CODE = {EMBED_CODE: EMBED_CODE}


def make_handler(media_base: str):
    request_log = []

    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):  # keep the test output readable
            print("[harness] " + fmt % args, flush=True)

        def _send(self, status: int, body: bytes, content_type: str):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)

        def _json(self, status: int, payload):
            self._send(status, json.dumps(payload).encode("utf-8"), "application/json")

        def _record(self, status: int):
            request_log.append(
                {
                    "method": self.command,
                    "path": self.path,
                    "status": status,
                    "ig_app_id": self.headers.get("X-IG-App-ID"),
                    "user_agent": self.headers.get("User-Agent"),
                    "cookie": self.headers.get("Cookie"),
                    "csrf": self.headers.get("X-CSRFToken"),
                }
            )

        def _extractor_page(self):
            # A real extractor hands back a percent-escaped UTF-8 blob; the app decodes it
            # with decodeURIComponent(escape(r)). Keep the media URL plain here: escape()
            # would otherwise turn its % signs into %25 and the URL would stay encoded.
            media = f"{media_base}/media/flower.mp4"
            body = (PAGE % {"payload": media}).encode("utf-8")
            self._record(200)
            self._send(200, body, "text/html; charset=utf-8")

        def _instagram(self, path: str):
            query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)

            # The signed-in comparison: which account the session belongs to.
            if path == "/ig/i/api/v1/users/web_profile_info/":
                name = (query.get("username") or [""])[0]
                self._record(200)
                self._json(200, profile_payload(name))
                return True

            if path == "/ig/www/api/v1/accounts/edit/web_form_data/":
                self._record(200)
                self._json(200, {"form_data": {"username": PRIVATE_USER}})
                return True

            if re.match(r"^/ig/i/api/v1/feed/user/\d+/$", path):
                self._record(200)
                self._json(200, feed_payload(media_base))
                return True

            # v1: /ig/i/api/v1/media/<id>/info/
            match = re.match(r"^/ig/i/api/v1/media/(\d+)/info/$", path)
            if match:
                print(f"[harness] ig v1 media {match.group(1)}", flush=True)
                if match.group(1) not in V1_BY_ID:
                    self._record(404)
                    self._json(404, {"message": "Not found", "status": "fail"})
                    return True
                self._record(200)
                self._json(200, v1_payload(media_base))
                return True

            # web payload: /ig/www/p/<code>/?__a=1&__d=dis
            match = re.match(r"^/ig/www/p/([^/]+)/$", path)
            if match and query.get("__a"):
                code = match.group(1)
                print(f"[harness] ig web {code}", flush=True)
                if code not in WEB_BY_CODE:
                    self._record(404)
                    self._json(404, {"message": "Not found"})
                    return True
                self._record(200)
                self._json(200, web_payload(code, media_base))
                return True

            # embed: /ig/www/p/<code>/embed/captioned/
            match = re.match(r"^/ig/www/p/([^/]+)/embed/captioned/$", path)
            if match:
                code = match.group(1)
                print(f"[harness] ig embed {code}", flush=True)
                if code not in EMBED_BY_CODE:
                    self._record(404)
                    self._send(404, b"not found", "text/plain")
                    return True
                self._record(200)
                self._send(200, embed_page(code, media_base).encode("utf-8"), "text/html; charset=utf-8")
                return True

            self._record(404)
            self._json(404, {"message": f"no mock for {path}"})
            return True

        def _serve_media(self, path: str):
            name = os.path.basename(path)
            if name == "wall.jpg":
                # A refusal that still looks like a successful download.
                self._record(200)
                self._send(200, b"<!DOCTYPE html><html><body>Log in to continue</body></html>", "text/html")
                return
            if name.endswith(".jpg"):
                self._record(200)
                self._send(200, SQUARE_JPEG, "image/jpeg")
                return
            # The web tier names a second file; reuse the sample clip for it.
            full = os.path.join(MEDIA_DIR, "flower.mp4" if name == "clip.mp4" else name)
            if not os.path.isfile(full):
                self._record(404)
                self._send(404, b"no such media", "text/plain")
                return
            with open(full, "rb") as handle:
                body = handle.read()
            self._record(200)
            self._send(200, body, "video/mp4")

        def do_GET(self):
            parsed = urllib.parse.urlparse(self.path)
            path = parsed.path
            if path == "/_log":
                self._send(200, json.dumps(request_log).encode("utf-8"), "application/json")
            elif path == "/_clear":
                request_log.clear()
                self._send(200, b"ok", "text/plain")
            elif path.startswith("/ig/"):
                self._instagram(path)
            elif path == "/":
                # cobalt answers `GET /` with instance info; the Settings "Test connection"
                # button reads version and services from exactly this payload.
                self._record(200)
                self._json(200, {
                    "cobalt": {
                        "version": "11.0.0-mock",
                        "url": media_base,
                        "services": ["instagram", "youtube", "twitter", "pinterest"],
                    }
                })
            elif path == "/action.php":
                self._extractor_page()
            elif path.startswith("/media/"):
                self._serve_media(path)
            else:
                self._record(404)
                self._send(404, b"not found", "text/plain")

        def _api_resolve(self, raw: str):
            """`POST /` of the cobalt API: the mock picks a reply from the link's text."""
            try:
                body = json.loads(raw or "{}")
            except ValueError:
                self._record(400)
                self._json(400, {"status": "error", "error": {"code": "error.api.invalid_body"}})
                return
            target = body.get("url", "")
            print(f"[harness] api resolve url={target!r}", flush=True)
            request_log.append(
                {
                    "method": self.command,
                    "path": self.path,
                    "status": 200,
                    "ig_app_id": None,
                    "user_agent": self.headers.get("User-Agent"),
                    "body_url": target,
                }
            )
            if "denied" in target:
                self._json(200, {"status": "error", "error": {"code": "error.api.auth.api_key.missing"}})
            elif "picker" in target:
                self._json(
                    200,
                    {
                        "status": "picker",
                        "picker": [
                            {
                                "type": "video",
                                "url": f"{media_base}/media/flower.mp4",
                                "thumb": f"{media_base}/media/square.jpg",
                            },
                            {"type": "photo", "url": f"{media_base}/media/square.jpg"},
                        ],
                    },
                )
            else:
                self._json(
                    200,
                    {"status": "tunnel", "url": f"{media_base}/media/clip.mp4", "filename": "clip.mp4"},
                )

        def do_POST(self):
            length = int(self.headers.get("Content-Length") or 0)
            form = self.rfile.read(length).decode("utf-8", "replace")
            print(f"[harness] POST {self.path} form={form!r}", flush=True)
            print(f"[harness] ua={self.headers.get('User-Agent')!r}", flush=True)
            route = urllib.parse.urlparse(self.path).path
            if route == "/_clear":
                request_log.clear()
                self._send(200, b"ok", "text/plain")
            elif route == "/":
                self._api_resolve(form)
            elif route == "/action.php":
                self._extractor_page()
            else:
                self._send(404, b"not found", "text/plain")

        def do_HEAD(self):
            self.do_GET()

    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--media-base", default="http://10.0.2.2:8000")
    args = parser.parse_args()

    server = http.server.ThreadingHTTPServer(
        (args.host, args.port), make_handler(args.media_base.rstrip("/"))
    )
    print(f"[harness] serving on {args.host}:{args.port}, media base {args.media_base}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
