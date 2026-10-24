"""
YouTube utan Google-konto, som FreeTube.

- Prenumerationer och tittade positioner sparas här (youtube.json), så att alla TV-apparater
  och webben ser samma.
- Flödet byggs av kanalernas RSS-flöden (YouTubes egna, utan inloggning).
- Sök, kanalsidor och videor hämtas med yt-dlp, som körs som eget program och uppdaterar sig
  själv varje dygn i /config/ytdlp. När YouTube ändrar något räcker det oftast att yt-dlp
  uppdateras; appen på TV:n behöver inte bytas.
- Videon spelas som HLS som byggs här av YouTubes egna DASH-filer (bild och ljud för sig):
  filernas index (sidx) läses och blir byte-intervall i spellistan. Både TV:ns spelare och
  hls.js i webbläsaren klarar det, och kvaliteten anpassas efter nätet. Allt går via servern,
  så TV:n pratar aldrig själv med Google.
- SponsorBlock: sponsorsnack och "prenumerera"-tjat hoppas över.
"""
from __future__ import annotations

import asyncio
import base64
import csv
import hashlib
import io
import json
import logging
import math
import os
import re
import shutil
import struct
import sys
import threading
import time
import xml.etree.ElementTree as ET
from datetime import datetime
from urllib.parse import parse_qs, quote, urlparse

import aiohttp

log = logging.getLogger("jptv.youtube")

YT = "https://www.youtube.com"
RSS_URL = YT + "/feeds/videos.xml?channel_id={}"
SPONSOR_API = "https://sponsor.ajay.app"


def _rss_url(cid: str) -> str:
    # Testerna pekar om adresserna med miljövariabler.
    return os.environ.get("JPTV_YT_RSS", RSS_URL).format(cid)


def _sponsor_api() -> str:
    return os.environ.get("JPTV_SPONSORBLOCK", SPONSOR_API)


def _codecs() -> tuple[tuple[str, ...], tuple[str, ...]]:
    """Godkända codecs: H.264 och AAC (spelas överallt). Testerna lägger till VP9/Opus."""
    video, audio = ["avc1"], ["mp4a"]
    for c in (os.environ.get("JPTV_YT_CODECS") or "").split(","):
        c = c.strip()
        if c in ("vp09", "vp9", "av01"):
            video.append(c)
        elif c in ("opus",):
            audio.append(c)
    return tuple(video), tuple(audio)
# Listor från yt-dlp (sök, kanal) får ungefärligt datum ur "för 3 år sedan".
APPROX_DATE = ("--extractor-args", "youtubetab:approximate_date")
# Testvideo för "Testa YouTube" (den första videon på YouTube, ligger kvar).
TEST_VIDEO = "jNQXAC9IVRw"
CHANNEL_RE = re.compile(r"^UC[0-9A-Za-z_-]{22}$")
VIDEO_RE = re.compile(r"^[0-9A-Za-z_-]{11}$")
# Kategorier som hoppas över automatiskt.
SKIP_CATEGORIES = ("sponsor", "selfpromo", "interaction")
# Bilder får bara hämtas från YouTubes bildservrar.
IMAGE_HOSTS = ("i.ytimg.com", "i1.ytimg.com", "i2.ytimg.com", "i3.ytimg.com", "i4.ytimg.com", "i9.ytimg.com",
               "yt3.ggpht.com", "yt3.googleusercontent.com")

NS = {
    "a": "http://www.w3.org/2005/Atom",
    "yt": "http://www.youtube.com/xml/schemas/2015",
    "media": "http://search.yahoo.com/mrss/",
}


class YtError(Exception):
    pass


# ------------------------------------------------------------------ yt-dlp


def _friendly(stderr: str) -> str:
    """Den sista ERROR-raden från yt-dlp, utan tekniskt brus."""
    lines = [l.strip() for l in (stderr or "").splitlines() if l.strip()]
    errors = [l for l in lines if l.startswith("ERROR")]
    text = (errors or lines or ["okänt fel"])[-1]
    text = re.sub(r"^ERROR:\s*(\[[^\]]+\]\s*)?([0-9A-Za-z_-]{11}:\s*)?", "", text)
    low = text.lower()
    if "sign in to confirm" in low or "not a bot" in low:
        return "YouTube vill att vi loggar in för att visa att vi inte är en robot (blockering av IP-adressen). Vänta en stund och försök igen."
    if "private video" in low:
        return "Videon är privat"
    if "video unavailable" in low or "is not available" in low:
        return "Videon finns inte (borttagen eller spärrad)"
    if "age" in low and ("restrict" in low or "confirm your age" in low):
        return "Videon har åldersgräns och kan inte visas utan inloggning"
    if "javascript runtime" in low or "js runtime" in low or "deno" in low:
        return "yt-dlp hittar ingen JavaScript-motor (Deno). Uppdatera JPTV Web-containern."
    return text[:300]


WORKER = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ytdlp_worker.py")


class _WorkerGone(Exception):
    """yt-dlp-processen dog eller startade inte. counts=False: vi stoppade den själva."""

    def __init__(self, reason: str, counts: bool = True):
        super().__init__(reason)
        self.counts = counts


class _Worker:
    def __init__(self, proc: asyncio.subprocess.Process):
        self.proc = proc
        self.pending: dict[int, asyncio.Future] = {}
        self.next_id = 0
        self.reader: asyncio.Future | None = None
        self.stopped = False


class YtDlp:
    """
    yt-dlp, helst i en process som hålls igång (ytdlp_worker.py) så att varje video slipper starta
    Python och läsa in yt-dlp. Går det inte körs yt-dlp som vanligt program. Nyare versioner
    installeras i lib_dir och går före den inbyggda.
    """

    def __init__(self, lib_dir: str):
        self.lib_dir = lib_dir
        self.version: str | None = None
        self.updated_at = 0.0
        self.update_error: str | None = None
        self.updating = False
        self._w: _Worker | None = None
        self._w_lock = asyncio.Lock()
        self.worker_failures = 0
        self.worker_error: str | None = None
        self._stamp = os.path.join(lib_dir, ".updated")
        try:
            self.updated_at = os.path.getmtime(self._stamp)
        except OSError:
            pass

    def _cmd(self) -> list[str]:
        custom = os.environ.get("JPTV_YTDLP")
        return [custom] if custom else [sys.executable, "-m", "yt_dlp"]

    def _env(self) -> dict:
        env = dict(os.environ)
        if os.path.isdir(self.lib_dir):
            env["PYTHONPATH"] = self.lib_dir + (os.pathsep + env["PYTHONPATH"] if env.get("PYTHONPATH") else "")
        return env

    @property
    def worker_running(self) -> bool:
        return self._w is not None and self._w.proc.returncode is None

    async def _start_worker(self) -> _Worker:
        proc = await asyncio.create_subprocess_exec(
            sys.executable, "-u", WORKER,
            stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL,
            env=self._env(), limit=1 << 27,
        )
        try:
            line = await asyncio.wait_for(proc.stdout.readline(), 60)
            hello = json.loads(line) if line else {}
        except (asyncio.TimeoutError, ValueError):
            hello = {}
        if "ready" not in hello:
            try:
                proc.kill()
            except ProcessLookupError:
                pass
            await proc.wait()
            raise _WorkerGone("startade inte")
        w = _Worker(proc)
        w.reader = asyncio.ensure_future(self._read(w))
        return w

    async def _read(self, w: _Worker) -> None:
        try:
            while True:
                line = await w.proc.stdout.readline()
                if not line:
                    break
                try:
                    msg = json.loads(line)
                except ValueError:
                    continue
                fut = w.pending.pop(msg.get("id"), None)
                if fut is not None and not fut.done():
                    fut.set_result(msg)
        finally:
            # Processen är borta: de som väntar tar det vanliga programmet i stället.
            for fut in w.pending.values():
                if not fut.done():
                    fut.set_exception(_WorkerGone("avslutades", counts=not w.stopped))
            w.pending.clear()
            if self._w is w:
                self._w = None

    def stop(self) -> None:
        """Stänger processen (efter en uppdatering startas en ny med nya yt-dlp)."""
        w, self._w = self._w, None
        if w is not None:
            w.stopped = True
        if w is not None and w.proc.returncode is None:
            try:
                w.proc.kill()
                w.proc.stdin.close()
            except (ProcessLookupError, OSError):
                pass
            try:
                asyncio.get_running_loop().create_task(w.proc.wait())
            except RuntimeError:
                pass

    async def _run_worker(self, args: list[str], timeout: float) -> dict:
        async with self._w_lock:
            w = self._w
            if w is None or w.proc.returncode is not None:
                w = self._w = await self._start_worker()
        w.next_id += 1
        rid = w.next_id
        fut = asyncio.get_running_loop().create_future()
        w.pending[rid] = fut
        try:
            w.proc.stdin.write((json.dumps({"id": rid, "args": args}) + "\n").encode())
            await w.proc.stdin.drain()
        except (BrokenPipeError, ConnectionResetError, AttributeError) as e:
            w.pending.pop(rid, None)
            raise _WorkerGone(str(e))
        try:
            msg = await asyncio.wait_for(fut, timeout)
        except asyncio.TimeoutError:
            w.pending.pop(rid, None)
            # Något hänger sig: nästa gång en ny process.
            if self._w is w:
                self.stop()
            raise YtError("YouTube svarade inte i tid")
        if "error" in msg:
            raise YtError(_friendly(str(msg["error"])))
        return msg.get("ok") or {}

    async def run_json(self, args: list[str], timeout: float = 60) -> dict:
        if not os.environ.get("JPTV_YTDLP") and self.worker_failures < 3:
            try:
                result = await self._run_worker(args, timeout)
                self.worker_failures = 0
                return result
            except (_WorkerGone, OSError) as e:
                if getattr(e, "counts", True):
                    self.worker_failures += 1
                self.worker_error = str(e) or type(e).__name__
                log.warning("yt-dlp-processen: %s; kör yt-dlp som vanligt program", self.worker_error)
        proc = await asyncio.create_subprocess_exec(
            *self._cmd(), "-J", "--no-warnings", "--no-progress", *args,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE, env=self._env(),
        )
        try:
            out, err = await asyncio.wait_for(proc.communicate(), timeout)
        except asyncio.TimeoutError:
            proc.kill()
            raise YtError("YouTube svarade inte i tid")
        if proc.returncode != 0:
            raise YtError(_friendly(err.decode("utf-8", "replace")))
        try:
            return json.loads(out)
        except ValueError:
            raise YtError("Konstigt svar från yt-dlp")

    async def get_version(self) -> str | None:
        try:
            proc = await asyncio.create_subprocess_exec(
                *self._cmd(), "--version", stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE, env=self._env(),
            )
            out, _ = await asyncio.wait_for(proc.communicate(), 30)
            self.version = out.decode().strip() or None
        except (OSError, asyncio.TimeoutError):
            self.version = None
        return self.version

    async def update(self) -> None:
        """Senaste yt-dlp (och dess JavaScript-del) till lib_dir. Misslyckas det används den inbyggda."""
        if os.environ.get("JPTV_YTDLP") or self.updating:
            return
        self.updating = True
        try:
            os.makedirs(self.lib_dir, exist_ok=True)
            proc = await asyncio.create_subprocess_exec(
                sys.executable, "-m", "pip", "install", "--quiet", "--disable-pip-version-check", "--no-cache-dir",
                "--upgrade", "--target", self.lib_dir, "yt-dlp[default]",
                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
            )
            _, err = await asyncio.wait_for(proc.communicate(), 600)
            if proc.returncode != 0:
                self.update_error = (err.decode("utf-8", "replace").strip().splitlines() or ["pip misslyckades"])[-1][:200]
                log.warning("yt-dlp kunde inte uppdateras: %s", self.update_error)
            else:
                self.update_error = None
                with open(self._stamp, "w") as f:
                    f.write(str(time.time()))
                self.updated_at = time.time()
                self.stop()
                self.worker_failures = 0
        except (OSError, asyncio.TimeoutError) as e:
            self.update_error = str(e)[:200]
        finally:
            self.updating = False
            await self.get_version()

    async def keep_fresh(self) -> None:
        await self.get_version()
        while True:
            if time.time() - self.updated_at > 20 * 3600:
                await self.update()
            await asyncio.sleep(3600)


