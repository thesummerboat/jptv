"""
Radarr (filmer) och Sonarr (serier): ett godkänt önskemål läggs till där, och de sköter resten.
Används bara om adress och API-nyckel finns under Inställningar. Vad de hämtar och varifrån
bestäms i Radarr/Sonarr, inte här.
"""
from __future__ import annotations

import json

import aiohttp


class ArrError(Exception):
    def __init__(self, message: str, status: int | None = None, data=None):
        super().__init__(message)
        self.status = status
        self.data = data


class Arr:
    def __init__(self, http: aiohttp.ClientSession, kind: str, url: str, key: str):
        self.http = http
        self.kind = kind  # "radarr" eller "sonarr"
        self.url = (url or "").strip().rstrip("/")
        if self.url and not self.url.startswith(("http://", "https://")):
            self.url = "http://" + self.url
        self.key = (key or "").strip()

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
        st = await self._call("GET", "/api/v3/system/status")
        return f"{self.name} {(st or {}).get('version', '')} svarar".strip()

    async def _defaults(self) -> tuple[int, str]:
        profiles = await self._call("GET", "/api/v3/qualityprofile") or []
        roots = await self._call("GET", "/api/v3/rootfolder") or []
        if not profiles or not roots:
            raise ArrError(f"{self.name} saknar kvalitetsprofil eller rotmapp")
        return profiles[0]["id"], roots[0]["path"]

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
            return "Fanns redan i Radarr"
        profile, root = await self._defaults()
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
            return "Fanns redan i Sonarr"
        profile, root = await self._defaults()
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


def _message(data) -> str | None:
    if isinstance(data, list) and data and isinstance(data[0], dict):
        return data[0].get("errorMessage") or data[0].get("message")
    if isinstance(data, dict):
        return data.get("message") or data.get("errorMessage")
    return None


def _exists(e: ArrError) -> bool:
    text = str(e).lower()
    return "already" in text or "exists" in text
