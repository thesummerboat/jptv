"""Hela servern mot låtsas-Plex: hemskärm, bibliotek, detaljer, uppspelning genom proxyn och live-listor."""
import json
import os
import shutil
import tempfile
import unittest

from aiohttp.test_utils import TestClient, TestServer

from app import main as jptv
from tests import fake_plex


class ApiTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        if not fake_plex.items:
            fake_plex.build()
        self.tmp = tempfile.mkdtemp()
        self.plex = TestServer(fake_plex.make_app(self.tmp, 0))
        await self.plex.start_server()
        base = f"http://127.0.0.1:{self.plex.port}"
        # Spellistan pekar på låtsas-servern; porten bestäms först nu.
        fake_app = self.plex.app
        cfg = os.path.join(self.tmp, "config")
        os.makedirs(cfg)
        with open(os.path.join(cfg, "settings.json"), "w") as f:
            json.dump({"server": {"name": "Test", "uri": base, "token": "tok"}, "m3u_url": base + "/iptv.m3u"}, f)
        self.app = jptv.create_app(cfg)
        self.client = TestClient(TestServer(self.app))
        await self.client.start_server()
        _ = fake_app

    async def asyncTearDown(self):
        await self.client.close()
        await self.plex.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    async def get(self, path):
        r = await self.client.get(path)
        self.assertEqual(r.status, 200, await r.text())
        return await r.json()

    async def test_status_and_index(self):
        st = await self.get("/api/status")
        self.assertTrue(st["plex"])
        self.assertEqual(st["server"]["name"], "Test")
        r = await self.client.get("/")
        html = await r.text()
        self.assertIn("core.js?v=", html)
        self.assertNotIn("__V__", html)

    async def test_home(self):
        home = await self.get("/api/home")
        titles = [r["title"] for r in home["rows"]]
        self.assertEqual(titles[0], "Fortsätt titta")
        self.assertIn("Nytt i Filmer", titles)
        self.assertIn("Nytt i TV-serier", titles)
        self.assertTrue(any(t.startswith("Rekommenderat för dig") for t in titles))
        self.assertTrue(any(t.startswith("Topplista IMDb") for t in titles))
        self.assertEqual(len(titles), len(set(titles)))
        self.assertTrue(1 <= len(home["featured"]) <= 5)
        # Nytt i serier: ett kort per serie.
        shows = next(r for r in home["rows"] if r["title"] == "Nytt i TV-serier")
        self.assertTrue(all(i["type"] == "show" for i in shows["items"]))
        self.assertTrue(any("nya avsnitt" in (i.get("label") or "") for i in shows["items"]))
        # Inga interna fält till webbsidan.
        self.assertFalse(any(k.startswith("_") for r in home["rows"] for i in r["items"] for k in i))

    async def test_section_rows_only_own_items(self):
        data = await self.get("/api/section/2/rows")
        for r in data["rows"]:
            for i in r["items"]:
                self.assertIn(i["type"], ("show", "season", "episode"), r["title"])
        movies = await self.get("/api/section/1/rows")
        for r in movies["rows"]:
            for i in r["items"]:
                self.assertEqual(i["type"], "movie", r["title"])

    async def test_items_filters_and_details(self):
        items = (await self.get("/api/section/1/items?sort=title"))["items"]
        self.assertEqual([i["title"] for i in items], sorted(i["title"] for i in items))
        imdb = (await self.get("/api/section/1/items?sort=imdb"))["items"]
        values = [next(r["value"] for r in i["ratings"] if r["src"] == "IMDb") for i in imdb]
        self.assertEqual(values, sorted(values, reverse=True))
        f = await self.get("/api/section/1/filters")
        self.assertIn("Drama", [g["title"] for g in f["genres"]])
        drama = (await self.get("/api/section/1/items?genre=Drama"))["items"]
        self.assertTrue(drama and all("Drama" in i["genres"] for i in drama))

        movie = await self.get("/api/item/" + items[0]["key"])
        self.assertTrue(movie["logo"])
        self.assertEqual(len(movie["audio"]), 2)
        self.assertEqual(len(movie["subtitles"]), 2)
        self.assertIn("IMDb", [r["src"] for r in movie["ratings"]])

        show = await self.get("/api/search?q=Skärgård")
        show_key = show["hubs"][-1]["items"][0]["key"]
        s = await self.get("/api/item/" + show_key)
        self.assertEqual(s["type"], "show")
        self.assertEqual(len(s["seasons"]), 3)
        self.assertEqual(s["onDeck"]["season"], 2)
        eps = await self.get("/api/children/" + s["seasons"][1]["key"])
        self.assertEqual([e["episode"] for e in eps], [1, 2, 3, 4, 5, 6])
        nxt = await self.get("/api/next/" + eps[0]["key"])
        self.assertEqual(nxt["key"], eps[1]["key"])

    async def test_play_through_proxy(self):
        eps = await self.get("/api/children/1026")
        r = await self.client.post("/api/play/" + eps[0]["key"], data="{}")
        play = await r.json()
        self.assertTrue(play["url"].startswith("/plex/video/:/transcode/universal/start.m3u8?"))
        self.assertIn("X-Plex-Platform=Chrome", play["url"])
        self.assertNotIn("X-Plex-Token", play["url"])
        self.assertEqual(play["next"]["episode"], 2)
        self.assertTrue(any(m["type"] == "intro" for m in play["item"]["markers"]))
        master = await self.client.get(play["url"])
        text = await master.text()
        self.assertEqual(master.status, 200)
        self.assertIn("session/", text)
        # Proxyn släpper bara igenom Plex-vägar för video och bilder.
        bad = await self.client.get("/plex/accounts")
        self.assertEqual(bad.status, 404)
        stop = await self.client.post("/api/stop", data=json.dumps({"key": eps[0]["key"], "time": 1000, "duration": 70000, "session": play["session"]}))
        self.assertEqual(stop.status, 200)

    async def test_live_lists(self):
        # Spellistan hämtas i bakgrunden vid start.
        iptv = self.app[jptv.JPTV]["iptv"]
        await iptv.refresh()
        groups = await self.get("/api/live/groups")
        self.assertEqual([g["name"] for g in groups["groups"]], ["Sverige", "Sport", "Nyheter", "Film"])
        chans = await self.get("/api/live/channels?group=Sverige")
        self.assertEqual(len(chans), 8)
        self.assertTrue(all(c["now"] for c in chans))
        guide = await self.get("/api/live/guide?group=Sport")
        self.assertTrue(guide["channels"][0]["programmes"])
        fav = await (await self.client.post("/api/live/fav/" + chans[0]["id"])).json()
        self.assertTrue(fav["fav"])
        favs = await self.get("/api/live/channels?fav=1")
        self.assertEqual([c["id"] for c in favs], [chans[0]["id"]])
        found = await self.get("/api/search?q=svt")
        self.assertEqual(len(found["channels"]), 2)
        # Dolda grupper syns inte i sök, men gruppen går att öppna och favoriter finns kvar.
        r = await self.client.post("/api/live/hidden", data=json.dumps({"groups": ["Sverige", "Film"]}))
        self.assertEqual((await r.json())["hidden"], ["Film", "Sverige"])
        groups = await self.get("/api/live/groups")
        self.assertEqual(groups["hidden"], ["Film", "Sverige"])
        self.assertEqual((await self.get("/api/search?q=svt"))["channels"], [])
        self.assertEqual(await self.get("/api/live/channels?q=svt"), [])
        self.assertEqual(len(await self.get("/api/live/channels?group=Sverige")), 8)
        self.assertEqual(len(await self.get("/api/live/channels?fav=1")), 1)


