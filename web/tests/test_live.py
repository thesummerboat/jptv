"""Live-kedjan på riktigt: en testkanal över http genom ffmpeg till HLS, en ström åt gången."""
import asyncio
import os
import shutil
import subprocess
import tempfile
import unittest

from aiohttp import web

from app.iptv import Channel, LiveStream


def make_ts(path: str, seconds: int = 20, codec: str = "libx264") -> None:
    subprocess.run(
        ["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", f"testsrc2=size=640x360:rate=25:duration={seconds}",
         "-f", "lavfi", "-i", f"sine=frequency=440:duration={seconds}", "-c:v", codec, "-preset", "ultrafast", "-g", "25",
         "-c:a", "ac3", "-f", "mpegts", path],
        check=True,
    )


@unittest.skipUnless(shutil.which("ffmpeg"), "ffmpeg saknas")
class LiveTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.mkdtemp()
        self.ts = os.path.join(self.tmp, "chan.ts")
        make_ts(self.ts)
        self.requests = []

        async def stream(request):
            self.requests.append(request.headers.get("User-Agent"))
            resp = web.StreamResponse(headers={"Content-Type": "video/mp2t"})
            await resp.prepare(request)
            with open(self.ts, "rb") as f:
                while chunk := f.read(188 * 400):
                    await resp.write(chunk)
                    await asyncio.sleep(0.05)
            return resp

        app = web.Application()
        app.router.add_get("/live/{n}.ts", stream)
        self.runner = web.AppRunner(app)
        await self.runner.setup()
        site = web.TCPSite(self.runner, "127.0.0.1", 0)
        await site.start()
        self.port = site._server.sockets[0].getsockname()[1]

    async def asyncTearDown(self):
        await self.runner.cleanup()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def chan(self, n):
        return Channel(id=f"c{n}", name=f"Kanal {n}", url=f"http://127.0.0.1:{self.port}/live/{n}.ts", group="Test", user_agent=None)

    async def test_copy_then_switch_channel(self):
        live = LiveStream(os.path.join(self.tmp, "out"))
        s1 = await live.start(self.chan(1))
        self.assertIsNotNone(live.file(s1, "index.m3u8"))
        playlist = open(live.file(s1, "index.m3u8")).read()
        self.assertIn("#EXTINF", playlist)
        seg = next(l for l in playlist.splitlines() if l.endswith(".ts"))
        probe = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=codec_name", "-of", "csv=p=0", live.file(s1, seg)], capture_output=True, text=True)
        self.assertEqual(sorted(set(probe.stdout.split())), ["aac", "h264"])
        first_proc = live.proc
        s2 = await live.start(self.chan(2))
        # Den förra kanalen är stängd innan den nya startar: aldrig två strömmar.
        self.assertIsNotNone(first_proc.returncode)
        self.assertNotEqual(s1, s2)
        self.assertIsNone(live.file(s1, "index.m3u8"))
        self.assertTrue(all(ua.startswith("VLC/") for ua in self.requests))
        await live.stop()
        self.assertIsNone(live.proc)

    async def test_transcode(self):
        live = LiveStream(os.path.join(self.tmp, "out"))
        s = await live.start(self.chan(3), transcode_video=True)
        self.assertTrue(live.transcoding)
        self.assertIsNotNone(live.file(s, "index.m3u8"))
        await live.stop()

    async def test_bad_channel(self):
        live = LiveStream(os.path.join(self.tmp, "out"))
        bad = Channel(id="x", name="X", url=f"http://127.0.0.1:{self.port}/nope", group="T")
        with self.assertRaises(RuntimeError) as e:
            await live.start(bad, timeout=10)
        self.assertIn("finns inte", str(e.exception))
        self.assertIsNone(live.proc)

    async def test_quick_switch_cancels_pending_start(self):
        live = LiveStream(os.path.join(self.tmp, "out"))
        first = asyncio.create_task(live.start(self.chan(5)))
        await asyncio.sleep(0.3)
        second = await live.start(self.chan(6))
        with self.assertRaises(RuntimeError):
            await first
        self.assertEqual(live.session, second)
        self.assertEqual(live.channel.id, "c6")
        self.assertIsNotNone(live.file(second, "index.m3u8"))
        await live.stop()

    async def test_idle_watchdog(self):
        live = LiveStream(os.path.join(self.tmp, "out"), idle_timeout=0.5)
        await live.start(self.chan(4))
        task = asyncio.create_task(live.watchdog())
        await asyncio.sleep(6)
        self.assertIsNone(live.proc)
        task.cancel()


if __name__ == "__main__":
    unittest.main()
