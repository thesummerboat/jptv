"""Hemskärmens och bibliotekens rader, som på TV:n: Fortsätt titta, Nytt, Rekommenderat, Topplista …"""
from __future__ import annotations

import asyncio
import random
import time

from . import recs
from .plex import Plex, public

CONTINUE = "Fortsätt titta"


def now_ms() -> int:
    return int(time.time() * 1000)


def group_new_episodes(items: list[dict]) -> list[dict]:
    """Nytt i serier: ett kort per serie ("3 nya avsnitt") i stället för ett per avsnitt."""
    groups: dict[str, list[dict]] = {}
    for it in items:
        groups.setdefault(it.get("showKey") or it["key"], []).append(it)
    out = []
    for group in groups.values():
        newest = group[0]
        if len(group) == 1 and newest["type"] != "season":
            out.append(newest)
            continue
        if not newest.get("showKey"):
            out.extend(group)
            continue
        if any(g["type"] == "season" for g in group):
            label = f"Nya avsnitt i säsong {newest.get('season') or ''}".strip() if len(group) == 1 else "Nya avsnitt"
        else:
            label = f"{len(group)} nya avsnitt"
        sub = f"S{newest.get('season')}E{newest.get('episode')} · {newest['title']}" if newest["type"] == "episode" else newest["title"]
        out.append(
            newest
            | {
                "key": newest["showKey"],
                "type": "show",
                "title": newest.get("show") or newest["title"],
                "show": None,
                "season": None,
                "episode": None,
                "still": None,
                "logo": None,
                "ratings": [],
                "watched": False,
                "offset": 0,
                "label": label,
                "summary": f"Senaste: {sub}. {newest.get('summary') or ''}".strip(),
                "_tags": [],
            }
        )
    return out


def row(title: str, items: list[dict], kind: str = "poster") -> dict:
    return {"title": title, "kind": kind, "items": [public(i) for i in items]}


def pick_featured(rows: list[dict], count: int = 5) -> list[dict]:
    """Fem av de femton bästa förslagen (film och serie om vartannat), nya varje gång."""
    def usable(lst):
        return [i for i in lst if i["type"] in ("movie", "show") and i.get("art") and not i.get("watched")]

    rec = usable([i for r in rows if r["title"].startswith("Rekommenderat för dig") for i in r["items"]])
    movies = [i for i in rec if i["type"] == "movie"]
    shows = [i for i in rec if i["type"] == "show"]
    mixed = []
    for i in range(max(len(movies), len(shows))):
        if i < len(movies):
            mixed.append(movies[i])
        if i < len(shows):
            mixed.append(shows[i])
    fallback = usable(
        [i for r in rows if r["title"].startswith(("Topplista", "Eftersom")) for i in r["items"]]
        + [i for r in rows if r["title"] != CONTINUE for i in r["items"]]
    )
    pool, seen = [], set()
    for i in mixed + fallback:
        if i["key"] not in seen:
            seen.add(i["key"])
            pool.append(i)
        if len(pool) >= 15:
            break
    random.shuffle(pool)
    return pool[:count]