if __name__ == "__main__":
    unittest.main()


class ElsewhereTest(unittest.IsolatedAsyncioTestCase):
    """Pausad spelare: har man tittat vidare på en annan enhet sedan vi senast rapporterade?"""

    asyncSetUp = ApiTest.asyncSetUp
    asyncTearDown = ApiTest.asyncTearDown
    get = ApiTest.get

    def episode_with_offset(self):
        return next(k for k, v in fake_plex.items.items() if v["type"] == "episode" and v.get("viewOffset"))

    async def test_unchanged(self):
        ek = self.episode_with_offset()
        res = await self.get(f"/api/elsewhere/{ek}?pos=30000&since={(fake_plex.NOW + 60) * 1000}")
        self.assertFalse(res["moved"])

    async def test_position_moved_elsewhere(self):
        ek = self.episode_with_offset()
        old = fake_plex.items[ek]["viewOffset"]
        fake_plex.items[ek]["viewOffset"] = 65000
        try:
            res = await self.get(f"/api/elsewhere/{ek}?pos=5000&since={(fake_plex.NOW + 60) * 1000}")
        finally:
            fake_plex.items[ek]["viewOffset"] = old
        self.assertTrue(res["moved"])
        # Seriens Fortsätt titta-avsnitt (här samma avsnitt, där den andra enheten kom).
        self.assertEqual(res["target"]["key"], ek)
        self.assertEqual(res["target"]["offset"], 65000)

    async def test_watched_elsewhere_points_to_show_deck(self):
        ek = self.episode_with_offset()
        show = fake_plex.items[ek]["grandparentRatingKey"]
        fake_plex.items[ek]["lastViewedAt"] = fake_plex.NOW + 600
        try:
            res = await self.get(f"/api/elsewhere/{ek}?pos=30000&since={(fake_plex.NOW + 60) * 1000}")
        finally:
            fake_plex.items[ek]["lastViewedAt"] = None
        self.assertTrue(res["moved"])
        self.assertEqual(res["target"]["showKey"], show)

    async def test_movie_moved(self):
        mk = next(k for k in fake_plex.order if fake_plex.items[k]["type"] == "movie" and fake_plex.items[k].get("viewOffset"))
        res = await self.get(f"/api/elsewhere/{mk}?pos=0&since={(fake_plex.NOW + 60) * 1000}")
        self.assertTrue(res["moved"])
        self.assertEqual(res["target"]["key"], mk)

    async def test_stop_without_timeline(self):
        mk = fake_plex.order[0]
        r = await self.client.post("/api/stop", json={"key": mk, "time": 1000, "duration": 5000, "timeline": False})
        self.assertEqual(r.status, 200)
