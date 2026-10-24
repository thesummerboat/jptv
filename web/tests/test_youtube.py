"""YouTube: flöden, sök, kanaler, prenumerationer, HLS av DASH-filer (riktiga filer från ffmpeg) och proxyn."""
import asyncio
import json
import os
import shutil
import subprocess
import tempfile
import time
from datetime import datetime, timezone
import unittest

from unittest import mock

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from app import main as jptv
from app.youtube import YtDlp, YtError
from app.youtube import comment_token, parse_comments, parse_date_text, parse_watch_page
from app.youtube import (parse_ago, parse_clock, parse_count, parse_import, parse_index, parse_related, parse_rss,
                         pick_formats, pick_subtitles)

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

CH_A = "UCaaaaaaaaaaaaaaaaaaaaaa"
CH_B = "UCbbbbbbbbbbbbbbbbbbbbbb"


def lockup(vid, title, chan, cid, views="1.2M views", ago="3 days ago", dur="12:34", live=False,
           kind="LOCKUP_CONTENT_TYPE_VIDEO", link_channel=True, combined=False, avatar=True):
    """En video i YouTubes nyare format (lockupViewModel), som bredvid en video på youtube.com."""
    channel_text = {"content": chan}
    if link_channel:
        channel_text["commandRuns"] = [{"startIndex": 0, "length": len(chan),
                                        "onTap": {"innertubeCommand": {"browseEndpoint": {"browseId": cid}}}}]
    if live:
        second = [{"text": {"content": "12K watching"}}]
    elif combined:
        second = [{"text": {"content": f"{views} • {ago}"}}]
    else:
        second = [{"text": {"content": views}, "accessibilityLabel": views}, {"text": {"content": ago}, "accessibilityLabel": ago}]
    return {"lockupViewModel": {
        "contentImage": {"thumbnailViewModel": {
            "image": {"sources": [
                {"url": f"https://i.ytimg.com/vi/{vid}/hqdefault.jpg?sqp=small", "width": 168, "height": 94},
                {"url": f"https://i.ytimg.com/vi/{vid}/hqdefault.jpg?sqp=big", "width": 336, "height": 188}]},
            "overlays": [{"thumbnailBottomOverlayViewModel": {"badges": [{"thumbnailBadgeViewModel": {
                "text": "LIVE" if live else dur,
                "badgeStyle": "THUMBNAIL_OVERLAY_BADGE_STYLE_LIVE" if live else "THUMBNAIL_OVERLAY_BADGE_STYLE_DEFAULT"}}]}}]}},
        "metadata": {"lockupMetadataViewModel": {
            "title": {"content": title},
            "image": {"decoratedAvatarViewModel": {
                "avatar": {"avatarViewModel": {"image": {"sources": [{"url": f"https://yt3.ggpht.com/{cid}=s68", "width": 68, "height": 68}]}}},
                "rendererContext": {"commandContext": {"onTap": {"innertubeCommand": {"browseEndpoint": {"browseId": cid}}}}}}} if avatar else {},
            "metadata": {"contentMetadataViewModel": {"metadataRows": [
                {"metadataParts": [{"text": channel_text}]},
                {"metadataParts": second}], "delimiter": " • "}}}},
        "contentId": vid, "contentType": kind,
        "rendererContext": {"commandContext": {"onTap": {"innertubeCommand": {"watchEndpoint": {"videoId": vid}}}}}}}


def compact(vid, title, chan, cid):
    """Samma sak i YouTubes äldre format (compactVideoRenderer)."""
    return {"compactVideoRenderer": {
        "videoId": vid, "title": {"simpleText": title},
        "longBylineText": {"runs": [{"text": chan, "navigationEndpoint": {"browseEndpoint": {"browseId": cid}}}]},
        "publishedTimeText": {"simpleText": "2 weeks ago"}, "viewCountText": {"simpleText": "1,234 views"},
        "lengthText": {"simpleText": "1:02:03"},
        "thumbnail": {"thumbnails": [{"url": f"https://i.ytimg.com/vi/{vid}/hqdefault.jpg", "width": 480, "height": 360}]},
        "channelThumbnail": {"thumbnails": [{"url": f"//yt3.ggpht.com/{cid}=s68", "width": 68, "height": 68}]}}}


