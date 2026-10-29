"""IPTV för JPTV Web: M3U-lista, XMLTV-guide och direktsändning via ffmpeg (en ström åt gången)."""
from __future__ import annotations

import asyncio
import gzip
import hashlib
import io
import os
import re
import shutil
import tempfile
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from urllib.parse import parse_qs, urlencode, urlsplit, urlunsplit

# Många leverantörer släpper bara in kända spelare, så vi presenterar oss som VLC (samma som TV-appen).
USER_AGENT = "VLC/3.0.21 LibVLC/3.0.21"

_ATTR = re.compile(r'([A-Za-z0-9_-]+)="([^"]*)"')
_VOD_EXT = {"mkv", "mp4", "avi", "mov", "wmv", "m4v"}


@dataclass(slots=True)
class Channel:
    id: str
    name: str
    url: str
    group: str
    logo: str | None = None
    tvg_id: str | None = None
    tvg_name: str | None = None
    number: int = 0
    user_agent: str | None = None
    referrer: str | None = None

    def public(self) -> dict:
        return {"id": self.id, "name": self.name, "group": self.group, "logo": self.logo, "number": self.number}


@dataclass
class Playlist:
    channels: list[Channel]
    epg_url: str | None = None

    def __post_init__(self) -> None:
        self.by_id = {c.id: c for c in self.channels}
        seen: dict[str, int] = {}
        for c in self.channels:
            seen[c.group] = seen.get(c.group, 0) + 1
        self.groups = [{"name": g, "count": n} for g, n in seen.items()]


def channel_id(url: str) -> str:
    return hashlib.sha1(url.encode("utf-8")).hexdigest()[:12]


def is_live(url: str) -> bool:
    u = url.lower()
    if "/movie/" in u or "/series/" in u:
        return False
    last = u.split("?", 1)[0].rsplit("/", 1)[-1]
    ext = last.rsplit(".", 1)[-1] if "." in last else ""
    return ext not in _VOD_EXT


def _name_comma(line: str) -> int:
    """Kommat som skiljer attributen från kanalnamnet, utan att räkna komman inom citattecken."""
    quoted = False
    for i, c in enumerate(line):
        if c == '"':
            quoted = not quoted
        elif c == "," and not quoted:
            return i
    return -1


def _attributes(line: str) -> dict[str, str]:
    idx = _name_comma(line)
    head = line[:idx] if idx >= 0 else line
    return {k.lower(): v for k, v in _ATTR.findall(head)}


def parse_m3u(lines, live_only: bool = True) -> Playlist:
    """
    M3U/M3U8-spellista (även Xtream m3u_plus) till kanaler, numrerade i listans ordning som på TV:n.
    Film och serier hoppas över: de hör hemma i Plex-delen.
    """
    if isinstance(lines, str):
        lines = lines.splitlines()
    channels: list[Channel] = []
    epg_url = None
    name = None
    attrs: dict[str, str] = {}
    ext_group = ua = ref = None
    for raw in lines:
        line = raw.strip()
        if not line:
            continue
        if line.startswith("#EXTM3U"):
            a = _attributes(line + ",")
            epg_url = a.get("url-tvg") or a.get("x-tvg-url")
        elif line.startswith("#EXTINF"):
            attrs = _attributes(line)
            idx = _name_comma(line)
            name = line[idx + 1:].strip() if idx >= 0 else ""
            ext_group = ua = ref = None
        elif line.startswith("#EXTGRP:"):
            ext_group = line.split(":", 1)[1].strip()
        elif line.startswith("#EXTVLCOPT:"):
            opt = line.split(":", 1)[1]
            if opt.startswith("http-user-agent="):
                ua = opt.split("=", 1)[1].strip()
            elif opt.startswith("http-referrer="):
                ref = opt.split("=", 1)[1].strip()
        elif line.startswith("#"):
            continue
        else:
            url = line.split("|", 1)[0]
            # Kodi-format: "url|User-Agent=...&Referer=..."
            if "|" in line:
                for part in line.split("|", 1)[1].split("&"):
                    k, _, v = part.partition("=")
                    k, v = k.strip().lower(), v.strip()
                    if not v:
                        continue
                    if k == "user-agent":
                        ua = ua or v
                    if k in ("referer", "referrer"):
                        ref = ref or v
            if not live_only or is_live(url):
                group = (attrs.get("group-title") or "").strip() or (ext_group or "").strip() or "Övrigt"
                channels.append(
                    Channel(
                        id=channel_id(url),
                        name=(name or "").strip() or attrs.get("tvg-name") or url,
                        url=url,
                        group=group,
                        logo=attrs.get("tvg-logo") or None,
                        tvg_id=attrs.get("tvg-id") or None,
                        tvg_name=attrs.get("tvg-name") or None,
                        number=len(channels) + 1,
                        user_agent=ua or attrs.get("user-agent") or None,
                        referrer=ref,
                    )
                )
            name = None
            attrs = {}
            ext_group = ua = ref = None
    return Playlist(channels, epg_url)


