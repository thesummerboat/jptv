"""
Radarr (filmer) och Sonarr (serier): ett godkänt önskemål läggs till där, och de sköter resten.
Används bara om adress och API-nyckel finns under Inställningar.

Kvalitetsprofil och rotmapp väljs med namn (Inställningar → Önskemål), aldrig "den första i
listan": i Radarr är den första profilen ofta "Any", som gärna tar en remux på 50 GB.
Finns inte den valda profilen eller mappen stoppar vi med ett tydligt fel i stället för att gissa.
"""
from __future__ import annotations

import asyncio
import json
import re
import time
from datetime import datetime, timezone

import aiohttp

# Samma som läggs till för hand på Unraid.
DEFAULTS = {
    "radarr": {"root": "/data/Filmer", "profile": "HD-1080p"},
    "sonarr": {"root": "/data/Serier", "profile": "HD - 720p/1080p"},
}


def _norm(name: str) -> str:
    """Profilnamn jämförs utan mellanslag, bindestreck och versaler: "HD - 1080p" = "HD-1080p"."""
    return re.sub(r"[^a-z0-9]", "", str(name).lower())


def _norm_path(path: str) -> str:
    return str(path).strip().rstrip("/") or "/"


class ArrError(Exception):
    def __init__(self, message: str, status: int | None = None, data=None):
        super().__init__(message)
        self.status = status
        self.data = data