RELATED = {
    # Bredvid den nyaste videon: kanal A och B, en spellista och en livesändning (hoppas över).
    "abcdefghijk": [
        lockup("rel00000001", "Förslag 1", "Kanal A", CH_A),
        lockup("rel00000002", "Förslag 2", "Kanal B", CH_B, link_channel=False),
        lockup("RDabcdefghi", "Mix", "YouTube", CH_A, kind="LOCKUP_CONTENT_TYPE_PLAYLIST"),
        lockup("live0000001", "Live nu", "Kanal A", CH_A, live=True),
        lockup("rel00000003", "Förslag 3", "Kanal B", CH_B, views="12K views", ago="1 year ago"),
        compact("cmp00000001", "Gammalt format", "Kanal A", CH_A),
        lockup("rel00000004", "Förslag 4", "Kanal A", CH_A),
        lockup("olderolder1", "Äldre video", "Testkanalen", "UCabcdefghijklmnopqrstuv"),
    ],
    # Bredvid den äldre: förslag 3 igen (då hamnar den först) och några till.
    "olderolder1": [
        lockup("rel00000003", "Förslag 3", "Kanal B", CH_B, views="12K views", ago="1 year ago"),
        lockup("rel00000005", "Förslag 5", "Kanal A", CH_A),
        lockup("rel00000006", "Förslag 6", "Kanal B", CH_B),
        lockup("rel00000007", "Förslag 7", "Kanal A", CH_A),
    ],
}


def comment(cid, author, text, likes="1.2K", replies="", when="2 weeks ago", pinned=False, hearted=False, creator=False):
    """En kommentar i YouTubes nyare format: commentViewModel + data i frameworkUpdates."""
    vm = {"commentKey": f"key-{cid}", "toolbarStateKey": f"state-{cid}", "commentId": cid}
    if pinned:
        vm["pinnedText"] = "Pinned by Testkanalen"
    entities = [
        {"entityKey": f"key-{cid}", "payload": {"commentEntityPayload": {
            "properties": {"commentId": cid, "content": {"content": text}, "publishedTime": when},
            "author": {"channelId": "UCcccccccccccccccccccccc", "displayName": author, "isCreator": creator,
                       "avatarThumbnailUrl": f"https://yt3.ggpht.com/{cid}=s88"},
            "toolbar": {"likeCountNotliked": likes, "likeCountA11y": f"{likes} likes", "replyCount": replies}}}},
        {"entityKey": f"state-{cid}", "payload": {"engagementToolbarStateEntityPayload": {
            "heartState": "TOOLBAR_HEART_STATE_HEARTED" if hearted else "TOOLBAR_HEART_STATE_UNHEARTED"}}},
    ]
    return vm, entities


def comments_page(threads, next_token=None, action="reloadContinuationItemsCommand", in_thread=True):
    items, mutations = [], []
    for vm, ents, replies_token in threads:
        mutations += ents
        if in_thread:
            thread = {"commentViewModel": {"commentViewModel": vm}}
            if replies_token:
                thread["replies"] = {"commentRepliesRenderer": {"contents": [{"continuationItemRenderer": {
                    "continuationEndpoint": {"continuationCommand": {"token": replies_token}}}}]}}
            items.append({"commentThreadRenderer": thread})
        else:
            items.append({"commentViewModel": vm})
    if next_token:
        items.append({"continuationItemRenderer": {"continuationEndpoint": {"continuationCommand": {"token": next_token}}}})
    return {"onResponseReceivedEndpoints": [{action: {"continuationItems": items}}],
            "frameworkUpdates": {"entityBatchUpdate": {"mutations": mutations}}}


COMMENTS = {
    # Första svaret: bara rubriken med antal och sortering.
    comment_token(VID): {"onResponseReceivedEndpoints": [{"reloadContinuationItemsCommand": {"continuationItems": [
        {"commentsHeaderRenderer": {"countText": {"runs": [{"text": "1,234"}, {"text": " Comments"}]},
                                    "sortMenu": {"sortFilterSubMenuRenderer": {"subMenuItems": [
                                        {"title": "Top", "serviceEndpoint": {"continuationCommand": {"token": "SORTTOP"}}},
                                        {"title": "Newest", "serviceEndpoint": {"continuationCommand": {"token": "SORTNEW"}}}]}}}}]}}]},
    "SORTTOP": comments_page([
        (*comment("c1", "@kanalen", "Tack för att ni tittar!", likes="3.4K", pinned=True, hearted=True, creator=True), None),
        (*comment("c2", "@anna", "Bästa videon hittills\nrad två", likes="58", replies="12", when="för 3 dagar sedan"), "REPLIES1"),
    ], next_token="PAGE2"),
    "PAGE2": comments_page([(*comment("c3", "@bertil", "Sista", likes=""), None)], action="appendContinuationItemsAction"),
    # Kommentarerna avstängda: inget att visa.
    comment_token("olderolder1"): {"onResponseReceivedEndpoints": [], "contents": {"messageRenderer": {"text": {"runs": [{"text": "Comments are turned off."}]}}}},
    "REPLIES1": comments_page([(*comment("r1", "@cecilia", "Håller med", likes="2"), None)],
                              action="appendContinuationItemsAction", in_thread=False),
}


