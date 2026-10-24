#!/usr/bin/env python3
"""Låtsas-yt-dlp för testerna: svarar med JSON utifrån adressen, utan nätet.

FAKE_YT_BASE pekar på testservern med filerna. FAKE_YT_CODEC=vp9 ger VP9/Opus (för Chromium
utan H.264 i webbläsartestet).
"""
import json
import os
import sys

BASE = os.environ.get("FAKE_YT_BASE", "http://127.0.0.1:1")
VP9 = os.environ.get("FAKE_YT_CODEC") == "vp9"
CID = "UCabcdefghijklmnopqrstuv"
OTHER = "UCzyxwvutsrqponmlkjihgfe"


APPROX = False  # sätts av --extractor-args youtubetab:approximate_date, som riktiga yt-dlp
# FAKE_YT_FAST: "" (snabbstarten ger samma), "low" (sämre kvalitet) eller "fail" (inga format).


def video_entry(i: int, short: bool = False, vid: str | None = None) -> dict:
    vid = vid or f"vid{i:08d}"
    return {
        "_type": "url", "ie_key": "Youtube", "id": vid, "title": f"Video {i}",
        "url": f"https://www.youtube.com/{'shorts/' if short else 'watch?v='}{vid}",
        "channel": "Testkanalen", "channel_id": CID, "duration": 600 + i, "view_count": 1000 * i,
        "thumbnails": [{"url": f"https://i.ytimg.com/vi/{vid}/hqdefault.jpg", "width": 480, "height": 360}],
        # Ungefärligt datum ("för i dagar sedan") bara när det efterfrågas.
        "timestamp": 1760000000 - i * 86400 if APPROX else None,
    }


def info(vid: str) -> dict:
    exp = "4102444800"  # år 2100
    if VP9:
        vcodec, acodec, vext = "vp09.00.10.08", "opus", "v9.mp4"
        aext = "o.mp4"
    else:
        vcodec, acodec, vext = "avc1.4d401e", "mp4a.40.2", "v.mp4"
        aext = "a.m4a"
    return {
        "id": vid, "title": "Testvideo", "channel": "Testkanalen", "channel_id": CID, "duration": 12,
        "view_count": 4242, "like_count": 42, "timestamp": 1760000000, "description": "En video för testerna.",
        "thumbnails": [{"url": f"https://i.ytimg.com/vi/{vid}/maxresdefault.jpg", "width": 1280, "height": 720}],
        "chapters": [{"title": "Början", "start_time": 0, "end_time": 6}, {"title": "Slutet", "start_time": 6, "end_time": 12}],
        "subtitles": {"sv": [{"ext": "json3", "url": BASE + "/sub.json"}, {"ext": "vtt", "url": BASE + "/sub.vtt"}]},
        "automatic_captions": {"en": [{"ext": "vtt", "url": BASE + "/sub.vtt"}]},
        "formats": [
            # Ska väljas: bild 180p och ljud.
            {"format_id": "134", "url": f"{BASE}/media/{vext}?expire={exp}", "protocol": "https", "ext": "mp4",
             "vcodec": vcodec, "acodec": "none", "width": 320, "height": 180, "fps": 25, "tbr": 250},
            {"format_id": "140", "url": f"{BASE}/media/{aext}?expire={exp}", "protocol": "https", "ext": "m4a" if not VP9 else "mp4",
             "vcodec": "none", "acodec": acodec, "abr": 96, "tbr": 96, "language_preference": 10},
            # Ska inte väljas: saknar PO-token, webm, med ljud, för hög upplösning (vid 720-gräns), DRM.
            {"format_id": "136", "url": f"{BASE}/media/{vext}", "protocol": "https", "ext": "mp4", "vcodec": vcodec,
             "acodec": "none", "height": 720, "format_note": "720p, MISSING POT"},
            {"format_id": "247", "url": f"{BASE}/media/x.webm", "protocol": "https", "ext": "webm", "vcodec": "vp9",
             "acodec": "none", "height": 720},
            {"format_id": "18", "url": f"{BASE}/media/x.mp4", "protocol": "https", "ext": "mp4", "vcodec": "avc1.42001E",
             "acodec": "mp4a.40.2", "height": 360},
            {"format_id": "137", "url": f"{BASE}/media/{vext}", "protocol": "https", "ext": "mp4", "vcodec": vcodec,
             "acodec": "none", "height": 1080, "has_drm": True},
            # Förhandsbilder: 9 bilder på 12 s i ett ark med 3×3 (och ett mindre som inte ska väljas).
            {"format_id": "sb0", "format_note": "storyboard", "ext": "mhtml", "protocol": "mhtml", "acodec": "none",
             "vcodec": "none", "url": f"{BASE}/media/sb0.jpg", "width": 160, "height": 90, "fps": 0.75, "rows": 3,
             "columns": 3, "fragments": [{"url": f"{BASE}/media/sb0.jpg", "duration": 12.0}]},
            {"format_id": "sb1", "format_note": "storyboard", "ext": "mhtml", "protocol": "mhtml", "acodec": "none",
             "vcodec": "none", "url": f"{BASE}/media/sb1.jpg", "width": 80, "height": 45, "fps": 2, "rows": 5,
             "columns": 5, "fragments": [{"url": f"{BASE}/media/sb1.jpg", "duration": 12.0}]},
        ],
    }