class Arr:
    def __init__(self, http: aiohttp.ClientSession, kind: str, url: str, key: str, root: str = "", profile: str = ""):
        self.http = http
        self.kind = kind  # "radarr" eller "sonarr"
        self.url = (url or "").strip().rstrip("/")
        if self.url and not self.url.startswith(("http://", "https://")):
            self.url = "http://" + self.url
        self.key = (key or "").strip()
        self.root = (root or "").strip() or DEFAULTS[kind]["root"]
        self.profile = (profile or "").strip() or DEFAULTS[kind]["profile"]

    @property
    def name(self) -> str:
        return "Radarr" if self.kind == "radarr" else "Sonarr"

    @property
    def configured(self) -> bool:
        return bool(self.url and self.key)

    async def _call(self, method: str, path: str, **kw):
        try:
            async with self.http.request(
                method, self.url + path, headers={"X-Api-Key": self.key, "Accept": "application/json"},
                timeout=aiohttp.ClientTimeout(total=30), **kw,
            ) as r:
                text = await r.text()
                try:
                    data = json.loads(text) if text else None
                except ValueError:
                    data = None
                if r.status == 401:
                    raise ArrError(f"{self.name} nekade API-nyckeln")
                if r.status >= 400:
                    raise ArrError(_message(data) or f"{self.name} svarade HTTP {r.status}", r.status, data)
                return data
        except aiohttp.ClientError as e:
            raise ArrError(f"Får ingen kontakt med {self.name} ({e.__class__.__name__})")

    async def test(self) -> str:
        """Svarar den, och finns den valda profilen och mappen? Texten visas under Inställningar."""
        st = await self._call("GET", "/api/v3/system/status")
        profile_id, profile_name, root = await self._defaults()
        version = (st or {}).get("version", "")
        return f"{self.name} {version} svarar · profil {profile_name} · {root}".replace("  ", " ")

    async def options(self) -> dict:
        """Profiler och rotmappar som finns, för förslagen under Inställningar."""
        profiles = await self._call("GET", "/api/v3/qualityprofile") or []
        roots = await self._call("GET", "/api/v3/rootfolder") or []
        return {
            "profiles": [p.get("name", "") for p in profiles],
            "roots": [r.get("path", "") for r in roots],
            "profile": self.profile,
            "root": self.root,
        }

    async def _defaults(self) -> tuple[int, str, str]:
        """(profil-id, profilnamn, rotmapp) enligt inställningarna. Fel om något inte finns."""
        profiles = await self._call("GET", "/api/v3/qualityprofile") or []
        roots = await self._call("GET", "/api/v3/rootfolder") or []
        want = self.profile
        profile = None
        if want.isdigit():
            profile = next((p for p in profiles if str(p.get("id")) == want), None)
        if profile is None:
            profile = next((p for p in profiles if _norm(p.get("name", "")) == _norm(want)), None)
        if profile is None:
            names = ", ".join(p.get("name", "?") for p in profiles) or "inga"
            raise ArrError(f"{self.name} har ingen kvalitetsprofil som heter {want} (finns: {names})")
        want_root = _norm_path(self.root)
        root = next((r for r in roots if _norm_path(r.get("path", "")) == want_root), None)
        if root is None:
            root = next((r for r in roots if _norm_path(r.get("path", "")).lower() == want_root.lower()), None)
        if root is None:
            paths = ", ".join(r.get("path", "?") for r in roots) or "inga"
            raise ArrError(f"{self.name} har ingen rotmapp {want_root} (finns: {paths})")
        return int(profile["id"]), profile.get("name", want), root["path"]

    async def add(self, item: dict, ids: dict) -> str:
        """Lägger till titeln och startar en sökning. Returnerar en kort text om vad som hände."""
        if self.kind == "radarr":
            return await self._add_movie(item, ids)
        return await self._add_series(item, ids)

    async def _add_movie(self, item: dict, ids: dict) -> str:
        if ids.get("tmdb"):
            movie = await self._call("GET", f"/api/v3/movie/lookup/tmdb?tmdbId={ids['tmdb']}")
        elif ids.get("imdb"):
            movie = await self._call("GET", f"/api/v3/movie/lookup/imdb?imdbId={ids['imdb']}")
        else:
            raise ArrError("Hittade inget TMDB- eller IMDb-id för filmen")
        if isinstance(movie, list):
            movie = movie[0] if movie else None
        if not movie:
            raise ArrError("Radarr hittade inte filmen")
        if movie.get("id"):
            return await self._existing_movie(int(movie["id"]))
        profile, _, root = await self._defaults()
        body = dict(movie)
        body.update(
            {"qualityProfileId": profile, "rootFolderPath": root, "monitored": True, "minimumAvailability": "released",
             "addOptions": {"searchForMovie": True}}
        )
        try:
            await self._call("POST", "/api/v3/movie", json=body)
        except ArrError as e:
            if _exists(e):
                return "Fanns redan i Radarr"
            raise
        return "Skickad till Radarr"

    async def _add_series(self, item: dict, ids: dict) -> str:
        term = f"tvdb:{ids['tvdb']}" if ids.get("tvdb") else item.get("title", "")
        found = await self._call("GET", "/api/v3/series/lookup", params={"term": term}) or []
        series = None
        if ids.get("tvdb"):
            series = found[0] if found else None
        else:
            series = next((s for s in found if s.get("year") == item.get("year")), found[0] if found else None)
        if not series:
            raise ArrError("Sonarr hittade inte serien")
        if series.get("id"):
            return await self._existing_series(int(series["id"]))
        profile, _, root = await self._defaults()
        body = dict(series)
        body.update(
            {"qualityProfileId": profile, "rootFolderPath": root, "monitored": True, "seasonFolder": True,
             "addOptions": {"monitor": "all", "searchForMissingEpisodes": True}}
        )
        # Sonarr v3 vill ha en språkprofil; v4 har inga.
        st = await self._call("GET", "/api/v3/system/status") or {}
        if str(st.get("version", "")).startswith("3."):
            langs = await self._call("GET", "/api/v3/languageprofile") or []
            if langs:
                body["languageProfileId"] = langs[0]["id"]
        try:
            await self._call("POST", "/api/v3/series", json=body)
        except ArrError as e:
            if _exists(e):
                return "Fanns redan i Sonarr"
            raise
        return "Skickad till Sonarr"


    async def _existing_movie(self, movie_id: int) -> str:
        """Filmen finns redan i Radarr. Saknas filen: bevaka den och sök, annars händer inget."""
        movie = await self._call("GET", f"/api/v3/movie/{movie_id}") or {}
        if movie.get("hasFile"):
            return "Fanns redan i Radarr"
        if not movie.get("monitored"):
            await self._call("PUT", "/api/v3/movie/editor", json={"movieIds": [movie_id], "monitored": True})
        await self._call("POST", "/api/v3/command", json={"name": "MoviesSearch", "movieIds": [movie_id]})
        return "Fanns redan i Radarr – sökning startad"

    async def _existing_series(self, series_id: int) -> str:
        """Serien finns redan i Sonarr: sök efter de bevakade avsnitt som saknas."""
        await self._call("POST", "/api/v3/command", json={"name": "SeriesSearch", "seriesId": series_id})
        return "Fanns redan i Sonarr – letar efter saknade avsnitt"

    # ---------- status i önskelistan ----------

    async def _find(self, item: dict, ids: dict) -> dict | None:
        """Titeln som den ligger i Radarr/Sonarr, eller None om den inte finns där."""
        if self.kind == "radarr":
            if ids.get("tmdb"):
                found = await self._call("GET", "/api/v3/movie", params={"tmdbId": str(ids["tmdb"])}) or []
                found = found if isinstance(found, list) else [found]
                # Äldre versioner struntar i filtret och skickar alla filmer.
                return next((m for m in found if str(m.get("tmdbId")) == str(ids["tmdb"])), None)
            if ids.get("imdb"):
                m = await self._call("GET", "/api/v3/movie/lookup/imdb", params={"imdbId": ids["imdb"]})
                m = (m[0] if m else None) if isinstance(m, list) else m
                return await self._call("GET", f"/api/v3/movie/{m['id']}") if m and m.get("id") else None
            raise ArrError("Hittar inget TMDB- eller IMDb-id för filmen")
        if ids.get("tvdb"):
            found = await self._call("GET", "/api/v3/series", params={"tvdbId": str(ids["tvdb"])}) or []
            found = found if isinstance(found, list) else [found]
            return next((s for s in found if str(s.get("tvdbId")) == str(ids["tvdb"])), None)
        found = await self._call("GET", "/api/v3/series/lookup", params={"term": item.get("title", "")}) or []
        mine = [s for s in found if s.get("id")]
        return next((s for s in mine if s.get("year") == item.get("year")), mine[0] if mine else None)

    def _link(self, found: dict, page: str = "") -> str:
        if page:
            return f"{self.url}/{page}"
        kind = "movie" if self.kind == "radarr" else "series"
        return f"{self.url}/{kind}/{found.get('titleSlug') or found.get('tmdbId') or found.get('id')}"

    async def statuses(self, wanted: list[tuple[str, dict, dict]]) -> dict[str, dict]:
        """Hur det går för godkända önskemål. wanted: (önskemålets id, önskemålet, ids)."""
        params = {"includeEpisode": "true"} if self.kind == "sonarr" else None
        queue, commands = await asyncio.gather(
            self._call("GET", "/api/v3/queue/details", params=params),
            self._call("GET", "/api/v3/command"),
            return_exceptions=True,
        )
        if isinstance(queue, ArrError):
            return {wid: _state("error", str(queue)) for wid, _, _ in wanted}
        if isinstance(queue, BaseException):
            raise queue
        searching = _searching(commands if isinstance(commands, list) else [])

        async def one(wid: str, item: dict, ids: dict):
            try:
                return wid, await self._status(item, ids, queue or [], searching)
            except ArrError as e:
                return wid, _state("error", str(e))

        return dict(await asyncio.gather(*(one(*w) for w in wanted)))

    async def _status(self, item: dict, ids: dict, queue: list, searching: set) -> dict:
        found = await self._find(item, ids)
        if not found:
            return _state("gone", f"Finns inte i {self.name} längre", actions=["resend"])
        fid = found.get("id")
        key = "movieId" if self.kind == "radarr" else "seriesId"
        mine = [q for q in queue if q.get(key) == fid]
        if mine:
            st = _queue_status(mine, self.kind == "sonarr", self.name)
            st["link"] = self._link(found, "activity/queue") if st["state"] in PROBLEMS else self._link(found)
            return st
        st = _movie_status(found, fid in searching) if self.kind == "radarr" else _series_status(found, fid in searching)
        st["link"] = self._link(found)
        return st

    async def search(self, item: dict, ids: dict) -> str:
        """Sök igen (och bevaka, om titeln inte är bevakad)."""
        found = await self._find(item, ids)
        if not found:
            raise ArrError(f"Finns inte i {self.name} – skicka den igen")
        fid = int(found["id"])
        if self.kind == "radarr":
            if not found.get("monitored"):
                await self._call("PUT", "/api/v3/movie/editor", json={"movieIds": [fid], "monitored": True})
            await self._call("POST", "/api/v3/command", json={"name": "MoviesSearch", "movieIds": [fid]})
        else:
            if not found.get("monitored"):
                await self._call("PUT", "/api/v3/series/editor", json={"seriesIds": [fid], "monitored": True})
            await self._call("POST", "/api/v3/command", json={"name": "SeriesSearch", "seriesId": fid})
        return f"{self.name} letar nu"

    async def retry(self, item: dict, ids: dict) -> str:
        """Släng nedladdningen som krånglar, svartlista släppet och leta efter ett annat."""
        found = await self._find(item, ids)
        if not found:
            raise ArrError(f"Finns inte i {self.name} – skicka den igen")
        fid = int(found["id"])
        key = "movieId" if self.kind == "radarr" else "seriesId"
        queue = await self._call("GET", "/api/v3/queue/details", params={key: str(fid)}) or []
        queue = [q for q in queue if q.get(key) == fid]
        bad = [q for q in queue if _queue_state(q) in PROBLEMS] or queue
        removed, episodes = set(), []
        for q in bad:
            if q.get("episodeId"):
                episodes.append(q["episodeId"])
            download = q.get("downloadId") or q.get("id")
            if download in removed:
                continue
            removed.add(download)
            try:
                # skipRedownload: vi startar sökningen själva nedan, så det blir likadant i alla versioner.
                await self._call("DELETE", f"/api/v3/queue/{q['id']}", params={
                    "removeFromClient": "true", "blocklist": "true", "skipRedownload": "true"})
            except ArrError as e:
                if e.status != 404:
                    raise
        if self.kind == "radarr":
            if not found.get("monitored"):
                await self._call("PUT", "/api/v3/movie/editor", json={"movieIds": [fid], "monitored": True})
            await self._call("POST", "/api/v3/command", json={"name": "MoviesSearch", "movieIds": [fid]})
        elif episodes:
            await self._call("POST", "/api/v3/command", json={"name": "EpisodeSearch", "episodeIds": sorted(set(episodes))})
        else:
            await self._call("POST", "/api/v3/command", json={"name": "SeriesSearch", "seriesId": fid})
        if removed:
            return f"Släppet är svartlistat – {self.name} letar efter ett annat"
        return f"{self.name} letar nu"


