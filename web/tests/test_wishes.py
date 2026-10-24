"""Önskemål: sök i Plex katalog, önska, OK skickar till Radarr/Sonarr, Finns nu när titeln dyker upp."""
import json
import os
import shutil
import tempfile
import unittest

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from app import main as jptv
from app.plex import Plex
from tests import fake_plex


def fake_arr(kind: str, log: list) -> web.Application:
    """Minsta möjliga Radarr/Sonarr: uppslag, profiler, rotmapp och tillägg."""
    app = web.Application()
    added = set()

    def auth(req):
        if req.headers.get("X-Api-Key") != "nyckel":
            raise web.HTTPUnauthorized()

    async def status(req):
        auth(req)
        return web.json_response({"version": "5.2.0" if kind == "radarr" else "4.0.1"})

    # Som en riktig installation: "Any" först (den tar gärna en remux), och en gammal mapp först.
    async def profiles(req):
        auth(req)
        return web.json_response([
            {"id": 1, "name": "Any"}, {"id": 2, "name": "SD"}, {"id": 3, "name": "HD-720p"},
            {"id": 4, "name": "HD-1080p"}, {"id": 5, "name": "Ultra-HD"}, {"id": 6, "name": "HD - 720p/1080p"},
        ])

    async def roots(req):
        auth(req)
        if kind == "radarr":
            return web.json_response([{"id": 1, "path": "/media/gamla"}, {"id": 2, "path": "/data/Filmer"}])
        return web.json_response([{"id": 1, "path": "/media/gamla/"}, {"id": 2, "path": "/data/Serier/"}])

    async def movie(req):
        auth(req)
        return web.json_response({"id": int(req.match_info["id"]), "title": "Polarexpressen 2", "hasFile": False, "monitored": False})

    async def editor(req):
        auth(req)
        log.append({"editor": await req.json()})
        return web.json_response([])

    async def command(req):
        auth(req)
        log.append({"command": await req.json()})
        return web.json_response({"id": 1}, status=201)

    async def lookup_movie(req):
        auth(req)
        tmdb = int(req.query["tmdbId"])
        out = {"title": "Polarexpressen 2", "tmdbId": tmdb, "year": 2026}
        if tmdb in added:
            out["id"] = 9
        return web.json_response(out)

    async def lookup_series(req):
        auth(req)
        tvdb = int(req.query["term"].split(":")[1])
        return web.json_response([{"title": "Fjällstationen", "tvdbId": tvdb, "year": 2025}])

    async def add(req):
        auth(req)
        body = await req.json()
        log.append(body)
        key = body.get("tmdbId") or body.get("tvdbId")
        if key in added:
            return web.json_response([{"errorMessage": "This movie has already been added"}], status=400)
        added.add(key)
        return web.json_response(body | {"id": 1}, status=201)

    app.router.add_get("/api/v3/system/status", status)
    app.router.add_get("/api/v3/qualityprofile", profiles)
    app.router.add_get("/api/v3/rootfolder", roots)
    app.router.add_get("/api/v3/movie/lookup/tmdb", lookup_movie)
    app.router.add_get("/api/v3/series/lookup", lookup_series)
    app.router.add_post("/api/v3/movie", add)
    app.router.add_post("/api/v3/series", add)
    app.router.add_get("/api/v3/movie/{id}", movie)
    app.router.add_put("/api/v3/movie/editor", editor)
    app.router.add_post("/api/v3/command", command)
    return app


class WishesTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        if not fake_plex.items:
            fake_plex.build()
        self.tmp = tempfile.mkdtemp()
        self.plex = TestServer(fake_plex.make_app(self.tmp, 0))
        await self.plex.start_server()
        base = f"http://127.0.0.1:{self.plex.port}"
        self.saved = (Plex.DISCOVER, Plex.METADATA)
        Plex.DISCOVER, Plex.METADATA = base + "/discover", base + "/meta"
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

    async def asyncTearDown(self):
        Plex.DISCOVER, Plex.METADATA = self.saved
        await self.client.close()
        await self.plex.close()
        await self.radarr.close()
        await self.sonarr.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    async def post(self, path, body=None, status=200):
        r = await self.client.post(path, data=json.dumps(body or {}))
        data = await r.json()
        self.assertEqual(r.status, status, data)
        return data

    async def search(self, q):
        r = await self.client.get("/api/discover?q=" + q)
        self.assertEqual(r.status, 200, await r.text())
        return (await r.json())["results"]

    async def test_flow_without_radarr(self):
        # Finns i Plex: länk dit, kan inte önskas.
        hits = await self.search("Nordljus")
        self.assertEqual(hits[0]["title"], "Nordljus")
        self.assertTrue(hits[0]["libraryKey"])
        await self.post("/api/requests", hits[0], status=409)
        # Saknas: önska, samma titel igen ger samma önskemål.
        film = (await self.search("Polarexpressen"))[0]
        self.assertIsNone(film["libraryKey"])
        w = (await self.post("/api/requests", film | {"by": "Ebba"}))["request"]
        self.assertEqual((w["status"], w["by"]), ("pending", "Ebba"))
        again = await self.post("/api/requests", film | {"by": "Jonas"})
        self.assertFalse(again["new"])
        self.assertEqual(again["request"]["also"], ["Jonas"])
        self.assertEqual((await self.search("Polarexpressen"))[0]["request"]["status"], "pending")
        st = await (await self.client.get("/api/status")).json()
        self.assertEqual(st["requests"], 1)
        # OK utan Radarr: bara godkänd.
        ok = await self.post(f"/api/requests/{w['id']}/approve")
        self.assertEqual(ok["request"]["status"], "approved")
        # Nej och ta bort.
        serie = (await self.search("Fjällstationen"))[0]
        s = (await self.post("/api/requests", serie | {"by": "Ebba"}))["request"]
        no = await self.post(f"/api/requests/{s['id']}/decline")
        self.assertEqual(no["request"]["status"], "declined")
        r = await self.client.delete(f"/api/requests/{s['id']}")
        self.assertTrue((await r.json())["ok"])
        lst = await (await self.client.get("/api/requests")).json()
        self.assertEqual([x["title"] for x in lst["items"]], ["Polarexpressen 2"])

    async def test_ok_sends_to_radarr_and_sonarr(self):
        await self.post("/api/settings", {
            "radarrUrl": f"127.0.0.1:{self.radarr.port}", "radarrKey": "nyckel",
            "sonarrUrl": f"http://127.0.0.1:{self.sonarr.port}/", "sonarrKey": "nyckel",
        })
        st = await (await self.client.get("/api/status")).json()
        self.assertTrue(st["settings"]["radarrKey"])
        self.assertNotIn("nyckel", json.dumps(st))
        # Standard: samma som läggs till för hand på Unraid.
        self.assertEqual((st["settings"]["radarrProfile"], st["settings"]["radarrRoot"]), ("HD-1080p", "/data/Filmer"))
        self.assertEqual((st["settings"]["sonarrProfile"], st["settings"]["sonarrRoot"]), ("HD - 720p/1080p", "/data/Serier"))
        test = (await self.post("/api/arr/test/radarr"))["ok"]
        self.assertIn("svarar", test)
        self.assertIn("HD-1080p", test)
        self.assertIn("/data/Filmer", test)
        opts = await (await self.client.get("/api/arr/options/sonarr")).json()
        self.assertIn("HD - 720p/1080p", opts["profiles"])
        self.assertIn("/data/Serier/", opts["roots"])

        film = (await self.search("Polarexpressen"))[0]
        w = (await self.post("/api/requests", film | {"by": "Ebba"}))["request"]
        ok = await self.post(f"/api/requests/{w['id']}/approve")
        self.assertEqual(ok["request"]["note"], "Skickad till Radarr")
        sent = self.radarr_log[0]
        # Inte den första profilen (Any) eller den första mappen.
        self.assertEqual((sent["tmdbId"], sent["qualityProfileId"], sent["rootFolderPath"]), (424242, 4, "/data/Filmer"))
        self.assertTrue(sent["monitored"])
        self.assertTrue(sent["addOptions"]["searchForMovie"])

        serie = (await self.search("Fjällstationen"))[0]
        s = (await self.post("/api/requests", serie | {"by": "Ebba"}))["request"]
        ok = await self.post(f"/api/requests/{s['id']}/approve")
        self.assertEqual(ok["request"]["note"], "Skickad till Sonarr")
        sent = self.sonarr_log[0]
        self.assertEqual((sent["tvdbId"], sent["qualityProfileId"], sent["rootFolderPath"]), (373737, 6, "/data/Serier/"))
        self.assertEqual(sent["addOptions"]["monitor"], "all")
        self.assertTrue(sent["addOptions"]["searchForMissingEpisodes"])

        # Finns redan i Radarr men saknar fil: bevaka och sök, i stället för att inget händer.
        await self.post(f"/api/requests/{w['id']}/decline")
        again = (await self.post("/api/requests", film | {"by": "Jonas"}))["request"]
        ok = await self.post(f"/api/requests/{again['id']}/approve")
        self.assertEqual(ok["request"]["note"], "Fanns redan i Radarr – sökning startad")
        self.assertIn({"editor": {"movieIds": [9], "monitored": True}}, self.radarr_log)
        self.assertIn({"command": {"name": "MoviesSearch", "movieIds": [9]}}, self.radarr_log)

        # Profil som inte finns: stoppa med ett tydligt fel, gissa inte.
        await self.post("/api/settings", {"radarrProfile": "Finns inte"})
        err = await self.post("/api/arr/test/radarr", status=502)
        self.assertIn("ingen kvalitetsprofil som heter Finns inte", err["error"])
        self.assertIn("HD-1080p", err["error"])
        await self.post("/api/settings", {"radarrProfile": "hd 1080p"})
        self.assertIn("HD-1080p", (await self.post("/api/arr/test/radarr"))["ok"])
        await self.post("/api/settings", {"radarrRoot": "/data/Fel"})
        err = await self.post("/api/arr/test/radarr", status=502)
        self.assertIn("ingen rotmapp /data/Fel", err["error"])
        await self.post("/api/settings", {"radarrRoot": "", "radarrProfile": ""})

        # Fel nyckel: önskemålet ligger kvar som väntande med felet.
        await self.post("/api/settings", {"radarrKey": "fel"})
        await self.post(f"/api/requests/{again['id']}/decline")
        w2 = (await self.post("/api/requests", film | {"by": "Ebba"}))["request"]
        err = await self.post(f"/api/requests/{w2['id']}/approve", status=502)
        self.assertIn("API-nyckeln", err["error"])
        lst = await (await self.client.get("/api/requests")).json()
        self.assertEqual(next(x for x in lst["items"] if x["id"] == w2["id"])["status"], "pending")

    async def test_marked_available_when_in_plex(self):
        film = (await self.search("Polarexpressen"))[0]
        w = (await self.post("/api/requests", film | {"by": "Ebba"}))["request"]
        # Filmen läggs till i Plex (samma guid i biblioteket).
        k = fake_plex.order[0]
        old = fake_plex.items[k]["guid"]
        fake_plex.items[k]["guid"] = film["guid"]
        try:
            self.app[jptv.JPTV]["library"].invalidate()
            self.app[jptv.JPTV]["plex"].forget_cache()
            lst = await (await self.client.get("/api/requests")).json()
            got = next(x for x in lst["items"] if x["id"] == w["id"])
            self.assertEqual(got["status"], "available")
            self.assertEqual(got["libraryKey"], k)
        finally:
            fake_plex.items[k]["guid"] = old


if __name__ == "__main__":
    unittest.main()
