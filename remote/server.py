#!/usr/bin/env python3
"""Phone remote for the Fastway box: a tiny web server that turns taps into adb commands.

    TV=<box-ip>:5555 python3 server.py   # then open the printed URL on your phone

Every API call needs the token from the printed URL, so other devices on the
network can't drive the TV just by finding the port.
"""
import json
import os
import re
import secrets
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
TV = os.environ.get("TV", "192.168.1.2:5555")      # the box's IP and ADB port
ADB = os.environ.get("ADB", str(Path.home() / ".local/share/platform-tools/adb"))
PORT = int(os.environ.get("PORT", "8765"))
MUSIC_STREAM = "3"

STATIC = {
    "/": ("index.html", "text/html; charset=utf-8"),
    "/manifest.webmanifest": ("manifest.webmanifest", "application/manifest+json"),
    "/icon.svg": ("icon.svg", "image/svg+xml"),
    "/Outfit.ttf": ("Outfit.ttf", "font/ttf"),
}

KEYS = {
    "up": "DPAD_UP", "down": "DPAD_DOWN", "left": "DPAD_LEFT", "right": "DPAD_RIGHT",
    "select": "DPAD_CENTER", "back": "BACK", "home": "HOME",
    "playpause": "MEDIA_PLAY_PAUSE", "next": "MEDIA_NEXT", "previous": "MEDIA_PREVIOUS",
    "forward": "MEDIA_FAST_FORWARD", "rewind": "MEDIA_REWIND",
    "volup": "VOLUME_UP", "voldown": "VOLUME_DOWN", "mute": "VOLUME_MUTE",
    "sleep": "SLEEP", "wake": "WAKEUP",
}

# Names and colours for the apps on this box; anything else gets a name from its package.
APPS = {
    "com.google.android.youtube.tv": ("YouTube", "#FF0033"),
    "com.google.android.youtube.tvmusic": ("YouTube Music", "#FF0033"),
    "in.startv.hotstar": ("JioHotstar", "#7B2FF7"),
    "com.graymatrix.did": ("Zee5", "#8230C6"),
    "com.sonyliv": ("Sony LIV", "#E4A11B"),
    "com.fastway.fastwaylivetv": ("Fastway Live TV", "#D7192D"),
    "com.plexapp.android": ("Plex", "#E5A00D"),
    "com.showtimeapp": ("Loco", "#FF5A1F"),
    "com.hinettvin": ("HiNet IPTV", "#0B4F9C"),
    "video.laminar.tv.chaupal.android": ("Chaupal", "#E5194B"),
    "com.shemaroo.shemarootv": ("ShemarooMe", "#F15A24"),
    "com.vzy.one.atv": ("Vzy", "#F7B500"),
    "org.videolan.vlc": ("VLC", "#FF8800"),
    "com.android.vending": ("Play Store", "#01875F"),
    "com.droidlogic.updater": ("Updater", "#3A7D74"),
    "com.clean.home": ("Home", "#636366"),
}

# Package names to leave out of the Apps tab, one per line ("#" comments allowed). Suspended
# apps still look launchable over ADB, so list them here.
HIDDEN_FILE = Path(os.environ.get("HIDE_APPS", HERE.parent / "disable-list.txt"))
HIDDEN_APPS = set(re.findall(r"^([\w.]+)\s*$", HIDDEN_FILE.read_text(), re.M)) if HIDDEN_FILE.exists() else set()

TOKEN_FILE = HERE / ".token"
TOKEN = TOKEN_FILE.read_text().strip() if TOKEN_FILE.exists() else secrets.token_urlsafe(12)
TOKEN_FILE.write_text(TOKEN)
TOKEN_FILE.chmod(0o600)

_connect_lock = threading.Lock()