def next_response(vid):
    items = RELATED.get(vid, [])
    return {
        "contents": {"twoColumnWatchNextResults": {
            # Texten under videon: datum och visningar (på svenska, som YouTube ibland svarar).
            "results": {"results": {"contents": [{"videoPrimaryInfoRenderer": {
                "title": {"runs": [{"text": "Videon"}]}, "dateText": {"simpleText": "3 okt. 2024"},
                "relativeDateText": {"simpleText": "för 1 år sedan"},
                "viewCount": {"videoViewCountRenderer": {"viewCount": {"simpleText": "4 242 visningar"}}}}}]}},
            "secondaryResults": {"secondaryResults": {"results": [
                {"itemSectionRenderer": {"contents": items + [{"continuationItemRenderer": {"trigger": "CONTINUATION_TRIGGER_ON_ITEM_SHOWN"}}]}}]}},
        }},
        # Slutskärmen räknas inte som förslag.
        "playerOverlays": {"playerOverlayRenderer": {"endScreen": {"watchNextEndScreenRenderer": {"results": [
            {"endScreenVideoRenderer": {"videoId": "endscreen01"}}, {"compactVideoRenderer": {"videoId": "endscreen02", "title": {"simpleText": "x"}}}]}}}},
    }


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
        # Förhandsbilder som YouTubes: 3×3 bilder à 160×90 i en jpg.
        ["-f", "lavfi", "-i", "testsrc2=size=160x90:rate=0.75", "-t", "12", "-vf", "tile=3x3", "-frames:v", "1", "sb0.jpg"],
        ["-f", "lavfi", "-i", "testsrc2=size=80x45:rate=2", "-t", "12", "-vf", "tile=5x5", "-frames:v", "1", "sb1.jpg"],
    ]
    for args in runs:
        out = os.path.join(directory, args[-1])
        if not os.path.exists(out):
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", *args[:-1], out], check=True)