# ------------------------------------------------------------------ data


def _best_thumb(thumbs, prefer_width: int = 480) -> str | None:
    """Bilden närmast önskad bredd (YouTube ger många storlekar)."""
    good = [t for t in (thumbs or []) if isinstance(t, dict) and t.get("url")]
    if not good:
        return None
    with_w = [t for t in good if t.get("width")]
    if with_w:
        return min(with_w, key=lambda t: abs(int(t["width"]) - prefer_width))["url"]
    return good[-1]["url"]


def _published(info: dict) -> int | None:
    """Tidpunkt i ms från timestamp eller upload_date (ÅÅÅÅMMDD)."""
    ts = info.get("timestamp") or info.get("release_timestamp")
    if ts:
        return int(ts) * 1000
    d = info.get("upload_date") or info.get("release_date")
    if d and len(str(d)) == 8:
        try:
            return int(datetime.strptime(str(d), "%Y%m%d").timestamp() * 1000)
        except ValueError:
            return None
    return None


def video_card(e: dict) -> dict:
    """En video som kort i listor (sök, kanal)."""
    vid = e.get("id") or ""
    return {
        "id": vid,
        "title": e.get("title") or "",
        "channel": e.get("channel") or e.get("uploader") or "",
        "channelId": e.get("channel_id") or "",
        "duration": int(e["duration"]) if e.get("duration") else None,
        "views": e.get("view_count"),
        "published": _published(e),
        "thumb": _best_thumb(e.get("thumbnails")) or (f"https://i.ytimg.com/vi/{vid}/hqdefault.jpg" if vid else None),
        "live": e.get("live_status") in ("is_live", "is_upcoming") or bool(e.get("is_live")),
        "short": "/shorts/" in str(e.get("url") or ""),
    }


def channel_card(e: dict) -> dict:
    cid = e.get("channel_id") or e.get("id") or ""
    return {
        "id": cid,
        "name": e.get("channel") or e.get("title") or e.get("uploader") or "",
        "avatar": _avatar(e.get("thumbnails")),
        "subscribers": e.get("channel_follower_count"),
    }


def _avatar(thumbs) -> str | None:
    """Kanalens profilbild: den kvadratiska (inte bannern)."""
    good = [t for t in (thumbs or []) if isinstance(t, dict) and t.get("url")]
    square = [t for t in good if t.get("width") and t.get("height") and t["width"] == t["height"]]
    pick = max(square, key=lambda t: t["width"]) if square else None
    if pick is None:
        avatar = [t for t in good if "avatar" in str(t.get("id", ""))]
        pick = avatar[-1] if avatar else (good[0] if good else None)
    url = pick and pick["url"]
    if url and url.startswith("//"):
        url = "https:" + url
    return url


def parse_rss(xml_text: str, channel_id: str) -> tuple[str, list[dict]]:
    """Kanalens namn och senaste videor från YouTubes RSS-flöde."""
    root = ET.fromstring(xml_text)
    name = (root.findtext("a:title", default="", namespaces=NS) or "").strip()
    out = []
    for entry in root.findall("a:entry", NS):
        vid = entry.findtext("yt:videoId", default="", namespaces=NS)
        if not vid:
            continue
        link = entry.find("a:link", NS)
        href = link.get("href", "") if link is not None else ""
        published = entry.findtext("a:published", default="", namespaces=NS)
        try:
            ts = int(datetime.fromisoformat(published.replace("Z", "+00:00")).timestamp() * 1000)
        except ValueError:
            ts = None
        group = entry.find("media:group", NS)
        thumb = None
        views = None
        if group is not None:
            t = group.find("media:thumbnail", NS)
            thumb = t.get("url") if t is not None else None
            stats = group.find("media:community/media:statistics", NS)
            if stats is not None and stats.get("views", "").isdigit():
                views = int(stats.get("views"))
        out.append({
            "id": vid,
            "title": entry.findtext("a:title", default="", namespaces=NS),
            "channel": entry.findtext("a:author/a:name", default=name, namespaces=NS) or name,
            "channelId": channel_id,
            "duration": None,
            "views": views,
            "published": ts,
            "thumb": thumb or f"https://i.ytimg.com/vi/{vid}/hqdefault.jpg",
            "live": False,
            "short": "/shorts/" in href,
        })
    return name, out


# ------------------------------------------------------------------ förslag (relaterade videor)
#
# YouTubes egna förslag bredvid en video ("next" i deras API, utan inloggning). Svaret ber vi om
# på engelska, så att "1.2M views" och "3 years ago" går att läsa. Två format förekommer: det
# nyare lockupViewModel och det äldre compactVideoRenderer; båda läses.

WEB_CLIENT_VERSION = "2.20260708.00.00"
_BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"


def _api_base() -> str:
    return os.environ.get("JPTV_YT_API", YT)


def _dig(obj, *keys):
    for k in keys:
        if isinstance(obj, dict):
            obj = obj.get(k)
        elif isinstance(obj, list) and isinstance(k, int) and -len(obj) <= k < len(obj):
            obj = obj[k]
        else:
            return None
    return obj


def _text(x) -> str:
    """YouTubes text: {"simpleText"}, {"runs": [{"text"}]}, {"content"} eller en sträng."""
    if isinstance(x, str):
        return x
    if isinstance(x, dict):
        if isinstance(x.get("simpleText"), str):
            return x["simpleText"]
        if isinstance(x.get("content"), str):
            return x["content"]
        if isinstance(x.get("runs"), list):
            return "".join(str(r.get("text", "")) for r in x["runs"] if isinstance(r, dict))
    return ""


def _walk(obj, want: tuple[str, ...], skip: tuple[str, ...] = ()):
    """Alla {nyckel: värde} med nyckel i want, var de än ligger i svaret."""
    stack = [obj]
    while stack:
        cur = stack.pop()
        if isinstance(cur, dict):
            for k, v in cur.items():
                if k in want and isinstance(v, dict):
                    yield k, v
                elif k not in skip and isinstance(v, (dict, list)):
                    stack.append(v)
        elif isinstance(cur, list):
            stack.extend(reversed(cur))


def _browse_id(obj) -> str:
    """Första kanal-id (UC…) i en del av svaret."""
    for _, ep in _walk(obj, ("browseEndpoint",)):
        bid = ep.get("browseId") or ""
        if CHANNEL_RE.match(bid):
            return bid
    return ""


# Ord för tusen/miljon/miljard i visningstexter ("12K", "257 tn", "1,2 mn", "3 md").
_MULT = {"k": 1e3, "tn": 1e3, "t": 1e3, "m": 1e6, "mn": 1e6, "mio": 1e6, "milj": 1e6,
         "b": 1e9, "md": 1e9, "mdr": 1e9, "mrd": 1e9}


def parse_count(text: str) -> int | None:
    """
    Antal visningar, på engelska eller svenska: "1.2M views" → 1200000, "257 tn visningar" →
    257000, "1,2 mn" → 1200000, "1 234 visningar" → 1234, "No views"/"Inga visningar" → 0.
    """
    t = (text or "").strip().lower().replace("\u00a0", " ").replace("\u202f", " ")
    if not t:
        return None
    if t.startswith(("no ", "inga ")):
        return 0
    m = re.match(r"^(\d[\d.,\s]*)", t)
    if not m:
        return None
    num = m.group(1).strip()
    word = re.match(r"^([a-zåäö]+)", t[m.end():].strip())
    mult = _MULT.get(word.group(1)) if word else None
    try:
        if mult:
            return int(float(num.replace(" ", "").replace(",", ".")) * mult)
        digits = re.sub(r"\D", "", num)
        return int(digits) if digits else None
    except ValueError:
        return None


_UNIT_S = {"second": 1, "minute": 60, "hour": 3600, "day": 86400, "week": 604800, "month": 2592000, "year": 31536000}
_AGO_EN = re.compile(r"(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago", re.I)
_AGO_SV = re.compile(r"för\s+(\d+)\s+(sekund|minut|timm|tim|dag|veck|månad|år)[a-zåäö]*\s+sedan", re.I)
_SV_UNIT = {"sekund": "second", "minut": "minute", "timm": "hour", "tim": "hour", "dag": "day", "veck": "week",
            "månad": "month", "år": "year"}


def parse_ago(text: str, now: float | None = None) -> int | None:
    """"3 years ago", "Streamed 2 weeks ago", "för 3 år sedan" → ungefärlig tidpunkt i ms."""
    now = time.time() if now is None else now
    m = _AGO_EN.search(text or "")
    if m:
        unit = m.group(2).lower()
    else:
        m = _AGO_SV.search(text or "")
        if not m:
            return None
        unit = _SV_UNIT[m.group(2).lower()]
    return int((now - int(m.group(1)) * _UNIT_S[unit]) * 1000)


def _is_live_text(low: str) -> bool:
    return "watching" in low or "tittar" in low or low in ("live", "live nu", "livesänds nu")


def parse_clock(text: str) -> int | None:
    """"12:34" → 754, "1:02:03" → 3723."""
    t = (text or "").strip()
    if not re.match(r"^\d{1,3}(:\d{2}){1,2}$", t):
        return None
    secs = 0
    for part in t.split(":"):
        secs = secs * 60 + int(part)
    return secs


def _sources_url(sources, prefer: int = 480) -> str | None:
    url = _best_thumb(sources, prefer)
    if url and url.startswith("//"):
        url = "https:" + url
    return url