# Nedladdningar som behöver en hand: knappen Släng och sök annan visas.
PROBLEMS = ("stuck", "failed", "stalled")


def _state(state: str, text: str, **kw) -> dict:
    out = {"state": state, "text": text, "progress": None, "eta": None, "messages": [], "hint": None,
           "searchedAt": None, "release": None, "actions": [], "link": None}
    out.update(kw)
    return out


def _low(v) -> str:
    return str(v or "").lower()


def _searching(commands: list) -> set:
    """Id:n för filmer/serier som Radarr/Sonarr letar efter just nu."""
    out = set()
    for c in commands:
        if not isinstance(c, dict) or _low(c.get("status")) not in ("queued", "started"):
            continue
        body = c.get("body") or {}
        out.update(i for i in body.get("movieIds") or [] if isinstance(i, int))
        if isinstance(body.get("movieId"), int):
            out.add(body["movieId"])
        if isinstance(body.get("seriesId"), int):
            out.add(body["seriesId"])
    return out


def _seconds(timeleft) -> int | None:
    """Tid kvar skrivs "01:02:03" eller "2.01:02:03" (dagar först)."""
    m = re.match(r"^(?:(\d+)\.)?(\d+):(\d+):(\d+)", str(timeleft or ""))
    if not m:
        return None
    d, h, mi, s = (int(x or 0) for x in m.groups())
    return ((d * 24 + h) * 60 + mi) * 60 + s


