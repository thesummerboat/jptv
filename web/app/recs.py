"""
Rekommendationer, samma modell som på TV:n:
 1. Varje titel blir en vektor av egenskaper (genre, regissör, manus, skådespelare, land, studio,
    årtionde). Ovanliga egenskaper väger mer (TF-IDF).
 2. Smakprofilen = summan av vektorerna för det du sett, viktad efter hur mycket du gillat titeln och
    hur nyligen du såg den (halveringstid ett halvår).
 3. Osedda titlar får poäng efter likhet med profilen, betyg och lite för nyhet.
 4. Listan blandas för variation (MMR), och de bästa dras om varje dag.
"""
from __future__ import annotations

import math
import random
from dataclasses import dataclass

DAY = 86_400_000
TYPE_WEIGHT = {"g": 1.0, "d": 1.6, "w": 1.1, "a": 0.8, "c": 0.4, "s": 0.4, "y": 0.35}


@dataclass(slots=True)
class RecItem:
    id: str
    type: str
    tags: list[str]
    engagement: float
    last_viewed: int
    unseen: bool
    rating: float | None
    votes: int
    added: int


class Model:
    def __init__(self, items: dict[str, RecItem], vectors: dict[str, dict[str, float]], profiles: dict[str, dict[str, float]]):
        self.items = items
        self.vectors = vectors
        self.profiles = profiles


def _normalize(v: dict[str, float]) -> dict[str, float]:
    norm = math.sqrt(sum(x * x for x in v.values()))
    return {k: x / norm for k, x in v.items()} if norm else {}


def cosine(a: dict[str, float], b: dict[str, float]) -> float:
    if not a or not b:
        return 0.0
    small, large = (a, b) if len(a) <= len(b) else (b, a)
    return sum(x * large.get(t, 0.0) for t, x in small.items())


def recency(last: int, now: int) -> float:
    if last <= 0:
        return 0.3
    days = max(0, now - last) / DAY
    return max(0.15, 0.5 ** (days / 180))


def quality(rating: float | None, votes: int) -> float:
    if rating is None:
        return 0.0
    r = min(1.0, max(-0.5, (rating - 5.5) / 3))
    confidence = min(1.0, math.log10(votes + 1) / 5)
    return r * (confidence if votes > 0 else 0.6)


def freshness(added: int, now: int) -> float:
    if added <= 0:
        return 0.0
    days = (now - added) / DAY
    return 1.0 if days < 30 else 0.4 if days < 120 else 0.0


def build(items: list[RecItem], now: int) -> Model:
    df: dict[str, int] = {}
    for it in items:
        for t in set(it.tags):
            df[t] = df.get(t, 0) + 1
    n = max(1, len(items))
    vectors = {}
    for it in items:
        v = {}
        for t in set(it.tags):
            c = df.get(t, 0)
            if c < 2:
                continue
            v[t] = TYPE_WEIGHT.get(t[:1], 0.5) * math.log(1 + n / c)
        vectors[it.id] = _normalize(v)
    profiles = {}
    for typ in sorted({i.type for i in items}) + ["all"]:
        p: dict[str, float] = {}
        for it in items:
            if it.engagement == 0 or (typ != "all" and it.type != typ):
                continue
            w = it.engagement * recency(it.last_viewed, now)
            for t, x in vectors.get(it.id, {}).items():
                p[t] = p.get(t, 0.0) + w * x
        profiles[typ] = _normalize({k: x for k, x in p.items() if x > 0})
    return Model({i.id: i for i in items}, vectors, profiles)


def signal(model: Model, typ: str) -> int:
    return sum(1 for i in model.items.values() if i.type == typ and i.engagement > 0)


def _diversify(model: Model, scored: list[tuple[str, float]], limit: int, lam: float = 0.75) -> list[str]:
    pool = sorted(scored, key=lambda x: -x[1])[: limit * 4]
    chosen: list[str] = []
    while len(chosen) < limit and pool:
        best_i, best = 0, -math.inf
        for i, (cid, score) in enumerate(pool):
            vec = model.vectors.get(cid, {})
            max_sim = max((cosine(vec, model.vectors.get(c, {})) for c in chosen), default=0.0)
            value = lam * score - (1 - lam) * max_sim
            if value > best:
                best, best_i = value, i
        chosen.append(pool.pop(best_i)[0])
    return chosen