def _lockup_video(vm: dict) -> dict | None:
    if vm.get("contentType") not in (None, "LOCKUP_CONTENT_TYPE_VIDEO"):
        return None  # spellistor och mixar
    vid = vm.get("contentId") or ""
    if not VIDEO_RE.match(vid):
        return None
    md = _dig(vm, "metadata", "lockupMetadataViewModel") or {}
    title = _text(md.get("title"))
    channel, cid, views, published, live = "", "", None, None, False
    # Raderna under titeln: kanalen, och "1,2 mn visningar · för 3 år sedan" (en eller två delar,
    # på det språk YouTube väljer). Kanalen är delen som länkar till kanalen, annars den första.
    rows = []
    for row in _dig(md, "metadata", "contentMetadataViewModel", "metadataRows") or []:
        parts = []
        for part in (row or {}).get("metadataParts") or []:
            raw = (part or {}).get("text")
            t = _text(raw).strip()
            if t:
                parts.append((t, raw))
        if parts:
            rows.append(parts)
    for parts in rows:
        for t, raw in parts:
            bid = _browse_id(raw)
            if bid:
                cid, channel = bid, t
                break
        if cid:
            break
    if not channel and rows and not re.match(r"^\d", rows[0][0][0]):
        channel = rows[0][0][0]
    for parts in rows:
        for t, _raw in parts:
            if t == channel:
                continue
            for piece in re.split(r"\s*[•·]\s*", t):
                low = piece.lower()
                if not piece:
                    continue
                if _is_live_text(low):
                    live = True
                elif (ago := parse_ago(piece)) is not None:
                    published = published or ago
                elif views is None and (re.match(r"^\d", piece) or low.startswith(("no ", "inga "))):
                    views = parse_count(piece)
    image = md.get("image") or {}
    cid = cid or _browse_id(image)
    avatar = None
    for _, av in _walk(image, ("avatarViewModel",)):
        avatar = _sources_url(_dig(av, "image", "sources"), 88)
        if avatar:
            break
    duration = None
    for _, badge in _walk(vm.get("contentImage") or {}, ("thumbnailBadgeViewModel",)):
        text = _text(badge.get("text")).strip()
        style = str(badge.get("badgeStyle") or "")
        if parse_clock(text) is not None:
            duration = parse_clock(text)
        elif "LIVE" in style or text.upper() in ("LIVE", "UPCOMING", "PREMIERE"):
            live = True
    thumb = _sources_url(_dig(vm, "contentImage", "thumbnailViewModel", "image", "sources"))
    return {
        "id": vid, "title": title, "channel": channel, "channelId": cid, "avatar": avatar,
        "duration": duration, "views": views, "published": published,
        "thumb": thumb or f"https://i.ytimg.com/vi/{vid}/hqdefault.jpg",
        "live": live, "short": False,
    }


def _renderer_video(r: dict) -> dict | None:
    vid = r.get("videoId") or ""
    if not VIDEO_RE.match(vid):
        return None
    by = r.get("longBylineText") or r.get("shortBylineText") or r.get("ownerText") or {}
    view_text = _text(r.get("viewCountText")) or _text(r.get("shortViewCountText"))
    length = _text(r.get("lengthText"))
    live = _is_live_text(view_text.lower())
    for _, o in _walk(r.get("thumbnailOverlays") or [], ("thumbnailOverlayTimeStatusRenderer",)):
        length = length or _text(o.get("text"))
        live = live or o.get("style") in ("LIVE", "UPCOMING")
    for _, b in _walk(r.get("badges") or [], ("metadataBadgeRenderer",)):
        live = live or "LIVE" in str(b.get("style") or "")
    avatar = _sources_url(_dig(r, "channelThumbnail", "thumbnails"), 88) or _sources_url(
        _dig(r, "channelThumbnailSupportedRenderers", "channelThumbnailWithLinkRenderer", "thumbnail", "thumbnails"), 88)
    return {
        "id": vid,
        "title": _text(r.get("title")) or _text(r.get("headline")),
        "channel": _text(by),
        "channelId": _browse_id(by),
        "avatar": avatar,
        "duration": parse_clock(length),
        "views": parse_count(view_text) if not live else None,
        "published": parse_ago(_text(r.get("publishedTimeText"))),
        "thumb": _sources_url(_dig(r, "thumbnail", "thumbnails")) or f"https://i.ytimg.com/vi/{vid}/hqdefault.jpg",
        "live": live,
        "short": bool(_dig(r, "navigationEndpoint", "reelWatchEndpoint")),
    }


def parse_related(data: dict) -> list[dict]:
    """Förslagen bredvid en video, i YouTubes ordning."""
    root = _dig(data, "contents", "twoColumnWatchNextResults", "secondaryResults") or data
    skip = ("playerOverlays", "engagementPanels", "frameworkUpdates", "topbar")
    out, seen = [], set()
    for key, value in _walk(root, ("lockupViewModel", "compactVideoRenderer", "videoRenderer"), skip):
        v = _lockup_video(value) if key == "lockupViewModel" else _renderer_video(value)
        if v and v["id"] not in seen and v["title"]:
            seen.add(v["id"])
            out.append(v)
    return out


# ------------------------------------------------------------------ kommentarer


def comment_token(vid: str) -> str:
    """Första "sidan" av kommentarerna till en video (samma som yt-dlp bygger)."""
    raw = f'\x12\r\x12\x0b{vid}\x18\x062\'"\x11"\x0b{vid}0\x00x\x020\x00B\x10comments-section'
    return base64.b64encode(raw.encode()).decode()


def _cont_token(obj) -> str | None:
    for _, cmd in _walk(obj, ("continuationCommand",)):
        if cmd.get("token"):
            return cmd["token"]
    for _, data in _walk(obj, ("nextContinuationData",)):
        if data.get("continuation"):
            return data["continuation"]
    return None


def _comment_entity(vm: dict, entities: dict) -> dict | None:
    """Nyare formatet: commentViewModel pekar på data i frameworkUpdates (commentEntityPayload)."""
    payload = (entities.get(vm.get("commentKey")) or {}).get("commentEntityPayload")
    if not isinstance(payload, dict):
        return None
    props = payload.get("properties") or {}
    author = payload.get("author") or {}
    toolbar = payload.get("toolbar") or {}
    state = (entities.get(vm.get("toolbarStateKey")) or {}).get("engagementToolbarStateEntityPayload") or {}
    likes = toolbar.get("likeCountNotliked") or toolbar.get("likeCountA11y") or ""
    return {
        "id": props.get("commentId") or "",
        "text": _text(props.get("content")),
        "author": author.get("displayName") or "",
        "authorId": author.get("channelId") or "",
        "avatar": author.get("avatarThumbnailUrl"),
        "creator": bool(author.get("isCreator")),
        "verified": bool(author.get("isVerified")),
        "likes": parse_count(str(likes)) or 0,
        "replies": parse_count(str(toolbar.get("replyCount") or "")) or 0,
        "published": parse_ago(props.get("publishedTime") or ""),
        "pinned": vm.get("pinnedText") is not None,
        "hearted": state.get("heartState") == "TOOLBAR_HEART_STATE_HEARTED",
    }


def _comment_renderer(r: dict) -> dict | None:
    """Äldre formatet (commentRenderer)."""
    if not r.get("commentId"):
        return None
    return {
        "id": r["commentId"],
        "text": _text(r.get("contentText")),
        "author": _text(r.get("authorText")),
        "authorId": _browse_id(r.get("authorEndpoint") or {}),
        "avatar": _sources_url(_dig(r, "authorThumbnail", "thumbnails"), 88),
        "creator": bool(r.get("authorIsChannelOwner")),
        "verified": False,
        "likes": parse_count(_text(r.get("voteCount"))) or 0,
        "replies": int(r.get("replyCount") or 0),
        "published": parse_ago(_text(r.get("publishedTimeText"))),
        "pinned": bool(r.get("pinnedCommentBadge")),
        "hearted": bool(_dig(r, "actionButtons", "commentActionButtonsRenderer", "creatorHeart")),
    }


def parse_comments(resp: dict) -> tuple[dict | None, list[dict], str | None]:
    """(rubriken med antal och sortering, kommentarerna, nästa sida)."""
    entities = {}
    for m in _dig(resp, "frameworkUpdates", "entityBatchUpdate", "mutations") or []:
        if isinstance(m, dict) and m.get("entityKey"):
            entities[m["entityKey"]] = m.get("payload") or {}
    items = []
    for ep in resp.get("onResponseReceivedEndpoints") or []:
        for key in ("reloadContinuationItemsCommand", "appendContinuationItemsAction"):
            items += _dig(ep, key, "continuationItems") or []
    header, comments, nxt = None, [], None
    for it in items:
        if not isinstance(it, dict):
            continue
        if "commentsHeaderRenderer" in it:
            header = it["commentsHeaderRenderer"]
        elif "commentThreadRenderer" in it or "commentViewModel" in it or "commentRenderer" in it:
            thread = it.get("commentThreadRenderer") or {}
            vm = _dig(thread, "commentViewModel", "commentViewModel") or it.get("commentViewModel") or {}
            c = _comment_entity(vm, entities) if vm else None
            if c is None:
                r = _dig(thread, "comment", "commentRenderer") or thread.get("commentRenderer") or it.get("commentRenderer")
                c = _comment_renderer(r) if isinstance(r, dict) else None
            if c and c["id"]:
                replies = _dig(thread, "replies", "commentRepliesRenderer")
                c["repliesToken"] = _cont_token(replies) if replies else None
                comments.append(c)
        elif "continuationItemRenderer" in it:
            nxt = _cont_token(it["continuationItemRenderer"])
    return header, comments, nxt


def _diversify(items: list[dict], per_channel: int, limit: int) -> list[dict]:
    """Bästa först, men högst per_channel från samma kanal tills listan är full."""
    out, later, count = [], [], {}
    for it in items:
        cid = it.get("channelId") or it.get("channel") or ""
        if count.get(cid, 0) < per_channel:
            count[cid] = count.get(cid, 0) + 1
            out.append(it)
        else:
            later.append(it)
        if len(out) >= limit:
            return out
    return (out + later)[:limit]