class Library:
    def __init__(self, plex: Plex):
        self.plex = plex
        self.items: dict[str, list[dict]] = {}
        self.rec_rows: list[dict] = []
        self.top_rows: dict[str, list[dict]] = {}
        self.index_at = 0.0
        self._task: asyncio.Task | None = None
        self._section_cache: dict[str, tuple[float, list[dict]]] = {}

    def invalidate(self) -> None:
        self.index_at = 0
        self._section_cache.clear()

    async def ensure_index(self, wait: float = 25, max_age: float = 2 * 3600) -> None:
        """
        Alla filmer och serier plus tittarhistoriken: underlaget för rekommendationer och topplistor.
        Byggs i bakgrunden högst varannan timme; den som frågar väntar högst `wait` sekunder.
        """
        if self.items and time.monotonic() - self.index_at < max_age:
            return
        if self._task is None or self._task.done():
            self._task = asyncio.create_task(self._build_index())
        try:
            await asyncio.wait_for(asyncio.shield(self._task), wait)
        except Exception:
            pass

    async def _build_index(self) -> None:
        sections = await self.plex.sections()
        results = await asyncio.gather(*(self.plex.all(s["key"], cache=0) for s in sections), return_exceptions=True)
        items = {s["key"]: (r if isinstance(r, list) else []) for s, r in zip(sections, results)}
        try:
            history = await self.plex.history()
        except Exception:
            history = []
        now = now_ms()
        everything = [i for lst in items.values() for i in lst]
        model = await asyncio.to_thread(recs.build, recs.rec_items(everything, history, now), now)
        by_id = {i["key"]: i for i in everything}
        today = now // recs.DAY
        rows = []

        def add(title, ids):
            lst = [by_id[i] for i in ids if i in by_id]
            if len(lst) >= 5:
                rows.append({"title": title, "items": lst})

        if recs.signal(model, "movie") >= 3:
            add("Rekommenderat för dig · Filmer", recs.daily_mix(recs.for_you(model, "movie", now, 40), today))
        if recs.signal(model, "show") >= 2:
            add("Rekommenderat för dig · Serier", recs.daily_mix(recs.for_you(model, "show", now, 40), today))
        for seed in recs.seeds(model, now):
            add(f"Eftersom du såg {by_id[seed]['title']}", recs.similar_to(model, seed))
        tops = {}
        for s in sections:
            # Som på TV:n: med röster från IMDb:s fil krävs tillräckligt många, så att smala titlar
            # med få röster inte toppar listan.
            min_votes = 25000 if s["type"] == "movie" else 10000
            rated = [
                (i, next((r["value"] for r in i.get("ratings", []) if r["src"] == "IMDb"), None))
                for i in items[s["key"]]
                if "_votes" not in i or i["_votes"] >= min_votes
            ]
            rated = sorted((x for x in rated if x[1] is not None), key=lambda x: -x[1])[:20]
            tops[s["key"]] = [i for i, _ in rated]
        self.items = items
        self.rec_rows = rows
        self.top_rows = tops
        self.index_at = time.monotonic()
        self._section_cache.clear()

    async def guid_map(self) -> dict[str, str]:
        """Plex-guid -> nyckel för allt i biblioteket, även det som nyss lagts till (för önskemål)."""
        await self.ensure_index(wait=5)
        out: dict[str, str] = {}
        for lst in self.items.values():
            for i in lst:
                if i.get("guid"):
                    out[i["guid"]] = i["key"]
                # Samma titel med TMDB-nyckeln (önskemål från Upptäck).
                if i.get("_tmdb") and i.get("type") in ("movie", "show"):
                    out.setdefault(f"tmdb://{'tv' if i['type'] == 'show' else 'movie'}/{i['_tmdb']}", i["key"])
        sections = await self._safe(self.plex.sections())
        recent = await asyncio.gather(*(self._safe(self.plex.recently_added(s["key"], 50)) for s in sections))
        for lst in recent:
            for i in lst:
                if i.get("guid") and i["type"] in ("movie", "show"):
                    out.setdefault(i["guid"], i["key"])
                if i.get("_tmdb") and i["type"] in ("movie", "show"):
                    out.setdefault(f"tmdb://{'tv' if i['type'] == 'show' else 'movie'}/{i['_tmdb']}", i["key"])
                if i.get("showGuid") and i.get("showKey"):
                    out.setdefault(i["showGuid"], i["showKey"])
        return out

    async def home(self) -> dict:
        sections = await self.plex.sections()
        deck, recent, hubs = await asyncio.gather(
            self._safe(self.plex.on_deck()),
            asyncio.gather(*(self._safe(self.plex.recently_added(s["key"])) for s in sections)),
            asyncio.gather(*(self._safe(self.plex.section_hubs(s["key"])) for s in sections)),
        )
        await self.ensure_index()
        rows = []
        if deck:
            rows.append(row(CONTINUE, deck, "wide"))
        for s, items in zip(sections, recent):
            if items:
                rows.append(row(f"Nytt i {s['title']}", group_new_episodes(items) if s["type"] == "show" else items))
        for r in self.rec_rows:
            rows.append(row(r["title"], r["items"]))
        for s in sections:
            top = self.top_rows.get(s["key"]) or []
            if len(top) >= 5:
                rows.append(row(f"Topplista IMDb · {s['title']}", top))
        for hub_rows in hubs:
            for r in (hub_rows or [])[:4]:
                rows.append(row(r["title"], r["items"]))
        rows = _unique(rows)
        return {"featured": pick_featured(rows), "rows": rows}

    async def recent_all(self, limit: int = 20) -> list[dict]:
        """Nyligen tillagt i alla filmer och serier, nyast först (Upptäck:s första rad)."""
        sections = [s for s in await self.plex.sections() if s["type"] in ("movie", "show")]
        recent = await asyncio.gather(*(self._safe(self.plex.recently_added(s["key"], 30)) for s in sections))
        items = []
        for s, lst in zip(sections, recent):
            items += group_new_episodes(lst) if s["type"] == "show" else (lst or [])
        items.sort(key=lambda i: i.get("addedAt") or 0, reverse=True)
        return [public(i) for i in items[:limit]]

    async def section(self, key: str) -> dict:
        sections = await self.plex.sections()
        sec = next((s for s in sections if s["key"] == key), None)
        if not sec:
            return {"featured": [], "rows": [], "section": None}
        cached = self._section_cache.get(key)
        if cached and time.monotonic() - cached[0] < 600:
            rows = cached[1]
        else:
            rows = await self._section_rows(sec)
            self._section_cache[key] = (time.monotonic(), rows)
        # Fortsätt titta ändras ofta: alltid färskt.
        deck = [i for i in await self._safe(self.plex.on_deck()) if i.get("section") == key]
        out = ([row(CONTINUE, deck, "wide")] if deck else []) + rows
        return {"featured": pick_featured(out), "rows": out, "section": sec}

    async def _section_rows(self, sec: dict) -> list[dict]:
        key = sec["key"]
        await self.ensure_index()
        all_items = self.items.get(key) or await self._safe(self.plex.all(key))
        ids = {i["key"] for i in all_items}
        types = {"show", "season", "episode"} if sec["type"] == "show" else {sec["type"]}

        def in_section(i: dict) -> bool:
            if i.get("section"):
                return i["section"] == key
            if ids:
                return i["key"] in ids or (i["type"] in ("season", "episode") and i.get("showKey") in ids)
            return i["type"] in types

        rows = []
        recent = await self._safe(self.plex.recently_added(key))
        if recent:
            rows.append(row("Nytt", group_new_episodes(recent) if sec["type"] == "show" else recent))
        for r in self.rec_rows:
            lst = [i for i in r["items"] if in_section(i)]
            if len(lst) >= 4:
                title = "Rekommenderat för dig" if r["title"].startswith("Rekommenderat för dig") else r["title"]
                rows.append(row(title, lst))
        top = self.top_rows.get(key) or []
        if len(top) >= 5:
            rows.append(row("Topplista IMDb", top))
        for r in await self._safe(self.plex.section_hubs(key)):
            lst = [i for i in r["items"] if in_section(i)]
            if len(lst) >= 3:
                rows.append(row(r["title"], lst))
        counts: dict[str, list[dict]] = {}
        for i in all_items:
            for g in i.get("genres") or []:
                counts.setdefault(g, []).append(i)
        for g, lst in sorted(counts.items(), key=lambda kv: -len(kv[1]))[:8]:
            if len(lst) >= 4:
                rows.append(row(g, sorted(lst, key=lambda i: -i.get("addedAt", 0))[:25]))
        return _unique(rows)

    @staticmethod
    async def _safe(coro):
        try:
            return await coro
        except Exception:
            return []


def _unique(rows: list[dict]) -> list[dict]:
    seen, out = set(), []
    for r in rows:
        if r["title"] in seen or not r["items"]:
            continue
        seen.add(r["title"])
        out.append(r)
    return out