def write_m3u(pl: Playlist, out) -> None:
    """Kompakt kopia med bara live-kanalerna, så att nästa start går fort."""
    def q(v: str) -> str:
        return v.replace('"', "'").replace("\n", " ").replace("\r", " ")

    out.write("#EXTM3U")
    if pl.epg_url:
        out.write(f' url-tvg="{q(pl.epg_url)}"')
    out.write("\n")
    for c in pl.channels:
        out.write("#EXTINF:-1")
        if c.tvg_id:
            out.write(f' tvg-id="{q(c.tvg_id)}"')
        if c.tvg_name:
            out.write(f' tvg-name="{q(c.tvg_name)}"')
        if c.logo:
            out.write(f' tvg-logo="{q(c.logo)}"')
        out.write(f' group-title="{q(c.group)}",{c.name.replace(chr(10), " ")}\n')
        if c.user_agent:
            out.write(f"#EXTVLCOPT:http-user-agent={c.user_agent}\n")
        if c.referrer:
            out.write(f"#EXTVLCOPT:http-referrer={c.referrer}\n")
        out.write(c.url + "\n")


def guess_epg_url(m3u_url: str | None, pl: Playlist | None) -> str | None:
    """Guidens adress: url-tvg i spellistan, annars Xtream-mönstret get.php -> xmltv.php."""
    if pl and pl.epg_url:
        for u in pl.epg_url.split(","):
            if u.strip().startswith("http"):
                return u.strip()
    if not m3u_url:
        return None
    parts = urlsplit(m3u_url.strip())
    if not parts.path.endswith("get.php"):
        return None
    q = parse_qs(parts.query)
    if "username" not in q or "password" not in q:
        return None
    query = urlencode({"username": q["username"][0], "password": q["password"][0]})
    return urlunsplit((parts.scheme, parts.netloc, "/xmltv.php", query, ""))


# ---------------------------------------------------------------- Guide


@dataclass(slots=True)
class Programme:
    start: int
    stop: int
    title: str
    desc: str | None = None

    def public(self) -> dict:
        return {"start": self.start, "stop": self.stop, "title": self.title, "desc": self.desc}


_PREFIX = re.compile(r"^(\|[a-z]{2,3}\||[a-z]{2,3}\s*[:|])\s*")
_QUALITY = re.compile(r"\b(fhd|uhd|hd|sd|4k|hevc|h265|h264|backup|raw|50fps|60fps)\b")
_BRACKETS = re.compile(r"\(.*?\)|\[.*?]")


def normalize(name: str | None) -> str:
    """"SE: SVT1 HD" -> "svt1", så att kanalnamn från olika källor kan jämföras (som på TV:n)."""
    if not name:
        return ""
    n = name.lower().strip()
    n = _PREFIX.sub("", n)
    n = _BRACKETS.sub(" ", n)
    n = _QUALITY.sub(" ", n)
    return "".join(c for c in n if c.isalnum())


def _days_from_civil(y: int, m: int, d: int) -> int:
    y = y - 1 if m <= 2 else y
    era = (y if y >= 0 else y - 399) // 400
    yoe = y - era * 400
    mp = (m + 9) % 12
    doy = (153 * mp + 2) // 5 + d - 1
    doe = yoe * 365 + yoe // 4 - yoe // 100 + doy
    return era * 146097 + doe - 719468


