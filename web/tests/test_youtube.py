"""YouTube: flöden, sök, kanaler, prenumerationer, HLS av DASH-filer (riktiga filer från ffmpeg) och proxyn."""
import asyncio
import json
import os
import shutil
import subprocess
import tempfile
import unittest

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from app import main as jptv
from app.youtube import parse_import, parse_index, parse_rss, pick_formats, pick_subtitles

HERE = os.path.dirname(os.path.abspath(__file__))
CID = "UCabcdefghijklmnopqrstuv"
VID = "abcdefghijk"

RSS = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns:media="http://search.yahoo.com/mrss/" xmlns="http://www.w3.org/2005/Atom">
 <title>Testkanalen</title>
 <entry>
  <yt:videoId>abcdefghijk</yt:videoId>
  <title>Nyaste videon</title>
  <link rel="alternate" href="https://www.youtube.com/watch?v=abcdefghijk"/>
  <author><name>Testkanalen</name></author>
  <published>2026-10-20T10:00:00+00:00</published>
  <media:group><media:thumbnail url="https://i1.ytimg.com/vi/abcdefghijk/hqdefault.jpg" width="480" height="360"/>
   <media:community><media:statistics views="1234"/></media:community></media:group>
 </entry>
 <entry>
  <yt:videoId>shortshort1</yt:videoId>
  <title>En short</title>
  <link rel="alternate" href="https://www.youtube.com/shorts/shortshort1"/>
  <published>2026-10-21T10:00:00+00:00</published>
 </entry>
 <entry>
  <yt:videoId>olderolder1</yt:videoId>
  <title>Äldre video</title>
  <link rel="alternate" href="https://www.youtube.com/watch?v=olderolder1"/>
  <published>2026-10-01T10:00:00+00:00</published>
 </entry>
