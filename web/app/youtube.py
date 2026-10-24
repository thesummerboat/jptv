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


class YtDlp:
    """Kör yt-dlp som eget program. Nyare versioner installeras i lib_dir och går före den inbyggda."""

    def __init__(self, lib_dir: str):
        self.lib_dir = lib_dir
        self.version: str | None = None
        self.updated_at = 0.0
        self.update_error: str | None = None
        self.updating = False
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

    async def run_json(self, args: list[str], timeout: float = 60) -> dict:
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
        f'#EXT-X-MAP:URI="s/{itag}",BYTERANGE="{init_len}@0"',
    ]
    for offset, length, dur in segments:
        lines += [f"#EXTINF:{dur:.3f},", f"#EXT-X-BYTERANGE:{length}@{offset}", f"s/{itag}"]
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
        try:
            with open(self.path, encoding="utf-8") as f:
                data = json.load(f)
            self.subs = [s for s in data.get("subs") or [] if isinstance(s, dict) and CHANNEL_RE.match(s.get("id", ""))]
            self.progress = {k: v for k, v in (data.get("progress") or {}).items() if isinstance(v, dict)}
        except (OSError, ValueError):
            pass

    def _save(self) -> None:
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({"subs": self.subs, "progress": self.progress}, f, ensure_ascii=False, indent=1)
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
                if meta.get(k):
                    p[k] = meta[k]
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
            items.append({
                "id": vid, "title": p.get("title") or "", "channel": p.get("channel") or "", "channelId": p.get("channelId") or "",
                "thumb": p.get("thumb") or f"https://i.ytimg.com/vi/{vid}/hqdefault.jpg", "duration": int(dur) if dur else None,
                "position": int(p["pos"]), "published": None, "views": None, "live": False, "short": False,
            })
            if len(items) >= limit:
                break
        return items

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


# ------------------------------------------------------------------ tjänsten


class YouTube:
    def __init__(self, http: aiohttp.ClientSession, store, config_dir: str):
        self.http = http
        self.settings = store
        self.data = YtStore(config_dir)
        self.ytdlp = YtDlp(os.path.join(config_dir, "ytdlp"))
        self._videos: dict[str, dict] = {}
        self._locks: dict[str, asyncio.Lock] = {}
        self._feeds: dict[str, tuple[float, list[dict]]] = {}
        self._channels: dict[str, tuple[float, dict]] = {}
        self._sponsor: dict[str, tuple[float, list]] = {}
        self._search: dict[str, tuple[float, dict]] = {}

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
            "deno": bool(shutil.which("deno")),
            "updatedAt": int(self.ytdlp.updated_at * 1000) if self.ytdlp.updated_at else None,
            "updating": self.ytdlp.updating,
            "updateError": self.ytdlp.update_error,
            "subs": len(self.data.subs),
            "maxHeight": self.max_height,
            "shorts": self.show_shorts,
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
        return self.data.decorate([dict(i) for i in items[:limit]])

    async def keep_feeds_fresh(self) -> None:
        """Flödet hämtas i bakgrunden, så att sidan öppnas direkt."""
        while True:
            try:
                if self.data.subs:
                    await self.feed()
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
        vids_task = self.ytdlp.run_json(["--flat-playlist", f"ytsearch30:{q}"])
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
        if hit and time.monotonic() - hit[0] < 1800:
            info = hit[1]
        else:
            info = await self.ytdlp.run_json(["--flat-playlist", "--playlist-items", "1:36", f"{YT}/channel/{cid}/videos"])
            self._channels[cid] = (time.monotonic(), info)
        name = info.get("channel") or re.sub(r"\s*-\s*Videos$", "", info.get("title") or "")
        avatar = _avatar(info.get("thumbnails"))
        self.data.update_channel(cid, name=name, avatar=avatar)
        videos = [video_card(e) for e in info.get("entries") or [] if VIDEO_RE.match(str(e.get("id") or ""))]
        for v in videos:
            v["channel"] = v["channel"] or name
            v["channelId"] = cid
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

    async def _video(self, vid: str, fresh: bool = False) -> dict:
        """yt-dlp:s uppgifter om videon, med valda format. Sparas tills adresserna går ut."""
        if not VIDEO_RE.match(vid):
            raise YtError("Ogiltig video")
        async with self._lock(vid):
            v = self._videos.get(vid)
            if v and not fresh and time.time() < v["expires"] - 600:
                return v
            info = await self.ytdlp.run_json(["--no-playlist", f"{YT}/watch?v={vid}"], timeout=90)
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
            v = {
                "info": info, "videos": videos, "audio": audio, "formats": formats, "subs": pick_subtitles(info),
                "expires": min(expires, time.time() + 5 * 3600), "index": {},
            }
            self._videos[vid] = v
            # Gamla videor ur minnet.
            if len(self._videos) > 40:
                for k in sorted(self._videos, key=lambda k: self._videos[k]["expires"])[:10]:
                    self._videos.pop(k, None)
            return v

    async def video(self, vid: str) -> dict:
        v = await self._video(vid)
        info = v["info"]
        chapters = [
            {"title": c.get("title") or "", "start": float(c.get("start_time") or 0), "end": float(c.get("end_time") or 0)}
            for c in info.get("chapters") or []
        ]
        sponsor = await self.sponsor_segments(vid)
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

    async def media(self, vid: str, itag: str) -> str:
        """Spellista för ett format: byte-intervall ur filens index (sidx)."""
        for attempt in (0, 1):
            v = await self._video(vid, fresh=attempt == 1)
            f = v["formats"].get(itag)
            if f is None:
                raise YtError("Okänt format")
            cached = v["index"].get(itag)
            if cached:
                return media_playlist(itag, *cached)
            headers = f.get("http_headers") or {}
            try:
                data = await self._fetch_range(f["url"], headers, 0, 65535)
                parsed = parse_index(data)
                if isinstance(parsed, tuple) and parsed and parsed[0] == "more":
                    data = await self._fetch_range(f["url"], headers, 0, int(parsed[1]) + 1023)
                    parsed = parse_index(data)
            except PermissionError:
                if attempt == 0:
                    continue
                raise YtError("YouTube nekade videon (403). Prova igen om en stund.")
            if not parsed or parsed[0] == "more":
                raise YtError("Kunde inte läsa videons index")
            v["index"][itag] = parsed
            return media_playlist(itag, *parsed)
        raise YtError("Kunde inte läsa videons index")

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
        v = await self._video(vid, fresh=retry)
        f = v["formats"].get(itag)
        if f is None:
            raise YtError("Okänt format")
        return f["url"], (f.get("http_headers") or {})

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
        best = max((f.get("height") or 0) for f in v["videos"])
        itag = v["videos"][-1]["format_id"]
        await self.media(TEST_VIDEO, itag)
        return f"yt-dlp {self.ytdlp.version or '?'} fungerar · upp till {best}p"
