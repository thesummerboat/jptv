"""Plex för JPTV Web: inloggning med kod, hitta servern, bibliotek, sök och uppspelning."""
from __future__ import annotations

import asyncio
import os
import re
import time
import uuid
from urllib.parse import quote, urlencode

import aiohttp

from . import imdb as imdb_ratings

PRODUCT = "JPTV"
PLEX_TV = "https://plex.tv"
IMDB_RE = re.compile(r"tt\d{5,10}")


class PlexError(Exception):
    pass


# Kvalitet för webbläsaren: (namn, max kbit/s, upplösning)
QUALITIES = [
    ("Bästa (upp till 4K)", 80000, "3840x2160"),
    ("1080p · 20 Mbit/s", 20000, "1920x1080"),
    ("1080p · 8 Mbit/s", 8000, "1920x1080"),
    ("720p · 4 Mbit/s", 4000, "1280x720"),
    ("720p · 2 Mbit/s", 2000, "1280x720"),
]


def _num(v, default=None):
    try:
        return float(v)
    except (TypeError, ValueError):
        return default


def format_ratings(o: dict) -> list[dict]:
    """Plex betyg (bild-id + värde 0–10) till korta märken: "IMDb 7,6", "RT 91 %" (som på TV:n)."""
    raw: list[tuple[str, float]] = []
    if isinstance(o.get("Rating"), list):
        for r in o["Rating"]:
            img, val = r.get("image"), _num(r.get("value"))
            if img and val is not None:
                raw.append((img, val))
    else:
        for val_key, img_key in (("rating", "ratingImage"), ("audienceRating", "audienceRatingImage")):
            val, img = _num(o.get(val_key)), o.get(img_key)
            if val is not None and img:
                raw.append((img, val))
    out: dict[str, dict] = {}
    for image, value in raw:
        if value <= 0:
            continue
        img = image.lower()
        dec = f"{value:.1f}".replace(".", ",")
        pct = f"{round(value * 10)} %"
        if img.startswith("imdb://"):
            r = ("IMDb", f"IMDb {dec}")
        elif img.startswith("rottentomatoes://") and (img.endswith(".ripe") or img.endswith(".rotten")):
            r = ("RT", f"RT {pct}")
        elif img.startswith("rottentomatoes://") and (img.endswith(".upright") or img.endswith(".spilled")):
            r = ("RT publik", f"RT publik {pct}")
        elif img.startswith("themoviedb://"):
            r = ("TMDB", f"TMDB {dec}")
        else:
            continue
        out.setdefault(r[0], {"src": r[0], "text": r[1], "value": value})
    return list(out.values())


def _tags(o: dict, name: str, limit: int = 99) -> list[str]:
    arr = o.get(name)
    if not isinstance(arr, list):
        return []
    return [t["tag"] for t in arr[:limit] if isinstance(t, dict) and t.get("tag")]


def badges(o: dict, media: dict | None, streams: list[dict]) -> list[str]:
    out = []
    video = next((s for s in streams if s.get("streamType") == 1), None)
    res = str((media or {}).get("videoResolution") or "").lower()
    height = (video or {}).get("height") or 0
    if res == "4k" or height >= 2000:
        out.append("4K")
    elif res == "1080" or height >= 1000:
        out.append("1080p")
    elif res == "720":
        out.append("720p")
    if video:
        if video.get("DOVIPresent"):
            out.append("Dolby Vision")
        elif video.get("colorTrc") == "smpte2084":
            out.append("HDR10")
        elif video.get("colorTrc") == "arib-std-b67":
            out.append("HLG")
    audio = next((s for s in streams if s.get("streamType") == 2 and s.get("selected")), None) or next(
        (s for s in streams if s.get("streamType") == 2), None
    )
    title = str((audio or {}).get("extendedDisplayTitle") or (audio or {}).get("displayTitle") or "")
    if "atmos" in title.lower():
        out.append("Atmos")
    ch = (audio or {}).get("channels") or (media or {}).get("audioChannels")
    if ch == 6:
        out.append("5.1")
    elif ch == 8:
        out.append("7.1")
    return out


