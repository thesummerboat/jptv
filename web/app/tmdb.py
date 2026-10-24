"""
Upptäck (som Seerr): trendande, populära och kommande filmer och serier, genrer, studior och
nätverk från TMDB (themoviedb.org), på svenska. Det som saknas i Plex kan önskas; önskemålen går
som vanligt till Radarr och Sonarr.

Kräver en gratis nyckel från themoviedb.org (API-nyckel v3 eller läs-token v4). Bilderna går
via servern, så att webbläsaren inte pratar med TMDB själv och bilderna sparas en tid.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import time
from datetime import date
from urllib.parse import urlencode

import aiohttp

log = logging.getLogger("jptv.tmdb")

IMG_BASE = "https://image.tmdb.org/t/p"
IMG_RE = re.compile(r"^(w\d{2,4}|h\d{2,4}|original)/[A-Za-z0-9_-]+\.(jpg|jpeg|png|svg)$")

# Som i Seerr. Logotyperna hämtas från TMDB första gången.
STUDIOS = [
    (2, "Walt Disney Pictures"), (127928, "20th Century Studios"), (34, "Sony Pictures"), (174, "Warner Bros. Pictures"),
    (33, "Universal Pictures"), (4, "Paramount"), (3, "Pixar"), (521, "DreamWorks Animation"), (420, "Marvel Studios"),
    (41077, "A24"), (1632, "Lionsgate"),
]
NETWORKS = [
    (213, "Netflix"), (2739, "Disney+"), (1024, "Prime Video"), (2552, "Apple TV+"), (49, "HBO"), (3186, "Max"),
    (453, "Hulu"), (4330, "Paramount+"), (4353, "discovery+"), (174, "AMC"), (4, "BBC One"), (88, "FX"),
    (2, "ABC"), (6, "NBC"), (19, "FOX"), (16, "CBS"),
]

LISTS = {
    "trending": ("Trendar just nu", None),
    "popular-movies": ("Populära filmer", "movie"),
    "upcoming-movies": ("Kommande filmer", "movie"),
    "popular-series": ("Populära serier", "tv"),
    "upcoming-series": ("Kommande serier", "tv"),
    "genre-movie": ("Filmer", "movie"),
    "genre-tv": ("Serier", "tv"),
    "studio": ("Filmer", "movie"),
    "network": ("Serier", "tv"),
}


def _api() -> str:
    return os.environ.get("JPTV_TMDB_API", "https://api.themoviedb.org/3")


def img_base() -> str:
    """TMDB:s bildserver (testerna pekar om den)."""
    return os.environ.get("JPTV_TMDB_IMG", IMG_BASE)


def img(path: str | None, size: str = "w342") -> str | None:
    """Bild via servern: /tmdb/img/w342/abc.jpg."""
    return f"/tmdb/img/{size}{path}" if path else None


def full_img(path: str | None, size: str = "w500") -> str | None:
    """Full adress till TMDB:s bild (sparas i önskemålen, som TV:n visar direkt)."""
    return f"{IMG_BASE}/{size}{path}" if path else None


def guid(kind: str, tmdb_id) -> str:
    """Samma nyckel för en titel överallt: tmdb://movie/603 eller tmdb://tv/1399."""
    return f"tmdb://{'tv' if kind in ('tv', 'show') else 'movie'}/{int(tmdb_id)}"


def _year(d: str | None) -> int | None:
    return int(d[:4]) if d and d[:4].isdigit() else None


def card(o: dict, kind: str | None = None) -> dict | None:
    """En film eller serie ur en TMDB-lista som kort."""
    t = kind or o.get("media_type")
    if t not in ("movie", "tv") or not o.get("id") or o.get("adult"):
        return None
    d = o.get("release_date") or o.get("first_air_date") or ""
    return {
        "tmdb": int(o["id"]),
        "type": "movie" if t == "movie" else "show",
        "title": o.get("title") or o.get("name") or o.get("original_title") or o.get("original_name") or "",
        "year": _year(d),
        "date": d or None,
        "poster": img(o.get("poster_path")),
        "backdrop": img(o.get("backdrop_path"), "w780"),
        "overview": (o.get("overview") or "")[:400],
        "rating": round(float(o.get("vote_average") or 0), 1) or None,
        "votes": int(o.get("vote_count") or 0),
        "popularity": float(o.get("popularity") or 0),
    }