def fake_youtube(media: str, log: list) -> web.Application:
    app = web.Application()

    async def media_file(req):
        log.append(("media", req.match_info["name"], req.headers.get("Range")))
        if req.query.get("fast") == "1":
            raise web.HTTPForbidden()
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

    async def watch_next(req):
        body = await req.json()
        if body.get("continuation"):
            log.append(("comments", body["continuation"]))
            page = COMMENTS.get(body["continuation"])
            return web.json_response(page) if page else web.Response(status=400, text="okänd")
        log.append(("next", body.get("videoId"), body["context"]["client"].get("hl")))
        if body.get("videoId") == "failfailfai":
            return web.Response(status=500, text="nej")
        return web.json_response(next_response(body.get("videoId")))

    app.router.add_get("/media/{name}", media_file)
    app.router.add_get("/rss/{cid}", rss)
    app.router.add_get("/sb/api/skipSegments/{prefix}", sponsor)
    app.router.add_get("/sub.vtt", sub)
    app.router.add_post("/youtubei/v1/next", watch_next)
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

    def test_related(self):
        items = parse_related(next_response("abcdefghijk"))
        ids = [i["id"] for i in items]
        # Spellistan och slutskärmen räknas inte; livesändningen markeras.
        self.assertEqual(ids, ["rel00000001", "rel00000002", "live0000001", "rel00000003", "cmp00000001", "rel00000004", "olderolder1"])
        first = items[0]
        self.assertEqual((first["title"], first["channel"], first["channelId"]), ("Förslag 1", "Kanal A", CH_A))
        self.assertEqual((first["views"], first["duration"]), (1_200_000, 754))
        self.assertAlmostEqual(first["published"] / 1000, time.time() - 3 * 86400, delta=60)
        self.assertEqual(first["thumb"], "https://i.ytimg.com/vi/rel00000001/hqdefault.jpg?sqp=big")
        self.assertEqual(first["avatar"], f"https://yt3.ggpht.com/{CH_A}=s68")
        # Kanal utan länk i texten: id:t tas från profilbilden.
        self.assertEqual((items[1]["channel"], items[1]["channelId"]), ("Kanal B", CH_B))
        self.assertTrue(items[2]["live"])
        old = items[4]
        self.assertEqual((old["channel"], old["channelId"], old["views"], old["duration"]), ("Kanal A", CH_A, 1234, 3723))
        self.assertEqual(old["avatar"], f"https://yt3.ggpht.com/{CH_A}=s68")
        self.assertEqual(parse_related({}), [])

    def test_related_other_layouts(self):
        """Svenska texter (YouTube väljer ibland språk efter IP-adressen), allt på en rad, ingen kanallänk."""
        data = {"contents": {"twoColumnWatchNextResults": {"secondaryResults": {"secondaryResults": {"results": [
            lockup("sv000000001", "Svensk", "WULFF DEN", CH_A, views="58 tn visningar", ago="för 2 veckor sedan"),
            lockup("sv000000002", "Miljon", "Kanal B", CH_B, views="1,2 mn visningar", ago="för 3 år sedan", link_channel=False, avatar=False),
            lockup("en000000003", "En rad", "Kanal A", CH_A, views="1,234 views", ago="5 days ago", combined=True, link_channel=False, avatar=False),
            lockup("sv000000004", "Live", "Kanal A", CH_A, live=True),
        ]}}}}}
        items = {i["id"]: i for i in parse_related(data)}
        now = time.time() * 1000
        a = items["sv000000001"]
        self.assertEqual((a["channel"], a["channelId"], a["views"]), ("WULFF DEN", CH_A, 58_000))
        self.assertAlmostEqual((now - a["published"]) / 86400e3, 14, delta=0.1)
        b = items["sv000000002"]
        self.assertEqual((b["channel"], b["channelId"], b["views"]), ("Kanal B", "", 1_200_000))
        self.assertAlmostEqual((now - b["published"]) / 86400e3, 3 * 365, delta=1)
        c = items["en000000003"]
        self.assertEqual((c["channel"], c["views"]), ("Kanal A", 1234))
        self.assertAlmostEqual((now - c["published"]) / 86400e3, 5, delta=0.1)
        self.assertTrue(items["sv000000004"]["live"])

    def test_real_response_2026(self):
        """Ett riktigt svar från oktober 2026: kortformer som "1.5m" och "1 yr ago"."""
        with open(os.path.join(HERE, "data", "next_2026.json"), encoding="utf-8") as f:
            data = json.load(f)
        items = parse_related(data)
        self.assertEqual(len(items), 2)
        a = items[0]
        self.assertEqual((a["channel"], a["channelId"], a["views"], a["duration"]), ("Neevs", "UC2BqYB26rjoGlVlKh8YMM3w", 1_500_000, 1071))
        self.assertAlmostEqual((time.time() * 1000 - a["published"]) / 86400e3, 365, delta=1)
        self.assertTrue(all(i["published"] and i["views"] and i["avatar"] and i["thumb"] for i in items))
        page = parse_watch_page(data)
        self.assertEqual(page["views"], 41_682_241)
        self.assertEqual(datetime.fromtimestamp(page["published"] / 1000, timezone.utc).date().isoformat(), "2019-08-24")
        for t, days in (("1 yr ago", 365), ("13 yr ago", 13 * 365), ("3 mo ago", 90), ("2 wk ago", 14), ("5 hr ago", 5 / 24)):
            self.assertAlmostEqual((1_800_000_000 - parse_ago(t, 1_800_000_000) / 1000) / 86400, days, delta=0.01, msg=t)
        self.assertEqual(parse_count("1.5m"), 1_500_000)
        self.assertEqual(parse_count("1.5 million views"), 1_500_000)

    def test_watch_page_date(self):
        oct3 = int(datetime(2024, 10, 3, 12, tzinfo=timezone.utc).timestamp() * 1000)
        self.assertEqual(parse_watch_page(next_response(VID)), {"published": oct3, "views": 4242})
        for t in ("Oct 3, 2024", "3 okt. 2024", "03.10.2024", "Premiered Oct 3, 2024", "2024-10-03", "3. Okt. 2024"):
            self.assertEqual(parse_date_text(t), oct3, t)
        self.assertIsNone(parse_date_text("igår"))
        # Bara relativ tid: den används.
        page = parse_watch_page({"contents": {"twoColumnWatchNextResults": {"results": {"results": {"contents": [
            {"videoPrimaryInfoRenderer": {"relativeDateText": {"simpleText": "vor 2 Wochen"}}}]}}}}})
        self.assertAlmostEqual((time.time() * 1000 - page["published"]) / 86400e3, 14, delta=0.1)

    def test_comments_parse(self):
        header, items, nxt = parse_comments(COMMENTS["SORTTOP"])
        self.assertIsNone(header)
        self.assertEqual(nxt, "PAGE2")
        a, b = items
        self.assertEqual((a["author"], a["likes"], a["pinned"], a["hearted"], a["creator"]), ("@kanalen", 3400, True, True, True))
        self.assertEqual((b["text"], b["likes"], b["replies"], b["repliesToken"]), ("Bästa videon hittills\nrad två", 58, 12, "REPLIES1"))
        self.assertAlmostEqual((time.time() * 1000 - b["published"]) / 86400e3, 3, delta=0.1)
        header, items, nxt = parse_comments(COMMENTS[comment_token(VID)])
        self.assertEqual((items, nxt), ([], None))
        self.assertEqual(header["countText"]["runs"][0]["text"], "1,234")
        _, replies, _ = parse_comments(COMMENTS["REPLIES1"])
        self.assertEqual([r["text"] for r in replies], ["Håller med"])
        self.assertEqual(parse_comments({}), (None, [], None))

    def test_counts_and_times(self):
        self.assertEqual(parse_count("1.2M views"), 1_200_000)
        self.assertEqual(parse_count("12K views"), 12_000)
        self.assertEqual(parse_count("1,234,567 views"), 1_234_567)
        self.assertEqual(parse_count("1 view"), 1)
        self.assertEqual(parse_count("No views"), 0)
        self.assertEqual(parse_count("2.5B views"), 2_500_000_000)
        self.assertIsNone(parse_count(""))
        self.assertEqual(parse_count("257 tn visningar"), 257_000)
        self.assertEqual(parse_count("1,2 mn visningar"), 1_200_000)
        self.assertEqual(parse_count("1\u00a0234 visningar"), 1234)
        self.assertEqual(parse_count("Inga visningar"), 0)
        now = 1_800_000_000
        self.assertEqual(parse_ago("3 years ago", now), (now - 3 * 31536000) * 1000)
        self.assertEqual(parse_ago("Streamed 2 weeks ago", now), (now - 2 * 604800) * 1000)
        self.assertEqual(parse_ago("1 day ago", now), (now - 86400) * 1000)
        self.assertIsNone(parse_ago("igår", now))
        self.assertEqual(parse_ago("vor 3 Jahren", now), (now - 3 * 31536000) * 1000)
        self.assertEqual(parse_ago("il y a 2 semaines", now), (now - 2 * 604800) * 1000)
        self.assertEqual(parse_ago("hace 5 días", now), (now - 5 * 86400) * 1000)
        for not_time in ("1.2M views", "58 tn visningar", "4 242 visningar", "12 svar", "2 Mio. Aufrufe"):
            self.assertIsNone(parse_ago(not_time, now), not_time)
        self.assertEqual(parse_ago("för 3 år sedan", now), (now - 3 * 31536000) * 1000)
        self.assertEqual(parse_ago("för 5 timmar sedan", now), (now - 5 * 3600) * 1000)
        self.assertEqual(parse_ago("Streamades för 1 vecka sedan", now), (now - 604800) * 1000)
        self.assertEqual((parse_clock("12:34"), parse_clock("1:02:03"), parse_clock("LIVE")), (754, 3723, None))

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
            "JPTV_YT_API": base,
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
        # Datum ("för 1 dag sedan") och visningar finns i sökträffarna.
        self.assertEqual(res["videos"][0]["published"], (1760000000 - 86400) * 1000)
        self.assertEqual(res["videos"][0]["views"], 1000)
        self.assertEqual(res["channels"][0]["id"], CID)
        self.assertEqual(res["channels"][0]["avatar"], "https://yt3.ggpht.com/avatar=s88")
        ch = await self.get_json(f"/api/yt/channel/{CID}")
        self.assertEqual((ch["name"], ch["subscribed"]), ("Testkanalen", False))
        self.assertEqual(ch["avatar"], "https://yt3.googleusercontent.com/avatar=s900")
        self.assertEqual(len(ch["videos"]), 4)
        # Exakt datum ur RSS-flödet för den som finns där, ungefärligt för resten.
        rss = {v["id"]: v for v in parse_rss(RSS, CID)[1]}
        self.assertEqual(ch["videos"][0]["published"], rss["abcdefghijk"]["published"])
        self.assertEqual(ch["videos"][1]["published"], (1760000000 - 86400) * 1000)
        await self.get("/api/yt/channel/inte-en-kanal", status=404)

    async def test_recommendations(self):
        y = self.app[jptv.JPTV]["youtube"]
        # Ingenting sett och inga kanaler: tomt, men inget fel.
        recs = await self.get_json("/api/yt/recommended")
        self.assertEqual((recs["recommended"], recs["because"], recs["channels"]), ([], [], []))

        await self.post("/api/yt/subs", {"id": CID, "name": "Testkanalen"})
        # Bara prenumeration: förslagen utgår från nya videor i flödet.
        recs = await self.get_json("/api/yt/recommended")
        self.assertEqual(recs["seeds"], 0)
        self.assertIn("rel00000001", [v["id"] for v in recs["recommended"]])
        self.assertEqual(recs["because"], [])

        # Sett två videor (uppgifterna om videon fylls i av servern).
        await self.get_json(f"/api/yt/video/{VID}")
        await self.post("/api/yt/progress", {"id": VID, "pos": 590, "dur": 600})
        await self.post("/api/yt/progress", {"id": "olderolder1", "pos": 400, "dur": 410, "title": "Äldre video", "channelId": CID})
        hist = (await self.get_json("/api/yt/history"))["videos"]
        self.assertEqual([v["id"] for v in hist], ["olderolder1", VID])
        oct3 = int(datetime(2024, 10, 3, 12, tzinfo=timezone.utc).timestamp() * 1000)
        self.assertEqual((hist[1]["title"], hist[1]["views"], hist[1]["published"]), ("Testvideo", 4242, oct3))

        recs = await self.get_json("/api/yt/recommended")
        ids = [v["id"] for v in recs["recommended"]]
        # Föreslås från båda hållen: först. Sett, live och spellistor: inte med.
        self.assertEqual(ids[0], "rel00000003")
        for gone in (VID, "olderolder1", "live0000001", "RDabcdefghi"):
            self.assertNotIn(gone, ids)
        self.assertIn("cmp00000001", ids)
        self.assertEqual(recs["seeds"], 2)
        self.assertEqual(recs["because"][0]["seed"], {"id": "olderolder1", "title": "Äldre video"})
        # Kanaler man inte prenumererar på men som föreslås ofta.
        self.assertEqual({c["id"] for c in recs["channels"]}, {CH_A, CH_B})
        self.assertTrue(all(c["avatar"] for c in recs["channels"]))
        # Frågat på engelska (för att kunna läsa "3 days ago").
        self.assertIn(("next", VID, "en"), self.log)

        # Inte intresserad, och dölj kanal B.
        await self.post("/api/yt/dismiss", {"id": "rel00000001"})
        await self.post("/api/yt/block", {"id": CH_B, "name": "Kanal B"})
        recs = await self.get_json("/api/yt/recommended")
        ids = [v["id"] for v in recs["recommended"]]
        self.assertNotIn("rel00000001", ids)
        self.assertFalse([v for v in recs["recommended"] if v["channelId"] == CH_B])
        self.assertEqual([c["id"] for c in recs["channels"]], [CH_A])
        self.assertEqual((await self.get_json("/api/yt/status"))["blocked"], [{"id": CH_B, "name": "Kanal B"}])
        r = await self.client.delete(f"/api/yt/block/{CH_B}")
        self.assertEqual((await r.json())["blocked"], [])

        # YouTubes förslag går inte att hämta: sökning på titeln i stället.
        await self.post("/api/yt/progress", {"id": "failfailfai", "pos": 300, "dur": 300, "title": "Något helt annat"})
        recs = await self.get_json("/api/yt/recommended")
        self.assertIn("vid00000001", [v["id"] for v in recs["recommended"]])
        self.assertEqual(y.recs_stats["search"], 1)

        # Historiken: ta bort en, sedan allt.
        r = await self.client.delete("/api/yt/history/failfailfai")
        self.assertEqual(len((await r.json())["videos"]), 2)
        r = await self.client.delete("/api/yt/history")
        self.assertEqual((await r.json())["removed"], 2)
        self.assertEqual((await self.get_json("/api/yt/history"))["videos"], [])
        await self.post("/api/yt/dismiss", {"id": "nej"}, status=400)

    async def test_video_hls_and_proxy(self):
        v = await self.get_json(f"/api/yt/video/{VID}")
        self.assertEqual((v["title"], v["duration"], v["hls"]), ("Testvideo", 12, f"/yt/{VID}/master.m3u8"))
        # Snabbstarten gav inget datum: det kom från sidan för videon.
        self.assertEqual(v["published"], int(datetime(2024, 10, 3, 12, tzinfo=timezone.utc).timestamp() * 1000))
        self.assertEqual([c["title"] for c in v["chapters"]], ["Början", "Slutet"])
        # Bara sponsor (inte musik) och bara för den här videon.
        self.assertEqual(v["sponsor"], [{"start": 2.0, "end": 4.5, "category": "sponsor"}])
        self.assertEqual([s["lang"] for s in v["subtitles"]], ["sv", "en"])
        # Förhandsbilder: det största arket (160 px), 9 bilder, och arket går att hämta via servern.
        self.assertEqual(v["storyboard"], {"width": 160, "height": 90, "columns": 3, "rows": 3, "fps": 0.75,
                                           "frames": 9, "sheets": 1, "url": f"/yt/{VID}/sb/{{n}}.jpg"})
        r = await self.client.get(f"/yt/{VID}/sb/0.jpg")
        self.assertEqual((r.status, r.content_type), (200, "image/jpeg"))
        sheet = await r.read()
        self.assertEqual(sheet[:2], b"\xff\xd8")
        with open(os.path.join(self.media, "sb0.jpg"), "rb") as f:
            self.assertEqual(sheet, f.read())
        await self.get(f"/yt/{VID}/sb/1.jpg", status=404)

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

    async def test_comments(self):
        c = await self.get_json(f"/api/yt/comments/{VID}")
        self.assertEqual(c["count"], 1234)
        self.assertEqual([x["id"] for x in c["comments"]], ["c1", "c2"])
        self.assertEqual(c["next"], "PAGE2")
        self.assertFalse(c["disabled"])
        more = await self.get_json(f"/api/yt/comments/{VID}?token=PAGE2")
        self.assertEqual(([x["id"] for x in more["comments"]], more["next"]), (["c3"], None))
        replies = await self.get_json(f"/api/yt/comments/{VID}?token=REPLIES1")
        self.assertEqual([x["author"] for x in replies["comments"]], ["@cecilia"])
        # Sparas en stund: ingen ny fråga till YouTube.
        asked = sum(1 for l in self.log if l[0] == "comments")
        await self.get_json(f"/api/yt/comments/{VID}")
        self.assertEqual(sum(1 for l in self.log if l[0] == "comments"), asked)
        # Avstängda kommentarer.
        off = await self.get_json("/api/yt/comments/olderolder1")
        self.assertEqual((off["disabled"], off["comments"]), (True, []))
        err = await self.get_json(f"/api/yt/comments/{VID}?token=TRASIG", status=502)
        self.assertIn("Kommentarerna", err["error"])

    async def test_prefetch(self):
        """Står man på ett kort förbereds videon: yt-dlp och index är klara när man trycker."""
        y = self.app[jptv.JPTV]["youtube"]
        r = await self.post(f"/api/yt/prefetch/{VID}")
        self.assertTrue(r["started"])
        for _ in range(200):
            v = y._videos.get(VID)
            if v and len(v["index"]) == len(v["formats"]):
                break
            await asyncio.sleep(0.05)
        self.assertEqual(set(v["index"]), {"134", "140"})
        fetched = sum(1 for l in self.log if l[0] == "media")
        playlist = await (await self.get(f"/yt/{VID}/134.m3u8")).text()
        self.assertIn("#EXT-X-BYTERANGE", playlist)
        # Spellistan kom ur det som redan var hämtat.
        self.assertEqual(sum(1 for l in self.log if l[0] == "media"), fetched)
        self.assertTrue((await self.post(f"/api/yt/prefetch/{VID}"))["started"])
        r = await self.client.post("/api/yt/prefetch/ogiltig")
        self.assertEqual(r.status, 404)

    async def _wait_bg(self, y):
        for _ in range(200):
            if not y._bg:
                return
            await asyncio.sleep(0.05)

    async def test_fast_start(self):
        """Snabbstart: används när den ger samma kvalitet, kontrolleras de första gångerna."""
        y = self.app[jptv.JPTV]["youtube"]
        for vid in (VID, "fastfast001", "fastfast002"):
            await self.get_json(f"/api/yt/video/{vid}")
            self.assertEqual(y.last_mode, "snabb")
            self.assertTrue(y._videos[vid]["fast"])
            await self._wait_bg(y)
        # Tre kontroller med samma kvalitet: på för gott.
        self.assertEqual((y.fast.state, y.fast.checks), ("on", 3))
        self.assertEqual(self.app[jptv.JPTV]["store"].get("youtube_fast")["state"], "on")
        st = await self.get_json("/api/yt/status")
        self.assertEqual((st["fast"], st["lastMode"]), ("på", "snabb"))

    async def test_fast_start_lower_quality(self):
        y = self.app[jptv.JPTV]["youtube"]
        with mock.patch.dict(os.environ, {"FAKE_YT_FAST": "low"}):
            v = await self.get_json(f"/api/yt/video/{VID}")
            self.assertEqual(v["maxHeight"], 144)
            await self._wait_bg(y)
            # Den vanliga hämtningen gav 180p: snabbstarten stängs av.
            self.assertEqual(y.fast.state, "off")
            self.assertIn("144p", y.fast.reason)
            await self.get_json("/api/yt/video/fastfast001")
            self.assertEqual(y.last_mode, "vanlig")

    async def test_fast_start_fails(self):
        y = self.app[jptv.JPTV]["youtube"]
        with mock.patch.dict(os.environ, {"FAKE_YT_FAST": "fail"}):
            v = await self.get_json(f"/api/yt/video/{VID}")
            self.assertEqual((v["maxHeight"], y.last_mode, y.fast.failures), (180, "vanlig", 1))

    async def test_fast_start_forbidden(self):
        """YouTube nekar snabbstartens adresser (403): den vanliga hämtningen tar över."""
        y = self.app[jptv.JPTV]["youtube"]
        with mock.patch.dict(os.environ, {"FAKE_YT_FAST": "403"}):
            v = await self.get_json(f"/api/yt/video/{VID}")
            self.assertTrue(y._videos[VID]["fast"])
            await self._wait_bg(y)
            playlist = await (await self.get(f"/yt/{VID}/134.m3u8")).text()
            self.assertIn("#EXT-X-BYTERANGE", playlist)
            self.assertFalse(y._videos[VID]["fast"])
            self.assertEqual(y.fast.failures, 1)
            r = await self.client.get(f"/yt/{VID}/s/140.mp4", headers={"Range": "bytes=0-99"})
            self.assertEqual(r.status, 206)

    async def test_errors_and_images(self):
        err = await self.get_json("/api/yt/video/privatevid0", status=502)
        self.assertEqual(err["error"], "Videon är privat")
        await self.get("/yt/img?u=https://evil.example.com/x.jpg", status=404)
        await self.get("/yt/inte-giltigt/master.m3u8", status=404)
        ok = await self.post("/api/yt/test")
        self.assertIn("fungerar", ok["ok"])
        self.assertIn("YouTubes förslag", ok["ok"])
        self.assertIn("videons datum: 2024-10-03", ok["ok"])

    async def test_import(self):
        text = json.dumps({"subscriptions": [{"url": f"https://www.youtube.com/channel/{CID}", "name": "Testkanalen"}]})
        r = await self.client.post("/api/yt/import", data=text)
        res = await r.json()
        self.assertEqual((res["found"], res["added"]), (1, 1))
        r = await self.client.post("/api/yt/import", data="ingenting")
        self.assertEqual(r.status, 400)