def parse_time(value: str) -> int | None:
    """XMLTV-tid "20261021183000 +0200" till millisekunder sedan 1970 (UTC)."""
    s = (value or "").strip()
    if len(s) < 12 or not s[:12].isdigit():
        return None
    month, day = int(s[4:6]), int(s[6:8])
    if not (1 <= month <= 12 and 1 <= day <= 31):
        return None
    seconds = int(s[12:14]) if len(s) >= 14 and s[12:14].isdigit() else 0
    t = (_days_from_civil(int(s[0:4]), month, day) * 86400 + int(s[8:10]) * 3600 + int(s[10:12]) * 60 + seconds) * 1000
    m = re.search(r"([+-])(\d{2})(\d{2})", s[12:])
    if m:
        sign = -1 if m.group(1) == "-" else 1
        t -= sign * (int(m.group(2)) * 60 + int(m.group(3))) * 60000
    return t


class Epg:
    """Program per guide-kanal, och kopplingen från spellistans kanaler till guiden."""

    def __init__(self, programmes: dict[str, list[Programme]], names: dict[str, str], loaded_at: int):
        self.programmes = programmes
        self.names = names
        self.loaded_at = loaded_at
        self._resolved: dict[str, str] = {}

    @property
    def channel_count(self) -> int:
        return len(self.programmes)

    def _key(self, ch: Channel) -> str | None:
        hit = self._resolved.get(ch.id)
        if hit is not None:
            return hit or None
        key = None
        if ch.tvg_id and ch.tvg_id.lower() in self.programmes:
            key = ch.tvg_id.lower()
        else:
            key = self.names.get(normalize(ch.name)) or (self.names.get(normalize(ch.tvg_name)) if ch.tvg_name else None)
        self._resolved[ch.id] = key or ""
        return key

    def for_channel(self, ch: Channel) -> list[Programme]:
        key = self._key(ch)
        return self.programmes.get(key, []) if key else []

    def now_next(self, ch: Channel, now_ms: int) -> tuple[Programme | None, Programme | None]:
        cur = nxt = None
        for p in self.for_channel(ch):
            if p.stop <= now_ms:
                continue
            if p.start <= now_ms:
                cur = p
            else:
                nxt = p
                break
        return cur, nxt

    def window(self, ch: Channel, frm: int, to: int) -> list[Programme]:
        return [p for p in self.for_channel(ch) if p.stop > frm and p.start < to]


def search_programmes(channels: list, epg: "Epg | None", query: str, now_ms: int, limit: int = 80) -> list:
    """
    Som på TV:n: program som inte har slutat och vars titel innehåller alla sökorden
    ("hockey frölunda"), det som går nu först. Samma program på flera kanalvarianter (SVT1 HD,
    SVT1 FHD …) visas en gång, på den kanal som kommer först; favoriterna läggs först av den som anropar.
    """
    words = [w for w in (query or "").lower().split() if w]
    if not epg or sum(len(w) for w in words) < 2:
        return []
    hits, seen, scanned = [], set(), set()
    for ch in channels:
        progs = epg.for_channel(ch)
        # Kanalvarianter delar samma lista i guiden: den gås bara igenom en gång.
        if not progs or id(progs) in scanned:
            continue
        scanned.add(id(progs))
        for p in progs:
            if p.stop <= now_ms:
                continue
            title = p.title.lower()
            if any(w not in title for w in words):
                continue
            key = (p.start, p.stop, title)
            if key not in seen:
                seen.add(key)
                hits.append((ch, p))
    hits.sort(key=lambda hit: hit[1].start)
    return hits[:limit]


_BAD_AMP = re.compile(rb"&(?!(?:amp|lt|gt|quot|apos|#\d+|#x[0-9a-fA-F]+);)")


