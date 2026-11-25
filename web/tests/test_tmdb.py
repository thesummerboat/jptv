"""Upptäck (TMDB): rader som i Seerr, titel- och personsidor, sök, bilder, och önska till Radarr/Sonarr."""
import json
import os
import shutil
import tempfile
import unittest

from aiohttp.test_utils import TestClient, TestServer

from app import imdb
from app import main as jptv
from app.plex import Plex
from app.tmdb import pick_trailer
from tests import fake_plex, fake_tmdb
from tests.test_wishes import fake_arr


class TmdbTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        if not fake_plex.items:
            fake_plex.build()
        self.tmp = tempfile.mkdtemp()
        self.plex = TestServer(fake_plex.make_app(self.tmp, 0))
        await self.plex.start_server()
        base = f"http://127.0.0.1:{self.plex.port}"
        self.saved = (Plex.DISCOVER, Plex.METADATA)
        Plex.DISCOVER, Plex.METADATA = base + "/discover", base + "/meta"
        self.log = []
        self.tmdb = TestServer(fake_tmdb.make_app(self.log))
        await self.tmdb.start_server()
        tbase = f"http://127.0.0.1:{self.tmdb.port}"
        self.omdb_log = []
        self.omdb = TestServer(fake_tmdb.make_omdb(self.omdb_log))
        await self.omdb.start_server()
        self.env = {"JPTV_TMDB_API": tbase + "/3", "JPTV_TMDB_IMG": tbase + "/t/p",
                    "JPTV_OMDB_API": f"http://127.0.0.1:{self.omdb.port}/"}
        self.saved_env = {k: os.environ.get(k) for k in self.env}
        os.environ.update(self.env)
        self.radarr_log, self.sonarr_log = [], []
        self.radarr = TestServer(fake_arr("radarr", self.radarr_log))
        self.sonarr = TestServer(fake_arr("sonarr", self.sonarr_log))
        await self.radarr.start_server()
        await self.sonarr.start_server()
        cfg = os.path.join(self.tmp, "config")
        os.makedirs(cfg)
        with open(os.path.join(cfg, "settings.json"), "w") as f:
            json.dump({"server": {"name": "Test", "uri": base, "token": "tok"}, "account_token": "acct"}, f)
        self.app = jptv.create_app(cfg)
        self.client = TestClient(TestServer(self.app))
        await self.client.start_server()
        # Plex-nycklarna för titlarna som finns (TMDB 500 = första filmen, 900 = första serien).
        self.movie_key = fake_plex.order[0]
        self.show_key = next(k for k, v in fake_plex.items.items() if v["type"] == "show")

    async def asyncTearDown(self):
        Plex.DISCOVER, Plex.METADATA = self.saved
        await self.client.close()
        for s in (self.plex, self.tmdb, self.omdb, self.radarr, self.sonarr):
            await s.close()
        for k, v in self.saved_env.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v
        shutil.rmtree(self.tmp, ignore_errors=True)

    async def get(self, path, status=200):
        r = await self.client.get(path)
        data = await r.json()
        self.assertEqual(r.status, status, data)
        return data

    async def post(self, path, body=None, status=200):
        r = await self.client.post(path, data=json.dumps(body or {}))
        data = await r.json()
        self.assertEqual(r.status, status, data)
        return data

    async def key(self, value="nyckel"):
        await self.post("/api/settings", {"tmdbKey": value})

    async def test_needs_key(self):
        self.assertFalse((await self.get("/api/status"))["tmdb"])
        err = await self.get("/api/tmdb/home", status=400)
        self.assertIn("TMDB-nyckel", err["error"])
        await self.key("fel")
        err = await self.post("/api/tmdb/test", status=502)
        self.assertIn("godkände inte", err["error"])
        await self.key()
        self.assertTrue((await self.get("/api/status"))["tmdb"])
        self.assertTrue((await self.get("/api/status"))["settings"]["tmdbKey"])
        self.assertIn("fungerar", (await self.post("/api/tmdb/test"))["ok"])
        # Läs-token (v4) skickas som Bearer.
        await self.key(fake_tmdb.V4)
        self.assertIn("fungerar", (await self.post("/api/tmdb/test"))["ok"])
        await self.key("-")
        self.assertFalse((await self.get("/api/status"))["tmdb"])

    async def test_home(self):
        await self.key()
        home = await self.get("/api/tmdb/home")
        rows = {r["id"]: r for r in home["rows"]}
        self.assertEqual([r["id"] for r in home["rows"]], [
            "trending", "popular-movies", "movie-genres", "upcoming-movies", "studios",
            "popular-series", "series-genres", "upcoming-series", "networks"])
        trend = rows["trending"]["items"]
        # Personer och vuxenfilm visas inte; titlar som finns i Plex känns igen.
        self.assertEqual([(t["type"], t["tmdb"]) for t in trend], [("movie", 500), ("movie", 601), ("show", 900), ("show", 950)])
        self.assertEqual(trend[0]["libraryKey"], self.movie_key)
        self.assertEqual(trend[2]["libraryKey"], self.show_key)
        self.assertIsNone(trend[1]["libraryKey"])
        self.assertEqual((trend[1]["poster"], trend[1]["year"], trend[1]["rating"]), ("/tmdb/img/w342/p601.jpg", 2026, 7.8))
        # Genrer med bild ur en populär titel (den första som har en).
        genres = {g["name"]: g for g in rows["movie-genres"]["items"]}
        self.assertEqual(genres["Action"]["backdrop"], "/tmdb/img/w780/b728.jpg")
        self.assertEqual(genres["Komedi"]["backdrop"], "/tmdb/img/w780/b835.jpg")
        self.assertEqual(rows["studios"]["items"][0], {"id": 2, "name": "Company 2", "logo": "/tmdb/img/w300/logo2.png"})
        self.assertEqual(rows["networks"]["items"][0]["logo"], "/tmdb/img/w300/logo213.png")
        self.assertEqual(rows["upcoming-movies"]["items"][0]["title"], "Kommer snart")
        # Nyligen tillagt i Plex först på sidan.
        self.assertTrue(home["recent"])
        # Logotyperna sparas: andra gången frågas TMDB inte om dem.
        asked = sum(1 for p, _ in self.log if p.startswith("/3/company/"))
        self.app[jptv.JPTV]["tmdb"]._cache.clear()
        await self.get("/api/tmdb/home")
        self.assertEqual(sum(1 for p, _ in self.log if p.startswith("/3/company/")), asked)
        # Svenska och Sverige.
        popular = next(q for p, q in self.log if p == "/3/movie/popular")
        self.assertEqual((popular["language"], popular["region"]), ("sv-SE", "SE"))

    async def test_lists(self):
        await self.key()
        lst = await self.get("/api/tmdb/list/genre-movie?id=28&page=2")
        self.assertEqual((lst["page"], lst["pages"]), (2, 3))
        self.assertEqual(lst["items"][0]["title"], "Genrefilm 28")
        studio = await self.get("/api/tmdb/list/studio?id=2")
        self.assertEqual(studio["items"][0]["title"], "Studiofilm 2")
        net = await self.get("/api/tmdb/list/network?id=213")
        self.assertEqual(net["items"][0]["type"], "show")
        await self.get("/api/tmdb/list/finns-inte", status=404)
        await self.get("/api/tmdb/list/genre-tv", status=400)

    async def test_title_pages(self):
        await self.key()
        t = await self.get("/api/tmdb/title/movie/601")
        self.assertEqual((t["title"], t["year"], t["runtime"], t["certification"]), ("Ny film", 2026, 128, "15"))
        self.assertEqual(t["trailer"], {"youtube": "trailerSVxx", "name": "Trailer"})
        self.assertEqual(t["providers"], [
            {"name": "Netflix", "logo": "/tmdb/img/w92/netflix.png", "kind": "Streaming"},
            {"name": "Apple TV", "logo": "/tmdb/img/w92/apple.png", "kind": "Hyra"}])
        self.assertEqual(t["makers"], ["Anna Lund"])
        self.assertEqual(t["cast"][0], {"id": 77, "name": "Lisa Ek", "role": "Maja", "photo": "/tmdb/img/w185/lisa.jpg"})
        self.assertEqual(t["ids"], {"tmdb": 601, "imdb": "tt601"})
        self.assertIsNone(t["libraryKey"])
        self.assertEqual(t["recommendations"][0]["libraryKey"], self.movie_key)
        # Ingen svensk beskrivning: den engelska.
        self.assertEqual((await self.get("/api/tmdb/title/movie/602"))["overview"], "English overview.")
        s = await self.get("/api/tmdb/title/show/950")
        self.assertEqual((s["type"], s["seasons"], s["certification"], s["networks"]), ("show", 2, "11", ["SVT1"]))
        self.assertEqual(s["ids"]["tvdb"], 373737)
        p = await self.get("/api/tmdb/person/77")
        self.assertEqual((p["name"], p["place"]), ("Lisa Ek", "Umeå"))
        # Flest röster först; samma titel bara en gång.
        self.assertEqual([i["tmdb"] for i in p["items"]], [500, 950, 601])
        self.assertEqual(p["items"][0]["libraryKey"], self.movie_key)
        res = await self.get("/api/tmdb/search?q=fj%C3%A4ll")
        self.assertEqual([i["tmdb"] for i in res["items"]], [601, 950])
        self.assertEqual(res["people"][0]["name"], "Lisa Ek")

    async def test_opinions(self):
        await self.key()
        # IMDb-betyget från IMDb:s datafil (finns redan); tt601 finns där, tt9500 och tt602 inte.
        saved = (imdb.RATINGS.ids, imdb.RATINGS.vals)
        self.addCleanup(imdb.RATINGS.set, *saved)
        imdb.RATINGS.set(*imdb.parse_tsv(["tconst\taverageRating\tnumVotes\n", "tt0000601\t8.2\t5000\n"]))
        # Utan OMDb-nyckel: TMDB och IMDb, och recensionerna nyaste först, utan HTML och markdown.
        o = await self.get("/api/tmdb/opinions/movie/601")
        self.assertFalse(o["omdb"])
        self.assertEqual(o["scores"], {"tmdb": 7.8, "tmdbVotes": 1200, "imdb": 8.2, "imdbVotes": 5000,
                                       "imdbUrl": "https://www.imdb.com/title/tt601/"})
        self.assertEqual(o["reviewCount"], 23)
        self.assertEqual([r["author"] for r in o["reviews"]], ["Kritikern", "tyst", "oldfan"])
        first = o["reviews"][0]
        self.assertEqual((first["rating"], first["date"], first["text"]), (9.0, "2026-03-05", "Mästerverk. Se den på bio."))
        self.assertEqual(first["url"], "https://www.themoviedb.org/review/a2")
        self.assertIsNone(o["reviews"][1]["rating"])
        self.assertEqual(o["reviews"][1]["text"], "Inget betyg här.")
        self.assertEqual(o["reviews"][2]["text"], "Helt okej.\n\nMen för lång.")
        self.assertEqual(self.omdb_log, [])
        self.assertTrue(any(p == "/3/movie/601/reviews" and q.get("language") == "en-US" for p, q in self.log))
        # Inga recensioner alls.
        o = await self.get("/api/tmdb/opinions/movie/602")
        self.assertEqual((o["reviews"], o["reviewCount"]), ([], 0))
        self.assertEqual((await self.client.get("/api/tmdb/opinions/person/77")).status, 404)

        self.assertIn("Ingen OMDb-nyckel", (await self.post("/api/omdb/test", status=502))["error"])
        # Fel OMDb-nyckel: testet säger till, och sidan visar ändå TMDB.
        await self.post("/api/settings", {"omdbKey": "fel"})
        self.assertTrue((await self.get("/api/status"))["settings"]["omdbKey"])
        err = await self.post("/api/omdb/test", status=502)
        self.assertIn("godkände inte", err["error"])
        o = await self.get("/api/tmdb/opinions/movie/601")
        self.assertIn("godkände inte", o["scoresError"])
        self.assertEqual((o["scores"]["tmdb"], o["scores"]["imdb"]), (7.8, 8.2))
        self.assertEqual(len(o["reviews"]), 3)

        # Rätt nyckel: Rotten Tomatoes och Metacritic till; IMDb från datafilen står kvar. Samma titel slås bara upp en gång.
        await self.post("/api/settings", {"omdbKey": "omdbnyckel"})
        self.assertIn("Rotten Tomatoes 88 %, Metacritic 71", (await self.post("/api/omdb/test"))["ok"])
        self.omdb_log.clear()
        for _ in range(2):
            o = await self.get("/api/tmdb/opinions/movie/601")
        self.assertTrue(o["omdb"])
        self.assertIsNone(o["scoresError"])
        self.assertEqual(o["scores"], {"tmdb": 7.8, "tmdbVotes": 1200, "imdb": 8.2, "imdbVotes": 5000, "rt": 88,
                                       "metacritic": 71, "imdbUrl": "https://www.imdb.com/title/tt601/"})
        self.assertEqual([q["i"] for q in self.omdb_log], ["tt601"])
        # Serie som datafilen saknar: IMDb från OMDb, inga kritiker. Okänd titel hos OMDb: bara TMDB, inget fel.
        s = (await self.get("/api/tmdb/opinions/show/950"))["scores"]
        self.assertEqual((s["imdb"], s["imdbVotes"], s.get("rt"), s.get("metacritic")), (8.4, 12345, None, None))
        o = await self.get("/api/tmdb/opinions/movie/602")
        self.assertIsNone(o["scoresError"])
        self.assertEqual(o["scores"], {"tmdb": 7.8, "tmdbVotes": 1200, "imdbUrl": "https://www.imdb.com/title/tt602/"})

        # Dagens gräns nådd.
        await self.post("/api/settings", {"omdbKey": "slut"})
        o = await self.get("/api/tmdb/opinions/movie/603")
        self.assertIn("1000 uppslag", o["scoresError"])
        # Ta bort nyckeln.
        await self.post("/api/settings", {"omdbKey": "-"})
        self.assertFalse((await self.get("/api/status"))["settings"]["omdbKey"])
        self.assertFalse((await self.get("/api/tmdb/opinions/movie/601"))["omdb"])

    async def test_wish_from_tmdb_to_radarr_and_sonarr(self):
        await self.key()
        await self.post("/api/settings", {
            "radarrUrl": f"http://127.0.0.1:{self.radarr.port}", "radarrKey": "nyckel",
            "sonarrUrl": f"http://127.0.0.1:{self.sonarr.port}", "sonarrKey": "nyckel"})
        m = await self.post("/api/requests", {"tmdb": 601, "type": "movie", "title": "Ny film", "year": 2026, "by": "Jonas",
                                              "thumb": "https://image.tmdb.org/t/p/w500/p601.jpg"})
        self.assertEqual((m["request"]["guid"], m["request"]["ids"]), ("tmdb://movie/601", {"tmdb": 601, "imdb": "tt601"}))
        s = await self.post("/api/requests", {"tmdb": 950, "type": "show", "title": "Fjällstationen", "by": "Jonas"})
        self.assertEqual(s["request"]["ids"]["tvdb"], 373737)
        # Önskad syns på titeln och i listorna.
        t = await self.get("/api/tmdb/title/movie/601")
        self.assertEqual(t["request"]["status"], "pending")
        # Finns redan i Plex: kan inte önskas.
        r = await self.post("/api/requests", {"tmdb": 500, "type": "movie", "title": "Filmen som finns", "by": "Jonas"}, status=409)
        self.assertEqual(r["libraryKey"], self.movie_key)
        # OK: Radarr får TMDB-id, Sonarr TVDB-id (ingen fråga till Plex katalog).
        await self.post(f"/api/requests/{m['request']['id']}/approve")
        await self.post(f"/api/requests/{s['request']['id']}/approve")
        self.assertEqual(self.radarr_log[-1]["tmdbId"], 601)
        self.assertEqual(self.sonarr_log[-1]["tvdbId"], 373737)

    async def test_wish_marked_available(self):
        """Ett önskemål från Upptäck blir "Finns nu" när titeln dyker upp i Plex (samma TMDB-id)."""
        await self.key()
        wishes = self.app[jptv.JPTV]["wishes"]
        w, _ = wishes.add({"guid": "tmdb://movie/502", "key": "502", "tmdb": 502, "type": "movie", "title": "Tredje filmen"}, "Jonas")
        wishes.update(w["id"], status="approved")
        data = await self.get("/api/requests")
        got = next(i for i in data["items"] if i["id"] == w["id"])
        self.assertEqual(got["status"], "available")
        self.assertEqual(got["libraryKey"], fake_plex.order[2])

    async def test_images(self):
        r = await self.client.get("/tmdb/img/w342/p601.jpg")
        self.assertEqual((r.status, await r.read()), (200, b"\xff\xd8\xff\xe0bild"))
        self.assertIn("max-age", r.headers.get("Cache-Control", ""))
        for bad in ("/tmdb/img/w342/..%2Fx.jpg", "/tmdb/img/huge/p.jpg", "/tmdb/img/w342/p.exe"):
            r = await self.client.get(bad)
            self.assertEqual(r.status, 404, bad)

    def test_pick_trailer(self):
        self.assertIsNone(pick_trailer([]))
        self.assertEqual(pick_trailer([{"site": "YouTube", "key": "a", "type": "Teaser", "official": True, "iso_639_1": "sv"},
                                       {"site": "YouTube", "key": "b", "type": "Trailer", "official": False, "iso_639_1": "en"}])["youtube"], "b")


if __name__ == "__main__":
    unittest.main()