class WorkerTest(unittest.IsolatedAsyncioTestCase):
    """yt-dlp i en process som hålls igång (här med ett låtsasbibliotek)."""

    async def asyncSetUp(self):
        self.env = mock.patch.dict(os.environ, {"FAKE_YT_BASE": "http://127.0.0.1:1"})
        self.env.start()
        os.environ.pop("JPTV_YTDLP", None)
        self.y = YtDlp(os.path.join(HERE, "fakelib"))

    async def asyncTearDown(self):
        self.y.stop()
        self.env.stop()

    async def test_worker(self):
        a = await self.y.run_json(["--flat-playlist", "ytsearch5:katter"])
        self.assertEqual(len(a["entries"]), 6)
        v = await self.y.run_json(["--no-playlist", f"https://www.youtube.com/watch?v={VID}"])
        b = await self.y.run_json(["--no-playlist", "https://www.youtube.com/watch?v=fastfast001"])
        # Samma process båda gångerna, inte testprocessen.
        self.assertEqual(v["_worker_pid"], b["_worker_pid"])
        self.assertNotEqual(v["_worker_pid"], os.getpid())
        self.assertTrue(self.y.worker_running)
        # Fel kommer tillbaka som vanligt.
        with self.assertRaises(YtError) as e:
            await self.y.run_json(["--no-playlist", "https://www.youtube.com/watch?v=privatevid0"])
        self.assertEqual(str(e.exception), "Videon är privat")
        # Flera samtidigt.
        many = await asyncio.gather(*(self.y.run_json(["--no-playlist", f"https://www.youtube.com/watch?v=many0000{i:03d}"]) for i in range(5)))
        self.assertEqual([m["id"] for m in many], [f"many0000{i:03d}" for i in range(5)])
        # Något som hänger sig: fel efter tidsgränsen, sedan en ny process.
        with self.assertRaises(YtError):
            await self.y.run_json(["--no-playlist", "https://www.youtube.com/watch?v=hangforeve"], timeout=1)
        c = await self.y.run_json(["--no-playlist", f"https://www.youtube.com/watch?v={VID}"])
        self.assertNotEqual(c["_worker_pid"], v["_worker_pid"])
        self.assertEqual(self.y.worker_failures, 0)

    async def test_worker_broken(self):
        """Startar inte processen används yt-dlp som vanligt program."""
        self.y.lib_dir = os.path.join(HERE, "finns-inte")
        with mock.patch.dict(os.environ, {"JPTV_YTDLP_PYTHON_BROKEN": "1"}), \
                mock.patch("app.youtube.WORKER", os.path.join(HERE, "finns-inte.py")), \
                mock.patch.object(YtDlp, "_cmd", lambda self: [os.path.join(HERE, "fake_ytdlp.py")]):
            r = await self.y.run_json(["--flat-playlist", "ytsearch5:katter"])
        self.assertEqual(len(r["entries"]), 6)
        self.assertEqual(self.y.worker_failures, 1)


if __name__ == "__main__":
    unittest.main()