def parse_import(text: str) -> list[dict]:
    """
    Prenumerationer från FreeTube, NewPipe, YouTube Takeout (CSV) eller OPML.
    Allt som innehåller ett kanal-id (UC…) och gärna ett namn räknas.
    """
    found: dict[str, str] = {}

    def add(cid: str, name: str = "") -> None:
        cid = (cid or "").strip()
        if CHANNEL_RE.match(cid) and (cid not in found or not found[cid]):
            found[cid] = (name or "").strip()

    stripped = text.strip()
    # NewPipe (en JSON-fil) eller FreeTube (en JSON per rad).
    docs = []
    try:
        docs.append(json.loads(stripped))
    except ValueError:
        for line in stripped.splitlines():
            try:
                docs.append(json.loads(line))
            except ValueError:
                continue
    for data in docs:
        if not isinstance(data, dict):
            continue
        for s in data.get("subscriptions") or []:
            if not isinstance(s, dict):
                continue
            cid = s.get("id") or ""
            if not cid and s.get("url"):
                m = re.search(r"/channel/(UC[0-9A-Za-z_-]{22})", s["url"])
                cid = m.group(1) if m else ""
            add(cid, s.get("name") or s.get("title") or "")
    # Takeout: "Channel Id,Channel Url,Channel Title" (svensk export: Kanal-id …).
    if not found and stripped:
        for row in csv.reader(io.StringIO(stripped)):
            if row and CHANNEL_RE.match(row[0].strip()):
                add(row[0], row[2] if len(row) > 2 else "")
    # OPML eller annat: leta efter kanal-id i texten.
    if not found:
        for m in re.finditer(r'(?:channel_id=|/channel/)(UC[0-9A-Za-z_-]{22})', stripped):
            # Namnet står ofta i samma rad (OPML: text="…" eller title="…").
            line_start = stripped.rfind("\n", 0, m.start()) + 1
            line_end = stripped.find("\n", m.end())
            line = stripped[line_start: line_end if line_end >= 0 else len(stripped)]
            nm = re.search(r'(?:title|text)="([^"]*)"', line)
            add(m.group(1), nm.group(1) if nm else "")
    return [{"id": cid, "name": name} for cid, name in found.items()]


# ------------------------------------------------------------------ HLS av DASH-filer


def parse_index(data: bytes):
    """
    Läser början av en DASH-mp4 (ftyp, moov, sidx). Returnerar (init_längd, segment) där segment
    är (offset, längd, sekunder), ("more", behövd_längd) om sidx inte fått plats, eller None.
    """
    off = 0
    while off + 8 <= len(data):
        size, typ = struct.unpack(">I4s", data[off:off + 8])
        hdr = 8
        if size == 1:
            if off + 16 > len(data):
                return ("more", off + 16)
            size = struct.unpack(">Q", data[off + 8:off + 16])[0]
            hdr = 16
        elif size == 0:
            return None
        if typ == b"sidx":
            if off + size > len(data):
                return ("more", off + size)
            p = off + hdr
            version = data[p]
            p += 4
            _ref_id, timescale = struct.unpack(">II", data[p:p + 8])
            p += 8
            if version == 0:
                _ept, first = struct.unpack(">II", data[p:p + 8])
                p += 8
            else:
                _ept, first = struct.unpack(">QQ", data[p:p + 16])
                p += 16
            p += 2
            (count,) = struct.unpack(">H", data[p:p + 2])
            p += 2
            seg_off = off + size + first
            segments = []
            for _ in range(count):
                ref, dur, _sap = struct.unpack(">III", data[p:p + 12])
                p += 12
                length = ref & 0x7FFFFFFF
                segments.append((seg_off, length, dur / timescale if timescale else 0.0))
                seg_off += length
            return off, segments
        if typ in (b"moof", b"mdat"):
            return None
        off += size
    return ("more", off + 8)


def media_playlist(itag: str, init_len: int, segments: list) -> str:
    target = max(1, math.ceil(max((s[2] for s in segments), default=1)))
    lines = [
        "#EXTM3U", "#EXT-X-VERSION:7", f"#EXT-X-TARGETDURATION:{target}", "#EXT-X-MEDIA-SEQUENCE:0",
        "#EXT-X-PLAYLIST-TYPE:VOD", "#EXT-X-INDEPENDENT-SEGMENTS",
        f'#EXT-X-MAP:URI="s/{itag}.mp4",BYTERANGE="{init_len}@0"',
    ]
    for offset, length, dur in segments:
        lines += [f"#EXTINF:{dur:.3f},", f"#EXT-X-BYTERANGE:{length}@{offset}", f"s/{itag}.mp4"]
    lines.append("#EXT-X-ENDLIST")
    return "\n".join(lines) + "\n"


def subtitle_playlist(lang: str, duration: float) -> str:
    d = max(1.0, float(duration or 1))
    return "\n".join([
        "#EXTM3U", "#EXT-X-VERSION:3", f"#EXT-X-TARGETDURATION:{math.ceil(d)}", "#EXT-X-MEDIA-SEQUENCE:0",
        "#EXT-X-PLAYLIST-TYPE:VOD", f"#EXTINF:{d:.3f},", f"{lang}.vtt", "#EXT-X-ENDLIST",
    ]) + "\n"


def pick_formats(info: dict, max_height: int = 1080) -> tuple[list[dict], dict | None]:
    """Bild (H.264, en per höjd upp till max_height) och ljud (AAC) som går att spela utan inloggning."""
    def usable(f: dict) -> bool:
        note = str(f.get("format_note") or "").upper()
        return (
            bool(f.get("url")) and f.get("protocol") in ("https", "http") and not f.get("has_drm")
            and not f.get("fragments") and "MISSING POT" not in note and "DAMAGED" not in note
        )

    vcodecs, acodecs = _codecs()
    fmts = [f for f in info.get("formats") or [] if usable(f)]
    by_height: dict[int, dict] = {}
    for f in fmts:
        if f.get("vcodec") in (None, "none") or f.get("acodec") not in (None, "none"):
            continue
        if f.get("ext") != "mp4" or not str(f.get("vcodec", "")).startswith(vcodecs):
            continue
        h = int(f.get("height") or 0)
        if not h or h > max_height or "sr" in str(f.get("format_id", "")).split("-"):
            continue
        cur = by_height.get(h)
        if cur is None or (f.get("tbr") or 0) > (cur.get("tbr") or 0):
            by_height[h] = f
    videos = [by_height[h] for h in sorted(by_height)]
    audios = [
        f for f in fmts
        if f.get("vcodec") == "none" and f.get("ext") in ("m4a", "mp4") and str(f.get("acodec", "")).startswith(acodecs)
        and "drc" not in str(f.get("format_id", ""))
    ]
    # Originalspråket först (inte dubbat), sedan högst kvalitet.
    audios.sort(key=lambda f: ((f.get("language_preference") or 0), f.get("abr") or f.get("tbr") or 0), reverse=True)
    return videos, (audios[0] if audios else None)


def pick_subtitles(info: dict) -> list[dict]:
    """Svenska och engelska undertexter; riktiga före automatiska. Som (lang, namn, url)."""
    out = []
    for kind, source in (("", info.get("subtitles") or {}), (" (automatisk)", info.get("automatic_captions") or {})):
        for want, name in (("sv", "Svenska"), ("en", "Engelska")):
            if any(s["lang"] == want for s in out):
                continue
            for lang, tracks in source.items():
                base = lang.split("-")[0]
                # Automatiska översättningar ("en-sv") räknas inte; bara originalet.
                if base != want or ("-" in lang and not lang.lower().endswith(("-orig",)) and kind):
                    continue
                vtt = next((t for t in tracks if t.get("ext") == "vtt" and t.get("url")), None)
                if vtt:
                    out.append({"lang": want, "name": name + kind, "url": vtt["url"]})
                    break
    return out


def pick_storyboard(info: dict) -> dict | None:
    """
    YouTubes förhandsbilder för spolning ("storyboard"): jpg-ark med ett rutnät av små bilder.
    Den största upp till 320 px bred väljs (större finns sällan, mindre blir suddiga på TV:n).
    """
    boards = [f for f in info.get("formats") or [] if f.get("format_note") == "storyboard" and f.get("fragments")
              and f.get("rows") and f.get("columns") and f.get("width") and f.get("height") and f.get("fps")]
    if not boards:
        return None
    fit = [f for f in boards if f["width"] <= 320]
    return max(fit, key=lambda f: f["width"]) if fit else min(boards, key=lambda f: f["width"])


def master_playlist(videos: list[dict], audio: dict, subs: list[dict]) -> str:
    lines = ["#EXTM3U", "#EXT-X-VERSION:7", "#EXT-X-INDEPENDENT-SEGMENTS"]
    aitag = audio["format_id"]
    lines.append(f'#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="Ljud",DEFAULT=YES,AUTOSELECT=YES,URI="{aitag}.m3u8"')
    for s in subs:
        lines.append(
            f'#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="s",NAME="{s["name"]}",LANGUAGE="{s["lang"]}",'
            f'DEFAULT=NO,AUTOSELECT=NO,URI="sub/{s["lang"]}.m3u8"'
        )
    abps = int((audio.get("tbr") or audio.get("abr") or 128) * 1000)
    acodec = audio.get("acodec") or "mp4a.40.2"
    for v in videos:
        bps = int((v.get("tbr") or 1000) * 1000) + abps
        attrs = [f"BANDWIDTH={bps}", f'CODECS="{v.get("vcodec")},{acodec}"', 'AUDIO="a"']
        if v.get("width") and v.get("height"):
            attrs.insert(1, f"RESOLUTION={v['width']}x{v['height']}")
        if v.get("fps"):
            attrs.append(f"FRAME-RATE={float(v['fps']):.3f}")
        if subs:
            attrs.append('SUBTITLES="s"')
        lines += ["#EXT-X-STREAM-INF:" + ",".join(attrs), f"{v['format_id']}.m3u8"]
    return "\n".join(lines) + "\n"


def _expires(url: str) -> float:
    try:
        return float(parse_qs(urlparse(url).query).get("expire", ["0"])[0])
    except ValueError:
        return 0.0


# ------------------------------------------------------------------ lagring


