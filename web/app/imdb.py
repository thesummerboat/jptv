"""
IMDb-betyg för allt i Plex, från IMDb:s öppna datafil (fri för privat, icke-kommersiellt bruk), som
på TV:n. Plex ger ofta bara Rotten Tomatoes; IMDb-id:t finns ändå i Plex, så betyget slås upp här.
Filen hämtas en gång i veckan och hålls som två kompakta tal-listor (~13 MB).
"""
from __future__ import annotations

import array
import asyncio
import gzip
import io
import os
import struct
import time
from bisect import bisect_left

import aiohttp

URL = "https://datasets.imdbws.com/title.ratings.tsv.gz"
MAX_AGE = 7 * 86400


def parse_tsv(lines) -> tuple[array.array, array.array]:
    """title.ratings.tsv (tconst, averageRating, numVotes) till sorterade id och betyg×10<<24 | röster."""
    ids = array.array("I")
    vals = array.array("I")
    for line in lines:
        if not line.startswith("tt"):
            continue
        parts = line.rstrip("\n").split("\t")
        if len(parts) < 3:
            continue
        try:
            tid = int(parts[0][2:])
            rating = round(float(parts[1]) * 10)
            votes = int(parts[2] or 0)
        except ValueError:
            continue
        ids.append(tid)
        vals.append((max(0, min(rating, 100)) << 24) | max(0, min(votes, 0xFFFFFF)))
    if any(ids[i] > ids[i + 1] for i in range(len(ids) - 1)):
        pairs = sorted(zip(ids, vals))
        ids = array.array("I", (p[0] for p in pairs))
        vals = array.array("I", (p[1] for p in pairs))
    return ids, vals


class ImdbRatings:
    def __init__(self) -> None:
        self.ids = array.array("I")
        self.vals = array.array("I")
        self.path: str | None = None
        self.updated_at = 0.0
        self.error: str | None = None
        self.loading = False

    @property
    def count(self) -> int:
        return len(self.ids)

    def status(self) -> dict:
        return {"count": self.count, "updatedAt": int(self.updated_at * 1000), "loading": self.loading, "error": self.error}

    def lookup(self, imdb_id: str | None) -> tuple[float, int] | None:
        """Betyg 0–10 och antal röster, eller None om titeln saknas."""
        if not imdb_id or not self.ids or not imdb_id.startswith("tt"):
            return None
        try:
            tid = int(imdb_id[2:])
        except ValueError:
            return None
        i = bisect_left(self.ids, tid)
        if i < len(self.ids) and self.ids[i] == tid:
            v = self.vals[i]
            return (v >> 24) / 10, v & 0xFFFFFF
        return None

    def set(self, ids: array.array, vals: array.array) -> None:
        self.ids, self.vals = ids, vals

    # ------------------------------------------------------------ fil på disk

    def load(self, path: str) -> bool:
        self.path = path
        try:
            with open(path, "rb") as f:
                (n,) = struct.unpack("<I", f.read(4))
                ids, vals = array.array("I"), array.array("I")
                ids.frombytes(f.read(4 * n))
                vals.frombytes(f.read(4 * n))
            if len(ids) != n or len(vals) != n:
                return False
            self.set(ids, vals)
            self.updated_at = os.path.getmtime(path)
            return True
        except (OSError, struct.error):
            return False

    def save(self, path: str) -> None:
        tmp = path + ".tmp"
        with open(tmp, "wb") as f:
            f.write(struct.pack("<I", len(self.ids)))
            f.write(self.ids.tobytes())
            f.write(self.vals.tobytes())
        os.replace(tmp, path)
        self.updated_at = time.time()

    async def refresh(self, http: aiohttp.ClientSession) -> int:
        """Hämtar senaste betygen från IMDb (ca 8 MB). Returnerar antal titlar."""
        assert self.path
        self.loading = True
        self.error = None
        try:
            gz = self.path + ".download"
            async with http.get(URL, headers={"User-Agent": "JPTV"}, timeout=aiohttp.ClientTimeout(total=600, sock_read=60)) as r:
                if r.status >= 400:
                    raise RuntimeError(f"IMDb svarade HTTP {r.status}")
                with open(gz, "wb") as f:
                    async for chunk in r.content.iter_chunked(1 << 16):
                        f.write(chunk)

            def parse():
                with gzip.open(gz, "rt", encoding="utf-8", errors="replace") as f:
                    return parse_tsv(f)

            ids, vals = await asyncio.to_thread(parse)
            os.remove(gz)
            if len(ids) < 1000:
                raise RuntimeError("Betygsfilen från IMDb verkar trasig")
            self.set(ids, vals)
            await asyncio.to_thread(self.save, self.path)
            return len(ids)
        except Exception as e:
            self.error = str(e) or e.__class__.__name__
            raise
        finally:
            self.loading = False

    async def keep_fresh(self, http: aiohttp.ClientSession, on_change) -> None:
        """Vid start och sedan dagligen: hämta nytt om filen saknas eller är äldre än en vecka."""
        while True:
            if not self.count or time.time() - self.updated_at > MAX_AGE:
                try:
                    await self.refresh(http)
                    on_change()
                except Exception:
                    pass
            await asyncio.sleep(86400 if self.count else 3600)


RATINGS = ImdbRatings()


def enrich(item: dict) -> None:
    """Lägger IMDb-betyget först bland betygen (ersätter Plex eget IMDb-värde) och sparar antalet röster."""
    hit = RATINGS.lookup(item.get("imdb"))
    if not hit:
        return
    rating, votes = hit
    if rating <= 0:
        return
    text = f"IMDb {rating:.1f}".replace(".", ",")
    item["ratings"] = [{"src": "IMDb", "text": text, "value": rating}] + [r for r in item.get("ratings", []) if r["src"] != "IMDb"]
    item["_votes"] = votes


def gz_bytes(text: str) -> bytes:
    """Hjälp för tester: en liten betygsfil som gzip."""
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode="wb") as g:
        g.write(text.encode())
    return buf.getvalue()