def for_you(model: Model, typ: str, now: int, limit: int = 20) -> list[str]:
    own, everything = model.profiles.get(typ, {}), model.profiles.get("all", {})
    if not own and not everything:
        return []
    scored = []
    for c in model.items.values():
        if c.type != typ or not c.unseen:
            continue
        vec = model.vectors.get(c.id)
        if not vec:
            continue
        like = 0.8 * cosine(own, vec) + 0.2 * cosine(everything, vec)
        if like <= 0.02:
            continue
        scored.append((c.id, 0.7 * like + 0.22 * quality(c.rating, c.votes) + 0.08 * freshness(c.added, now)))
    return _diversify(model, scored, limit)


def similar_to(model: Model, seed: str, limit: int = 15) -> list[str]:
    s = model.items.get(seed)
    sv = model.vectors.get(seed)
    if not s or not sv:
        return []
    scored = []
    for c in model.items.values():
        if c.type != s.type or not c.unseen or c.id == seed:
            continue
        sim = cosine(sv, model.vectors.get(c.id, {}))
        if sim > 0.05:
            scored.append((c.id, 0.8 * sim + 0.2 * quality(c.rating, c.votes)))
    return _diversify(model, scored, limit, lam=0.85)


def seeds(model: Model, now: int, count: int = 2) -> list[str]:
    liked = sorted(
        (i for i in model.items.values() if i.engagement >= 0.8 and i.last_viewed > 0 and now - i.last_viewed < 90 * DAY),
        key=lambda i: -i.last_viewed,
    )
    picked: list[RecItem] = []
    for it in liked:
        if len(picked) >= count:
            break
        new_type = all(p.type != it.type for p in picked)
        others_left = any(all(p.type != l.type for p in picked) and l not in picked for l in liked)
        if new_type or not others_left:
            if len(similar_to(model, it.id, 5)) >= 5:
                picked.append(it)
    return [p.id for p in picked]


def daily_mix(ids: list[str], day: int, pool: int = 40, take: int = 25) -> list[str]:
    """Nya förslag varje dag: de bästa dras om viktat mot toppen, samma ordning hela dagen."""
    remaining = list(enumerate(ids[:pool]))
    rnd = random.Random(day * 7919 + len(remaining))
    out = []
    while len(out) < take and remaining:
        weights = [1.0 / (i + 3) for i, _ in remaining]
        r = rnd.random() * sum(weights)
        pick = len(remaining) - 1
        for j, w in enumerate(weights):
            r -= w
            if r <= 0:
                pick = j
                break
        out.append(remaining.pop(pick)[1])
    return out


def rec_items(library: list[dict], history: list[dict], now: int) -> list[RecItem]:
    """Bibliotekets titlar (slim-format) och tittarhistoriken till det modellen räknar på."""
    plays: dict[str, int] = {}
    last_seen: dict[str, int] = {}
    episodes: dict[str, set[str]] = {}
    for h in history:
        if h["type"] == "episode":
            key = h.get("show")
            if not key:
                continue
            episodes.setdefault(key, set()).add(h["key"])
        else:
            key = h["key"]
            plays[key] = plays.get(key, 0) + 1
        last_seen[key] = max(last_seen.get(key, 0), h.get("at", 0))
    out = []
    seen_ids = set()
    for it in library:
        ident = it.get("imdb") or f"{it['type']}|{it['title']}|{it.get('year')}"
        if ident in seen_ids:
            continue
        seen_ids.add(ident)
        key = it["key"]
        last = max(it.get("lastViewedAt", 0) * 1000, last_seen.get(key, 0))
        if it["type"] == "show":
            n = max(len(episodes.get(key, ())), it.get("viewedLeafs") or 0)
            unseen = n == 0
            if n >= 3:
                eng = 1 + min(0.6, n / 30)
            elif n >= 1:
                eng = -0.3 if now - last > 30 * DAY else 0.3
            else:
                eng = 0.0
        else:
            p = max(plays.get(key, 0), it.get("viewCount", 0))
            off, dur = it.get("offset", 0), it.get("duration", 0)
            unseen = p == 0 and off == 0
            if p > 0:
                eng = 1 + 0.2 * min(3, max(0, p - 1))
            elif off > 0:
                frac = off / dur if dur else 0
                eng = 0.6 if frac >= 0.2 else (-0.4 if now - last > 14 * DAY else 0.2)
            else:
                eng = 0.0
        imdb = next((r["value"] for r in it.get("ratings", []) if r["src"] == "IMDb"), None)
        audience = next((r["value"] for r in it.get("ratings", []) if r["src"] in ("TMDB", "RT publik")), None)
        rating = imdb if imdb is not None else (audience if audience is not None and audience <= 10 else None)
        out.append(RecItem(key, it["type"], it.get("_tags", []), eng, last, unseen, rating, 0, it.get("addedAt", 0) * 1000))
    return out
