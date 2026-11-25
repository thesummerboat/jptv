"""
OMDb (omdbapi.com): betyg från IMDb, Rotten Tomatoes och Metacritic för en titel, via dess IMDb-id.
Valfritt: kräver en gratis nyckel (1000 uppslag per dygn), som läggs in under Inställningar → Önskemål.
IMDb-betyget finns redan via IMDb:s datafil (imdb.py); OMDb behövs för Rotten Tomatoes och Metacritic.
Svaren sparas ett dygn, så samma titel kostar bara ett uppslag.
"""
from __future__ import annotations

import asyncio
import os
import re
import time

import aiohttp

DAY = 24 * 3600
IMDB_ID = re.compile(r"^tt\d{1,12}$")


def _api() -> str:
    return os.environ.get("JPTV_OMDB_API", "https://www.omdbapi.com/")


class OmdbError(Exception):
    pass


def _num(value) -> float | None:
    """'8.1', '93%', '78/100', '1,234,567' -> tal; 'N/A' -> None."""
    m = re.match(r"^\s*([\d.,]+)", str(value or ""))
    if not m:
        return None
    try:
        return float(m.group(1).replace(",", ""))
    except ValueError:
        return None


def scores(data: dict) -> dict:
    """OMDb-svaret -> {"imdb": 8.1, "imdbVotes": 1234567, "rt": 93, "metacritic": 78}."""
    ratings = {r.get("Source"): r.get("Value") for r in data.get("Ratings") or [] if isinstance(r, dict)}
    imdb = _num(data.get("imdbRating")) or _num(ratings.get("Internet Movie Database"))
    votes = _num(data.get("imdbVotes"))
    rt = _num(ratings.get("Rotten Tomatoes"))
    mc = _num(data.get("Metascore")) or _num(ratings.get("Metacritic"))
    out = {
        "imdb": imdb,
        "imdbVotes": int(votes) if votes else None,
        "rt": int(rt) if rt is not None else None,
        "metacritic": int(mc) if mc is not None else None,
    }
    if data.get("imdbID"):
        out["imdbUrl"] = f"https://www.imdb.com/title/{data['imdbID']}/"
    return out


class Omdb:
    def __init__(self, http: aiohttp.ClientSession, store):
        self.http = http
        self.store = store
        self._cache: dict[str, tuple[float, dict | None]] = {}
        self._inflight: dict[str, asyncio.Future] = {}

    @property
    def key(self) -> str:
        return str(self.store.get("omdb_key", "") or "").strip()

    @property
    def configured(self) -> bool:
        return bool(self.key)

    async def scores(self, imdb_id: str | None) -> dict | None:
        """Betygen för en titel, eller None om OMDb inte känner till den."""
        if not self.configured or not imdb_id or not IMDB_ID.match(str(imdb_id)):
            return None
        hit = self._cache.get(imdb_id)
        if hit and time.monotonic() - hit[0] < DAY:
            return hit[1]
        task = self._inflight.get(imdb_id)
        if task is None:
            task = self._inflight[imdb_id] = asyncio.ensure_future(self._fetch(imdb_id))
            task.add_done_callback(lambda _: self._inflight.pop(imdb_id, None))
        result = await asyncio.shield(task)
        self._cache[imdb_id] = (time.monotonic(), result)
        if len(self._cache) > 2000:
            for k in sorted(self._cache, key=lambda k: self._cache[k][0])[:500]:
                self._cache.pop(k, None)
        return result

    async def _fetch(self, imdb_id: str) -> dict | None:
        try:
            async with self.http.get(_api(), params={"i": imdb_id, "apikey": self.key},
                                     timeout=aiohttp.ClientTimeout(total=10)) as r:
                if r.status == 401:
                    raise OmdbError("OMDb godkände inte nyckeln (Inställningar → Önskemål)")
                if r.status >= 500:
                    raise OmdbError(f"OMDb svarade {r.status}")
                data = await r.json(content_type=None)
        except (aiohttp.ClientError, asyncio.TimeoutError, ValueError) as e:
            raise OmdbError(f"OMDb svarar inte ({e or type(e).__name__})")
        if str(data.get("Response")) == "False":
            err = str(data.get("Error") or "")
            if "key" in err.lower():
                raise OmdbError("OMDb godkände inte nyckeln (Inställningar → Önskemål)")
            if "limit" in err.lower():
                raise OmdbError("OMDb: dagens gräns på 1000 uppslag är nådd")
            return None  # okänd titel
        return scores(data)

    async def test(self) -> str:
        if not self.configured:
            raise OmdbError("Ingen OMDb-nyckel sparad")
        self._cache.pop("tt0133093", None)
        s = await self.scores("tt0133093")
        if not s:
            return "OMDb svarar, men hittade inte testfilmen"
        parts = [f"Rotten Tomatoes {s['rt']} %" if s.get("rt") is not None else None,
                 f"Metacritic {s['metacritic']}" if s.get("metacritic") is not None else None]
        return "OMDb fungerar (The Matrix: " + (", ".join(p for p in parts if p) or "inga kritikerbetyg") + ")"