class _AmpFixer(io.RawIOBase):
    """Läser XML och lagar lösa "&" (vanligt i IPTV-guider), så att en enda trasig rad inte stoppar allt."""

    def __init__(self, raw):
        self.raw = raw
        self.tail = b""

    def readable(self) -> bool:
        return True

    def readinto(self, b) -> int:
        while True:
            chunk = self.raw.read(max(64, len(b)))
            data = self.tail + (chunk or b"")
            self.tail = b""
            if chunk:
                # Ett "&" precis i slutet kan fortsätta i nästa bit: vänta med det.
                amp = data.rfind(b"&")
                if amp >= 0 and len(data) - amp < 12:
                    self.tail, data = data[amp:], data[:amp]
                if not data:
                    continue
            break
        data = _BAD_AMP.sub(b"&amp;", data.replace(b"&nbsp;", b" "))
        n = len(data)
        if n > len(b):
            self.tail = data[len(b):] + self.tail
            data = data[: len(b)]
            n = len(b)
        b[:n] = data
        return n


def parse_xmltv(source, pl: Playlist, now_ms: int | None = None, back_h: int = 3, ahead_h: int = 36) -> Epg:
    """
    XMLTV (fil, filnamn eller bytes, gärna gzip) till guide. Bara kanaler i spellistan och program i
    tidsfönstret sparas, så att även guider på flera hundra MB går bra.
    """
    if isinstance(source, (bytes, bytearray)):
        raw = io.BytesIO(source)
    elif isinstance(source, str):
        raw = open(source, "rb")
    else:
        raw = source
    head = raw.read(2)
    raw.seek(0)
    if head == b"\x1f\x8b":
        raw = gzip.GzipFile(fileobj=raw)
    stream = io.BufferedReader(_AmpFixer(raw), 1 << 16)

    now_ms = now_ms or int(time.time() * 1000)
    lo, hi = now_ms - back_h * 3600_000, now_ms + ahead_h * 3600_000
    wanted_ids = {c.tvg_id.lower() for c in pl.channels if c.tvg_id}
    wanted_names = {normalize(c.name) for c in pl.channels} | {normalize(c.tvg_name) for c in pl.channels if c.tvg_name}
    wanted_names.discard("")
    epg_ids: set[str] = set()
    names: dict[str, str] = {}
    progs: dict[str, list[Programme]] = {}
    titles: dict[str, str] = {}
    try:
        for _ev, el in ET.iterparse(stream, events=("end",)):
            tag = el.tag
            if tag == "programme":
                cid = (el.get("channel") or "").lower()
                if cid in epg_ids or cid in wanted_ids:
                    start = parse_time(el.get("start", ""))
                    stop = parse_time(el.get("stop", ""))
                    if start and stop and stop > lo and start < hi:
                        t_el = el.find("title")
                        title = (t_el.text or "").strip() if t_el is not None and t_el.text else "Okänt program"
                        title = titles.setdefault(title, title)
                        d_el = el.find("desc")
                        desc = d_el.text.strip()[:300] if d_el is not None and d_el.text else None
                        progs.setdefault(cid, []).append(Programme(start, stop, title, desc))
                el.clear()
            elif tag == "channel":
                cid = (el.get("id") or "").lower()
                if cid in wanted_ids:
                    epg_ids.add(cid)
                for dn in el.findall("display-name"):
                    n = normalize(dn.text)
                    if n and n in wanted_names:
                        epg_ids.add(cid)
                        names.setdefault(n, cid)
                el.clear()
    except ET.ParseError:
        # Trasig XML längre ner: behåll det som hann läsas.
        if not progs:
            raise
    finally:
        try:
            raw.close()
        except Exception:
            pass
    for lst in progs.values():
        lst.sort(key=lambda p: p.start)
    return Epg(progs, names, now_ms)


# ---------------------------------------------------------------- Direktsändning


