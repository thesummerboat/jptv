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
        ],
    }


def main() -> int:
    global APPROX
    args = sys.argv[1:]
    APPROX = any(a == "youtubetab:approximate_date" for a in args)
    if "--version" in args:
        print("2099.01.01")
        return 0
    url = args[-1]
    if url.startswith("ytsearch"):
        out = {"_type": "playlist", "entries": [video_entry(i) for i in range(1, 6)] + [video_entry(99, short=True)]}
    elif "results?search_query" in url:
        out = {"_type": "playlist", "entries": [
            {"_type": "url", "ie_key": "YoutubeTab", "id": CID, "channel_id": CID, "title": "Testkanalen",
             "channel": "Testkanalen", "channel_follower_count": 12345,
             "thumbnails": [{"url": "//yt3.ggpht.com/avatar=s88", "width": 88, "height": 88}]},
            {"_type": "url", "ie_key": "YoutubeTab", "id": OTHER, "channel_id": OTHER, "title": "Annan kanal",
             "thumbnails": [{"url": "https://yt3.ggpht.com/other=s88", "width": 88, "height": 88}]},
        ]}
    elif "/channel/" in url:
        out = {
            "_type": "playlist", "id": CID, "channel_id": CID, "channel": "Testkanalen", "title": "Testkanalen - Videos",
            "description": "Om kanalen.", "channel_follower_count": 12345,
            "thumbnails": [
                {"url": "https://yt3.googleusercontent.com/banner=w1060", "width": 1060, "height": 175, "id": "banner"},
                {"url": "https://yt3.googleusercontent.com/avatar=s900", "width": 900, "height": 900, "id": "avatar_uncropped"},
            ],
            # Den första finns också i RSS-flödet (där med exakt datum).
            "entries": [video_entry(0, vid="abcdefghijk")] + [video_entry(i) for i in range(1, 4)],
        }
    elif "watch?v=" in url:
        vid = url.split("v=", 1)[1]
        if vid == "privatevid0":
            sys.stderr.write("ERROR: [youtube] privatevid0: Private video. Sign in if you've been granted access\n")
            return 1
        out = info(vid)
    else:
        sys.stderr.write("ERROR: Unsupported URL: " + url + "\n")
        return 1
    print(json.dumps(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
