"""Live-kedjan på riktigt: en testkanal över http genom ffmpeg till HLS, en ström åt gången."""
import asyncio
import os
import shutil
import subprocess
import tempfile
import unittest

from aiohttp import web

from app.iptv import Channel, HwAccel, LiveStream, ffmpeg_args


def make_interlaced_ts(path: str, seconds: int = 20) -> None:
    """Radsprångad H.264 som en HD-kanal (1080i50), fast liten: 25 bilder/s med 50 halvbilder."""
    subprocess.run(
        ["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", f"testsrc2=size=640x360:rate=50:duration={seconds}",
         "-f", "lavfi", "-i", f"sine=frequency=440:duration={seconds}",
         "-vf", "tinterlace=interleave_top,fieldorder=tff", "-flags", "+ildct+ilme", "-c:v", "libx264", "-x264opts", "tff=1",
         "-pix_fmt", "yuv420p", "-preset", "ultrafast", "-g", "25", "-c:a", "mp2", "-f", "mpegts", path],
        check=True,
    )


def fps_of(path: str) -> float:
    out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=avg_frame_rate,r_frame_rate",
                          "-of", "csv=p=0", path], capture_output=True, text=True).stdout.split()[0]
    num, den = out.split(",")[0].split("/")
    return float(num) / float(den)


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
        self.its = os.path.join(self.tmp, "interlaced.ts")
        make_interlaced_ts(self.its)
        self.requests = []

        async def stream(request):
            self.requests.append(request.headers.get("User-Agent"))
            resp = web.StreamResponse(headers={"Content-Type": "video/mp2t"})
            await resp.prepare(request)
            try:
                with open(self.its if request.match_info["n"].startswith("i") else self.ts, "rb") as f:
                    while chunk := f.read(188 * 400):
                        await resp.write(chunk)
                        await asyncio.sleep(0.05)
            except ConnectionError:
                pass  # ffmpeg stängde (kanalbyte eller stopp)
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

    async def test_interlaced_channel_is_deinterlaced_to_50(self):
        live = LiveStream(os.path.join(self.tmp, "out"))
        s = await live.start(self.chan("i1"), deinterlace=True)
        self.assertTrue(live.info["interlaced"])
        self.assertEqual(live.info["fps"], 25)
        self.assertTrue(live.deinterlacing)
        self.assertTrue(live.status()["deinterlacing"])
        playlist = open(live.file(s, "index.m3u8")).read()
        seg = next(l for l in playlist.splitlines() if l.endswith(".ts"))
        self.assertAlmostEqual(fps_of(live.file(s, seg)), 50, delta=1)
        # Nästa gång vet vi att kanalen är radsprångad: avflätning direkt, utan omstart.
        await live.start(self.chan("i1"), deinterlace=True)
        self.assertTrue(live.deinterlacing)
        await live.stop()

    async def test_progressive_channel_is_copied(self):
        live = LiveStream(os.path.join(self.tmp, "out"))
        await live.start(self.chan(7), deinterlace=True)
        self.assertFalse(live.info["interlaced"])
        self.assertFalse(live.deinterlacing)
        self.assertFalse(live.transcoding)
        await live.stop()

    async def test_deinterlace_off(self):
        live = LiveStream(os.path.join(self.tmp, "out"))
        await live.start(self.chan("i2"), deinterlace=False)
        self.assertTrue(live.info["interlaced"])
        self.assertFalse(live.deinterlacing)
        await live.stop()

    async def test_too_slow_falls_back_to_copy(self):
        live = LiveStream(os.path.join(self.tmp, "out"))
        live.MIN_SPEED, live.SLOW_SECONDS, live.GRACE = 100, 0.5, 0
        s = await live.start(self.chan("i3"), deinterlace=True)
        self.assertTrue(live.deinterlacing)
        for _ in range(60):
            await asyncio.sleep(0.25)
            if live.session != s and live.session:
                break
        self.assertNotEqual(live.session, s)
        self.assertFalse(live.deinterlacing)
        self.assertIn("c" + "i3", live.too_slow)
        self.assertTrue(live.status()["note"])
        await live.stop()

    async def test_hardware_missing_is_reported(self):
        hw = HwAccel(os.path.join(self.tmp, "renderD128"))
        self.assertFalse(await hw.probe())
        self.assertTrue(hw.checked)
        self.assertIn("/dev/dri", hw.error)

    def test_hardware_args(self):
        hw = HwAccel("/dev/dri/renderD128")
        hw.ok, hw.low_power = True, True
        args = ffmpeg_args(self.chan(1), "/tmp/x", False, True, hw)
        joined = " ".join(args)
        self.assertLess(args.index("-hwaccel"), args.index("-i"))
        self.assertIn("deinterlace_vaapi=mode=motion_adaptive:rate=field", joined)
        self.assertIn("h264_vaapi", joined)
        self.assertIn("-low_power 1", joined)
        # Utan omkodning används inte grafikdelen alls.
        self.assertNotIn("-hwaccel", ffmpeg_args(self.chan(1), "/tmp/x", False, False, hw))

    async def test_broken_hardware_falls_back_to_cpu(self):
        live = LiveStream(os.path.join(self.tmp, "out"))
        live.hw = HwAccel(os.path.join(self.tmp, "saknas"))
        live.hw.ok = True  # låtsas att provet gick bra; ffmpeg får ändå inte öppna enheten
        s = await live.start(self.chan("i4"), deinterlace=True)
        self.assertTrue(live.deinterlacing)
        self.assertFalse(live.using_hw)
        self.assertIn("ci4", live.hw_bad)
        self.assertIsNotNone(live.file(s, "index.m3u8"))
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