def adb(*args, timeout=8):
    """Runs adb against the box, reconnecting once if the connection dropped."""
    for attempt in (1, 2):
        try:
            r = subprocess.run([ADB, "-s", TV, *args], capture_output=True, text=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            raise RuntimeError("The TV didn't answer in time")
        if r.returncode == 0:
            return r.stdout
        if attempt == 1 and re.search(r"not found|offline|no devices|closed", r.stderr + r.stdout):
            with _connect_lock:
                subprocess.run([ADB, "connect", TV], capture_output=True, timeout=8)
            continue
        raise RuntimeError((r.stderr or r.stdout).strip() or "adb failed")


def shell(cmd, timeout=8):
    return adb("shell", cmd, timeout=timeout)


def status():
    out = shell(
        "dumpsys media_session; echo @@VOL; cmd media_session volume --stream 3 --get;"
        " echo @@TOP; dumpsys activity activities | grep -m1 topResumedActivity"
    )
    sessions, vol_part = out.split("@@VOL", 1)
    vol_part, top_part = vol_part.split("@@TOP", 1)

    now = None
    for block in re.split(r"\n\s{4}\S.*\n\s+ownerPid=", sessions)[1:]:
        pkg = re.search(r"package=(\S+)", block)
        meta = re.search(r"metadata: size=\d+, description=(.*)", block)
        state = re.search(r"state=PlaybackState \{state=(\w+)", block)
        if not (pkg and meta and state) or state.group(1) in ("NONE", "ERROR", "STOPPED"):
            continue
        parts = [p.strip() for p in meta.group(1).split(",")]
        title = parts[0] if parts and parts[0] != "null" else ""
        artist = parts[1] if len(parts) > 1 and parts[1] != "null" else ""
        p = pkg.group(1)
        now = {"package": p, "app": app_name(p), "color": app_color(p), "title": title,
               "artist": artist, "playing": state.group(1) == "PLAYING"}
        if "youtube" in p and title:
            video = match_now_playing(title, artist)
            if video:
                now.update(videoId=video["id"], thumb=video["thumb"])
        if now["playing"]:
            break

    vol = re.search(r"volume is (\d+) in range \[(\d+)\.\.(\d+)\]", vol_part)
    top = re.search(r"u0 ([\w.]+)/", top_part)
    top_pkg = top.group(1) if top else None
    return {
        "connected": True,
        "now": now,
        "volume": int(vol.group(1)) if vol else None,
        "volumeMax": int(vol.group(3)) if vol else 25,
        "foreground": {"package": top_pkg, "app": app_name(top_pkg)} if top_pkg else None,
    }


def app_name(pkg):
    if not pkg:
        return ""
    if pkg in APPS:
        return APPS[pkg][0]
    return pkg.rsplit(".", 1)[-1].replace("_", " ").title()


def app_color(pkg):
    return APPS.get(pkg, (None, "#8E8E93"))[1]


_apps_cache = {"at": 0, "apps": []}


def apps():
    if time.time() - _apps_cache["at"] < 60:
        return _apps_cache["apps"]
    out = shell("cmd package query-activities --brief -a android.intent.action.MAIN"
                " -c android.intent.category.LEANBACK_LAUNCHER")
    hidden = HIDDEN_APPS | {"com.clean.home"}
    pkgs = sorted({m for m in re.findall(r"^\s+([\w.]+)/", out, re.M)} - hidden,
                  key=lambda p: app_name(p).lower())
    result = [{"package": p, "name": app_name(p), "color": app_color(p)} for p in pkgs]
    _apps_cache.update(at=time.time(), apps=result)
    return result


def launch(pkg):
    if not re.fullmatch(r"[\w.]+", pkg):
        raise ValueError("Bad package name")
    shell(f"monkey -p {pkg} -c android.intent.category.LEANBACK_LAUNCHER 1")


def youtube(query, play):
    if not query.strip():
        raise ValueError("Type something to search for")
    if play:
        hits = yt_search(query, limit=1)
        if not hits:
            raise ValueError("Nothing found on YouTube")
        play_video(hits[0]["id"])
        return
    q = urllib.parse.quote_plus(query.strip())
    shell(f"am start -a android.intent.action.VIEW -d 'https://www.youtube.com/results?search_query={q}' "
          "-n com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity")


def type_text(text):
    # `input text` takes %s for spaces and needs shell metacharacters escaped.
    safe = re.sub(r"([\\'\"`$&|;<>(){}\[\]*?!#~])", r"\\\1", text).replace(" ", "%s")
    shell(f"input text {safe}")


def get_volume():
    out = shell(f"cmd media_session volume --stream {MUSIC_STREAM} --get")
    m = re.search(r"volume is (\d+) in range \[\d+\.\.(\d+)\]", out)
    if not m:
        raise RuntimeError("Couldn't read the TV volume")
    return int(m.group(1)), int(m.group(2))


def set_volume(level):
    # This firmware ignores `media_session volume --set`, but volume keys work, so step there.
    current, top = get_volume()
    target = max(0, min(top, int(level)))
    steps = target - current
    if steps:
        key = "VOLUME_UP" if steps > 0 else "VOLUME_DOWN"
        shell("input keyevent " + " ".join([key] * abs(steps)))
    return get_volume()[0]


# ---------------------------------------------------------------- YouTube lookups
# The TV only reports title + artist and never the queue, so the PC looks the song up on
# YouTube for its thumbnail and for YouTube's own "up next" suggestions.
YT_CTX = {"client": {"clientName": "WEB", "clientVersion": "2.20250901.00.00", "hl": "en", "gl": "IN"}}
VIDEO_ID = re.compile(r"[A-Za-z0-9_-]{11}")


def _yt(endpoint, body):
    req = urllib.request.Request(
        f"https://www.youtube.com/youtubei/v1/{endpoint}?prettyPrint=false",
        data=json.dumps({"context": YT_CTX, **body}).encode(),
        headers={"Content-Type": "application/json", "User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=8) as r:
        return json.load(r)


def _walk(o, key, out):
    if isinstance(o, dict):
        for k, v in o.items():
            if k == key:
                out.append(v)
            _walk(v, key, out)
    elif isinstance(o, list):
        for v in o:
            _walk(v, key, out)
    return out


def _text(t):
    if not t:
        return ""
    return t.get("simpleText") or "".join(r.get("text", "") for r in t.get("runs", []))


def thumb(video_id):
    return f"https://i.ytimg.com/vi/{video_id}/hq720.jpg"


_search_cache = {}         # (query, limit) -> (time, results)


def yt_search(query, limit=15):
    key = (query.strip().lower(), limit)
    hit = _search_cache.get(key)
    if hit and time.time() - hit[0] < 600:
        return hit[1]
    if len(_search_cache) > 200:
        _search_cache.clear()
    out = _yt_search(query, limit)
    _search_cache[key] = (time.time(), out)
    return out


def _yt_search(query, limit):
    out = []
    for v in _walk(_yt("search", {"query": query}), "videoRenderer", []):
        if "videoId" not in v:
            continue
        out.append({"id": v["videoId"], "title": _text(v.get("title")), "channel": _text(v.get("ownerText")),
                    "duration": _text(v.get("lengthText")), "thumb": thumb(v["videoId"])})
        if len(out) >= limit:
            break
    return out


def yt_next(video_id, limit=15):
    out = []
    for x in _walk(_yt("next", {"videoId": video_id}), "lockupViewModel", []):
        if x.get("contentType") != "LOCKUP_CONTENT_TYPE_VIDEO" or not VIDEO_ID.fullmatch(x.get("contentId", "")):
            continue
        md = x.get("metadata", {}).get("lockupMetadataViewModel", {})
        rows = md.get("metadata", {}).get("contentMetadataViewModel", {}).get("metadataRows", [])
        parts = [p.get("text", {}).get("content", "") for r in rows for p in r.get("metadataParts", [])]
        badges = _walk(x, "thumbnailBadgeViewModel", [])
        out.append({"id": x["contentId"], "title": md.get("title", {}).get("content", ""),
                    "channel": parts[0] if parts else "", "duration": badges[0].get("text", "") if badges else "",
                    "thumb": thumb(x["contentId"])})
        if len(out) >= limit:
            break
    return out


_match_cache = {}          # "title|artist" -> video dict (or None when nothing matched)
_match_pending = set()
_next_cache = {}           # video id -> (time, list)
_yt_lock = threading.Lock()


def match_now_playing(title, artist):
    """Video for what the TV is playing; looked up in the background so /api/status stays fast."""
    key = f"{title}|{artist}"
    with _yt_lock:
        if key in _match_cache:
            return _match_cache[key]
        if key in _match_pending:
            return None
        _match_pending.add(key)

    def lookup():
        try:
            hits = yt_search(f"{title} {artist}".strip(), limit=1)
            found = hits[0] if hits else None
        except Exception:
            found = None
        with _yt_lock:
            if len(_match_cache) > 300:
                _match_cache.clear()
            _match_cache[key] = found
            _match_pending.discard(key)

    threading.Thread(target=lookup, daemon=True).start()
    return None


def up_next(video_id):
    hit = _next_cache.get(video_id)
    if hit and time.time() - hit[0] < 600:
        return hit[1]
    items = yt_next(video_id)
    _next_cache[video_id] = (time.time(), items)
    return items


def play_video(video_id):
    if not VIDEO_ID.fullmatch(video_id):
        raise ValueError("Bad video id")
    shell(f"am start -a android.intent.action.VIEW -d 'https://www.youtube.com/watch?v={video_id}' "
          "-n com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity")


class Handler(BaseHTTPRequestHandler):
    server_version = "FastwayRemote/1"

    def log_message(self, fmt, *args):
        pass

    def _send(self, code, body, ctype="application/json"):
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass  # the phone navigated away mid-request

    def _authorised(self):
        return secrets.compare_digest(self.headers.get("X-Token", ""), TOKEN)

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path
        if path in STATIC:
            name, ctype = STATIC[path]
            return self._send(200, (HERE / name).read_bytes(), ctype)
        if not path.startswith("/api/"):
            return self._send(404, {"error": "Not found"})
        if not self._authorised():
            return self._send(401, {"error": "Open the link printed by the server to pair this phone"})
        try:
            if path == "/api/status":
                return self._send(200, status())
            if path == "/api/apps":
                return self._send(200, {"apps": apps()})
            query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            if path == "/api/search":
                q = query.get("q", [""])[0][:200].strip()
                return self._send(200, {"results": yt_search(q) if q else []})
            if path == "/api/upnext":
                vid = query.get("v", [""])[0]
                if not VIDEO_ID.fullmatch(vid):
                    return self._send(400, {"error": "Bad video id"})
                return self._send(200, {"items": up_next(vid)})
        except (urllib.error.URLError, TimeoutError):
            return self._send(502, {"error": "Couldn't reach YouTube"})
        except RuntimeError as e:
            return self._send(503, {"connected": False, "error": str(e)})
        self._send(404, {"error": "Not found"})

    def do_POST(self):
        path = urllib.parse.urlparse(self.path).path
        if not self._authorised():
            return self._send(401, {"error": "Open the link printed by the server to pair this phone"})
        try:
            length = min(int(self.headers.get("Content-Length", 0)), 4096)
            body = json.loads(self.rfile.read(length) or b"{}")
            if path == "/api/key":
                key = KEYS.get(body.get("key"))
                if not key:
                    raise ValueError("Unknown button")
                shell(f"input keyevent {key}")
                return self._send(200, {"ok": True})
            if path == "/api/volume":
                return self._send(200, {"volume": set_volume(body.get("level", 0))})
            if path == "/api/launch":
                launch(str(body.get("package", "")))
                return self._send(200, {"ok": True})
            if path == "/api/youtube":
                youtube(str(body.get("query", ""))[:200], bool(body.get("play")))
                return self._send(200, {"ok": True})
            if path == "/api/play":
                play_video(str(body.get("videoId", "")))
                return self._send(200, {"ok": True})
            if path == "/api/text":
                type_text(str(body.get("text", ""))[:200])
                return self._send(200, {"ok": True})
        except ValueError as e:
            return self._send(400, {"error": str(e)})
        except (urllib.error.URLError, TimeoutError):
            return self._send(502, {"error": "Couldn't reach YouTube"})
        except RuntimeError as e:
            return self._send(503, {"connected": False, "error": str(e)})
        self._send(404, {"error": "Not found"})


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect((TV.split(":")[0], 9))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


if __name__ == "__main__":
    subprocess.run([ADB, "connect", TV], capture_output=True, timeout=10)
    ip = lan_ip()
    print(f"Fastway remote running.\n  On this PC:   http://127.0.0.1:{PORT}/#k={TOKEN}\n"
          f"  On your phone: http://{ip}:{PORT}/#k={TOKEN}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