def slim(o: dict, full: bool = False) -> dict:
    """En titel i Plex JSON till det lilla som webbsidan behöver."""
    t = o.get("type", "")
    media = (o.get("Media") or [None])[0]
    part = ((media or {}).get("Part") or [None])[0]
    streams = (part or {}).get("Stream") or []
    if t == "episode":
        thumb = o.get("grandparentThumb") or o.get("parentThumb") or o.get("thumb")
    elif t == "season":
        thumb = o.get("thumb") or o.get("parentThumb")
    else:
        thumb = o.get("thumb")
    logo = None
    for img in o.get("Image") or []:
        if img.get("type") == "clearLogo":
            logo = img.get("url")
            break
    guids = [g.get("id", "") for g in (o.get("Guid") or []) if isinstance(g, dict)] + [o.get("guid") or ""]
    imdb = next((m.group(0) for m in (IMDB_RE.search(g) for g in guids) if m), None)
    tmdb = next((g.split("://", 1)[1] for g in guids if g.startswith("tmdb://") and g.split("://", 1)[1].isdigit()), None)
    show_key = o.get("grandparentRatingKey") or (o.get("parentRatingKey") if t == "season" else None)
    year = o.get("year")
    item = {
        "key": str(o.get("ratingKey", "")),
        "type": t,
        "title": o.get("title", ""),
        "show": o.get("grandparentTitle") or (o.get("parentTitle") if t == "season" else None),
        "season": o.get("parentIndex") if t == "episode" else (o.get("index") if t == "season" else None),
        "episode": o.get("index") if t == "episode" else None,
        "year": year,
        "summary": o.get("summary") or None,
        "thumb": thumb,
        "art": o.get("art") or o.get("grandparentArt"),
        "still": o.get("thumb") if t == "episode" else None,
        "logo": logo,
        "duration": int(o.get("duration") or (media or {}).get("duration") or 0),
        "offset": int(o.get("viewOffset") or 0),
        "watched": int(o.get("viewCount") or 0) > 0,
        "viewCount": int(o.get("viewCount") or 0),
        "leafs": o.get("leafCount") or o.get("childCount"),
        "viewedLeafs": o.get("viewedLeafCount"),
        "showKey": str(show_key) if show_key else None,
        "seasonKey": str(o.get("parentRatingKey")) if t == "episode" and o.get("parentRatingKey") else None,
        "section": str(o.get("librarySectionID")) if o.get("librarySectionID") else None,
        "contentRating": o.get("contentRating"),
        "genres": _tags(o, "Genre"),
        "ratings": format_ratings(o),
        "badges": badges(o, media, streams),
        "theme": o.get("theme") or o.get("parentTheme") or o.get("grandparentTheme"),
        "titleSort": o.get("titleSort"),
        "addedAt": int(o.get("addedAt") or 0),
        "lastViewedAt": int(o.get("lastViewedAt") or 0),
        "imdb": imdb,
        # TMDB-id (för Upptäck: finns titeln redan i Plex?). Visas inte i webbläsaren.
        "_tmdb": int(tmdb) if tmdb else None,
        # Plex egna id (plex://movie/…) – samma i Plex katalog, så att önskemål känns igen i biblioteket.
        "guid": o.get("guid") if str(o.get("guid") or "").startswith("plex://") else None,
        "showGuid": o.get("grandparentGuid") or (o.get("parentGuid") if t == "season" else None),
        "studio": o.get("studio"),
        "originallyAvailableAt": o.get("originallyAvailableAt"),
    }
    if t == "collection":
        item["leafs"] = o.get("childCount")
    if full:
        item["partId"] = (part or {}).get("id")
        item["partKey"] = (part or {}).get("key")
        # Plex har förhandsbilder för spolning ("video preview thumbnails").
        item["previews"] = (part or {}).get("indexes") == "sd"
        item["container"] = (part or {}).get("container")
        item["videoCodec"] = (media or {}).get("videoCodec")
        item["audioCodec"] = (media or {}).get("audioCodec")
        item["directors"] = _tags(o, "Director", 3)
        item["writers"] = _tags(o, "Writer", 3)
        item["roles"] = [
            {"name": r.get("tag"), "role": r.get("role"), "thumb": r.get("thumb")}
            for r in (o.get("Role") or [])[:16]
            if r.get("tag")
        ]
        item["tagline"] = o.get("tagline")
        item["audio"] = [
            {"id": s.get("id"), "title": s.get("extendedDisplayTitle") or s.get("displayTitle") or "Ljud", "selected": bool(s.get("selected"))}
            for s in streams
            if s.get("streamType") == 2
        ]
        item["subtitles"] = [
            {"id": s.get("id"), "title": s.get("extendedDisplayTitle") or s.get("displayTitle") or "Undertext", "selected": bool(s.get("selected"))}
            for s in streams
            if s.get("streamType") == 3
        ]
        item["markers"] = [
            {"type": m.get("type"), "start": int(m.get("startTimeOffset") or 0), "end": int(m.get("endTimeOffset") or 0), "final": bool(m.get("final"))}
            for m in (o.get("Marker") or [])
        ]
        item["primaryExtraKey"] = o.get("primaryExtraKey")
    # Egenskaper för rekommendationerna (samma prefix som på TV:n).
    tags = (
        ["g:" + x for x in _tags(o, "Genre", 8)]
        + ["d:" + x for x in _tags(o, "Director", 3)]
        + ["w:" + x for x in _tags(o, "Writer", 2)]
        + ["a:" + x for x in _tags(o, "Role", 6)]
        + ["c:" + x for x in _tags(o, "Country", 2)]
    )
    if o.get("studio"):
        tags.append("s:" + o["studio"])
    if isinstance(year, int):
        tags.append(f"y:{year // 10 * 10}")
    item["_tags"] = tags
    # IMDb-betyget från IMDb:s egen fil när Plex bara har Rotten Tomatoes (som på TV:n).
    imdb_ratings.enrich(item)
    return item