def respond(args: list) -> tuple:
    """Svaret på ett anrop: (felkod, JSON, feltext). Används av programmet och av låtsasbiblioteket."""
    global APPROX
    APPROX = any(a == "youtubetab:approximate_date" for a in args)
    fast = any("player_client=visionos" in a for a in args)
    url = args[-1]
    if url.startswith("ytsearch"):
        return 0, {"_type": "playlist", "entries": [video_entry(i) for i in range(1, 6)] + [video_entry(99, short=True)]}, ""
    if "results?search_query" in url:
        return 0, {"_type": "playlist", "entries": [
            {"_type": "url", "ie_key": "YoutubeTab", "id": CID, "channel_id": CID, "title": "Testkanalen",
             "channel": "Testkanalen", "channel_follower_count": 12345,
             "thumbnails": [{"url": "//yt3.ggpht.com/avatar=s88", "width": 88, "height": 88}]},
            {"_type": "url", "ie_key": "YoutubeTab", "id": OTHER, "channel_id": OTHER, "title": "Annan kanal",
             "thumbnails": [{"url": "https://yt3.ggpht.com/other=s88", "width": 88, "height": 88}]},
        ]}, ""
    if "/channel/" in url:
        return 0, {
            "_type": "playlist", "id": CID, "channel_id": CID, "channel": "Testkanalen", "title": "Testkanalen - Videos",
            "description": "Om kanalen.", "channel_follower_count": 12345,
            "thumbnails": [
                {"url": "https://yt3.googleusercontent.com/banner=w1060", "width": 1060, "height": 175, "id": "banner"},
                {"url": "https://yt3.googleusercontent.com/avatar=s900", "width": 900, "height": 900, "id": "avatar_uncropped"},
            ],
            # Den första finns också i RSS-flödet (där med exakt datum).
            "entries": [video_entry(0, vid="abcdefghijk")] + [video_entry(i) for i in range(1, 4)],
        }, ""
    if "watch?v=" in url:
        vid = url.split("v=", 1)[1]
        if vid == "privatevid0":
            return 1, None, "ERROR: [youtube] privatevid0: Private video. Sign in if you've been granted access\n"
        out = info(vid)
        mode = os.environ.get("FAKE_YT_FAST", "")
        if fast and mode == "fail":
            return 1, None, f"ERROR: [youtube] {vid}: No video formats found!\n"
        if fast:
            # visionOS-klienten: bara det som går att spela (som riktiga svaret utan JavaScript),
            # och inget uppladdningsdatum.
            out.pop("timestamp", None)
            out["formats"] = [f for f in out["formats"] if f["format_id"] in ("134", "140") or f.get("format_note") == "storyboard"]
            if mode == "low":
                # Snabbstarten ger sämre kvalitet än den vanliga hämtningen (bara 144p).
                v134 = next(f for f in out["formats"] if f["format_id"] == "134")
                out["formats"] = [dict(v134, format_id="160", height=144, width=256)] + [
                    f for f in out["formats"] if f["format_id"] != "134"]
            if mode == "403":
                # YouTube nekar snabbstartens adresser.
                out["formats"] = [dict(f, url=f["url"] + "&fast=1") if f.get("protocol") == "https" else f for f in out["formats"]]
        out["_mode"] = "fast" if fast else "full"
        return 0, out, ""
    return 1, None, "ERROR: Unsupported URL: " + url + "\n"


def main() -> int:
    args = sys.argv[1:]
    if "--version" in args:
        print("2099.01.01")
        return 0
    code, out, err = respond(args)
    if code:
        sys.stderr.write(err)
        return code
    print(json.dumps(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