</feed>"""

VTT = "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nHej!\n"


def make_media(directory: str) -> None:
    """Riktiga DASH-filer (ftyp, moov, sidx, fragment) som YouTubes, i H.264/AAC och VP9/Opus."""
    common = ["-movflags", "+dash+global_sidx", "-frag_duration", "2000000", "-f", "mp4"]
    runs = [
        ["-f", "lavfi", "-i", "testsrc2=size=320x180:rate=25", "-t", "12", "-c:v", "libx264", "-pix_fmt", "yuv420p",
         "-g", "50", "-keyint_min", "50", "-sc_threshold", "0", "-an", *common, "v.mp4"],
        ["-f", "lavfi", "-i", "sine=frequency=440:duration=12", "-c:a", "aac", "-b:a", "96k", *common, "a.m4a"],
        ["-f", "lavfi", "-i", "testsrc2=size=320x180:rate=25", "-t", "12", "-c:v", "libvpx-vp9", "-b:v", "300k",
         "-deadline", "realtime", "-cpu-used", "8", "-g", "50", "-keyint_min", "50", "-an", *common, "v9.mp4"],
        ["-f", "lavfi", "-i", "sine=frequency=440:duration=12", "-c:a", "libopus", "-b:a", "96k", *common, "o.mp4"],
    ]
    for args in runs:
        out = os.path.join(directory, args[-1])
        if not os.path.exists(out):
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", *args[:-1], out], check=True)


def fake_youtube(media: str, log: list) -> web.Application:
    app = web.Application()

    async def media_file(req):
        log.append(("media", req.match_info["name"], req.headers.get("Range")))
        path = os.path.join(media, req.match_info["name"])
        if not os.path.exists(path):
            raise web.HTTPNotFound()
        return web.FileResponse(path)

    async def rss(req):
        if req.match_info["cid"] != CID:
            raise web.HTTPNotFound()
        return web.Response(text=RSS, content_type="application/xml")

    async def sponsor(req):
        return web.json_response([{"videoID": VID, "segments": [
            {"category": "sponsor", "segment": [2.0, 4.5], "actionType": "skip"},
            {"category": "music_offtopic", "segment": [8.0, 9.0], "actionType": "skip"},
        ]}, {"videoID": "someoneelse", "segments": [{"category": "sponsor", "segment": [1, 2]}]}])

    async def sub(_):
        return web.Response(text=VTT, content_type="text/vtt")

    app.router.add_get("/media/{name}", media_file)
    app.router.add_get("/rss/{cid}", rss)
    app.router.add_get("/sb/api/skipSegments/{prefix}", sponsor)
    app.router.add_get("/sub.vtt", sub)
    return app


class ParseTest(unittest.TestCase):
    def test_rss(self):
        name, items = parse_rss(RSS, CID)
        self.assertEqual(name, "Testkanalen")
        self.assertEqual([i["id"] for i in items], ["abcdefghijk", "shortshort1", "olderolder1"])
        self.assertEqual(items[0]["views"], 1234)
        self.assertTrue(items[1]["short"])
        self.assertFalse(items[0]["short"])
        self.assertGreater(items[0]["published"], items[2]["published"])

    def test_import_formats(self):
        newpipe = json.dumps({"app_version": "0.27", "subscriptions": [
            {"service_id": 0, "url": f"https://www.youtube.com/channel/{CID}", "name": "Testkanalen"}]}, indent=2)
        self.assertEqual(parse_import(newpipe), [{"id": CID, "name": "Testkanalen"}])
        freetube = "\n".join([
            json.dumps({"_id": "allChannels", "name": "All Channels", "subscriptions": [{"id": CID, "name": "Testkanalen", "thumbnail": "x"}]}),
            json.dumps({"_id": "x", "name": "Annat", "subscriptions": [{"id": "UCzyxwvutsrqponmlkjihgfe", "name": "Annan"}]}),
        ])
        self.assertEqual({c["id"] for c in parse_import(freetube)}, {CID, "UCzyxwvutsrqponmlkjihgfe"})
        takeout = f"Channel Id,Channel Url,Channel Title\n{CID},http://www.youtube.com/channel/{CID},Testkanalen\n"
        self.assertEqual(parse_import(takeout), [{"id": CID, "name": "Testkanalen"}])
        opml = f'<opml><body><outline text="Testkanalen" xmlUrl="https://www.youtube.com/feeds/videos.xml?channel_id={CID}"/></body></opml>'
        self.assertEqual(parse_import(opml), [{"id": CID, "name": "Testkanalen"}])
        self.assertEqual(parse_import("inget här"), [])

    def test_pick_formats_and_subtitles(self):
        env = dict(os.environ)
        info = json.loads(subprocess.run([os.path.join(HERE, "fake_ytdlp.py"), "-J", f"https://www.youtube.com/watch?v={VID}"],
                                         capture_output=True, text=True, env=env, check=True).stdout)
        videos, audio = pick_formats(info, 1080)
        self.assertEqual([v["format_id"] for v in videos], ["134"])
        self.assertEqual(audio["format_id"], "140")
        subs = pick_subtitles(info)
        self.assertEqual([(s["lang"], s["name"]) for s in subs], [("sv", "Svenska"), ("en", "Engelska (automatisk)")])

    def test_parse_index_needs_more(self):
        self.assertIsNone(parse_index(b"\x00\x00\x00\x08moof"))
        head = b"\x00\x00\x00\x10ftypisom\x00\x00\x00\x00" + b"\x00\x00\x10\x00sidx"
        self.assertEqual(parse_index(head)[0], "more")


class YouTubeApiTest(unittest.IsolatedAsyncioTestCase):
    @classmethod
    def setUpClass(cls):
        cls.media = os.path.join(tempfile.gettempdir(), "jptv-yt-media")
        os.makedirs(cls.media, exist_ok=True)
        make_media(cls.media)

    async def asyncSetUp(self):
        self.tmp = tempfile.mkdtemp()
        self.log = []
        self.fake = TestServer(fake_youtube(self.media, self.log))
        await self.fake.start_server()
        base = f"http://127.0.0.1:{self.fake.port}"
        self.env = {
            "JPTV_YTDLP": os.path.join(HERE, "fake_ytdlp.py"),
            "FAKE_YT_BASE": base,
            "JPTV_YT_RSS": base + "/rss/{}",
            "JPTV_SPONSORBLOCK": base + "/sb",
        }
        self.saved_env = {k: os.environ.get(k) for k in self.env}
        os.environ.update(self.env)
        cfg = os.path.join(self.tmp, "config")
        os.makedirs(cfg)
        self.app = jptv.create_app(cfg)
        self.client = TestClient(TestServer(self.app))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()
        await self.fake.close()
        for k, v in self.saved_env.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v
        shutil.rmtree(self.tmp, ignore_errors=True)

    async def get(self, path, status=200):
        r = await self.client.get(path)
        self.assertEqual(r.status, status, await r.text())
        return r

    async def get_json(self, path, status=200):
        return await (await self.get(path, status)).json()

    async def post(self, path, data=None, status=200):
        r = await self.client.post(path, json=data or {})
        self.assertEqual(r.status, status, await r.text())
        return await r.json()

    async def test_subscribe_feed_and_progress(self):
        st = await self.get_json("/api/status")
        self.assertTrue(st["youtube"])
        home = await self.get_json("/api/yt/home")
        self.assertEqual((home["feed"], home["subs"]), ([], []))
        await self.post("/api/yt/subs", {"id": CID, "name": ""})
        await self.post("/api/yt/subs", {"id": "inte-ett-id"}, status=400)
        home = await self.get_json("/api/yt/home")
        # Shorts visas inte som standard; nyast först; namnet kommer från flödet.
        self.assertEqual([v["id"] for v in home["feed"]], ["abcdefghijk", "olderolder1"])
        self.assertEqual(home["subs"][0]["name"], "Testkanalen")
        await self.post("/api/settings", {"youtubeShorts": True})
        self.app[jptv.JPTV]["youtube"]._feeds.clear()
        home = await self.get_json("/api/yt/home")
        self.assertIn("shortshort1", [v["id"] for v in home["feed"]])

        # Position: påbörjad hamnar i Fortsätt titta, sedd försvinner därifrån.
        await self.post("/api/yt/progress", {"id": VID, "pos": 120, "dur": 600, "title": "Nyaste videon", "channel": "Testkanalen"})
        home = await self.get_json("/api/yt/home")
        self.assertEqual(home["continue"][0]["id"], VID)
        self.assertEqual(home["continue"][0]["position"], 120)
        self.assertEqual(home["feed"][1]["position"], 120)
        await self.post("/api/yt/progress", {"id": VID, "pos": 590, "dur": 600})
        home = await self.get_json("/api/yt/home")
        self.assertEqual(home["continue"], [])
        self.assertTrue(next(v for v in home["feed"] if v["id"] == VID)["watched"])

        r = await self.client.delete(f"/api/yt/subs/{CID}")
        self.assertEqual((await r.json())["subs"], [])

    async def test_search_and_channel(self):
        res = await self.get_json("/api/yt/search?q=test")
        self.assertEqual(len(res["videos"]), 5)  # utan short
        self.assertEqual(res["channels"][0]["id"], CID)
        self.assertEqual(res["channels"][0]["avatar"], "https://yt3.ggpht.com/avatar=s88")
        ch = await self.get_json(f"/api/yt/channel/{CID}")
        self.assertEqual((ch["name"], ch["subscribed"]), ("Testkanalen", False))
        self.assertEqual(ch["avatar"], "https://yt3.googleusercontent.com/avatar=s900")
        self.assertEqual(len(ch["videos"]), 3)
        await self.get("/api/yt/channel/inte-en-kanal", status=404)

    async def test_video_hls_and_proxy(self):
        v = await self.get_json(f"/api/yt/video/{VID}")
        self.assertEqual((v["title"], v["duration"], v["hls"]), ("Testvideo", 12, f"/yt/{VID}/master.m3u8"))
        self.assertEqual([c["title"] for c in v["chapters"]], ["Början", "Slutet"])
        # Bara sponsor (inte musik) och bara för den här videon.
        self.assertEqual(v["sponsor"], [{"start": 2.0, "end": 4.5, "category": "sponsor"}])
        self.assertEqual([s["lang"] for s in v["subtitles"]], ["sv", "en"])

        master = await (await self.get(f"/yt/{VID}/master.m3u8")).text()
        self.assertIn('URI="140.m3u8"', master)
        self.assertIn("134.m3u8", master)
        self.assertIn('CODECS="avc1.4d401e,mp4a.40.2"', master)
        self.assertIn("RESOLUTION=320x180", master)
        self.assertIn('URI="sub/sv.m3u8"', master)
        self.assertNotIn("136", master)

        playlist = await (await self.get(f"/yt/{VID}/134.m3u8")).text()
        size = os.path.getsize(os.path.join(self.media, "v.mp4"))
        ranges = [line.split(":")[1] for line in playlist.splitlines() if line.startswith("#EXT-X-BYTERANGE")]
        self.assertGreaterEqual(len(ranges), 5)
        last_len, last_off = map(int, ranges[-1].split("@"))
        self.assertLessEqual(last_off + last_len, size)
        self.assertIn('#EXT-X-MAP:URI="s/134.mp4",BYTERANGE=', playlist)
        durations = [float(l[8:-1]) for l in playlist.splitlines() if l.startswith("#EXTINF:")]
        self.assertAlmostEqual(sum(durations), 12.0, delta=0.2)

        # Ett segment genom proxyn: samma byte som filen.
        length, offset = map(int, ranges[1].split("@"))
        r = await self.client.get(f"/yt/{VID}/s/134.mp4", headers={"Range": f"bytes={offset}-{offset + length - 1}"})
        self.assertEqual(r.status, 206)
        data = await r.read()
        with open(os.path.join(self.media, "v.mp4"), "rb") as f:
            f.seek(offset)
            self.assertEqual(data, f.read(length))

        sub_pl = await (await self.get(f"/yt/{VID}/sub/sv.m3u8")).text()
        self.assertIn("sv.vtt", sub_pl)
        vtt = await (await self.get(f"/yt/{VID}/sub/sv.vtt")).text()
        self.assertTrue(vtt.startswith("WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:0,LOCAL:00:00:00.000\n"))

        # Hela videon går att läsa som HLS (ffmpeg följer spellistorna och byte-intervallen).
        url = f"http://127.0.0.1:{self.client.port}/yt/{VID}/master.m3u8"
        proc = await asyncio.create_subprocess_exec(
            "ffmpeg", "-v", "error", "-i", url, "-map", "0:v:0", "-map", "0:a:0", "-f", "null", "-",
            "-progress", "pipe:1", "-nostats",
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
        )
        out, err = await proc.communicate()
        self.assertEqual(proc.returncode, 0, err.decode())
        times = [int(l.split("=")[1]) for l in out.decode().splitlines() if l.startswith("out_time_us=") and l.split("=")[1].isdigit()]
        # Både bild och ljud avkodades ända till slutet (12 s).
        self.assertAlmostEqual(max(times) / 1e6, 12.0, delta=0.5)

    async def test_errors_and_images(self):
        err = await self.get_json("/api/yt/video/privatevid0", status=502)
        self.assertEqual(err["error"], "Videon är privat")
        await self.get("/yt/img?u=https://evil.example.com/x.jpg", status=404)
        await self.get("/yt/inte-giltigt/master.m3u8", status=404)
        ok = await self.post("/api/yt/test")
        self.assertIn("fungerar", ok["ok"])

    async def test_import(self):
        text = json.dumps({"subscriptions": [{"url": f"https://www.youtube.com/channel/{CID}", "name": "Testkanalen"}]})
        r = await self.client.post("/api/yt/import", data=text)
        res = await r.json()
        self.assertEqual((res["found"], res["added"]), (1, 1))
        r = await self.client.post("/api/yt/import", data="ingenting")
        self.assertEqual(r.status, 400)


if __name__ == "__main__":
    unittest.main()