def public(item: dict) -> dict:
    return {k: v for k, v in item.items() if not k.startswith("_")}


def metadata_list(mc: dict) -> list[dict]:
    out = []
    for name in ("Metadata", "Directory", "Video"):
        for o in mc.get(name) or []:
            if isinstance(o, dict) and "ratingKey" in o:
                out.append(o)
    return out


class Plex:
    # Plex egen katalog med alla filmer och serier (även sådant som inte finns på servern).
    DISCOVER = os.environ.get("JPTV_PLEX_DISCOVER", "https://discover.provider.plex.tv")
    METADATA = os.environ.get("JPTV_PLEX_METADATA", "https://metadata.provider.plex.tv")

    def __init__(self, store, http: aiohttp.ClientSession):
        self.store = store
        self.http = http
        self._cache: dict[str, tuple[float, object]] = {}
        self._pins: dict[str, int] = {}

    # ------------------------------------------------------------ grund

    @property
    def client_id(self) -> str:
        cid = self.store.get("client_id")
        if not cid:
            cid = "jptv-web-" + uuid.uuid4().hex[:16]
            self.store.set(client_id=cid)
        return cid

    @property
    def server(self) -> dict | None:
        return self.store.get("server")

    def headers(self, token: str | None = None) -> dict:
        h = {
            "Accept": "application/json",
            "X-Plex-Product": PRODUCT,
            "X-Plex-Version": "1.0",
            "X-Plex-Client-Identifier": self.client_id,
            "X-Plex-Platform": "Web",
            "X-Plex-Device": "Webbläsare",
            "X-Plex-Device-Name": "JPTV Web",
            "X-Plex-Language": "sv",
        }
        if token:
            h["X-Plex-Token"] = token
        return h

    def _srv(self) -> dict:
        s = self.server
        if not s:
            raise PlexError("Inte inloggad på Plex")
        return s

    async def get(self, path: str, params: dict | None = None, timeout: float = 20, cache: float = 0) -> dict:
        s = self._srv()
        url = s["uri"] + path
        if params:
            url += ("&" if "?" in url else "?") + urlencode(params)
        if cache:
            hit = self._cache.get(url)
            if hit and time.monotonic() - hit[0] < cache:
                return hit[1]  # type: ignore[return-value]
        async with self.http.get(url, headers=self.headers(s["token"]), timeout=aiohttp.ClientTimeout(total=timeout)) as r:
            if r.status == 401:
                raise PlexError("Plex nekade åtkomst – logga in igen")
            if r.status >= 400:
                raise PlexError(f"Plex svarade HTTP {r.status}")
            data = await r.json(content_type=None)
        mc = (data or {}).get("MediaContainer") or {}
        if cache:
            self._cache[url] = (time.monotonic(), mc)
            if len(self._cache) > 300:
                for k in sorted(self._cache, key=lambda k: self._cache[k][0])[:100]:
                    self._cache.pop(k, None)
        return mc

    async def send(self, method: str, path: str, params: dict | None = None) -> int:
        s = self._srv()
        url = s["uri"] + path
        if params:
            url += ("&" if "?" in url else "?") + urlencode(params)
        async with self.http.request(method, url, headers=self.headers(s["token"]), timeout=aiohttp.ClientTimeout(total=15)) as r:
            await r.read()
            return r.status

    def forget_cache(self) -> None:
        self._cache.clear()

    async def items(self, path: str, params: dict | None = None, cache: float = 0, full: bool = False) -> list[dict]:
        p = {"includeGuids": 1}
        p.update(params or {})
        mc = await self.get(path, p, cache=cache)
        return [slim(o, full) for o in metadata_list(mc)]

    # ------------------------------------------------------------ inloggning

    async def start_login(self) -> dict:
        async with self.http.post(f"{PLEX_TV}/api/v2/pins", headers=self.headers(), timeout=aiohttp.ClientTimeout(total=15)) as r:
            if r.status >= 400:
                raise PlexError(f"Kunde inte hämta inloggningskod (HTTP {r.status})")
            o = await r.json(content_type=None)
        return {"id": o["id"], "code": o["code"], "url": "https://plex.tv/link"}

    async def check_login(self, pin_id: int) -> dict | None:
        async with self.http.get(f"{PLEX_TV}/api/v2/pins/{pin_id}", headers=self.headers(), timeout=aiohttp.ClientTimeout(total=15)) as r:
            if r.status >= 400:
                raise PlexError(f"Plex svarade HTTP {r.status}")
            o = await r.json(content_type=None)
        token = o.get("authToken")
        if not token:
            return None
        server = await self.find_server(token)
        self.store.set(account_token=token, server=server)
        self.forget_cache()
        return server

    async def find_server(self, account_token: str) -> dict:
        url = f"{PLEX_TV}/api/v2/resources?includeHttps=1&includeRelay=1"
        async with self.http.get(url, headers=self.headers(account_token), timeout=aiohttp.ClientTimeout(total=20)) as r:
            if r.status >= 400:
                raise PlexError(f"Kunde inte hämta servrar (HTTP {r.status})")
            arr = await r.json(content_type=None)
        servers = [s for s in arr if "server" in (s.get("provides") or "").split(",")]
        servers.sort(key=lambda s: not s.get("owned"))
        if not servers:
            raise PlexError("Hittade ingen Plex-server på kontot")
        for s in servers:
            token = s.get("accessToken") or account_token
            uris = candidate_uris(s.get("connections") or [])
            ok = await asyncio.gather(*(self._reachable(u, token) for u in uris))
            for u, good in zip(uris, ok):
                if good:
                    return {"name": s.get("name") or "Plex", "uri": u, "token": token, "id": s.get("clientIdentifier")}
        raise PlexError("Plex-servern svarar inte. Är Unraid på samma nät som servern?")

    async def _reachable(self, uri: str, token: str) -> bool:
        try:
            async with self.http.get(f"{uri}/identity", headers=self.headers(token), timeout=aiohttp.ClientTimeout(total=4)) as r:
                return r.status < 400
        except Exception:
            return False

    async def ensure_server(self) -> None:
        """Svarar inte den sparade adressen (ny IP?) letar vi upp servern igen."""
        s = self.server
        acct = self.store.get("account_token")
        if not s or not acct:
            return
        if await self._reachable(s["uri"], s["token"]):
            return
        try:
            self.store.set(server=await self.find_server(acct))
        except Exception:
            pass

    def logout(self) -> None:
        self.store.set(account_token=None, server=None)
        self.forget_cache()

    # ------------------------------------------------------------ bibliotek

    # ------------------------------------------------------------ Plex katalog (för önskemål)

    def _account(self) -> str:
        token = self.store.get("account_token") or (self.server or {}).get("token")
        if not token:
            raise PlexError("Inte inloggad på Plex")
        return token

    async def discover(self, query: str, limit: int = 24) -> list[dict]:
        """Filmer och serier i Plex katalog, oavsett om de finns på servern."""
        params = {"query": query, "limit": limit, "searchTypes": "movies,tv", "searchProviders": "discover", "includeMetadata": 1}
        async with self.http.get(
            f"{self.DISCOVER}/library/search?{urlencode(params)}",
            headers=self.headers(self._account()),
            timeout=aiohttp.ClientTimeout(total=15),
        ) as r:
            if r.status >= 400:
                raise PlexError(f"Plex katalog svarade HTTP {r.status}")
            data = await r.json(content_type=None)
        groups = ((data or {}).get("MediaContainer") or {}).get("SearchResults") or []
        out = []
        for g in groups:
            for res in g.get("SearchResult") or []:
                m = res.get("Metadata") or {}
                if m.get("type") not in ("movie", "show") or not m.get("guid"):
                    continue
                out.append(online_item(m))
        seen, unique = set(), []
        for i in out:
            if i["guid"] not in seen:
                seen.add(i["guid"])
                unique.append(i)
        return unique

    async def online_ids(self, rating_key: str) -> dict:
        """IMDb-, TMDB- och TVDB-id för en titel i Plex katalog (det Radarr och Sonarr behöver)."""
        async with self.http.get(
            f"{self.METADATA}/library/metadata/{rating_key}",
            headers=self.headers(self._account()),
            timeout=aiohttp.ClientTimeout(total=15),
        ) as r:
            if r.status >= 400:
                raise PlexError(f"Plex katalog svarade HTTP {r.status}")
            data = await r.json(content_type=None)
        meta = (((data or {}).get("MediaContainer") or {}).get("Metadata") or [{}])[0]
        ids = {}
        for g in meta.get("Guid") or []:
            gid = str(g.get("id") or "")
            if "://" in gid:
                scheme, value = gid.split("://", 1)
                ids[scheme] = value
        return ids

    async def sections(self) -> list[dict]:
        mc = await self.get("/library/sections", cache=60)
        return [
            {"key": str(d.get("key")), "title": d.get("title"), "type": d.get("type")}
            for d in mc.get("Directory") or []
            if d.get("type") in ("movie", "show")
        ]

    async def on_deck(self) -> list[dict]:
        return await self.items("/library/onDeck", cache=20)

    async def recently_added(self, key: str, size: int = 30) -> list[dict]:
        return await self.items(f"/library/sections/{key}/recentlyAdded", {"X-Plex-Container-Start": 0, "X-Plex-Container-Size": size}, cache=60)

    async def all(self, key: str, sort: str = "titleSort", unwatched: bool = False, genre: str | None = None, cache: float = 300) -> list[dict]:
        params: dict = {"sort": sort, "X-Plex-Container-Start": 0, "X-Plex-Container-Size": 5000}
        if unwatched:
            params["unwatched"] = 1
        if genre:
            params["genre"] = genre
        return await self.items(f"/library/sections/{key}/all", params, cache=cache)

    async def genres(self, key: str) -> list[dict]:
        mc = await self.get(f"/library/sections/{key}/genre", cache=600)
        out = []
        for d in mc.get("Directory") or []:
            title, k = d.get("title"), str(d.get("key") or "")
            if not title or not k:
                continue
            m = re.search(r"genre=([^&]+)", k) or re.search(r"genre=([^&]+)", str(d.get("fastKey") or ""))
            out.append({"id": m.group(1) if m else k, "title": title})
        return out

    async def collections(self, key: str) -> list[dict]:
        return await self.items(f"/library/sections/{key}/collections", cache=300)

    async def collection_items(self, key: str) -> list[dict]:
        return await self.items(f"/library/collections/{key}/children", cache=120)

    async def section_hubs(self, key: str) -> list[dict]:
        mc = await self.get(f"/hubs/sections/{key}", {"count": 20, "includeGuids": 1, "excludeContinueWatching": 1}, cache=300)
        rows = []
        for hub in mc.get("Hub") or []:
            hid = str(hub.get("hubIdentifier") or "").lower()
            if any(x in hid for x in ("recentlyadded", "continuewatching", "ondeck", "inprogress")):
                continue
            items = [slim(o) for o in metadata_list(hub) if o.get("type") in ("movie", "show", "season", "episode")]
            if len(items) >= 3:
                rows.append({"title": hub.get("title") or "Mer", "items": items})
        return rows

    async def history(self, limit: int = 3000) -> list[dict]:
        """Tittarhistoriken från servern (alla enheter). accountID=1 är serverns ägare."""
        mc = await self.get(
            "/status/sessions/history/all",
            {"sort": "viewedAt:desc", "accountID": 1, "X-Plex-Container-Start": 0, "X-Plex-Container-Size": limit},
            timeout=40,
        )
        out = []
        for o in mc.get("Metadata") or []:
            key = o.get("ratingKey")
            if not key:
                continue
            show = o.get("grandparentRatingKey") or str(o.get("grandparentKey") or "").rsplit("/", 1)[-1] or None
            out.append({"key": str(key), "type": o.get("type", ""), "show": str(show) if show else None, "at": int(o.get("viewedAt") or 0) * 1000})
        return out

    async def metadata(self, key: str) -> dict | None:
        mc = await self.get(f"/library/metadata/{key}", {"includeMarkers": 1, "includeGuids": 1, "includeOnDeck": 1, "includeExtras": 1, "includeRelated": 1})
        raw = metadata_list(mc)
        if not raw:
            return None
        o = raw[0]
        item = slim(o, full=True)
        deck = metadata_list(o.get("OnDeck") or {})
        item["onDeck"] = public(slim(deck[0])) if deck else None
        extras = metadata_list(o.get("Extras") or {})
        item["trailers"] = [
            public(slim(e, full=True)) | {"extraType": e.get("extraType")}
            for e in extras
            if e.get("extraType") == 1 or "trailer" in str(e.get("title", "")).lower()
        ][:3]
        related = []
        for hub in (o.get("Related") or {}).get("Hub") or []:
            items = [public(slim(x)) for x in metadata_list(hub) if x.get("type") in ("movie", "show")]
            if len(items) >= 3:
                related.append({"title": hub.get("title") or "Liknande", "items": items[:20]})
        item["related"] = related[:2]
        return item

    async def children(self, key: str) -> list[dict]:
        return await self.items(f"/library/metadata/{key}/children", cache=30)

    async def all_leaves(self, key: str) -> list[dict]:
        return await self.items(f"/library/metadata/{key}/allLeaves", cache=30)

    async def next_episode(self, item: dict) -> dict | None:
        if item.get("type") != "episode" or not item.get("showKey"):
            return None
        eps = await self.all_leaves(item["showKey"])
        for i, e in enumerate(eps):
            if e["key"] == item["key"]:
                return eps[i + 1] if i + 1 < len(eps) else None
        return None

    async def search(self, query: str) -> list[dict]:
        mc = await self.get("/hubs/search", {"query": query, "limit": 30, "includeCollections": 0, "includeGuids": 1})
        out = []
        for hub in mc.get("Hub") or []:
            items = [public(slim(o)) for o in metadata_list(hub) if o.get("type") in ("movie", "show", "episode", "season")]
            if items:
                out.append({"title": hub.get("title") or "Träffar", "items": items})
        return out

    # ------------------------------------------------------------ uppspelning

    def transcode_query(self, key: str, session: str, quality: int, offset_ms: int = 0) -> str:
        name, kbps, res = QUALITIES[max(0, min(quality, len(QUALITIES) - 1))]
        params = {
            "path": f"/library/metadata/{key}",
            "mediaIndex": 0,
            "partIndex": 0,
            "protocol": "hls",
            "fastSeek": 1,
            "directPlay": 0,
            "directStream": 1,
            "directStreamAudio": 0,
            "videoQuality": 100,
            "maxVideoBitrate": kbps,
            "videoResolution": res,
            "offset": round(offset_ms / 1000),
            "subtitles": "burn",
            "session": session,
            "X-Plex-Session-Identifier": session,
            "X-Plex-Client-Identifier": self.client_id,
            "X-Plex-Product": PRODUCT,
            # Webbläsarprofilen: H.264 + AAC i HLS, som alla webbläsare klarar med hls.js.
            "X-Plex-Platform": "Chrome",
            "X-Plex-Device": "Webbläsare",
        }
        return urlencode(params, quote_via=quote)

    async def start_playback(self, key: str, quality: int, offset_ms: int = 0) -> dict:
        s = self._srv()
        session = uuid.uuid4().hex
        q = self.transcode_query(key, session, quality, offset_ms)
        # Beslutet först (samma parametrar), annars kan första start.m3u8 svara 400.
        try:
            async with self.http.get(
                f"{s['uri']}/video/:/transcode/universal/decision?{q}",
                headers={"Accept": "application/json", "X-Plex-Token": s["token"]},
                timeout=aiohttp.ClientTimeout(total=20),
            ) as r:
                decision = await r.json(content_type=None) if r.status < 400 else {}
        except Exception:
            decision = {}
        mc = (decision or {}).get("MediaContainer") or {}
        info = describe_decision(mc)
        return {"session": session, "url": f"/plex/video/:/transcode/universal/start.m3u8?{q}", "decision": info}

    async def stop_transcode(self, session: str) -> None:
        try:
            await self.send("GET", "/video/:/transcode/universal/stop", {"session": session})
        except Exception:
            pass

    async def timeline(self, key: str, state: str, time_ms: int, duration_ms: int, session: str | None) -> None:
        s = self._srv()
        params = {
            "ratingKey": key,
            "key": f"/library/metadata/{key}",
            "state": state,
            "time": int(time_ms),
            "duration": int(duration_ms),
            "playbackTime": int(time_ms),
        }
        h = self.headers(s["token"])
        if session:
            h["X-Plex-Session-Identifier"] = session
        try:
            async with self.http.get(f"{s['uri']}/:/timeline?{urlencode(params)}", headers=h, timeout=aiohttp.ClientTimeout(total=10)) as r:
                await r.read()
        except Exception:
            pass
        self.forget_cache()

    async def set_watched(self, key: str, watched: bool) -> None:
        await self.send("GET", "/:/scrobble" if watched else "/:/unscrobble", {"key": key, "identifier": "com.plexapp.plugins.library"})
        self.forget_cache()

    async def remove_from_deck(self, key: str) -> None:
        await self.send("PUT", "/actions/removeFromContinueWatching", {"ratingKey": key})
        self.forget_cache()

    async def set_streams(self, part_id: int, audio: int | None, subtitle: int | None) -> None:
        params: dict = {"allParts": 1}
        if audio is not None:
            params["audioStreamID"] = audio
        if subtitle is not None:
            params["subtitleStreamID"] = subtitle
        await self.send("PUT", f"/library/parts/{part_id}", params)