class YtStore:
    """Prenumerationer och tittade positioner i youtube.json."""

    def __init__(self, directory: str):
        self.path = os.path.join(directory, "youtube.json")
        self._lock = threading.Lock()
        self.subs: list[dict] = []
        self.progress: dict[str, dict] = {}
        # "Inte intresserad" (videor) och kanaler som inte ska föreslås.
        self.dismissed: list[str] = []
        self.blocked: list[dict] = []
        try:
            with open(self.path, encoding="utf-8") as f:
                data = json.load(f)
            self.subs = [s for s in data.get("subs") or [] if isinstance(s, dict) and CHANNEL_RE.match(s.get("id", ""))]
            self.progress = {k: v for k, v in (data.get("progress") or {}).items() if isinstance(v, dict)}
            self.dismissed = [d for d in data.get("dismissed") or [] if isinstance(d, str) and VIDEO_RE.match(d)]
            self.blocked = [b for b in data.get("blocked") or [] if isinstance(b, dict) and CHANNEL_RE.match(b.get("id", ""))]
        except (OSError, ValueError):
            pass

    def _save(self) -> None:
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({"subs": self.subs, "progress": self.progress, "dismissed": self.dismissed, "blocked": self.blocked},
                      f, ensure_ascii=False, indent=1)
        os.replace(tmp, self.path)

    def subscribed(self, cid: str) -> bool:
        return any(s["id"] == cid for s in self.subs)

    def subscribe(self, cid: str, name: str = "", avatar: str | None = None) -> bool:
        if not CHANNEL_RE.match(cid or ""):
            raise ValueError("ogiltigt kanal-id")
        with self._lock:
            for s in self.subs:
                if s["id"] == cid:
                    if name and not s.get("name"):
                        s["name"] = name
                    if avatar and not s.get("avatar"):
                        s["avatar"] = avatar
                    self._save()
                    return False
            self.subs.append({"id": cid, "name": name or "", "avatar": avatar, "added": int(time.time() * 1000)})
            self.subs.sort(key=lambda s: (s.get("name") or "").lower())
            self._save()
            return True

    def unsubscribe(self, cid: str) -> bool:
        with self._lock:
            before = len(self.subs)
            self.subs = [s for s in self.subs if s["id"] != cid]
            if len(self.subs) != before:
                self._save()
                return True
            return False

    def update_channel(self, cid: str, name: str | None = None, avatar: str | None = None) -> None:
        with self._lock:
            for s in self.subs:
                if s["id"] == cid:
                    changed = False
                    if name and s.get("name") != name:
                        s["name"] = name
                        changed = True
                    if avatar and not s.get("avatar"):
                        s["avatar"] = avatar
                        changed = True
                    if changed:
                        self._save()

    def set_progress(self, vid: str, pos: float, dur: float, meta: dict) -> dict:
        if not VIDEO_RE.match(vid or ""):
            raise ValueError("ogiltigt video-id")
        with self._lock:
            p = self.progress.get(vid, {})
            p.update({
                "pos": max(0.0, float(pos)), "dur": max(0.0, float(dur or p.get("dur") or 0)),
                "at": int(time.time() * 1000),
            })
            for k in ("title", "channel", "channelId", "thumb"):
                if meta.get(k) and isinstance(meta[k], str):
                    p[k] = meta[k]
            for k in ("views", "published"):
                if isinstance(meta.get(k), (int, float)) and not isinstance(meta[k], bool):
                    p[k] = int(meta[k])
            p["watched"] = bool(p["dur"] and p["pos"] >= p["dur"] * 0.9) or p.get("watched", False) and p["pos"] < 30
            self.progress[vid] = p
            # Högst 500 sparade; de äldsta försvinner.
            if len(self.progress) > 500:
                for k, _ in sorted(self.progress.items(), key=lambda kv: kv[1].get("at", 0))[: len(self.progress) - 500]:
                    del self.progress[k]
            self._save()
            return p

    def position(self, vid: str) -> float:
        p = self.progress.get(vid)
        if not p or p.get("watched"):
            return 0.0
        return float(p.get("pos") or 0)

    def continue_watching(self, limit: int = 20) -> list[dict]:
        items = []
        for vid, p in sorted(self.progress.items(), key=lambda kv: kv[1].get("at", 0), reverse=True):
            dur = p.get("dur") or 0
            if p.get("watched") or p.get("pos", 0) < 30 or (dur and p["pos"] > dur * 0.92):
                continue
            items.append(self._card(vid, p))
            if len(items) >= limit:
                break
        return items

    @staticmethod
    def _card(vid: str, p: dict) -> dict:
        dur = p.get("dur") or 0
        card = {
            "id": vid, "title": p.get("title") or "", "channel": p.get("channel") or "", "channelId": p.get("channelId") or "",
            "thumb": p.get("thumb") or f"https://i.ytimg.com/vi/{vid}/hqdefault.jpg", "duration": int(dur) if dur else None,
            "published": p.get("published"), "views": p.get("views"), "live": False, "short": False,
            "watched": bool(p.get("watched")), "at": p.get("at"),
        }
        if not p.get("watched") and p.get("pos", 0) >= 30:
            card["position"] = int(p["pos"])
        return card

    def seen(self, vid: str) -> bool:
        """Sedd eller påbörjad på riktigt (mer än en minut): föreslås inte igen."""
        p = self.progress.get(vid)
        return bool(p and (p.get("watched") or p.get("pos", 0) >= 60))

    def history(self, limit: int = 200) -> list[dict]:
        items = []
        for vid, p in sorted(self.progress.items(), key=lambda kv: kv[1].get("at", 0), reverse=True):
            if p.get("watched") or p.get("pos", 0) >= 30:
                items.append(self._card(vid, p))
                if len(items) >= limit:
                    break
        return items

    def forget(self, vid: str | None = None) -> int:
        """Tar bort en video ur historiken, eller allt (vid=None)."""
        with self._lock:
            if vid is None:
                n = len(self.progress)
                self.progress = {}
            else:
                n = 1 if self.progress.pop(vid, None) is not None else 0
            if n:
                self._save()
            return n

    def dismiss(self, vid: str) -> None:
        if not VIDEO_RE.match(vid or ""):
            raise ValueError("ogiltigt video-id")
        with self._lock:
            if vid not in self.dismissed:
                self.dismissed.append(vid)
                self.dismissed = self.dismissed[-2000:]
                self._save()

    def block(self, cid: str, name: str = "") -> None:
        if not CHANNEL_RE.match(cid or ""):
            raise ValueError("ogiltigt kanal-id")
        with self._lock:
            if not any(b["id"] == cid for b in self.blocked):
                self.blocked.append({"id": cid, "name": name or ""})
                self._save()

    def unblock(self, cid: str) -> bool:
        with self._lock:
            before = len(self.blocked)
            self.blocked = [b for b in self.blocked if b["id"] != cid]
            if len(self.blocked) != before:
                self._save()
                return True
            return False

    def blocked_ids(self) -> set[str]:
        return {b["id"] for b in self.blocked}

    def decorate(self, items: list[dict]) -> list[dict]:
        """Sedda och påbörjade videor markeras i listor."""
        for it in items:
            p = self.progress.get(it.get("id", ""))
            if p:
                it["watched"] = bool(p.get("watched"))
                if not p.get("watched") and p.get("pos", 0) >= 30:
                    it["position"] = int(p["pos"])
                if not it.get("duration") and p.get("dur"):
                    it["duration"] = int(p["dur"])
        return items


# ------------------------------------------------------------------ snabbstart


def fast_formats_ok(info: dict, max_height: int) -> bool:
    """
    Duger snabbstartens svar? Spelbara bild- och ljudfiler, och ingen högre upplösning i listan
    som vi inte kan spela (då ger den vanliga hämtningen troligen bättre kvalitet).
    """
    videos, audio = pick_formats(info, max_height)
    if not videos or not audio or not info.get("duration"):
        return False
    vcodecs = _codecs()[0]
    best = max((f.get("height") or 0) for f in videos)
    listed = max((f.get("height") or 0 for f in info.get("formats") or []
                  if str(f.get("vcodec") or "").startswith(vcodecs) and (f.get("height") or 0) <= max_height), default=0)
    return best >= listed


class FastStart:
    """
    Snabbstart: yt-dlp frågar bara YouTubes visionOS-klient. Den behöver ingen JavaScript (Deno)
    och bara en förfrågan, så videon hämtas på en bråkdel av tiden. De första gångerna (och då och
    då) görs även den vanliga hämtningen i bakgrunden: ger den bättre kvalitet stängs snabbstarten
    av i en vecka. Misslyckas den tre gånger i rad stängs den av ett dygn.
    """

    CHECKS = 3

    def __init__(self, settings):
        self.settings = settings
        d = settings.get("youtube_fast") or {}
        self.state = d.get("state") if d.get("state") in ("trial", "on", "off") else "trial"
        self.checks = int(d.get("checks") or 0)
        self.until = float(d.get("until") or 0)
        self.reason = d.get("reason")
        self.failures = 0
        self.count = 0

    def _save(self) -> None:
        self.settings.set(youtube_fast={"state": self.state, "checks": self.checks, "until": self.until, "reason": self.reason})

    def enabled(self) -> bool:
        if self.state == "off" and time.time() > self.until:
            self.state, self.checks, self.reason = "trial", 0, None
            self._save()
        return self.state != "off"

    def should_check(self) -> bool:
        self.count += 1
        return self.state == "trial" or self.count % 25 == 0

    def compared(self, fast_best: int, full_best: int) -> None:
        if full_best > fast_best:
            self.disable(f"gav {fast_best}p när {full_best}p fanns", 7 * 86400)
        elif self.state == "trial":
            self.checks += 1
            if self.checks >= self.CHECKS:
                self.state = "on"
            self._save()

    def failed(self, why: str) -> None:
        self.failures += 1
        if self.failures >= 3:
            self.disable(why, 86400)

    def worked(self) -> None:
        self.failures = 0

    def disable(self, why: str, seconds: float) -> None:
        self.state, self.until, self.reason, self.checks, self.failures = "off", time.time() + seconds, why, 0, 0
        self._save()
        log.info("Snabbstart av: %s", why)

    def describe(self) -> str:
        if self.state == "on":
            return "på"
        if self.state == "trial":
            return f"provas ({self.checks} av {self.CHECKS} kontroller klara)"
        return f"av till {datetime.fromtimestamp(self.until).strftime('%d/%m %H:%M')} ({self.reason or 'fel'})"


# ------------------------------------------------------------------ tjänsten