def _when(text) -> float | None:
    try:
        return datetime.fromisoformat(str(text).replace("Z", "+00:00")).timestamp()
    except (TypeError, ValueError):
        return None


def _size(b) -> str:
    try:
        b = float(b or 0)
    except (TypeError, ValueError):
        return ""
    if b >= 1e9:
        return f"{b / 1e9:.1f} GB".replace(".", ",")
    return f"{b / 1e6:.0f} MB" if b > 0 else ""


def _queue_state(q: dict) -> str:
    st, tds, tdst = _low(q.get("status")), _low(q.get("trackedDownloadState")), _low(q.get("trackedDownloadStatus"))
    if tds in ("failed", "failedpending") or st == "failed" or tdst == "error":
        return "failed"
    if tds == "importblocked" or (tds in ("importpending", "importing") and tdst == "warning") \
            or (st == "completed" and tdst == "warning"):
        return "stuck"
    if tds in ("importpending", "importing", "imported") or st == "completed":
        return "importing"
    if st == "warning" or tdst == "warning":
        return "stalled"
    if st == "downloading":
        return "downloading"
    if st == "paused":
        return "paused"
    if st == "delay":
        return "delay"
    if st == "downloadclientunavailable":
        return "client"
    return "queued"


def _messages(q: dict) -> list[str]:
    out: list[str] = []
    for sm in q.get("statusMessages") or []:
        msgs = [m for m in sm.get("messages") or [] if m] or ([sm["title"]] if sm.get("title") else [])
        out += [str(m) for m in msgs if str(m) not in out]
    if q.get("errorMessage") and q["errorMessage"] not in out:
        out.append(str(q["errorMessage"]))
    return out