def ffmpeg_args(ch: Channel, out_dir: str, transcode_video: bool, deinterlace: bool = False, hw: "HwAccel | None" = None) -> list[str]:
    """
    deinterlace: kanalen sänds radsprångat (t.ex. 1080i50). Varje halvbild blir en hel bild, så att
    25 bilder/s blir 50 – det som gör sport mjuk, som TV:n gör själv. Kräver omkodning.
    hw: Intel QuickSync (VAAPI) avkodar, avflätar och kodar i grafikdelen i stället för processorn.
    """
    encode = transcode_video or deinterlace
    use_hw = encode and hw is not None and hw.ok
    args = [
        "ffmpeg", "-hide_banner", "-loglevel", "level+info", "-nostdin", "-nostats",
        "-progress", "pipe:1", "-stats_period", "2", "-fflags", "+discardcorrupt",
    ]
    if use_hw:
        args += [
            "-init_hw_device", f"vaapi=va:{hw.device}", "-filter_hw_device", "va",
            "-hwaccel", "vaapi", "-hwaccel_device", "va", "-hwaccel_output_format", "vaapi",
        ]
    if ch.url.startswith("http"):
        args += ["-user_agent", ch.user_agent or USER_AGENT]
        if ch.referrer:
            args += ["-headers", f"Referer: {ch.referrer}\r\n"]
        args += ["-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5", "-rw_timeout", "15000000"]
    args += ["-i", ch.url, "-map", "0:v:0?", "-map", "0:a:0?", "-sn", "-dn"]
    if use_hw:
        # "format=nv12|vaapi,hwupload" fungerar både när grafikdelen avkodar och när den inte kan.
        vf = "format=nv12|vaapi,hwupload"
        if deinterlace:
            vf += f",deinterlace_vaapi=mode={hw.mode}:rate=field"
        vf += ",scale_vaapi=format=nv12"
        args += ["-vf", vf, "-c:v", "h264_vaapi"]
        if hw.low_power:
            args += ["-low_power", "1"]
        args += [
            "-b:v", "10M", "-maxrate", "15M", "-bufsize", "20M", "-bf", "0", "-g", "100",
            "-force_key_frames", "expr:gte(t,n_forced*2)",
        ]
    elif encode:
        vf = "bwdif=mode=send_field:parity=auto:deint=all," if deinterlace else ""
        args += [
            "-vf", vf + "scale='min(1920,iw)':-2",
            # Hemmanätet: hög bithastighet och snabb förinställning, så att Unraid hänger med i 50 bilder/s.
            "-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency", "-crf", "19",
            "-maxrate", "16M", "-bufsize", "24M", "-pix_fmt", "yuv420p",
            "-force_key_frames", "expr:gte(t,n_forced*2)", "-sc_threshold", "0",
        ]
    else:
        args += ["-c:v", "copy"]
    args += [
        "-c:a", "aac", "-b:a", "160k", "-ac", "2",
        "-f", "hls", "-hls_time", "2", "-hls_list_size", "10",
        "-hls_flags", "delete_segments+omit_endlist+independent_segments+temp_file",
        "-hls_delete_threshold", "4",
        "-hls_segment_filename", os.path.join(out_dir, "seg_%05d.ts"),
        os.path.join(out_dir, "index.m3u8"),
    ]
    return args


_VIDEO_RE = re.compile(r"Stream #0:\d+.*?: Video: (\w+)")
# "-loglevel level+info": "[info] text" eller "[h264 @ 0x55] [error] text".
_LEVEL_RE = re.compile(r"^(?:\[[^\]]*@[^\]]*\] )?\[(\w+)\] (.*)$")


def parse_video_info(line: str) -> dict | None:
    """ffmpegs rad om kanalens bild: codec, upplösning, bilder/s och om den sänds radsprångat."""
    m = _VIDEO_RE.search(line)
    if not m:
        return None
    size = re.search(r", (\d{2,5})x(\d{2,5})", line)
    fps = re.search(r", ([\d.]+) fps", line)
    lower = line.lower()
    if "top first" in lower or "bottom first" in lower or "top coded first" in lower or "bottom coded first" in lower:
        interlaced = True
    elif "progressive" in lower:
        interlaced = False
    else:
        interlaced = None
    return {
        "codec": m.group(1),
        "width": int(size.group(1)) if size else None,
        "height": int(size.group(2)) if size else None,
        "fps": float(fps.group(1)) if fps else None,
        "interlaced": interlaced,
    }