def online_item(m: dict) -> dict:
    """En titel ur Plex katalog i samma lilla form som resten (bilderna är fulla adresser)."""
    return {
        "guid": m.get("guid"),
        "key": str(m.get("ratingKey") or str(m.get("guid", "")).rsplit("/", 1)[-1]),
        "type": m.get("type"),
        "title": m.get("title", ""),
        "year": m.get("year"),
        "summary": m.get("summary") or None,
        "thumb": m.get("thumb"),
        "art": m.get("art"),
        "contentRating": m.get("contentRating"),
        "originallyAvailableAt": m.get("originallyAvailableAt"),
    }


def describe_decision(mc: dict) -> dict:
    """Vad servern gör med filen: spelas som den är eller kodas om (för info i spelaren)."""
    out = {"video": None, "audio": None, "text": mc.get("generalDecisionText") or mc.get("transcodeDecisionText")}
    for m in metadata_list(mc)[:1]:
        for media in m.get("Media") or []:
            for part in media.get("Part") or []:
                for st in part.get("Stream") or []:
                    if st.get("streamType") == 1:
                        out["video"] = st.get("decision")
                    elif st.get("streamType") == 2:
                        out["audio"] = st.get("decision")
    return out


def candidate_uris(conns: list[dict]) -> list[str]:
    ranked: list[tuple[int, str]] = []
    for c in conns:
        local, relay = bool(c.get("local")), bool(c.get("relay"))
        base = 0 if local and not relay else (10 if not relay else 20)
        if c.get("uri"):
            ranked.append((base, c["uri"].rstrip("/")))
        addr = c.get("address")
        # Reserv om routern blockerar plex.direct-adresser: vanlig http på lokal IP.
        if local and not relay and addr and ":" not in addr:
            ranked.append((base + 1, f"http://{addr}:{c.get('port') or 32400}"))
    seen, out = set(), []
    for _, u in sorted(ranked, key=lambda x: x[0]):
        if u not in seen:
            seen.add(u)
            out.append(u)
    return out