def _episodes(items: list) -> str:
    """S01E03, Säsong 1 eller 4 avsnitt (Sonarr)."""
    eps = sorted({(e.get("seasonNumber"), e.get("episodeNumber")) for e in (q.get("episode") or {} for q in items)
                  if isinstance(e.get("seasonNumber"), int) and isinstance(e.get("episodeNumber"), int)})
    if not eps:
        return ""
    if len(eps) == 1:
        return "S%02dE%02d" % eps[0]
    seasons = {s for s, _ in eps}
    return f"säsong {seasons.pop()}" if len(seasons) == 1 else f"{len(eps)} avsnitt"


ORDER = ("stuck", "failed", "stalled", "downloading", "importing", "delay", "paused", "client", "queued")
TEXTS = {
    "stuck": "Nedladdad men inte importerad",
    "failed": "Nedladdningen misslyckades",
    "stalled": "Nedladdningen står still",
    "downloading": "Laddar ner",
    "importing": "Nedladdad – flyttas in i biblioteket",
    "delay": "Hittad – väntar ut fördröjningen innan nedladdning",
    "paused": "Pausad i nedladdaren",
    "client": "Nedladdaren svarar inte",
    "queued": "I kö hos nedladdaren",
}


def _queue_status(items: list, series: bool, name: str = "Radarr") -> dict:
    by_download: dict = {}
    for q in items:
        by_download.setdefault(q.get("downloadId") or q.get("id"), []).append(q)
    downloads = [(group[0], _queue_state(group[0]), group) for group in by_download.values()]
    downloads.sort(key=lambda d: ORDER.index(d[1]))
    q, state, group = downloads[0]
    text = TEXTS[state]
    what = _episodes(group) if series else ""
    if state == "downloading" and series:
        what = _episodes([x for d in downloads if d[1] == "downloading" for x in d[2]])
    active = [d[0] for d in downloads if d[1] in ("downloading", "stalled", "queued", "paused", "delay")]
    size = sum(float(d.get("size") or 0) for d in active)
    left = sum(float(d.get("sizeleft") or 0) for d in active)
    progress = round((size - left) / size, 3) if size > 0 and state in ("downloading", "stalled", "paused") else None
    if progress is not None:
        text += f" {int(progress * 100)} %"
    if what:
        text = f"{text} · {what}"
    eta = None
    if state == "downloading":
        times = []
        for d in active:
            t = _seconds(d.get("timeleft"))
            if t is None and _when(d.get("estimatedCompletionTime")):
                t = max(0, int(_when(d["estimatedCompletionTime"]) - time.time()))
            if t is not None:
                times.append(t)
        eta = max(times) if times else None
    messages = []
    for d in downloads:
        if d[1] in PROBLEMS:
            messages += [m for m in _messages(d[0]) if m not in messages]
    quality = (((q.get("quality") or {}).get("quality") or {}).get("name")) or ""
    hint = None
    if state == "stuck":
        hint = f"Importera för hand i {name} (Activity → Queue), eller släng släppet så letar {name} upp ett annat."
    elif state == "failed":
        hint = f"Släng släppet så letar {name} upp ett annat."
    others = len(downloads) - 1
    if others > 0:
        hint = ((hint + " ") if hint else "") + f"Ytterligare {others} nedladdning{'ar' if others > 1 else ''} på gång."
    return _state(
        state, text, progress=progress, eta=eta, messages=messages[:4], hint=hint,
        actions=["retry"] if state in PROBLEMS else [], quality=quality or None,
        release_name=q.get("title") or None,
    )