class HwAccel:
    """
    Intel QuickSync via VAAPI (t.ex. N100 i Unraid). Kräver att /dev/dri lagts till som Device på
    containern. Provas vid start med en kort testkodning; fungerar den inte används processorn.
    """

    def __init__(self, device: str = "/dev/dri/renderD128"):
        self.device = device
        self.ok = False
        self.low_power = False
        self.mode = "motion_adaptive"
        self.error: str | None = None
        self.checked = False

    def status(self) -> dict:
        return {"available": self.ok, "checked": self.checked, "device": self.device, "lowPower": self.low_power, "mode": self.mode, "error": self.error}

    async def probe(self) -> bool:
        self.checked = False
        if not os.path.exists(self.device):
            self.ok, self.error, self.checked = False, "Ingen Intel-grafik i containern (lägg till /dev/dri som Device)", True
            return False
        last = None
        # Gen12 (N100) kodar H.264 bara i "low power"-läget; äldre tvärtom. Rörelseanpassad avflätning
        # först, annars enklare "bob".
        for mode in ("motion_adaptive", "bob"):
            for low_power in (False, True):
                cmd = [
                    "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin",
                    "-init_hw_device", f"vaapi=va:{self.device}", "-filter_hw_device", "va",
                    "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=25,setfield=tff", "-frames:v", "15",
                    "-vf", f"format=nv12,hwupload,deinterlace_vaapi=mode={mode}:rate=field,scale_vaapi=format=nv12",
                    "-c:v", "h264_vaapi", *(["-low_power", "1"] if low_power else []), "-bf", "0", "-f", "null", "-",
                ]
                try:
                    proc = await asyncio.create_subprocess_exec(*cmd, stdin=asyncio.subprocess.DEVNULL, stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.PIPE)
                    _, err = await asyncio.wait_for(proc.communicate(), 20)
                except (OSError, asyncio.TimeoutError) as e:
                    last = str(e) or "tidsgräns"
                    continue
                if proc.returncode == 0:
                    self.ok, self.low_power, self.mode, self.error, self.checked = True, low_power, mode, None, True
                    return True
                lines = err.decode(errors="ignore").strip().splitlines()
                last = lines[-1] if lines else f"ffmpeg avslutade med {proc.returncode}"
        self.ok, self.error, self.checked = False, f"Intel-grafiken gick inte att använda: {last}", True
        return False