def cards(results, kind: str | None = None) -> list[dict]:
    out, seen = [], set()
    for o in results or []:
        c = card(o, kind) if isinstance(o, dict) else None
        if c and (c["type"], c["tmdb"]) not in seen:
            seen.add((c["type"], c["tmdb"]))
            out.append(c)
    return out


def pick_trailer(videos: list[dict]) -> dict | None:
    """Trailern på YouTube: officiell före annan, svensk före engelsk, trailer före teaser."""
    yt = [v for v in videos or [] if v.get("site") == "YouTube" and v.get("key") and v.get("type") in ("Trailer", "Teaser")]
    if not yt:
        return None
    lang = {"sv": 0, "en": 1}
    yt.sort(key=lambda v: (v.get("type") != "Trailer", not v.get("official"), lang.get(v.get("iso_639_1"), 2), -(v.get("size") or 0)))
    return {"youtube": yt[0]["key"], "name": yt[0].get("name") or "Trailer"}


def certification(d: dict, kind: str) -> str | None:
    """Åldersgräns i Sverige, annars USA."""
    if kind == "movie":
        by = {r.get("iso_3166_1"): r.get("release_dates") or [] for r in (d.get("release_dates") or {}).get("results") or []}
        for country in ("SE", "US"):
            c = next((x.get("certification") for x in by.get(country, []) if x.get("certification")), None)
            if c:
                return c
        return None
    by = {r.get("iso_3166_1"): r.get("rating") for r in (d.get("content_ratings") or {}).get("results") or []}
    return by.get("SE") or by.get("US") or None


def providers(d: dict) -> list[dict]:
    """Var titeln går att se i Sverige (streaming först, sedan hyra och köpa)."""
    se = ((d.get("watch/providers") or {}).get("results") or {}).get("SE") or {}
    out, seen = [], set()
    for kind, label in (("flatrate", "Streaming"), ("free", "Gratis"), ("ads", "Med reklam"), ("rent", "Hyra"), ("buy", "Köpa")):
        for p in sorted(se.get(kind) or [], key=lambda p: p.get("display_priority") or 99):
            if p.get("provider_id") in seen:
                continue
            seen.add(p.get("provider_id"))
            out.append({"name": p.get("provider_name") or "", "logo": img(p.get("logo_path"), "w92"), "kind": label})
    return out


class TmdbError(Exception):
    def __init__(self, message: str, status: int | None = None):
        super().__init__(message)
        self.status = status