def _movie_status(movie: dict, searching: bool) -> dict:
    if movie.get("hasFile"):
        f = movie.get("movieFile") or {}
        quality = (((f.get("quality") or {}).get("quality") or {}).get("name")) or ""
        extra = ", ".join(x for x in (quality, _size(f.get("size") or movie.get("sizeOnDisk"))) if x)
        return _state("done", f"Nedladdad{f' ({extra})' if extra else ''} – syns i Plex efter nästa skanning",
                      actions=["scan"])
    if not movie.get("monitored"):
        return _state("unmonitored", "Inte bevakad i Radarr – letar inte", actions=["search"])
    if movie.get("isAvailable") is False:
        now = time.time()
        dates = [("digitalt", movie.get("digitalRelease")), ("på skiva", movie.get("physicalRelease"))]
        future = sorted((_when(d), label, d) for label, d in dates if _when(d) and _when(d) > now)
        cinema = movie.get("inCinemas")
        if future:
            release = {"label": "släpps " + future[0][1], "at": future[0][2]}
        elif _when(cinema) and _when(cinema) > now:
            release = {"label": "biopremiär", "at": cinema}
        else:
            release = {"label": "på bio sedan", "at": cinema} if cinema else None
        return _state("waiting", "Väntar på release", release=release, actions=["search"],
                      hint="Radarr börjar leta av sig själv när den släpps.")
    if searching:
        return _state("searching", "Söker…")
    return _state(
        "missing", "Inget släpp hittat än", searchedAt=movie.get("lastSearchTime"), actions=["search"],
        hint="Nya släpp tas automatiskt när de dyker upp. Sök igen letar även bland äldre.",
    )


def _series_status(series: dict, searching: bool) -> dict:
    stats = series.get("statistics") or {}
    have, want = int(stats.get("episodeFileCount") or 0), int(stats.get("episodeCount") or 0)
    nxt = {"label": "nästa avsnitt", "at": series["nextAiring"]} if series.get("nextAiring") else None
    if not series.get("monitored"):
        return _state("unmonitored", "Inte bevakad i Sonarr – letar inte", actions=["search"])
    if want and have >= want:
        return _state("done", f"Alla {want} avsnitt nedladdade", release=nxt, actions=["scan"])
    if not want:
        first = series.get("firstAired") or series.get("nextAiring")
        return _state("waiting", "Väntar på premiär", release={"label": "premiär", "at": first} if first else None)
    if searching:
        return _state("searching", f"Söker… · {have} av {want} avsnitt" if have else "Söker…")
    if have:
        return _state("partial", f"{have} av {want} avsnitt nedladdade", release=nxt, actions=["search"],
                      hint="Resten tas automatiskt när de dyker upp. Sök igen letar även bland äldre.")
    return _state("missing", "Inga avsnitt hittade än", actions=["search"],
                  hint="Nya släpp tas automatiskt när de dyker upp. Sök igen letar även bland äldre.")


def _message(data) -> str | None:
    if isinstance(data, list) and data and isinstance(data[0], dict):
        return data[0].get("errorMessage") or data[0].get("message")
    if isinstance(data, dict):
        return data.get("message") or data.get("errorMessage")
    return None


def _exists(e: ArrError) -> bool:
    text = str(e).lower()
    return "already" in text or "exists" in text