class LiveStream:
    """
    En kanal åt gången: ffmpeg läser kanalen och skriver HLS som webbläsaren spelar. En ny kanal stänger
    den förra först, och tittar ingen på en halv minut stängs strömmen. Ljudet blir alltid AAC; bilden
    kopieras som den är, kodas om till H.264 när webbläsaren inte klarar formatet, och radsprångade
    kanaler (1080i) avflätas till 50 bilder/s när det är påslaget.
    """

    # Avflätningen slås av för kanalen om Unraid inte hinner med (lägre än så här i 15 s).
    MIN_SPEED = 0.93
    SLOW_SECONDS = 15
    GRACE = 10

    def __init__(self, workdir: str | None = None, idle_timeout: float = 30):
        self.workdir = workdir or tempfile.gettempdir()
        self.idle_timeout = idle_timeout
        self.proc: asyncio.subprocess.Process | None = None
        self.channel: Channel | None = None
        self.transcoding = False
        self.deinterlacing = False
        self.dir: str | None = None
        self.session: str | None = None
        self.started_at = 0.0
        self.last_access = 0.0
        self.info: dict | None = None
        self.progress: dict = {}
        self.note: str | None = None
        # Vad vi vet om kanalerna sedan tidigare: radsprångad, och om Unraid var för långsam.
        self.interlaced: dict[str, bool] = {}
        self.too_slow: set[str] = set()
        self.hw: HwAccel | None = None
        self.hw_bad: set[str] = set()
        self.using_hw = False
        self._tasks: list[asyncio.Task] = []
        self._log: list[str] = []
        self._errors: list[str] = []
        self._slow_since = 0.0
        # Strömmar som servern själv bytt ut (för långsam avflätning): gammal session -> ny.
        self._replaced: dict[str, str] = {}
        self._lock = asyncio.Lock()

    def touch(self) -> None:
        self.last_access = time.monotonic()

    def status(self) -> dict | None:
        if not self.proc or not self.channel:
            return None
        return {
            "channel": self.channel.public(),
            "session": self.session,
            "transcoding": self.transcoding,
            "deinterlacing": self.deinterlacing,
            "hardware": self.using_hw,
            "running": self.proc.returncode is None,
            "error": self._errors[-1] if self._errors else None,
            "video": self.info,
            "speed": self.progress.get("speed"),
            "bitrate": self.progress.get("bitrate") or self._segment_kbps(),
            "note": self.note,
        }

    async def start(self, ch: Channel, transcode_video: bool = False, deinterlace: bool = False, timeout: float = 25) -> str:
        """
        Startar kanalen och väntar tills de första bitarna finns. Väljs en annan kanal under tiden
        avbryts den här (RuntimeError), så att ett snabbt kanalbyte inte behöver vänta.

        deinterlace: avfläta radsprångade kanaler. Första gången kanalen startas syns det först när
        ffmpeg läst bilden; då startas den om direkt med avflätning (samma ström, efter varandra).
        """
        deint = deinterlace and self.interlaced.get(ch.id) is True and ch.id not in self.too_slow
        session = await self._spawn(ch, transcode_video, deint)
        deadline = time.monotonic() + timeout
        restarted = False
        while time.monotonic() < deadline:
            if self.session != session:
                if self._replaced.get(session) == self.session and self.session:
                    session = self.session  # servern bytte själv ström för samma kanal
                    continue
                raise RuntimeError("En annan kanal valdes")
            proc = self.proc
            if proc is None or proc.returncode is not None:
                await asyncio.sleep(0.2)
                if self.using_hw and ch.id not in self.hw_bad:
                    # Grafikdelen klarade inte kanalen: samma sak med processorn.
                    self.hw_bad.add(ch.id)
                    session = await self._spawn(ch, transcode_video, deint)
                    continue
                msg = (self._errors or self._log or ["Kanalen startade inte"])[-1]
                await self._stop_if(session)
                raise RuntimeError(_friendly(msg))
            info = self.info
            if info and info["interlaced"] is not None:
                self.interlaced[ch.id] = info["interlaced"]
                if deinterlace and info["interlaced"] and not deint and not restarted and ch.id not in self.too_slow:
                    deint = restarted = True
                    session = await self._spawn(ch, transcode_video, True)
                    continue
            if self._playlist_ready():
                return session
            await asyncio.sleep(0.25)
        await self._stop_if(session)
        raise RuntimeError("Kanalen svarade inte i tid")

    async def _spawn(self, ch: Channel, transcode_video: bool, deinterlace: bool) -> str:
        async with self._lock:
            await self._stop()
            os.makedirs(self.workdir, exist_ok=True)
            self.dir = tempfile.mkdtemp(prefix="live-", dir=self.workdir)
            self.session = os.path.basename(self.dir)
            self.channel = ch
            self.transcoding = transcode_video or deinterlace
            self.deinterlacing = deinterlace
            hw = self.hw if self.hw and self.hw.ok and ch.id not in self.hw_bad else None
            self.using_hw = bool(hw) and self.transcoding
            self.info = None
            self.progress = {}
            self.note = None
            self._log, self._errors = [], []
            self._slow_since = 0.0
            self.proc = await asyncio.create_subprocess_exec(
                *ffmpeg_args(ch, self.dir, transcode_video, deinterlace, hw),
                stdin=asyncio.subprocess.DEVNULL,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            self._tasks = [
                asyncio.create_task(self._read_stderr(self.proc)),
                asyncio.create_task(self._read_progress(self.proc, ch, transcode_video, self.session)),
            ]
            self.started_at = time.monotonic()
            self.touch()
            return self.session

    def _segment_kbps(self) -> float | None:
        """Bithastigheten ut till webbläsaren, räknad på de senaste bitarna i spellistan."""
        if not self.dir:
            return None
        try:
            with open(os.path.join(self.dir, "index.m3u8"), encoding="utf-8", errors="ignore") as f:
                lines = f.read().splitlines()
            pairs = [(float(lines[i].split(":", 1)[1].rstrip(",")), lines[i + 1]) for i in range(len(lines) - 1) if lines[i].startswith("#EXTINF")]
            secs = size = 0.0
            for dur, name in pairs[-4:]:
                size += os.path.getsize(os.path.join(self.dir, name))
                secs += dur
            return round(size * 8 / 1000 / secs, 1) if secs else None
        except (OSError, ValueError, IndexError):
            return None

    def _playlist_ready(self) -> bool:
        if not self.dir:
            return False
        try:
            with open(os.path.join(self.dir, "index.m3u8"), encoding="utf-8", errors="ignore") as f:
                return f.read().count("#EXTINF") >= 2
        except OSError:
            return False

    async def _stop_if(self, session: str) -> None:
        async with self._lock:
            if self.session == session:
                await self._stop()

    async def _read_stderr(self, proc: asyncio.subprocess.Process) -> None:
        assert proc.stderr is not None
        while True:
            line = await proc.stderr.readline()
            if not line:
                return
            text = line.decode(errors="ignore").rstrip()
            if not text.strip():
                continue
            m = _LEVEL_RE.match(text)
            level, msg = (m.group(1), m.group(2).strip()) if m else ("info", text.strip())
            if level in ("error", "fatal", "panic"):
                self._errors.append(msg)
                del self._errors[:-10]
            self._log.append(msg)
            del self._log[:-30]
            if self.info is None:
                info = parse_video_info(msg)
                if info:
                    self.info = info

    async def _read_progress(self, proc: asyncio.subprocess.Process, ch: Channel, transcode_video: bool, session: str) -> None:
        """-progress: hastighet och bithastighet. Hinner Unraid inte avfläta slås det av för kanalen."""
        assert proc.stdout is not None
        block: dict = {}
        while True:
            line = await proc.stdout.readline()
            if not line:
                return
            key, _, value = line.decode(errors="ignore").strip().partition("=")
            block[key] = value
            if key != "progress":
                continue
            speed = _num(block.get("speed", "").rstrip("x"))
            kbps = _num(block.get("bitrate", "").replace("kbits/s", ""))
            self.progress = {"speed": speed, "bitrate": kbps}
            block = {}
            if not self.deinterlacing or speed is None or time.monotonic() - self.started_at < self.GRACE:
                continue
            if speed < self.MIN_SPEED:
                self._slow_since = self._slow_since or time.monotonic()
                if time.monotonic() - self._slow_since > self.SLOW_SECONDS and self.session == session:
                    # Unraid hinner inte: tillbaka till kanalens egen bild (25 bilder/s) för den här kanalen.
                    self.too_slow.add(ch.id)
                    asyncio.create_task(self._fallback(ch, transcode_video, session))
                    return
            else:
                self._slow_since = 0.0

    async def _fallback(self, ch: Channel, transcode_video: bool, session: str) -> None:
        if self.session != session:
            return
        new = await self._spawn(ch, transcode_video, False)
        self._replaced = {session: new}
        self.note = "Unraid hann inte avfläta kanalen, så den visas som den sänds"

    async def stop(self) -> None:
        async with self._lock:
            await self._stop()

    async def _stop(self) -> None:
        proc, self.proc = self.proc, None
        if proc and proc.returncode is None:
            proc.terminate()
            try:
                await asyncio.wait_for(proc.wait(), 4)
            except asyncio.TimeoutError:
                proc.kill()
                await proc.wait()
        for t in self._tasks:
            if t is not asyncio.current_task():
                t.cancel()
        self._tasks = []
        if self.dir:
            shutil.rmtree(self.dir, ignore_errors=True)
        self.dir = None
        self.session = None
        self.channel = None

    def file(self, session: str, name: str) -> str | None:
        if not self.dir or session != self.session or not re.fullmatch(r"[\w.-]+\.(m3u8|ts)", name):
            return None
        path = os.path.join(self.dir, name)
        return path if os.path.exists(path) else None

    async def watchdog(self) -> None:
        while True:
            await asyncio.sleep(5)
            if self.proc and time.monotonic() - self.last_access > self.idle_timeout:
                await self.stop()


def _num(v: str | None) -> float | None:
    try:
        return float(v) if v not in (None, "", "N/A") else None
    except ValueError:
        return None


def _friendly(msg: str) -> str:
    m = msg.lower()
    if "403" in m or "401" in m or "forbidden" in m:
        return "Leverantören nekade kanalen (kanske upptagen på en annan enhet)"
    if "404" in m or "not found" in m:
        return "Kanalen finns inte hos leverantören just nu"
    if "connection refused" in m or "timed out" in m or "resolve" in m:
        return "Fick ingen kontakt med leverantören"
    return msg
