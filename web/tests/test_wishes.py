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


MOVIE_ID, SERIES_ID = 9, 7


def fake_arr(kind: str, log: list, state: dict | None = None) -> web.Application:
    """Minsta möjliga Radarr/Sonarr: uppslag, profiler, rotmapp, tillägg, kö och kommandon.
    state: "title" (fält som skrivs över på filmen/serien), "queue" och "commands" – ändras av testerna."""
    app = web.Application()
    added = set()
    state = state if state is not None else {}
    state.setdefault("title", {})
    state.setdefault("queue", [])
    state.setdefault("commands", [])
    state.setdefault("history", [])
    state.setdefault("episodes", [])
    state["added"] = added

    def record(key):
        if kind == "radarr":
            out = {"id": MOVIE_ID, "tmdbId": key, "title": "Polarexpressen 2", "titleSlug": "polarexpressen-2-2026",
                   "monitored": True, "hasFile": False, "isAvailable": True, "lastSearchTime": "2026-10-23T12:00:00Z"}
        else:
            out = {"id": SERIES_ID, "tvdbId": key, "title": "Fjällstationen", "titleSlug": "fjallstationen",
                   "monitored": True, "statistics": {"episodeFileCount": 0, "episodeCount": 8, "totalEpisodeCount": 8}}
        return out | state["title"]

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
            out["id"] = MOVIE_ID
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

    async def library(req):
        auth(req)
        want = req.query.get("tmdbId") if kind == "radarr" else req.query.get("tvdbId")
        return web.json_response([record(k) for k in added if want is None or str(k) == want])

    async def queue(req):
        auth(req)
        key = "movieId" if kind == "radarr" else "seriesId"
        want = req.query.get(key)
        return web.json_response([q for q in state["queue"] if want is None or str(q.get(key)) == want])

    async def queue_delete(req):
        auth(req)
        qid = int(req.match_info["id"])
        if not any(q["id"] == qid for q in state["queue"]):
            raise web.HTTPNotFound()
        log.append({"delete": qid, "params": dict(req.query)})
        gone = next(q for q in state["queue"] if q["id"] == qid).get("downloadId")
        state["queue"] = [q for q in state["queue"] if q.get("downloadId") != gone]
        return web.json_response({})

    async def commands(req):
        auth(req)
        return web.json_response(state["commands"])

    async def history(req):
        auth(req)
        out = state["history"]
        if "seasonNumber" in req.query:
            out = [h for h in out if h.get("seasonNumber", int(req.query["seasonNumber"])) == int(req.query["seasonNumber"])]
        return web.json_response(out)

    async def mark_failed(req):
        auth(req)
        log.append({"failed": int(req.match_info["id"])})
        return web.json_response({})

    async def delete_file(req):
        auth(req)
        log.append({"deleteFile": int(req.match_info["id"])})
        return web.Response(status=200)

    async def episodes(req):
        auth(req)
        season = int(req.query.get("seasonNumber", "0"))
        return web.json_response([e for e in state["episodes"] if e.get("seasonNumber") == season])

    async def episode_monitor(req):
        auth(req)
        log.append({"monitorEpisodes": await req.json()})
        return web.json_response([])

    app.router.add_get("/api/v3/history/movie", history)
    app.router.add_get("/api/v3/history/series", history)
    app.router.add_post("/api/v3/history/failed/{id}", mark_failed)
    app.router.add_delete("/api/v3/moviefile/{id}", delete_file)
    app.router.add_delete("/api/v3/episodefile/{id}", delete_file)
    app.router.add_get("/api/v3/episode", episodes)
    app.router.add_put("/api/v3/episode/monitor", episode_monitor)
    app.router.add_get("/api/v3/movie", library)
    app.router.add_get("/api/v3/series", library)
    app.router.add_get("/api/v3/queue/details", queue)
    app.router.add_delete("/api/v3/queue/{id}", queue_delete)
    app.router.add_get("/api/v3/command", commands)
    app.router.add_put("/api/v3/series/editor", editor)
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
        self.radarr_state, self.sonarr_state = {}, {}
        self.radarr = TestServer(fake_arr("radarr", self.radarr_log, self.radarr_state))
        self.sonarr = TestServer(fake_arr("sonarr", self.sonarr_log, self.sonarr_state))
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

    async def arr_status(self):
        r = await self.client.get("/api/requests/arr")
        self.assertEqual(r.status, 200, await r.text())
        return (await r.json())["items"]

    async def test_arr_status_in_list(self):
        await self.post("/api/settings", {
            "radarrUrl": f"127.0.0.1:{self.radarr.port}", "radarrKey": "nyckel",
            "sonarrUrl": f"127.0.0.1:{self.sonarr.port}", "sonarrKey": "nyckel",
        })
        film = (await self.search("Polarexpressen"))[0]
        w = (await self.post("/api/requests", film | {"by": "Ebba"}))["request"]
        await self.post(f"/api/requests/{w['id']}/approve")
        serie = (await self.search("Fjällstationen"))[0]
        s = (await self.post("/api/requests", serie | {"by": "Ebba"}))["request"]
        await self.post(f"/api/requests/{s['id']}/approve")
        wishes = self.app[jptv.JPTV]["wishes"]
        # Id:n sparas vid OK, så statusen slipper fråga Plex.
        self.assertEqual(str(wishes.get(w["id"])["ids"]["tmdb"]), "424242")

        # Inget hittat än: senast sökt, knappen Sök igen.
        st = await self.arr_status()
        self.assertEqual((st[w["id"]]["state"], st[w["id"]]["text"]), ("missing", "Inget släpp hittat än"))
        self.assertEqual(st[w["id"]]["searchedAt"], "2026-10-23T12:00:00Z")
        self.assertEqual(st[w["id"]]["actions"], ["search"])
        self.assertTrue(st[w["id"]]["link"].endswith("/movie/polarexpressen-2-2026"))
        self.assertEqual(st[s["id"]]["state"], "missing")

        # Radarr letar just nu.
        self.radarr_state["commands"] = [{"name": "MoviesSearch", "status": "started", "body": {"movieIds": [9]}}]
        self.assertEqual((await self.arr_status())[w["id"]]["text"], "Söker…")
        self.radarr_state["commands"] = []

        # Laddar ner: procent och tid kvar.
        self.radarr_state["queue"] = [{
            "id": 50, "movieId": 9, "downloadId": "abc", "status": "downloading", "trackedDownloadState": "downloading",
            "trackedDownloadStatus": "ok", "size": 1000, "sizeleft": 550, "timeleft": "00:12:00",
            "title": "Polarexpressen.2.2026.1080p.WEB-DL", "quality": {"quality": {"name": "WEBDL-1080p"}},
        }]
        got = (await self.arr_status())[w["id"]]
        self.assertEqual((got["state"], got["text"], got["progress"], got["eta"]), ("downloading", "Laddar ner 45 %", 0.45, 720))
        self.assertEqual(got["actions"], [])
        # Dagar skrivs först: 1.02:00:00.
        self.radarr_state["queue"][0]["timeleft"] = "1.02:00:00"
        self.assertEqual((await self.arr_status())[w["id"]]["eta"], 26 * 3600)

        # Nedladdad men fast (BR-DISK): Radarrs förklaring och Släng och sök annan.
        self.radarr_state["queue"] = [{
            "id": 51, "movieId": 9, "downloadId": "bad", "status": "completed", "trackedDownloadState": "importBlocked",
            "trackedDownloadStatus": "warning", "size": 50e9, "sizeleft": 0, "title": "Polarexpressen.2.COMPLETE.BLURAY",
            "statusMessages": [{"title": "Polarexpressen.2.COMPLETE.BLURAY", "messages": ["BR-DISK is not supported"]}],
        }]
        got = (await self.arr_status())[w["id"]]
        self.assertEqual((got["state"], got["text"]), ("stuck", "Nedladdad men inte importerad"))
        self.assertEqual(got["messages"], ["BR-DISK is not supported"])
        self.assertEqual(got["actions"], ["retry"])
        self.assertTrue(got["link"].endswith("/activity/queue"))
        ok = await self.post(f"/api/requests/{w['id']}/retry")
        self.assertIn("svartlistat", ok["ok"])
        self.assertIn({"delete": 51, "params": {"removeFromClient": "true", "blocklist": "true", "skipRedownload": "true"}}, self.radarr_log)
        self.assertEqual(self.radarr_log[-1], {"command": {"name": "MoviesSearch", "movieIds": [9]}})
        self.assertEqual(self.radarr_state["queue"], [])

        # Sök igen.
        ok = await self.post(f"/api/requests/{w['id']}/search")
        self.assertEqual(ok["ok"], "Radarr letar nu")
        self.assertEqual(self.radarr_log[-1], {"command": {"name": "MoviesSearch", "movieIds": [9]}})

        # Väntar på digital release (som Clayface).
        self.radarr_state["title"] = {"isAvailable": False, "inCinemas": "2026-10-23T00:00:00Z",
                                      "digitalRelease": "2099-11-24T00:00:00Z", "physicalRelease": "2099-12-20T00:00:00Z"}
        got = (await self.arr_status())[w["id"]]
        self.assertEqual((got["state"], got["text"]), ("waiting", "Väntar på release"))
        self.assertEqual(got["release"], {"label": "släpps digitalt", "at": "2099-11-24T00:00:00Z"})

        # Nedladdad, väntar på Plex.
        self.radarr_state["title"] = {"hasFile": True, "movieFile": {"size": 8.2e9, "quality": {"quality": {"name": "Bluray-1080p"}}}}
        got = (await self.arr_status())[w["id"]]
        self.assertEqual(got["state"], "done")
        self.assertIn("Bluray-1080p, 8,2 GB", got["text"])
        self.radarr_state["title"] = {}

        # Serie: säsongspaket som fastnat (en rad per avsnitt i kön, samma nedladdning).
        self.sonarr_state["queue"] = [
            {"id": 60 + e, "seriesId": 7, "episodeId": 100 + e, "downloadId": "pack", "status": "completed",
             "trackedDownloadState": "importPending", "trackedDownloadStatus": "warning", "size": 9e9, "sizeleft": 0,
             "episode": {"seasonNumber": 1, "episodeNumber": e},
             "statusMessages": [{"title": "x", "messages": ["One or more episodes expected in this release were not imported"]}]}
            for e in (1, 2, 3)
        ]
        got = (await self.arr_status())[s["id"]]
        self.assertEqual((got["state"], got["text"]), ("stuck", "Nedladdad men inte importerad · säsong 1"))
        ok = await self.post(f"/api/requests/{s['id']}/retry")
        self.assertIn("svartlistat", ok["ok"])
        self.assertEqual(sum(1 for x in self.sonarr_log if "delete" in x), 1)
        self.assertEqual(self.sonarr_log[-1], {"command": {"name": "EpisodeSearch", "episodeIds": [101, 102, 103]}})

        # Delvis nedladdad serie.
        self.sonarr_state["title"] = {"statistics": {"episodeFileCount": 3, "episodeCount": 8}, "nextAiring": "2099-01-01T20:00:00Z"}
        got = (await self.arr_status())[s["id"]]
        self.assertEqual((got["state"], got["text"]), ("partial", "3 av 8 avsnitt nedladdade"))
        self.assertEqual(got["release"]["label"], "nästa avsnitt")

        # Äldre önskemål utan sparade id:n: hämtas från Plex och sparas.
        wishes.update(w["id"], ids=None)
        self.assertEqual((await self.arr_status())[w["id"]]["state"], "missing")
        self.assertEqual(str(wishes.get(w["id"])["ids"]["tmdb"]), "424242")

        # Borttagen ur Radarr: Skicka igen.
        self.radarr_state["added"].clear()
        got = (await self.arr_status())[w["id"]]
        self.assertEqual((got["state"], got["actions"]), ("gone", ["resend"]))
        err = await self.post(f"/api/requests/{w['id']}/search", status=502)
        self.assertIn("Finns inte i Radarr", err["error"])

        # Radarr svarar inte rätt: felet visas på raden, sidan fungerar.
        await self.post("/api/settings", {"radarrKey": "fel"})
        got = (await self.arr_status())[w["id"]]
        self.assertEqual(got["state"], "error")
        self.assertIn("API-nyckeln", got["text"])

    async def test_uhd_wishes(self):
        """Önska i 4K: en egen Radarr för 4K, ett eget önskemål, klart när den finns i 4K-biblioteket."""
        log4k, state4k = [], {}
        radarr4k = TestServer(fake_arr("radarr", log4k, state4k))
        await radarr4k.start_server()
        fake_plex.extra_sections[:] = [{"key": "4", "title": "Filmer 4K", "type": "movie"}]
        try:
            # Utan Radarr 4K går det inte att önska i 4K.
            self.assertFalse((await (await self.client.get("/api/status")).json())["uhd"])
            film = (await self.search("Polarexpressen"))[0]
            self.assertNotIn("uhdKey", film)
            await self.post("/api/requests", film | {"by": "Ebba", "uhd": True}, status=400)

            await self.post("/api/settings", {
                "radarrUrl": f"127.0.0.1:{self.radarr.port}", "radarrKey": "nyckel",
                "radarr4kUrl": f"127.0.0.1:{radarr4k.port}", "radarr4kKey": "nyckel",
            })
            self.assertTrue((await (await self.client.get("/api/status")).json())["uhd"])
            # 4K-biblioteket hittas på namnet.
            secs = await (await self.client.get("/api/plex/sections")).json()
            self.assertEqual((secs["uhd"], [x["title"] for x in secs["sections"]]), ("4", ["Filmer", "Filmer 4K"]))
            film = (await self.search("Polarexpressen"))[0]
            self.assertEqual((film["hdKey"], film["uhdKey"], film["request"], film["requestUhd"]), (None, None, None, None))

            # Vanligt önskemål och 4K-önskemål är två olika.
            hd = (await self.post("/api/requests", film | {"by": "Ebba"}))["request"]
            uhd = (await self.post("/api/requests", film | {"by": "Jonas", "uhd": True}))["request"]
            self.assertNotEqual(hd["id"], uhd["id"])
            self.assertTrue(uhd["uhd"])
            self.assertFalse(hd["uhd"])
            again = await self.post("/api/requests", film | {"by": "Ebba", "uhd": True})
            self.assertEqual((again["request"]["id"], again["new"]), (uhd["id"], False))
            film = (await self.search("Polarexpressen"))[0]
            self.assertEqual((film["request"]["id"], film["requestUhd"]["id"]), (hd["id"], uhd["id"]))

            # Radarr 4K har två rotmappar och ingen vald: tydligt fel.
            err = await self.post(f"/api/requests/{uhd['id']}/approve", status=502)
            self.assertIn("Välj rotmapp för Radarr 4K", err["error"])
            await self.post("/api/settings", {"radarr4kRoot": "/data/Filmer"})
            ok = await self.post(f"/api/requests/{uhd['id']}/approve")
            self.assertEqual(ok["request"]["note"], "Skickad till Radarr 4K")
            sent = next(x for x in log4k if "qualityProfileId" in x)
            self.assertEqual((sent["qualityProfileId"], sent["rootFolderPath"]), (5, "/data/Filmer"))
            # Den vanliga Radarr fick inget.
            self.assertFalse(any("qualityProfileId" in x for x in self.radarr_log))

            # Status hämtas från Radarr 4K.
            st = await (await self.client.get("/api/requests/arr")).json()
            self.assertEqual(st["items"][uhd["id"]]["state"], "missing")
            self.assertNotIn(hd["id"], st["items"])
            await self.post(f"/api/requests/{uhd['id']}/search")
            self.assertEqual(log4k[-1], {"command": {"name": "MoviesSearch", "movieIds": [9]}})

            # Filmen dyker upp i 4K-biblioteket: bara 4K-önskemålet blir klart.
            fake_plex.items["uhd1"] = {"ratingKey": "uhd1", "key": "/library/metadata/uhd1", "type": "movie", "title": "Polarexpressen 2",
                                       "guid": "plex://movie/nyfilm1", "year": 2026, "librarySectionID": 4, "addedAt": fake_plex.NOW}
            fake_plex.order.append("uhd1")
            self.app[jptv.JPTV]["library"].invalidate()
            self.app[jptv.JPTV]["plex"].forget_cache()
            lst = await (await self.client.get("/api/requests")).json()
            by_id = {w["id"]: w for w in lst["items"]}
            self.assertEqual((by_id[uhd["id"]]["status"], by_id[uhd["id"]]["libraryKey"]), ("available", "uhd1"))
            self.assertEqual(by_id[hd["id"]]["status"], "pending")
            self.assertTrue(lst["radarr4k"])
            film = (await self.search("Polarexpressen"))[0]
            self.assertEqual((film["uhdKey"], film["hdKey"], film["libraryKey"]), ("uhd1", None, "uhd1"))
            err = await self.post("/api/requests", film | {"by": "Ebba", "uhd": True}, status=409)
            self.assertEqual(err["error"], "Finns redan i 4K")
        finally:
            fake_plex.extra_sections.clear()
            if "uhd1" in fake_plex.items:
                del fake_plex.items["uhd1"]
                fake_plex.order.remove("uhd1")
            await radarr4k.close()

    async def test_report_redownload(self):
        """Anmäl: fel språk → släppet svartlistas, filen tas bort, Radarr/Sonarr letar efter ett annat."""
        movie_key = next(k for k in fake_plex.order if fake_plex.items[k]["type"] == "movie")
        show_key = next(k for k in fake_plex.order if fake_plex.items[k]["type"] == "show")
        ep_key = next(k for k, v in fake_plex.items.items()
                      if v["type"] == "episode" and v.get("grandparentRatingKey") == show_key and v["parentIndex"] == 1 and v["index"] == 2)
        show_title = fake_plex.items[show_key]["title"]

        # Utan Radarr går det inte.
        err = await self.post("/api/report", {"key": movie_key, "reason": "language"}, status=400)
        self.assertIn("Radarr", err["error"])
        await self.post("/api/settings", {
            "radarrUrl": f"127.0.0.1:{self.radarr.port}", "radarrKey": "nyckel",
            "sonarrUrl": f"127.0.0.1:{self.sonarr.port}", "sonarrKey": "nyckel",
        })
        # Serier och säsonger anmäls inte som helhet.
        await self.post("/api/report", {"key": show_key, "reason": "language"}, status=400)

        # Filmen (TMDB 500) finns i Radarr med en tysk fil.
        self.radarr_state["added"].add(500)
        self.radarr_state["title"] = {"hasFile": True, "movieFile": {"id": 77}}
        self.radarr_state["history"] = [
            {"id": 4, "eventType": "grabbed", "date": "2026-10-01T10:00:00Z", "sourceTitle": "Gammal.1080p"},
            {"id": 5, "eventType": "grabbed", "date": "2026-11-01T10:00:00Z", "sourceTitle": "Film.GERMAN.1080p"},
            {"id": 6, "eventType": "downloadFolderImported", "date": "2026-11-01T11:00:00Z"},
        ]
        ok = await self.post("/api/report", {"key": movie_key, "reason": "language", "by": "Jonas"})
        self.assertIn("svartlistat", ok["ok"])
        self.assertIn({"failed": 5}, self.radarr_log)
        self.assertNotIn({"failed": 4}, self.radarr_log)
        self.assertIn({"deleteFile": 77}, self.radarr_log)
        self.assertEqual(self.radarr_log[-1], {"command": {"name": "MoviesSearch", "movieIds": [9]}})
        rep = ok["request"]
        self.assertEqual((rep["kind"], rep["status"], rep["reason"], rep["type"]), ("report", "approved", "Fel språk", "movie"))
        self.assertTrue(rep["note"].startswith("Fel språk – "))
        # Samma titel igen medan den laddas ner: ingen ny anmälan.
        again = await self.post("/api/report", {"key": movie_key, "reason": "quality"})
        self.assertEqual((again["request"]["id"], again["ok"]), (rep["id"], "Redan anmäld – laddas ner igen"))

        # Status: inget nytt än, sedan nedladdad igen → klar.
        self.radarr_state["title"] = {"hasFile": False}
        st = await (await self.client.get("/api/requests/arr")).json()
        self.assertEqual(st["items"][rep["id"]]["state"], "missing")
        self.radarr_state["title"] = {"hasFile": True, "movieFile": {"id": 78}}
        st = await (await self.client.get("/api/requests/arr")).json()
        self.assertEqual(st["items"][rep["id"]]["state"], "done")
        done = self.app[jptv.JPTV]["wishes"].get(rep["id"])
        self.assertEqual((done["status"], done["libraryKey"], done["note"]), ("available", movie_key, "Fel språk – nedladdad igen"))
        # Pending-räknaren i menyn påverkas inte.
        self.assertEqual((await (await self.client.get("/api/requests")).json())["pending"], 0)

        # Avsnitt S01E02: Sonarr hittar serien på TVDB-id:t från Plex.
        self.sonarr_state["added"].add(373737)
        self.sonarr_state["episodes"] = [
            {"id": 201, "seasonNumber": 1, "episodeNumber": 2, "episodeFileId": 88, "hasFile": True, "monitored": False},
            {"id": 202, "seasonNumber": 1, "episodeNumber": 3, "episodeFileId": 89, "hasFile": True, "monitored": True},
        ]
        self.sonarr_state["history"] = [
            {"id": 9, "episodeId": 201, "seasonNumber": 1, "eventType": "grabbed", "date": "2026-11-02T10:00:00Z"},
            {"id": 10, "episodeId": 202, "seasonNumber": 1, "eventType": "grabbed", "date": "2026-11-03T10:00:00Z"},
        ]
        ok = await self.post("/api/report", {"key": ep_key, "reason": "language", "by": "Jonas"})
        self.assertIn({"failed": 9}, self.sonarr_log)
        self.assertNotIn({"failed": 10}, self.sonarr_log)
        self.assertIn({"deleteFile": 88}, self.sonarr_log)
        self.assertIn({"monitorEpisodes": {"episodeIds": [201], "monitored": True}}, self.sonarr_log)
        self.assertEqual(self.sonarr_log[-1], {"command": {"name": "EpisodeSearch", "episodeIds": [201]}})
        rep = ok["request"]
        self.assertEqual((rep["title"], rep["type"], rep["episode"]["number"]), (f"{show_title} · S01E02", "show", 2))

        # Statusen gäller just det avsnittet.
        self.sonarr_state["queue"] = [{"id": 70, "seriesId": 7, "episodeId": 201, "downloadId": "x", "status": "downloading",
                                       "trackedDownloadState": "downloading", "trackedDownloadStatus": "ok", "size": 1000, "sizeleft": 550,
                                       "timeleft": "00:05:00", "episode": {"seasonNumber": 1, "episodeNumber": 2}},
                                      {"id": 71, "seriesId": 7, "episodeId": 999, "downloadId": "y", "status": "downloading",
                                       "trackedDownloadState": "downloading", "trackedDownloadStatus": "ok", "size": 1000, "sizeleft": 0}]
        got = (await (await self.client.get("/api/requests/arr")).json())["items"][rep["id"]]
        self.assertEqual((got["state"], got["text"], got["eta"]), ("downloading", "Laddar ner 45 % · S01E02", 300))
        self.sonarr_state["queue"] = []
        self.sonarr_state["episodes"][0]["hasFile"] = False
        got = (await (await self.client.get("/api/requests/arr")).json())["items"][rep["id"]]
        self.assertEqual((got["state"], got["actions"]), ("missing", ["search"]))
        await self.post(f"/api/requests/{rep['id']}/search")
        self.assertEqual(self.sonarr_log[-1], {"command": {"name": "EpisodeSearch", "episodeIds": [201]}})

    async def test_scan_library(self):
        fake_plex.scans.clear()
        r = await self.post("/api/library/scan")
        self.assertEqual(r["sections"], ["Filmer", "TV-serier"])
        self.assertEqual(sorted(fake_plex.scans), ["1", "2"])
        st = await (await self.client.get("/api/library/scan")).json()
        self.assertEqual(st["scanning"], ["Filmer", "TV-serier"])
        st = await (await self.client.get("/api/library/scan")).json()
        self.assertEqual(st["scanning"], [])
        # Ett bibliotek.
        fake_plex.scans.clear()
        r = await self.post("/api/library/scan", {"section": "2"})
        self.assertEqual((r["sections"], fake_plex.scans), (["TV-serier"], ["2"]))
        await self.post("/api/library/scan", {"section": "99"}, status=502)
        fake_plex.refreshing.clear()

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