class YouTube:
    def __init__(self, http: aiohttp.ClientSession, store, config_dir: str):
        self.http = http
        self.settings = store
        self.data = YtStore(config_dir)
        self.ytdlp = YtDlp(os.path.join(config_dir, "ytdlp"))
        self.fast = FastStart(store)
        self.last_mode: str | None = None
        self._videos: dict[str, dict] = {}
        self._locks: dict[str, asyncio.Lock] = {}
        self._feeds: dict[str, tuple[float, list[dict]]] = {}
        self._channels: dict[str, tuple[float, dict]] = {}
        self._sponsor: dict[str, tuple[float, list]] = {}
        self._search: dict[str, tuple[float, dict]] = {}
        # Förslag: YouTubes relaterade videor per video, och den färdiga startsidan.
        self._related: dict[str, tuple[float, list[dict]]] = {}
        self._warming: set[str] = set()
        self._related_tasks: dict[str, asyncio.Future] = {}
        self._sheets: dict[str, bytes] = {}
        self._comments: dict[str, tuple[float, dict]] = {}
        self._prefetching: set[str] = set()
        self._prefetch_times: list[float] = []
        self.last_extract: float | None = None
        self._bg: set[asyncio.Future] = set()
        self._recs: tuple[float, tuple, dict] | None = None
        self._recs_lock = asyncio.Lock()
        self.recs_stats = {"youtube": 0, "search": 0, "failed": 0, "error": None}

    # ---------- inställningar

    @property
    def max_height(self) -> int:
        try:
            return int(self.settings.get("youtube_max_height", 1080) or 1080)
        except (TypeError, ValueError):
            return 1080

    @property
    def show_shorts(self) -> bool:
        return bool(self.settings.get("youtube_shorts", False))

    def status(self) -> dict:
        return {
            "ytdlp": self.ytdlp.version,
            "worker": self.ytdlp.worker_running,
            "workerError": self.ytdlp.worker_error,
            "deno": bool(shutil.which("deno")),
            "updatedAt": int(self.ytdlp.updated_at * 1000) if self.ytdlp.updated_at else None,
            "updating": self.ytdlp.updating,
            "updateError": self.ytdlp.update_error,
            "subs": len(self.data.subs),
            "maxHeight": self.max_height,
            "shorts": self.show_shorts,
            "history": len(self.data.history(10000)),
            "blocked": list(self.data.blocked),
            "recs": dict(self.recs_stats),
            "lastExtract": self.last_extract,
            "lastMode": self.last_mode,
            "fast": self.fast.describe(),
        }

    # ---------- flöden

    async def channel_feed(self, cid: str, max_age: float = 1800) -> list[dict]:
        hit = self._feeds.get(cid)
        if hit and time.monotonic() - hit[0] < max_age:
            return hit[1]
        try:
            async with self.http.get(_rss_url(cid), timeout=aiohttp.ClientTimeout(total=20)) as r:
                if r.status != 200:
                    raise YtError(f"RSS svarade {r.status}")
                text = await r.text()
            name, items = parse_rss(text, cid)
            if name:
                self.data.update_channel(cid, name=name)
        except (aiohttp.ClientError, asyncio.TimeoutError, ET.ParseError, YtError) as e:
            log.info("Flöde för %s gick inte att hämta: %s", cid, e)
            return hit[1] if hit else []
        self._feeds[cid] = (time.monotonic(), items)
        return items

    async def feed(self, limit: int = 120) -> list[dict]:
        subs = list(self.data.subs)
        sem = asyncio.Semaphore(8)

        async def one(s):
            async with sem:
                return await self.channel_feed(s["id"])

        lists = await asyncio.gather(*(one(s) for s in subs))
        items = [i for lst in lists for i in lst if self.show_shorts or not i.get("short")]
        items.sort(key=lambda i: i.get("published") or 0, reverse=True)
        seen: set[str] = set()
        items = [i for i in items if not (i["id"] in seen or seen.add(i["id"]))]
        return self.data.decorate([dict(i) for i in items[:limit]])

    async def keep_feeds_fresh(self) -> None:
        """Flödet hämtas i bakgrunden, så att sidan öppnas direkt."""
        while True:
            try:
                if self.data.subs:
                    await self.feed()
                if self.data.subs or self.data.progress:
                    await self.recommendations()
            except Exception:
                log.exception("YouTube-flödet")
            await asyncio.sleep(1800)

    def subs_public(self) -> list[dict]:
        return [dict(s) for s in self.data.subs]

    async def home(self) -> dict:
        return {
            "continue": self.data.continue_watching(),
            "feed": await self.feed(),
            "subs": self.subs_public(),
        }

    # ---------- sök och kanaler

    async def search(self, q: str) -> dict:
        q = q.strip()
        if not q:
            return {"videos": [], "channels": []}
        hit = self._search.get(q.lower())
        if hit and time.monotonic() - hit[0] < 600:
            return hit[1]
        vids_task = self.ytdlp.run_json(["--flat-playlist", *APPROX_DATE, f"ytsearch30:{q}"])
        chans_task = self.ytdlp.run_json([
            "--flat-playlist", "--playlist-items", "1:6",
            f"{YT}/results?search_query={quote(q)}&sp=EgIQAg%253D%253D",
        ])
        vids, chans = await asyncio.gather(vids_task, chans_task, return_exceptions=True)
        if isinstance(vids, Exception) and isinstance(chans, Exception):
            raise vids if isinstance(vids, YtError) else YtError(str(vids))
        videos = [] if isinstance(vids, Exception) else [
            video_card(e) for e in vids.get("entries") or [] if VIDEO_RE.match(str(e.get("id") or ""))
        ]
        channels = [] if isinstance(chans, Exception) else [
            channel_card(e) for e in chans.get("entries") or [] if CHANNEL_RE.match(str(e.get("channel_id") or e.get("id") or ""))
        ]
        for c in channels:
            c["subscribed"] = self.data.subscribed(c["id"])
        out = {"videos": self.data.decorate([v for v in videos if self.show_shorts or not v["short"]]), "channels": channels}
        self._search[q.lower()] = (time.monotonic(), out)
        return out

    async def channel(self, cid: str) -> dict:
        if not CHANNEL_RE.match(cid):
            raise YtError("Ogiltig kanal")
        hit = self._channels.get(cid)
        # RSS-flödet har exakta datum för de senaste videorna; listan från yt-dlp bara ungefärliga.
        rss_task = asyncio.ensure_future(self.channel_feed(cid))
        if hit and time.monotonic() - hit[0] < 1800:
            info = hit[1]
        else:
            try:
                info = await self.ytdlp.run_json(["--flat-playlist", *APPROX_DATE, "--playlist-items", "1:36", f"{YT}/channel/{cid}/videos"])
            except BaseException:
                rss_task.cancel()
                raise
            self._channels[cid] = (time.monotonic(), info)
        rss = {r["id"]: r for r in await rss_task}
        name = info.get("channel") or re.sub(r"\s*-\s*Videos$", "", info.get("title") or "")
        avatar = _avatar(info.get("thumbnails"))
        self.data.update_channel(cid, name=name, avatar=avatar)
        videos = [video_card(e) for e in info.get("entries") or [] if VIDEO_RE.match(str(e.get("id") or ""))]
        for v in videos:
            v["channel"] = v["channel"] or name
            v["channelId"] = cid
            r = rss.get(v["id"])
            if r:
                v["published"] = r.get("published") or v["published"]
                v["views"] = v["views"] if v["views"] is not None else r.get("views")
        return {
            "id": cid,
            "name": name,
            "avatar": avatar,
            "description": (info.get("description") or "")[:1000],
            "subscribers": info.get("channel_follower_count"),
            "subscribed": self.data.subscribed(cid),
            "videos": self.data.decorate([v for v in videos if self.show_shorts or not v["short"]]),
        }

    # ---------- videor

    def _lock(self, vid: str) -> asyncio.Lock:
        lock = self._locks.get(vid)
        if lock is None:
            lock = self._locks[vid] = asyncio.Lock()
        return lock

    def _extract_args(self, vid: str, fast: bool) -> list[str]:
        return [
            "--no-playlist", "--cache-dir", os.path.join(self.ytdlp.lib_dir, "cache"),
            # Varken m3u8/dash-manifesten eller automatiskt översatta undertexter används: färre
            # förfrågningar och mindre JSON, så att videon startar fortare.
            "--extractor-args", "youtube:" + ("player_client=visionos;" if fast else "") + "skip=hls,dash,translated_subs",
            f"{YT}/watch?v={vid}",
        ]

    async def _video(self, vid: str, fresh: bool = False, full: bool = False) -> dict:
        """
        yt-dlp:s uppgifter om videon, med valda format. Sparas tills adresserna går ut.
        fresh: hämta på nytt (adressen gick ut). full: inte snabbstart (den gav fel).
        """
        if not VIDEO_RE.match(vid):
            raise YtError("Ogiltig video")
        async with self._lock(vid):
            v = self._videos.get(vid)
            usable = v and (not full or not v["fast"])
            if usable and not fresh and time.time() < v["expires"] - 600:
                return v
            # Flera delar fick 403 samtidigt: en ny hämtning räcker.
            if usable and fresh and time.monotonic() - v["fetched"] < 30:
                return v
            if full and v and v["fast"]:
                self.fast.failed("YouTube nekade snabbstartens adresser")
            info, fast = None, False
            started = time.monotonic()
            if not full and self.fast.enabled():
                try:
                    candidate = await self.ytdlp.run_json(self._extract_args(vid, True), timeout=45)
                    if fast_formats_ok(candidate, self.max_height):
                        info, fast = candidate, True
                        self.fast.worked()
                    else:
                        self.fast.failed("inga spelbara format")
                except YtError as e:
                    text = str(e)
                    if any(w in text for w in ("privat", "finns inte", "åldersgräns")):
                        raise
                    self.fast.failed(text)
            if info is None:
                info = await self.ytdlp.run_json(self._extract_args(vid, False), timeout=90)
            self.last_extract = round(time.monotonic() - started, 1)
            self.last_mode = "snabb" if fast else "vanlig"
            log.info("yt-dlp hämtade %s på %.1f s (%s)", vid, self.last_extract, self.last_mode)
            v = self._prepare(info, fast)
            self._videos[vid] = v
            # Gamla videor ur minnet.
            if len(self._videos) > 40:
                for k in sorted(self._videos, key=lambda k: self._videos[k]["expires"])[:10]:
                    self._videos.pop(k, None)
            if fast and self.fast.should_check():
                self._check_fast(vid, v)
            return v

    def _prepare(self, info: dict, fast: bool) -> dict:
        if info.get("is_live") or info.get("live_status") in ("is_live", "is_upcoming"):
            raise YtError("Livesändningar stöds inte än")
        videos, audio = pick_formats(info, self.max_height)
        if not videos or not audio:
            raise YtError(
                "YouTube skickade inga format som går att spela utan inloggning. "
                "Det brukar lösa sig när yt-dlp har uppdaterats (sker varje natt)."
            )
        urls = [f["url"] for f in videos + [audio]]
        expires = min((_expires(u) for u in urls if _expires(u)), default=time.time() + 4 * 3600)
        formats = {f["format_id"]: f for f in videos + [audio]}
        return {
            "info": info, "videos": videos, "audio": audio, "formats": formats, "subs": pick_subtitles(info),
            "storyboard": pick_storyboard(info), "expires": min(expires, time.time() + 5 * 3600), "index": {},
            "index_tasks": {}, "fast": fast, "fetched": time.monotonic(),
        }

    def _check_fast(self, vid: str, v: dict) -> None:
        """Den vanliga hämtningen i bakgrunden: ger snabbstarten lika bra kvalitet?"""
        fast_best = max((f.get("height") or 0) for f in v["videos"])

        async def run():
            try:
                info = await self.ytdlp.run_json(self._extract_args(vid, False), timeout=120)
            except YtError:
                return
            videos, audio = pick_formats(info, self.max_height)
            if videos and audio:
                self.fast.compared(fast_best, max((f.get("height") or 0) for f in videos))

        task = asyncio.ensure_future(run())
        self._bg.add(task)
        task.add_done_callback(self._bg.discard)

    async def video(self, vid: str) -> dict:
        sponsor_task = asyncio.ensure_future(self.sponsor_segments(vid))
        try:
            v = await self._video(vid)
        except BaseException:
            sponsor_task.cancel()
            raise
        # Spelaren ber om spellistorna strax: läs filernas index redan nu.
        self._warm_indexes(v)
        info = v["info"]
        chapters = [
            {"title": c.get("title") or "", "start": float(c.get("start_time") or 0), "end": float(c.get("end_time") or 0)}
            for c in info.get("chapters") or []
        ]
        sponsor = await sponsor_task
        sb = v.get("storyboard")
        duration = float(info.get("duration") or 0)
        out = {
            "id": vid,
            "title": info.get("title") or "",
            "channel": info.get("channel") or info.get("uploader") or "",
            "channelId": info.get("channel_id") or "",
            "description": (info.get("description") or "")[:5000],
            "duration": int(info.get("duration") or 0),
            "views": info.get("view_count"),
            "likes": info.get("like_count"),
            "published": _published(info),
            "thumb": _best_thumb(info.get("thumbnails"), 1280),
            "chapters": chapters,
            "sponsor": sponsor,
            "subtitles": [{"lang": s["lang"], "name": s["name"]} for s in v["subs"]],
            "hls": f"/yt/{vid}/master.m3u8",
            "position": self.data.position(vid),
            "subscribed": self.data.subscribed(info.get("channel_id") or ""),
            "maxHeight": max((f.get("height") or 0) for f in v["videos"]),
            # Förhandsbilder när man spolar: bild nr floor(sekund × fps), ark = nr / (rader × kolumner).
            "storyboard": {
                "width": sb["width"], "height": sb["height"], "columns": sb["columns"], "rows": sb["rows"],
                "fps": sb["fps"], "frames": max(1, int(round(sb["fps"] * duration))) if duration else
                len(sb["fragments"]) * sb["rows"] * sb["columns"],
                "sheets": len(sb["fragments"]), "url": f"/yt/{vid}/sb/{{n}}.jpg",
            } if sb else None,
        }
        return out

    async def master(self, vid: str) -> str:
        v = await self._video(vid)
        return master_playlist(v["videos"], v["audio"], v["subs"])

    async def _fetch_range(self, url: str, headers: dict, start: int, end: int) -> bytes:
        h = dict(headers)
        h["Range"] = f"bytes={start}-{end}"
        async with self.http.get(url, headers=h, timeout=aiohttp.ClientTimeout(total=30)) as r:
            if r.status in (403, 410):
                raise PermissionError(r.status)
            if r.status >= 400:
                raise YtError(f"YouTube svarade {r.status}")
            return await r.read()

    async def _load_index(self, v: dict, itag: str) -> tuple:
        f = v["formats"][itag]
        headers = f.get("http_headers") or {}
        data = await self._fetch_range(f["url"], headers, 0, 65535)
        parsed = parse_index(data)
        if isinstance(parsed, tuple) and parsed and parsed[0] == "more":
            data = await self._fetch_range(f["url"], headers, 0, int(parsed[1]) + 1023)
            parsed = parse_index(data)
        if not parsed or parsed[0] == "more":
            raise YtError("Kunde inte läsa videons index")
        v["index"][itag] = parsed
        return parsed

    def _index_task(self, v: dict, itag: str) -> asyncio.Future:
        """Indexet för ett format hämtas en gång, även om flera frågar samtidigt."""
        task = v["index_tasks"].get(itag)
        if task is None or (task.done() and (task.cancelled() or task.exception() is not None)):
            task = v["index_tasks"][itag] = asyncio.ensure_future(self._load_index(v, itag))
            # Fel hämtas av den som väntar; i bakgrunden ska de inte loggas som obehandlade.
            task.add_done_callback(lambda t: t.cancelled() or t.exception())
        return task

    def _warm_indexes(self, v: dict) -> None:
        for itag in v["formats"]:
            if itag not in v["index"]:
                self._index_task(v, itag)

    async def media(self, vid: str, itag: str) -> str:
        """Spellista för ett format: byte-intervall ur filens index (sidx)."""
        for attempt in (0, 1):
            v = await self._video(vid, fresh=attempt == 1, full=attempt == 1)
            if itag not in v["formats"]:
                raise YtError("Okänt format")
            cached = v["index"].get(itag)
            if cached:
                return media_playlist(itag, *cached)
            try:
                parsed = await asyncio.shield(self._index_task(v, itag))
            except PermissionError:
                if attempt == 0:
                    continue
                raise YtError("YouTube nekade videon (403). Prova igen om en stund.")
            return media_playlist(itag, *parsed)
        raise YtError("Kunde inte läsa videons index")

    def prefetch(self, vid: str) -> bool:
        """
        Förbereder en video man står på (TV) eller håller musen över (webben): yt-dlp, index och
        SponsorBlock i bakgrunden, så att den startar direkt när man trycker. Högst två åt gången
        och tolv i minuten, så att YouTube inte tröttnar på oss.
        """
        if not VIDEO_RE.match(vid or ""):
            return False
        v = self._videos.get(vid)
        if v and time.time() < v["expires"] - 600:
            self._warm_indexes(v)
            return True
        now = time.monotonic()
        self._prefetch_times = [t for t in self._prefetch_times if now - t < 60]
        if vid in self._prefetching or len(self._prefetching) >= 2 or len(self._prefetch_times) >= 12:
            return False
        self._prefetching.add(vid)
        self._prefetch_times.append(now)

        async def run():
            try:
                v = await self._video(vid)
                self._warm_indexes(v)
                await self.sponsor_segments(vid)
            except Exception as e:
                log.info("Förberedelse av %s misslyckades: %s", vid, e)
            finally:
                self._prefetching.discard(vid)

        task = asyncio.ensure_future(run())
        self._bg.add(task)
        task.add_done_callback(self._bg.discard)
        return True

    def subtitle_url(self, v: dict, lang: str) -> str | None:
        return next((s["url"] for s in v["subs"] if s["lang"] == lang), None)

    async def subtitle_vtt(self, vid: str, lang: str) -> str:
        v = await self._video(vid)
        url = self.subtitle_url(v, lang)
        if not url:
            raise YtError("Undertexten finns inte")
        async with self.http.get(url, timeout=aiohttp.ClientTimeout(total=30)) as r:
            if r.status >= 400:
                raise YtError(f"YouTube svarade {r.status}")
            text = await r.text()
        # Talar om för spelaren att texten börjar vid filmens början.
        if "X-TIMESTAMP-MAP" not in text:
            text = re.sub(r"^WEBVTT[^\n]*\n", lambda m: m.group(0) + "X-TIMESTAMP-MAP=MPEGTS:0,LOCAL:00:00:00.000\n", text, count=1)
        return text

    async def segment_source(self, vid: str, itag: str, retry: bool = False) -> tuple[str, dict]:
        v = await self._video(vid, fresh=retry, full=retry)
        f = v["formats"].get(itag)
        if f is None:
            raise YtError("Okänt format")
        return f["url"], (f.get("http_headers") or {})

    async def storyboard_sheet(self, vid: str, n: int) -> bytes | None:
        """Ett ark med förhandsbilder (jpg). None om det inte finns."""
        key = f"{vid}/{n}"
        hit = self._sheets.get(key)
        if hit is not None:
            return hit
        for attempt in (0, 1):
            v = await self._video(vid, fresh=attempt == 1, full=attempt == 1)
            sb = v.get("storyboard")
            if not sb or not 0 <= n < len(sb["fragments"]):
                return None
            async with self.http.get(sb["fragments"][n]["url"], timeout=aiohttp.ClientTimeout(total=20)) as r:
                if r.status in (403, 404, 410) and attempt == 0:
                    continue  # adressen har gått ut: hämta videon på nytt
                if r.status != 200:
                    raise YtError(f"YouTube svarade {r.status}")
                data = await r.read()
            self._sheets[key] = data
            while len(self._sheets) > 200:
                self._sheets.pop(next(iter(self._sheets)))
            return data
        return None

    # ---------- SponsorBlock

    async def sponsor_segments(self, vid: str) -> list[dict]:
        hit = self._sponsor.get(vid)
        if hit and time.monotonic() - hit[0] < 3600:
            return hit[1]
        out: list[dict] = []
        prefix = hashlib.sha256(vid.encode()).hexdigest()[:4]
        cats = json.dumps(list(SKIP_CATEGORIES))
        try:
            async with self.http.get(
                f"{_sponsor_api()}/api/skipSegments/{prefix}", params={"categories": cats},
                timeout=aiohttp.ClientTimeout(total=6),
            ) as r:
                if r.status == 200:
                    for item in await r.json(content_type=None):
                        if item.get("videoID") != vid:
                            continue
                        for s in item.get("segments") or []:
                            seg = s.get("segment") or []
                            if len(seg) == 2 and s.get("category") in SKIP_CATEGORIES and s.get("actionType", "skip") == "skip":
                                out.append({"start": float(seg[0]), "end": float(seg[1]), "category": s["category"]})
        except (aiohttp.ClientError, asyncio.TimeoutError, ValueError):
            pass
        out.sort(key=lambda s: s["start"])
        self._sponsor[vid] = (time.monotonic(), out)
        return out

    # ---------- test

    async def test(self) -> str:
        await self.ytdlp.get_version()
        if not shutil.which("deno") and not os.environ.get("JPTV_YTDLP"):
            raise YtError("Deno saknas i containern – uppdatera JPTV Web")
        v = await self._video(TEST_VIDEO, fresh=True)
        v["fetched"] = 0  # nästa hämtning ska inte återanvända den här
        best = max((f.get("height") or 0) for f in v["videos"])
        itag = v["videos"][-1]["format_id"]
        await self.media(TEST_VIDEO, itag)
        first = f"{self.last_extract} s ({self.last_mode})"
        # En gång till: nu är yt-dlp redan igång och har sin cache, som när man tittar på riktigt.
        await self._video(TEST_VIDEO, fresh=True)
        process = "hålls igång" if self.ytdlp.worker_running else "startas varje gång"
        text = (f"yt-dlp {self.ytdlp.version or '?'} fungerar · upp till {best}p · hämtning {first}, "
                f"igen {self.last_extract} s ({self.last_mode}) · yt-dlp {process} · snabbstart {self.fast.describe()}")
        try:
            items = parse_related(await self._next(TEST_VIDEO))
            n = len(items)
            nv = sum(1 for i in items if i.get("views") is not None)
            nd = sum(1 for i in items if i.get("published"))
            text += (f" · YouTubes förslag: {n} st (visningar på {nv}, datum på {nd})" if n
                     else " · YouTubes förslag gick inte att läsa (sök används i stället)")
        except (aiohttp.ClientError, asyncio.TimeoutError, ValueError, YtError) as e:
            text += f" · YouTubes förslag fungerar inte ({e or type(e).__name__}); sök används i stället"
        return text

    # ---------- förslag

    def save_progress(self, vid: str, pos: float, dur: float, meta: dict) -> dict:
        """Position från spelaren. Titel, kanal, visningar och datum fylls i från videon om de saknas."""
        v = self._videos.get(vid)
        meta = dict(meta)
        if v:
            info = v["info"]
            meta.setdefault("title", info.get("title"))
            meta.setdefault("channel", info.get("channel") or info.get("uploader"))
            meta.setdefault("channelId", info.get("channel_id"))
            meta["thumb"] = _best_thumb(info.get("thumbnails")) or meta.get("thumb")
            meta["views"] = info.get("view_count")
            meta["published"] = _published(info)
        p = self.data.set_progress(vid, pos, dur, meta)
        if p.get("watched") or p.get("pos", 0) >= 60:
            self.warm(vid, p.get("title") or "")
        return p

    def warm(self, vid: str, title: str = "") -> None:
        """Hämtar förslagen för en video i bakgrunden, så att startsidan är klar när man kommer dit."""
        hit = self._related.get(vid)
        if (hit and time.monotonic() - hit[0] < 3600) or vid in self._warming:
            return
        self._warming.add(vid)

        async def run():
            try:
                await self.related(vid, title)
            except Exception:
                log.exception("Förslag för %s", vid)
            finally:
                self._warming.discard(vid)

        task = asyncio.ensure_future(run())
        self._bg.add(task)
        task.add_done_callback(self._bg.discard)

    def close(self) -> None:
        for t in list(self._bg) + list(self._related_tasks.values()):
            t.cancel()
        self.ytdlp.stop()

    async def _next(self, vid: str | None = None, continuation: str | None = None) -> dict:
        """YouTubes "next": förslagen bredvid en video, eller nästa del av något (kommentarer)."""
        body: dict = {"context": {"client": {"clientName": "WEB", "clientVersion": WEB_CLIENT_VERSION, "hl": "en", "gl": "SE"}}}
        if continuation:
            body["continuation"] = continuation
        else:
            body.update(videoId=vid, racyCheckOk=True, contentCheckOk=True)
        headers = {
            "Content-Type": "application/json", "User-Agent": _BROWSER_UA, "Origin": YT,
            "Accept-Language": "en-US,en;q=0.9",
            "X-YouTube-Client-Name": "1", "X-YouTube-Client-Version": WEB_CLIENT_VERSION,
            "Cookie": "SOCS=CAI; CONSENT=YES+",
        }
        async with self.http.post(_api_base() + "/youtubei/v1/next?prettyPrint=false", json=body, headers=headers,
                                  timeout=aiohttp.ClientTimeout(total=15)) as r:
            if r.status != 200:
                raise YtError(f"YouTube svarade {r.status}")
            return await r.json(content_type=None)

    async def comments(self, vid: str, token: str | None = None) -> dict:
        """
        Kommentarer, de populäraste först. Utan token: första sidan (med antal). Med token: nästa
        sida eller svaren på en kommentar.
        """
        key = f"{vid}:{token or ''}"
        hit = self._comments.get(key)
        if hit and time.monotonic() - hit[0] < 900:
            return hit[1]
        if token:
            _, items, nxt = parse_comments(await self._next(continuation=token))
            out = {"comments": items, "next": nxt}
        else:
            header, items, nxt = parse_comments(await self._next(continuation=comment_token(vid)))
            count = parse_count(_text((header or {}).get("countText"))) if header else None
            # Första svaret är oftast bara rubriken; sorteringen "populärast" ger själva kommentarerna.
            top = _cont_token(_dig(header, "sortMenu", "sortFilterSubMenuRenderer", "subMenuItems", 0) or {}) if header else None
            if not items and (top or nxt):
                _, items, nxt = parse_comments(await self._next(continuation=top or nxt))
            out = {"count": count, "comments": items, "next": nxt, "disabled": header is None and not items}
        for c in out["comments"]:
            c["text"] = c["text"][:3000]
        self._comments[key] = (time.monotonic(), out)
        while len(self._comments) > 200:
            self._comments.pop(next(iter(self._comments)))
        return out

    async def related_debug(self, vid: str) -> dict:
        """YouTubes råa svar för de första förslagen, och hur de lästes (för felsökning)."""
        data = await self._next(vid)
        root = _dig(data, "contents", "twoColumnWatchNextResults", "secondaryResults") or data
        raw = [{k: v} for k, v in _walk(root, ("lockupViewModel", "compactVideoRenderer", "videoRenderer"),
                                        ("playerOverlays", "engagementPanels", "frameworkUpdates", "topbar"))][:3]
        return {"parsed": parse_related(data)[:5], "raw": raw}

    async def related(self, vid: str, title: str = "") -> list[dict]:
        """YouTubes förslag bredvid videon. Går de inte att få: en sökning på titeln i stället."""
        hit = self._related.get(vid)
        if hit and time.monotonic() - hit[0] < (6 * 3600 if hit[1] else 600):
            return hit[1]
        # Samma video efterfrågad samtidigt (bakgrunden och startsidan): en hämtning räcker.
        task = self._related_tasks.get(vid)
        if task is None:
            task = self._related_tasks[vid] = asyncio.ensure_future(self._fetch_related(vid, title))
            task.add_done_callback(lambda _: self._related_tasks.pop(vid, None))
        return await asyncio.shield(task)

    async def _fetch_related(self, vid: str, title: str) -> list[dict]:
        items: list[dict] = []
        try:
            items = parse_related(await self._next(vid))
            if items:
                self.recs_stats["youtube"] += 1
                self.recs_stats["error"] = None
            else:
                self.recs_stats["error"] = "YouTube skickade inga förslag som gick att läsa"
        except (aiohttp.ClientError, asyncio.TimeoutError, ValueError, YtError) as e:
            self.recs_stats["error"] = str(e) or type(e).__name__
            log.info("Förslag för %s gick inte att hämta: %s", vid, e)
        if not items and title.strip():
            try:
                res = await self.ytdlp.run_json(["--flat-playlist", *APPROX_DATE, f"ytsearch20:{title.strip()}"])
                items = [video_card(e) for e in res.get("entries") or [] if VIDEO_RE.match(str(e.get("id") or ""))]
                self.recs_stats["search"] += 1
            except YtError as e:
                log.info("Reservsökning för %s misslyckades: %s", vid, e)
        if not items:
            self.recs_stats["failed"] += 1
        items = [i for i in items if i["id"] != vid]
        self._related[vid] = (time.monotonic(), items)
        if len(self._related) > 400:
            for k in sorted(self._related, key=lambda k: self._related[k][0])[:100]:
                self._related.pop(k, None)
        return items

    def _seeds(self, feed: list[dict]) -> list[dict]:
        """Det man har tittat på senast, plus nya videor från kanalerna om historiken är tunn."""
        blocked = self.data.blocked_ids()
        seeds = []
        for vid, p in sorted(self.data.progress.items(), key=lambda kv: kv[1].get("at", 0), reverse=True):
            if not self.data.seen(vid) or p.get("channelId") in blocked:
                continue
            seeds.append({"id": vid, "title": p.get("title") or "", "channelId": p.get("channelId") or "", "weight": 1.0, "watched": True})
            if len(seeds) >= 10:
                break
        if len(seeds) < 5:
            used = {s["channelId"] for s in seeds}
            for v in feed:
                if len(seeds) >= 5:
                    break
                if v["channelId"] in used or v.get("watched") or v["id"] in self.data.dismissed or v.get("short"):
                    continue
                used.add(v["channelId"])
                seeds.append({"id": v["id"], "title": v["title"], "channelId": v["channelId"], "weight": 0.6, "watched": False})
        return seeds

    async def recommendations(self, fresh: bool = False) -> dict:
        """
        Startsidans förslag, räknade här av YouTubes förslag bredvid det man har sett:
        det som föreslås från flera håll och efter det man såg senast hamnar först.
        """
        feed = await self.feed() if self.data.subs else []
        seeds = self._seeds(feed)
        key = (tuple(s["id"] for s in seeds), len(self.data.dismissed), tuple(sorted(self.data.blocked_ids())),
               len(self.data.subs), self.show_shorts)
        async with self._recs_lock:
            if not fresh and self._recs and self._recs[1] == key and time.monotonic() - self._recs[0] < 1800:
                return self._recs[2]
            result = await self._build_recs(seeds)
            self._recs = (time.monotonic(), key, result)
            return result

    def invalidate_recs(self) -> None:
        self._recs = None

    async def _build_recs(self, seeds: list[dict]) -> dict:
        sem = asyncio.Semaphore(4)

        async def one(s):
            async with sem:
                return await self.related(s["id"], s["title"])

        lists = await asyncio.gather(*(one(s) for s in seeds), return_exceptions=True)
        lists = [l if isinstance(l, list) else [] for l in lists]
        blocked = self.data.blocked_ids()
        dismissed = set(self.data.dismissed)
        skip = {s["id"] for s in seeds} | dismissed | {c["id"] for c in self.data.continue_watching(50)}
        subs = {s["id"] for s in self.data.subs}

        def ok(v: dict) -> bool:
            return (v["id"] not in skip and not self.data.seen(v["id"]) and v.get("channelId") not in blocked
                    and not v.get("live") and (self.show_shorts or not v.get("short")))

        now_ms = time.time() * 1000
        cands: dict[str, dict] = {}
        for i, (seed, lst) in enumerate(zip(seeds, lists)):
            weight = seed["weight"] * (0.9 ** i)
            for rank, v in enumerate(lst[:24]):
                if not ok(v):
                    continue
                c = cands.setdefault(v["id"], {"v": v, "score": 0.0})
                c["score"] += weight / (1 + 0.12 * rank)
        for c in cands.values():
            v = c["v"]
            if v.get("published") and now_ms - v["published"] < 14 * 86400e3:
                c["score"] *= 1.15
            if v.get("channelId") in subs:
                c["score"] *= 1.1
        ranked = [c["v"] for c in sorted(cands.values(), key=lambda c: c["score"], reverse=True)]
        recommended = _diversify(ranked, per_channel=2, limit=40)

        top = {v["id"] for v in recommended[:12]}
        because = []
        for seed, lst in zip(seeds, lists):
            if not seed["watched"]:
                continue
            # Helst sådant som inte redan syns överst, men hellre dubbletter än en tunn rad.
            vids = [v for v in lst if ok(v) and v["id"] not in top]
            if len(vids) < 4:
                vids = [v for v in lst if ok(v)]
            vids = vids[:16]
            if len(vids) >= 4:
                because.append({"seed": {"id": seed["id"], "title": seed["title"]}, "videos": self.data.decorate([dict(v) for v in vids])})
            if len(because) >= 3:
                break

        chans: dict[str, dict] = {}
        for c in cands.values():
            v = c["v"]
            cid = v.get("channelId") or ""
            if not CHANNEL_RE.match(cid) or cid in subs or cid in blocked:
                continue
            ch = chans.setdefault(cid, {"id": cid, "name": v.get("channel") or "", "avatar": None, "score": 0.0, "videos": 0})
            ch["score"] += c["score"]
            ch["videos"] += 1
            ch["avatar"] = ch["avatar"] or v.get("avatar")
        channels = sorted((c for c in chans.values() if c["videos"] >= 2), key=lambda c: c["score"], reverse=True)[:15]
        for ch in channels:
            ch.pop("score")
            ch["subscribed"] = False

        return {
            "recommended": self.data.decorate([dict(v) for v in recommended]),
            "because": because,
            "channels": channels,
            "seeds": sum(1 for s in seeds if s["watched"]),
        }