class Tmdb:
    def __init__(self, http: aiohttp.ClientSession, store, config_dir: str):
        self.http = http
        self.store = store
        self._cache: dict[str, tuple[float, dict]] = {}
        self._inflight: dict[str, asyncio.Future] = {}
        self._logos_path = os.path.join(config_dir, "tmdb-logos.json")
        try:
            with open(self._logos_path, encoding="utf-8") as f:
                self.logos: dict = json.load(f)
        except (OSError, ValueError):
            self.logos = {}

    @property
    def key(self) -> str:
        return str(self.store.get("tmdb_key", "") or "").strip()

    @property
    def configured(self) -> bool:
        return bool(self.key)

    # ---------- anrop

    async def get(self, path: str, params: dict | None = None, ttl: float = 3600, language: str = "sv-SE") -> dict:
        key = self.key
        if not key:
            raise TmdbError("Lägg in en TMDB-nyckel under Inställningar → Önskemål", 400)
        p = {"language": language, **(params or {})}
        headers = {"Accept": "application/json"}
        # Läs-token (v4) är lång och skickas som Bearer; API-nyckeln (v3) som parameter.
        if len(key) > 40:
            headers["Authorization"] = f"Bearer {key}"
        else:
            p["api_key"] = key
        cache_key = path + "?" + urlencode(sorted((k, str(v)) for k, v in p.items() if k != "api_key"))
        hit = self._cache.get(cache_key)
        if hit and time.monotonic() - hit[0] < ttl:
            return hit[1]
        task = self._inflight.get(cache_key)
        if task is None:
            task = self._inflight[cache_key] = asyncio.ensure_future(self._fetch(path, p, headers))
            task.add_done_callback(lambda _: self._inflight.pop(cache_key, None))
        data = await asyncio.shield(task)
        self._cache[cache_key] = (time.monotonic(), data)
        if len(self._cache) > 800:
            for k in sorted(self._cache, key=lambda k: self._cache[k][0])[:200]:
                self._cache.pop(k, None)
        return data

    async def _fetch(self, path: str, params: dict, headers: dict) -> dict:
        try:
            async with self.http.get(_api() + path, params=params, headers=headers, timeout=aiohttp.ClientTimeout(total=15)) as r:
                if r.status == 401:
                    raise TmdbError("TMDB godkände inte nyckeln (Inställningar → Önskemål)", 401)
                if r.status == 404:
                    raise TmdbError("Finns inte hos TMDB", 404)
                if r.status == 429:
                    raise TmdbError("TMDB vill att vi väntar en stund", 429)
                if r.status >= 400:
                    raise TmdbError(f"TMDB svarade {r.status}", r.status)
                return await r.json(content_type=None)
        except (aiohttp.ClientError, asyncio.TimeoutError) as e:
            raise TmdbError(f"TMDB svarar inte ({e or type(e).__name__})")

    async def test(self) -> str:
        data = await self.get("/trending/all/day", ttl=0)
        n = len(data.get("results") or [])
        return f"TMDB fungerar ({n} trendande titlar)"

    # ---------- listor

    def _list_call(self, what: str, page: int, ident: int | None) -> tuple[str, dict]:
        today = date.today().isoformat()
        if what == "trending":
            return "/trending/all/week", {"page": page}
        if what == "popular-movies":
            return "/movie/popular", {"page": page, "region": "SE"}
        if what == "upcoming-movies":
            return "/discover/movie", {"page": page, "region": "SE", "primary_release_date.gte": today,
                                       "sort_by": "popularity.desc", "include_adult": "false"}
        if what == "popular-series":
            return "/tv/popular", {"page": page}
        if what == "upcoming-series":
            return "/discover/tv", {"page": page, "first_air_date.gte": today, "sort_by": "popularity.desc", "include_adult": "false"}
        if ident is None:
            raise TmdbError("Saknar id", 400)
        if what == "genre-movie":
            return "/discover/movie", {"page": page, "with_genres": ident, "sort_by": "popularity.desc", "include_adult": "false"}
        if what == "genre-tv":
            return "/discover/tv", {"page": page, "with_genres": ident, "sort_by": "popularity.desc", "include_adult": "false"}
        if what == "studio":
            return "/discover/movie", {"page": page, "with_companies": ident, "sort_by": "popularity.desc", "include_adult": "false"}
        if what == "network":
            return "/discover/tv", {"page": page, "with_networks": ident, "sort_by": "popularity.desc", "include_adult": "false"}
        raise TmdbError("Okänd lista", 404)

    async def titles(self, what: str, page: int = 1, ident: int | None = None) -> dict:
        if what not in LISTS:
            raise TmdbError("Okänd lista", 404)
        path, params = self._list_call(what, max(1, min(page, 500)), ident)
        data = await self.get(path, params, ttl=3600)
        return {
            "items": cards(data.get("results"), LISTS[what][1]),
            "page": int(data.get("page") or page),
            "pages": min(int(data.get("total_pages") or 1), 500),
        }

    async def genres(self, kind: str) -> list[dict]:
        """Genrerna med en bild ur en populär titel i varje (som Seerr:s genrekort)."""
        data = await self.get(f"/genre/{kind}/list", ttl=86400)
        sem = asyncio.Semaphore(6)

        async def one(g):
            async with sem:
                try:
                    lst = await self.titles(f"genre-{kind}", 1, g["id"])
                except TmdbError:
                    lst = {"items": []}
            art = next((c["backdrop"] for c in lst["items"] if c["backdrop"]), None)
            return {"id": g["id"], "name": g.get("name") or "", "backdrop": art}

        return list(await asyncio.gather(*(one(g) for g in data.get("genres") or [])))

    async def logo(self, kind: str, ident: int, name: str) -> dict:
        """Logotyp för ett studio (company) eller nätverk; sparas i /config för gott."""
        key = f"{kind}:{ident}"
        hit = self.logos.get(key)
        if hit is None:
            try:
                d = await self.get(f"/{kind}/{ident}", ttl=86400 * 30, language="en-US")
                hit = {"name": d.get("name") or name, "logo": d.get("logo_path")}
            except TmdbError:
                return {"id": ident, "name": name, "logo": None}
            self.logos[key] = hit
            try:
                tmp = self._logos_path + ".tmp"
                with open(tmp, "w", encoding="utf-8") as f:
                    json.dump(self.logos, f, ensure_ascii=False)
                os.replace(tmp, self._logos_path)
            except OSError:
                pass
        return {"id": ident, "name": hit.get("name") or name, "logo": img(hit.get("logo"), "w300")}

    async def home(self) -> list[dict]:
        """Raderna på Upptäck, i Seerr:s ordning."""
        names = ["trending", "popular-movies", "upcoming-movies", "popular-series", "upcoming-series"]
        lists = await asyncio.gather(*(self.titles(n) for n in names), return_exceptions=True)
        movie_genres, tv_genres = await asyncio.gather(self.genres("movie"), self.genres("tv"), return_exceptions=True)
        studios = await asyncio.gather(*(self.logo("company", i, n) for i, n in STUDIOS))
        networks = await asyncio.gather(*(self.logo("network", i, n) for i, n in NETWORKS))
        errors = [r for r in [*lists, movie_genres, tv_genres] if isinstance(r, Exception)]
        if len(errors) == len(lists) + 2:
            raise errors[0] if isinstance(errors[0], TmdbError) else TmdbError(str(errors[0]))
        by = dict(zip(names, lists))

        def titles_row(name):
            r = by[name]
            return {"id": name, "title": LISTS[name][0], "kind": "titles", "items": [] if isinstance(r, Exception) else r["items"]}

        rows = [
            titles_row("trending"),
            titles_row("popular-movies"),
            {"id": "movie-genres", "title": "Filmgenrer", "kind": "genres", "media": "movie",
             "items": [] if isinstance(movie_genres, Exception) else movie_genres},
            titles_row("upcoming-movies"),
            {"id": "studios", "title": "Studior", "kind": "studios", "items": list(studios)},
            titles_row("popular-series"),
            {"id": "series-genres", "title": "Seriegenrer", "kind": "genres", "media": "tv",
             "items": [] if isinstance(tv_genres, Exception) else tv_genres},
            titles_row("upcoming-series"),
            {"id": "networks", "title": "Nätverk", "kind": "networks", "items": list(networks)},
        ]
        return [r for r in rows if r["items"]]

    # ---------- titel, person, sök

    async def title(self, kind: str, ident: int) -> dict:
        tk = "tv" if kind in ("tv", "show") else "movie"
        extra = "credits,videos,external_ids,recommendations,similar,watch/providers," + (
            "release_dates" if tk == "movie" else "content_ratings")
        d = await self.get(f"/{tk}/{ident}", {"append_to_response": extra, "include_video_language": "sv,en,null"}, ttl=6 * 3600)
        overview, tagline = d.get("overview") or "", d.get("tagline") or ""
        if not overview:
            # Ingen svensk text: engelska i stället.
            try:
                en = await self.get(f"/{tk}/{ident}", ttl=6 * 3600, language="en-US")
                overview, tagline = en.get("overview") or "", tagline or en.get("tagline") or ""
            except TmdbError:
                pass
        ext = d.get("external_ids") or {}
        credits = d.get("credits") or {}
        cast = [
            {"id": p.get("id"), "name": p.get("name") or "", "role": p.get("character") or "", "photo": img(p.get("profile_path"), "w185")}
            for p in (credits.get("cast") or [])[:20] if p.get("id")
        ]
        if tk == "movie":
            makers = [p.get("name") for p in credits.get("crew") or [] if p.get("job") == "Director"][:3]
        else:
            makers = [p.get("name") for p in d.get("created_by") or []][:3]
        runtime = d.get("runtime") if tk == "movie" else next(iter(d.get("episode_run_time") or []), None)
        dt = d.get("release_date") if tk == "movie" else d.get("first_air_date")
        return {
            "tmdb": int(d.get("id") or ident),
            "type": "movie" if tk == "movie" else "show",
            "title": d.get("title") or d.get("name") or "",
            "originalTitle": d.get("original_title") or d.get("original_name") or "",
            "year": _year(dt),
            "date": dt or None,
            "lastDate": d.get("last_air_date"),
            "status": d.get("status"),
            "runtime": runtime,
            "seasons": d.get("number_of_seasons"),
            "episodes": d.get("number_of_episodes"),
            "genres": [g.get("name") for g in d.get("genres") or [] if g.get("name")],
            "overview": overview,
            "tagline": tagline,
            "rating": round(float(d.get("vote_average") or 0), 1) or None,
            "votes": int(d.get("vote_count") or 0),
            "poster": img(d.get("poster_path"), "w500"),
            "backdrop": img(d.get("backdrop_path"), "w1280"),
            "posterFull": full_img(d.get("poster_path")),
            "backdropFull": full_img(d.get("backdrop_path"), "w1280"),
            "certification": certification(d, tk),
            "language": d.get("original_language"),
            "makers": makers,
            "companies": [c.get("name") for c in (d.get("production_companies") or [])[:3] if c.get("name")],
            "networks": [n.get("name") for n in (d.get("networks") or [])[:3] if n.get("name")],
            "cast": cast,
            "trailer": pick_trailer((d.get("videos") or {}).get("results")),
            "providers": providers(d),
            "recommendations": cards((d.get("recommendations") or {}).get("results"), tk)[:20],
            "similar": cards((d.get("similar") or {}).get("results"), tk)[:20],
            "ids": {k: v for k, v in {
                "tmdb": int(d.get("id") or ident),
                "imdb": d.get("imdb_id") or ext.get("imdb_id"),
                "tvdb": ext.get("tvdb_id"),
            }.items() if v},
        }

    async def external_ids(self, kind: str, ident: int) -> dict:
        tk = "tv" if kind in ("tv", "show") else "movie"
        ext = await self.get(f"/{tk}/{ident}/external_ids", ttl=86400)
        return {k: v for k, v in {"tmdb": int(ident), "imdb": ext.get("imdb_id"), "tvdb": ext.get("tvdb_id")}.items() if v}

    async def person(self, ident: int) -> dict:
        d = await self.get(f"/person/{ident}", {"append_to_response": "combined_credits"}, ttl=6 * 3600)
        bio = d.get("biography") or ""
        if not bio:
            try:
                bio = (await self.get(f"/person/{ident}", ttl=6 * 3600, language="en-US")).get("biography") or ""
            except TmdbError:
                pass
        credits = (d.get("combined_credits") or {})
        items = cards((credits.get("cast") or []) + (credits.get("crew") or []))
        items.sort(key=lambda c: (-c["votes"] if c["votes"] >= 50 else 0, -c["popularity"]))
        return {
            "id": int(d.get("id") or ident),
            "name": d.get("name") or "",
            "photo": img(d.get("profile_path"), "h632"),
            "department": d.get("known_for_department"),
            "birthday": d.get("birthday"),
            "deathday": d.get("deathday"),
            "place": d.get("place_of_birth"),
            "biography": bio[:4000],
            "items": items[:80],
        }

    async def search(self, q: str, page: int = 1) -> dict:
        q = q.strip()
        if not q:
            return {"items": [], "people": [], "page": 1, "pages": 1}
        data = await self.get("/search/multi", {"query": q, "page": max(1, page), "include_adult": "false"}, ttl=600)
        people = [
            {"id": o.get("id"), "name": o.get("name") or "", "photo": img(o.get("profile_path"), "w185"),
             "department": o.get("known_for_department")}
            for o in data.get("results") or [] if o.get("media_type") == "person" and o.get("id")
        ]
        return {"items": cards(data.get("results")), "people": people[:12], "page": int(data.get("page") or 1),
                "pages": min(int(data.get("total_pages") or 1), 500)}
