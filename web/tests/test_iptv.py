import gzip
import io
import unittest

from app.iptv import guess_epg_url, is_live, normalize, parse_m3u, parse_time, parse_xmltv, write_m3u

M3U = """#EXTM3U url-tvg="http://epg.example/guide.xml.gz"
#EXTINF:-1 tvg-id="svt1.se" tvg-name="SE: SVT1 HD" tvg-logo="http://logo/svt1.png" group-title="Sverige",SE: SVT1 HD
http://prov.example/live/u/p/1.ts
#EXTINF:-1 tvg-id="" group-title="Sverige",TV4, Sport
#EXTVLCOPT:http-user-agent=Special/1.0
#EXTVLCOPT:http-referrer=http://ref.example/
http://prov.example/live/u/p/2.m3u8
#EXTINF:-1,Kodi-kanal
#EXTGRP:Övrigt grupp
http://kodi.example/stream|User-Agent=KodiUA&Referer=http://kodi.ref/
#EXTINF:-1 group-title="Film",En film (2020)
http://prov.example/movie/u/p/99.mkv
"""


class M3uTest(unittest.TestCase):
    def test_parse(self):
        pl = parse_m3u(M3U)
        self.assertEqual(pl.epg_url, "http://epg.example/guide.xml.gz")
        self.assertEqual([c.name for c in pl.channels], ["SE: SVT1 HD", "TV4, Sport", "Kodi-kanal"])
        self.assertEqual([c.number for c in pl.channels], [1, 2, 3])
        svt, tv4, kodi = pl.channels
        self.assertEqual(svt.tvg_id, "svt1.se")
        self.assertEqual(svt.logo, "http://logo/svt1.png")
        self.assertIsNone(tv4.tvg_id)
        self.assertEqual(tv4.user_agent, "Special/1.0")
        self.assertEqual(tv4.referrer, "http://ref.example/")
        self.assertEqual(kodi.url, "http://kodi.example/stream")
        self.assertEqual(kodi.user_agent, "KodiUA")
        self.assertEqual(kodi.group, "Övrigt grupp")
        self.assertEqual([g["name"] for g in pl.groups], ["Sverige", "Övrigt grupp"])

    def test_roundtrip(self):
        pl = parse_m3u(M3U)
        buf = io.StringIO()
        write_m3u(pl, buf)
        again = parse_m3u(buf.getvalue())
        self.assertEqual([(c.name, c.url, c.group, c.user_agent) for c in again.channels], [(c.name, c.url, c.group, c.user_agent) for c in pl.channels])
        self.assertEqual(again.channels[0].id, pl.channels[0].id)

    def test_live(self):
        self.assertTrue(is_live("http://x/live/a/b/1.ts"))
        self.assertFalse(is_live("http://x/movie/a/b/1.mkv"))
        self.assertFalse(is_live("http://x/a/film.mp4?token=1"))

    def test_guess_epg(self):
        self.assertEqual(
            guess_epg_url("http://prov.example:8080/get.php?username=u&password=p&type=m3u_plus", None),
            "http://prov.example:8080/xmltv.php?username=u&password=p",
        )
        self.assertIsNone(guess_epg_url("http://x/list.m3u", None))


class EpgTest(unittest.TestCase):
    def test_time(self):
        self.assertEqual(parse_time("20261021180000 +0200"), 1792598400000)
        self.assertEqual(parse_time("20261021160000"), 1792598400000)
        self.assertEqual(parse_time("202610211600 +0000"), 1792598400000)
        self.assertIsNone(parse_time("garbage"))

    def test_normalize(self):
        self.assertEqual(normalize("SE: SVT1 HD"), "svt1")
        self.assertEqual(normalize("|SE| TV4 Sport (backup)"), "tv4sport")
        self.assertEqual(normalize("Kanal Ö FHD"), "kanalö")

    def test_parse(self):
        pl = parse_m3u(M3U)
        now = parse_time("20261021183000 +0200")
        xml = f"""<?xml version="1.0" encoding="UTF-8"?>
<tv>
 <channel id="SVT1.se"><display-name>SVT1</display-name></channel>
 <channel id="tv4sport"><display-name>TV4, Sport</display-name></channel>
 <channel id="other"><display-name>Annan</display-name></channel>
 <programme start="20261021180000 +0200" stop="20261021190000 +0200" channel="svt1.se"><title>Rapport & väder</title><desc>Nyheter &nbsp; i dag</desc></programme>
 <programme start="20261021190000 +0200" stop="20261021200000 +0200" channel="svt1.se"><title>Kvällen</title></programme>
 <programme start="20261021170000 +0200" stop="20261021173000 +0200" channel="svt1.se"><title>Gammalt</title></programme>
 <programme start="20261021180000 +0200" stop="20261021200000 +0200" channel="tv4sport"><title>Fotboll</title></programme>
 <programme start="20261021180000 +0200" stop="20261021200000 +0200" channel="other"><title>Ska bort</title></programme>
 <programme start="20261101180000 +0200" stop="20261101200000 +0200" channel="svt1.se"><title>För långt fram</title></programme>
</tv>""".encode()
        epg = parse_xmltv(gzip.compress(xml), pl, now_ms=now)
        svt, tv4, kodi = pl.channels
        cur, nxt = epg.now_next(svt, now)
        self.assertEqual(cur.title, "Rapport & väder")
        self.assertEqual(cur.desc, "Nyheter   i dag")
        self.assertEqual(nxt.title, "Kvällen")
        self.assertEqual(len(epg.for_channel(svt)), 3)
        self.assertEqual(epg.now_next(tv4, now)[0].title, "Fotboll")
        self.assertEqual(epg.for_channel(kodi), [])
        self.assertNotIn("other", epg.programmes)

    def test_broken_xml_keeps_what_was_read(self):
        pl = parse_m3u(M3U)
        now = parse_time("20261021183000 +0200")
        xml = b"""<tv><programme start="20261021180000 +0200" stop="20261021190000 +0200" channel="svt1.se"><title>Ok</title></programme>
<programme start="x" <<<broken"""
        epg = parse_xmltv(xml, pl, now_ms=now)
        self.assertEqual(epg.now_next(pl.channels[0], now)[0].title, "Ok")

    def test_ampersand_across_chunks(self):
        pl = parse_m3u(M3U)
        now = parse_time("20261021183000 +0200")
        filler = "<!--" + "x" * 65520 + "-->"
        xml = ('<tv>' + filler + '<programme start="20261021180000 +0200" stop="20261021190000 +0200" channel="svt1.se"><title>A & B &amp; C</title></programme></tv>').encode()
        for shift in range(0, 20):
            data = b" " * shift + xml
            epg = parse_xmltv(data, pl, now_ms=now)
            self.assertEqual(epg.now_next(pl.channels[0], now)[0].title, "A & B & C", shift)


if __name__ == "__main__":
    unittest.main()
