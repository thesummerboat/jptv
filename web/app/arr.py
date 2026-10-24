"""
Radarr (filmer) och Sonarr (serier): ett godkänt önskemål läggs till där, och de sköter resten.
Används bara om adress och API-nyckel finns under Inställningar.

Kvalitetsprofil och rotmapp väljs med namn (Inställningar → Önskemål), aldrig "den första i
listan": i Radarr är den första profilen ofta "Any", som gärna tar en remux på 50 GB.
Finns inte den valda profilen eller mappen stoppar vi med ett tydligt fel i stället för att gissa.
"""
from __future__ import annotations

import json
import re

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


def _message(data) -> str | None:
    if isinstance(data, list) and data and isinstance(data[0], dict):
        return data[0].get("errorMessage") or data[0].get("message")
    if isinstance(data, dict):
        return data.get("message") or data.get("errorMessage")
    return None


def _exists(e: ArrError) -> bool:
    text = str(e).lower()
    return "already" in text or "exists" in text
