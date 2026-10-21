"""
Låtsas-Plex och låtsas-IPTV för att prova JPTV Web lokalt (skärmbilder, spelaren, live).
Kör: python -m tests.fake_plex 32400
"""
from __future__ import annotations

import asyncio
import hashlib
import io
import os
import random
import subprocess
import sys
import tempfile
import time
from urllib.parse import unquote

from aiohttp import web

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
FONT_REG = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
NOW = int(time.time())

MOVIES = [
    ("Nordljus", 2023, ["Drama", "Romantik"], 7.6), ("Den sista vintern", 2021, ["Drama", "Thriller"], 7.9),
    ("Kustlinjen", 2022, ["Thriller", "Kriminal"], 7.1), ("Midnattståget", 2019, ["Thriller", "Mysterium"], 6.8),
    ("Stormvarning", 2024, ["Action", "Äventyr"], 6.4), ("Fyrvaktaren", 2018, ["Drama"], 8.1),
    ("Glaciären", 2020, ["Dokumentär"], 8.3), ("Orbit 9", 2023, ["Sci-fi", "Action"], 7.2),
    ("Sommaren på Gotland", 2017, ["Komedi", "Romantik"], 6.2), ("Skuggspel", 2022, ["Thriller", "Kriminal"], 7.4),
    ("Järnvägen", 2016, ["Drama", "Historia"], 7.7), ("Tidvatten", 2021, ["Sci-fi", "Drama"], 7.0),
    ("Röda stugan", 2015, ["Skräck"], 5.9), ("Vargavinter", 2024, ["Action", "Thriller"], 6.9),
    ("Alla dessa dagar", 2019, ["Drama", "Komedi"], 7.3), ("Ljusets hastighet", 2023, ["Sci-fi"], 7.8),
    ("Hamnen", 2020, ["Kriminal", "Drama"], 7.5), ("Fjällräddningen", 2022, ["Action", "Äventyr"], 6.6),
    ("Kaffe och cigaretter", 2014, ["Komedi"], 6.7), ("Nattfjäril", 2021, ["Mysterium", "Thriller"], 7.1),
    ("Saltvatten", 2018, ["Drama"], 7.4), ("Stjärnfall", 2024, ["Sci-fi", "Äventyr"], 7.0),
    ("Bron över älven", 2017, ["Historia", "Drama"], 8.0), ("Snötäcke", 2023, ["Familj", "Komedi"], 6.5),
]
SHOWS = [
    ("Skärgårdsmorden", 2021, ["Kriminal", "Drama"], 7.8, 3), ("Rymdstationen", 2022, ["Sci-fi"], 8.2, 2),
    ("Familjen Berg", 2019, ["Komedi"], 7.0, 4), ("Gränsland", 2023, ["Thriller", "Drama"], 8.0, 2),
    ("Polarnatt", 2020, ["Mysterium", "Thriller"], 7.6, 3), ("Köket", 2024, ["Dokumentär"], 7.9, 1),
    ("Kungsleden", 2018, ["Äventyr", "Dokumentär"], 8.4, 2), ("Hotell Vågen", 2022, ["Komedi", "Drama"], 7.2, 2),
]
SUMMARY = (
    "När en gammal hemlighet dyker upp igen tvingas en ovanlig grupp människor samarbeta. "
    "Vädret slår om, vägarna stängs och ingen kan längre lita på någon annan."
)

items: dict[str, dict] = {}
children: dict[str, list[str]] = {}
order: list[str] = []
rk = 1000


def nk() -> str:
    global rk
    rk += 1
    return str(rk)


def build() -> None:
    rnd = random.Random(7)
    for i, (title, year, genres, rating) in enumerate(MOVIES):
        k = nk()
        viewed = rnd.random() < 0.3
        items[k] = {
            "ratingKey": k, "key": f"/library/metadata/{k}", "type": "movie", "title": title, "titleSort": title,
            "year": year, "summary": SUMMARY, "thumb": f"/library/metadata/{k}/thumb/1", "art": f"/library/metadata/{k}/art/1",
            "duration": 5400000 + i * 60000, "librarySectionID": 1, "contentRating": "15", "studio": "Nordisk Film",
            "addedAt": NOW - i * 86400 * 3, "viewCount": 1 if viewed else 0, "lastViewedAt": NOW - i * 86400 if viewed else None,
            "viewOffset": 2400000 if i in (2, 7) else None,
            "Genre": [{"tag": g} for g in genres], "Director": [{"tag": ["Anna Lund", "Erik Holm", "Sara Nyberg"][i % 3]}],
            "Role": [{"tag": n, "role": r} for n, r in [("Lisa Ek", "Maja"), ("Johan Berg", "Nils"), ("Karin Sjö", "Eva"), ("Per Ström", "Olle")]],
            "Rating": [{"image": "imdb://image.rating", "value": rating, "type": "audience"}, {"image": "rottentomatoes://image.rating.ripe", "value": min(10, rating + 1.1), "type": "critic"}],
            "Guid": [{"id": f"imdb://tt{1000000 + i}"}],
            "Media": [{"videoResolution": "4k" if i % 4 == 0 else "1080", "audioChannels": 6, "videoCodec": "hevc" if i % 4 == 0 else "h264", "audioCodec": "eac3",
                       "Part": [{"id": int(k) * 10, "key": f"/library/parts/{k}/file.mkv", "container": "mkv",
                                 "Stream": [{"id": 1, "streamType": 1, "codec": "h264", "height": 2160 if i % 4 == 0 else 1080, "colorTrc": "smpte2084" if i % 4 == 0 else None},
                                            {"id": 2, "streamType": 2, "codec": "eac3", "channels": 6, "selected": True, "displayTitle": "Svenska (EAC3 5.1)", "extendedDisplayTitle": "Svenska (EAC3 5.1)"},
                                            {"id": 3, "streamType": 2, "codec": "aac", "channels": 2, "displayTitle": "Engelska (AAC Stereo)"},
                                            {"id": 4, "streamType": 3, "codec": "srt", "displayTitle": "Svenska (SRT)"},
                                            {"id": 5, "streamType": 3, "codec": "srt", "displayTitle": "Engelska (SRT)"}]}]}],
            "Marker": [],
        }
        order.append(k)
    for i, (title, year, genres, rating, nseasons) in enumerate(SHOWS):
        sk = nk()
        show = {
            "ratingKey": sk, "key": f"/library/metadata/{sk}/children", "type": "show", "title": title, "titleSort": title, "year": year,
            "summary": SUMMARY, "thumb": f"/library/metadata/{sk}/thumb/1", "art": f"/library/metadata/{sk}/art/1", "librarySectionID": 2,
            "contentRating": "15", "addedAt": NOW - i * 86400 * 2, "childCount": nseasons, "leafCount": nseasons * 6,
            "viewedLeafCount": 8 if i in (0, 1, 3) else 0, "theme": f"/library/metadata/{sk}/theme/1",
            "Genre": [{"tag": g} for g in genres], "Role": [{"tag": "Lisa Ek", "role": "Kommissarie"}, {"tag": "Johan Berg", "role": "Viktor"}],
            "Rating": [{"image": "imdb://image.rating", "value": rating, "type": "audience"}],
            "lastViewedAt": NOW - 86400 * (i + 1) if i in (0, 1, 3) else None,
        }
        items[sk] = show
        order.append(sk)
        children[sk] = []
        epno = 0
        for s in range(1, nseasons + 1):
            seak = nk()
            items[seak] = {"ratingKey": seak, "type": "season", "title": f"Säsong {s}", "index": s, "parentRatingKey": sk, "parentTitle": title,
                           "thumb": show["thumb"], "art": show["art"], "leafCount": 6, "viewedLeafCount": 6 if (i in (0, 1, 3) and s == 1) else (2 if i in (0, 1, 3) and s == 2 else 0),
                           "librarySectionID": 2, "summary": f"Säsong {s} av {title}."}
            children[sk].append(seak)
            children[seak] = []
            for e in range(1, 7):
                epno += 1
                ek = nk()
                watched = i in (0, 1, 3) and (s == 1 or (s == 2 and e <= 2))
                items[ek] = {
                    "ratingKey": ek, "type": "episode", "title": ["Ankomsten", "Stormen", "Fyren", "Sanningen", "Flykten", "Hemkomsten"][e - 1],
                    "index": e, "parentIndex": s, "parentRatingKey": seak, "grandparentRatingKey": sk, "grandparentTitle": title,
                    "grandparentThumb": show["thumb"], "grandparentArt": show["art"], "thumb": f"/library/metadata/{ek}/thumb/1",
                    "summary": "Ett nytt spår leder utredningen åt ett oväntat håll, samtidigt som någon i gruppen döljer något.",
                    "duration": 70000, "librarySectionID": 2, "viewCount": 1 if watched else 0, "contentRating": "15",
                    "viewOffset": 30000 if (i == 0 and s == 2 and e == 3) else None, "originallyAvailableAt": f"{year + s - 1}-0{min(9, e)}-1{e}",
                    "addedAt": NOW - epno * 3600, "grandparentTheme": show["theme"],
                    "Media": [{"videoResolution": "1080", "audioChannels": 2, "Part": [{"id": int(ek) * 10, "key": f"/library/parts/{ek}/file.mkv",
                               "Stream": [{"id": 1, "streamType": 1, "height": 1080}, {"id": 2, "streamType": 2, "channels": 2, "selected": True, "displayTitle": "Svenska (AAC)"}]}]}],
                    "Marker": [{"type": "intro", "startTimeOffset": 5000, "endTimeOffset": 20000}, {"type": "credits", "startTimeOffset": 50000, "endTimeOffset": 60000, "final": True}],
                }
                children[seak].append(ek)
    # Trailer som extra för första filmen.
    tk = nk()
    items[tk] = {"ratingKey": tk, "type": "clip", "title": "Trailer", "extraType": 1, "duration": 60000,
                 "Media": [{"Part": [{"key": f"/library/parts/{tk}/trailer.mp4"}]}]}
    items[order[0]]["Extras"] = [tk]


def mc(metadata=None, **extra) -> web.Response:
    c = {"size": len(metadata or [])}
    if metadata is not None:
        c["Metadata"] = metadata
    c.update(extra)
    return web.json_response({"MediaContainer": c})


def strip(o: dict) -> dict:
    return {k: v for k, v in o.items() if k not in ("Extras",)}


def section_items(sec: int) -> list[dict]:
    return [strip(items[k]) for k in order if items[k].get("librarySectionID") == sec]


# ------------------------------------------------------------------ bilder


def color_for(key: str) -> tuple:
    h = int(hashlib.md5(key.encode()).hexdigest(), 16)
    palette = [(44, 62, 99), (94, 48, 78), (36, 92, 87), (120, 70, 40), (70, 45, 110), (30, 70, 120), (110, 40, 45), (60, 90, 50)]
    return palette[h % len(palette)]


def draw_image(key: str, w: int, h: int, title: str | None, kind: str) -> bytes:
    from PIL import Image, ImageDraw, ImageFilter, ImageFont

    base = color_for(key)
    img = Image.new("RGB", (w, h), base)
    d = ImageDraw.Draw(img)
    rnd = random.Random(key)
    for _ in range(9):
        r = rnd.randint(min(w, h) // 6, max(w, h) // 2)
        x, y = rnd.randint(-r, w), rnd.randint(-r, h)
        c = tuple(min(255, max(0, v + rnd.randint(-50, 70))) for v in base)
        d.ellipse((x, y, x + r, y + r), fill=c)
    img = img.filter(ImageFilter.GaussianBlur(radius=max(w, h) / 22))
    d = ImageDraw.Draw(img)
    # Horisont och "landskap" för bakgrunder.
    if kind == "art":
        for i in range(5):
            y0 = int(h * (0.55 + i * 0.08))
            pts = [(0, h)] + [(x, y0 + int(30 * ((x * (i + 3)) % 97) / 97)) for x in range(0, w + 60, 60)] + [(w, h)]
            shade = tuple(max(0, v - 30 - i * 12) for v in base)
            d.polygon(pts, fill=shade)
        d.ellipse((int(w * 0.68), int(h * 0.16), int(w * 0.68) + h // 5, int(h * 0.16) + h // 5), fill=(250, 220, 170))
    if title and kind == "thumb":
        size = max(14, w // 9)
        font = ImageFont.truetype(FONT, size)
        words, lines, line = title.split(), [], ""
        for wd in words:
            t = (line + " " + wd).strip()
            if d.textlength(t, font=font) > w * 0.82 and line:
                lines.append(line)
                line = wd
            else:
                line = t
        lines.append(line)
        y = h - len(lines) * (size + 6) - h // 10
        for ln in lines:
            tw = d.textlength(ln, font=font)
            d.text(((w - tw) / 2 + 2, y + 2), ln, font=font, fill=(0, 0, 0))
            d.text(((w - tw) / 2, y), ln, font=font, fill=(245, 240, 230))
            y += size + 6
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=82)
    return buf.getvalue()


def draw_logo(title: str) -> bytes:
    from PIL import Image, ImageDraw, ImageFont

    font = ImageFont.truetype(FONT, 96)
    tmp = ImageDraw.Draw(Image.new("RGBA", (10, 10)))
    tw = int(tmp.textlength(title.upper(), font=font))
    img = Image.new("RGBA", (tw + 20, 130), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.text((10, 10), title.upper(), font=font, fill=(250, 246, 236, 255))
    buf = io.BytesIO()
    img.save(buf, "PNG")
    return buf.getvalue()


# ------------------------------------------------------------------ server


def make_app(media_dir: str, port: int) -> web.Application:
    app = web.Application()
    r = web.RouteTableDef()

    @r.get("/identity")
    async def identity(_):
        return mc(machineIdentifier="fake")

    @r.get("/library/sections")
    async def sections(_):
        return mc(Directory=[{"key": "1", "title": "Filmer", "type": "movie"}, {"key": "2", "title": "TV-serier", "type": "show"}, {"key": "3", "title": "Musik", "type": "artist"}])

    @r.get("/library/onDeck")
    async def deck(_):
        out = [strip(items[k]) for k in order if items[k].get("viewOffset") and items[k]["type"] == "movie"]
        eps = [strip(v) for v in items.values() if v["type"] == "episode" and v.get("viewOffset")]
        nxt = [strip(items[children[children[order[len(MOVIES) + 1]][1]][2]])]
        return mc(eps + out + nxt)

    @r.get("/library/sections/{s}/recentlyAdded")
    async def recent(req):
        s = int(req.match_info["s"])
        if s == 2:
            eps = sorted((v for v in items.values() if v["type"] == "episode"), key=lambda e: -e["addedAt"])[:20]
            return mc([strip(e) for e in eps])
        return mc(sorted(section_items(s), key=lambda x: -x["addedAt"])[:30])

    @r.get("/library/sections/{s}/all")
    async def all_items(req):
        s = int(req.match_info["s"])
        lst = section_items(s)
        g = req.query.get("genre")
        if g:
            lst = [x for x in lst if any(t["tag"] == unquote(g) for t in x.get("Genre", []))]
        if req.query.get("unwatched"):
            lst = [x for x in lst if not x.get("viewCount")]
        sort = req.query.get("sort", "titleSort")
        if sort.startswith("addedAt"):
            lst.sort(key=lambda x: -x["addedAt"])
        elif sort.startswith("year"):
            lst.sort(key=lambda x: -x["year"])
        else:
            lst.sort(key=lambda x: x["titleSort"])
        return mc(lst)

    @r.get("/library/sections/{s}/genre")
    async def genres(req):
        s = int(req.match_info["s"])
        gs = sorted({t["tag"] for x in section_items(s) for t in x.get("Genre", [])})
        return mc(Directory=[{"key": g, "title": g} for g in gs])

    @r.get("/library/sections/{s}/collections")
    async def collections(req):
        if req.match_info["s"] != "1":
            return mc([])
        return mc([{"ratingKey": "900", "type": "collection", "title": "Nordiska thrillers", "thumb": "/library/collections/900/thumb/1", "childCount": 4}])

    @r.get("/library/collections/{c}/children")
    async def coll_children(_):
        return mc([strip(items[k]) for k in order[1:5]])

    @r.get("/hubs/sections/{s}")
    async def hubs(req):
        s = int(req.match_info["s"])
        lst = section_items(s)
        return mc(Hub=[
            {"hubIdentifier": "movie.toprated" if s == 1 else "show.toprated", "title": "Högst betyg", "Metadata": sorted(lst, key=lambda x: -x["Rating"][0]["value"])[:12]},
            {"hubIdentifier": "movie.recentlyreleased", "title": "Nyligen släppta", "Metadata": sorted(lst, key=lambda x: -x["year"])[:12]},
        ])

    @r.get("/status/sessions/history/all")
    async def history(_):
        out = []
        for v in items.values():
            if v.get("viewCount"):
                e = {"ratingKey": v["ratingKey"], "type": v["type"], "viewedAt": v.get("lastViewedAt") or NOW - 86400 * 5}
                if v["type"] == "episode":
                    e["grandparentRatingKey"] = v["grandparentRatingKey"]
                out.append(e)
        return mc(out)

    @r.get("/library/metadata/{k}")
    async def metadata(req):
        k = req.match_info["k"]
        if k == "900":
            return mc([{"ratingKey": "900", "type": "collection", "title": "Nordiska thrillers", "summary": "Mörka nätter och kalla fall.", "thumb": "/library/collections/900/thumb/1"}])
        if k not in items:
            raise web.HTTPNotFound()
        o = strip(items[k])
        o["Image"] = [{"type": "clearLogo", "url": f"/library/metadata/{k}/clearLogo/1"}] if o["type"] in ("movie", "show") else []
        if o["type"] == "show":
            deck_ep = next((items[e] for s in children[k] for e in children[s] if not items[e].get("viewCount")), None)
            if deck_ep:
                o["OnDeck"] = {"Metadata": [strip(deck_ep)]}
        if items[k].get("Extras"):
            o["Extras"] = {"Metadata": [items[t] for t in items[k]["Extras"]]}
        if o["type"] in ("movie", "show"):
            same = [strip(items[x]) for x in order if items[x]["type"] == o["type"] and x != k][:10]
            o["Related"] = {"Hub": [{"title": "Mer som detta", "Metadata": same}]}
        return mc([o])

    @r.get("/library/metadata/{k}/children")
    async def kids(req):
        return mc([strip(items[c]) for c in children.get(req.match_info["k"], [])])

    @r.get("/library/metadata/{k}/allLeaves")
    async def leaves(req):
        return mc([strip(items[e]) for s in children.get(req.match_info["k"], []) for e in children.get(s, [])])

    @r.get("/hubs/search")
    async def search(req):
        q = req.query.get("query", "").lower()
        movies = [strip(items[k]) for k in order if items[k]["type"] == "movie" and q in items[k]["title"].lower()]
        shows = [strip(items[k]) for k in order if items[k]["type"] == "show" and q in items[k]["title"].lower()]
        return mc(Hub=[{"title": "Filmer", "type": "movie", "Metadata": movies}, {"title": "Serier", "type": "show", "Metadata": shows}])

    @r.get("/photo/:/transcode")
    async def photo(req):
        url = req.query.get("url", "")
        w, h = int(req.query.get("width", 300)), int(req.query.get("height", 450))
        parts = url.strip("/").split("/")
        key = parts[2] if len(parts) > 2 else url
        kind = "art" if "/art/" in url else ("still" if items.get(key, {}).get("type") == "episode" else "thumb")
        title = items.get(key, {}).get("title") if kind == "thumb" else None
        data = await asyncio.to_thread(draw_image, url, w, h, title, kind)
        return web.Response(body=data, content_type="image/jpeg", headers={"Cache-Control": "max-age=3600"})

    @r.get("/library/metadata/{k}/clearLogo/{n}")
    async def logo(req):
        k = req.match_info["k"]
        return web.Response(body=draw_logo(items[k]["title"]), content_type="image/png")

    @r.get("/library/metadata/{k}/theme/{n}")
    async def theme(_):
        return web.FileResponse(os.path.join(media_dir, "theme.mp3"))

    @r.get("/library/parts/{k}/trailer.mp4")
    async def trailer(_):
        return web.FileResponse(os.path.join(media_dir, "trailer.mp4"))

    @r.get("/video/:/transcode/universal/decision")
    async def decision(_):
        return mc([{"Media": [{"Part": [{"Stream": [{"streamType": 1, "decision": "copy"}, {"streamType": 2, "decision": "transcode"}]}]}]}], generalDecisionText="Direct Stream")

    @r.get("/video/:/transcode/universal/start.m3u8")
    async def start(req):
        if not req.headers.get("X-Plex-Token"):
            raise web.HTTPUnauthorized()
        sid = req.query.get("session", "s")
        # Riktiga Plex ger H.264/AAC. Testwebbläsaren (Chromium utan H.264) får VP9/Opus i stället.
        return web.Response(text=f"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720\nsession/{sid}/base/index.m3u8\n", content_type="application/vnd.apple.mpegurl")

    @r.get("/video/:/transcode/universal/session/{sid}/base/{name}")
    async def session_file(req):
        if not req.headers.get("X-Plex-Token"):
            raise web.HTTPUnauthorized()
        return web.FileResponse(os.path.join(media_dir, "vod9", req.match_info["name"]))

    @r.get("/video/:/transcode/universal/stop")
    @r.get("/:/timeline")
    @r.get("/:/scrobble")
    @r.get("/:/unscrobble")
    async def ok(_):
        return web.Response(text="ok")

    @r.put("/library/parts/{p}")
    @r.put("/actions/removeFromContinueWatching")
    async def put_ok(_):
        return web.Response(text="ok")

    # ---- IPTV
    @r.get("/iptv.m3u")
    async def m3u(req):
        base = f"http://{req.host}"
        groups = {"Sverige": ["SVT1 HD", "SVT2 HD", "TV4 HD", "Kanal 5 HD", "TV6", "Sjuan", "TV8", "Kunskapskanalen"],
                  "Sport": ["Sportkanalen 1", "Sportkanalen 2", "Fotboll Live", "Motorsport HD"],
                  "Nyheter": ["Nyhetskanalen", "World News"], "Film": ["Filmkanalen", "Klassiker"]}
        lines = [f'#EXTM3U url-tvg="{base}/epg.xml"']
        n = 0
        for g, chans in groups.items():
            for c in chans:
                n += 1
                cid = c.lower().replace(" ", "")
                lines.append(f'#EXTINF:-1 tvg-id="{cid}.se" tvg-name="{c}" tvg-logo="{base}/logo/{cid}.png" group-title="{g}",{c}')
                lines.append(f"{base}/live/{n}.ts")
        return web.Response(text="\n".join(lines) + "\n")

    @r.get("/logo/{name}")
    async def ch_logo(req):
        from PIL import Image, ImageDraw, ImageFont

        name = req.match_info["name"].rsplit(".", 1)[0]
        img = Image.new("RGBA", (160, 90), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        c = color_for(name)
        d.rounded_rectangle((4, 14, 156, 76), radius=12, fill=tuple(min(255, v + 60) for v in c) + (255,))
        font = ImageFont.truetype(FONT, 26)
        label = name[:6].upper()
        tw = d.textlength(label, font=font)
        d.text(((160 - tw) / 2, 30), label, font=font, fill=(255, 255, 255, 255))
        buf = io.BytesIO()
        img.save(buf, "PNG")
        return web.Response(body=buf.getvalue(), content_type="image/png")

    @r.get("/epg.xml")
    async def epg(_):
        titles = ["Rapport", "Go'kväll", "Sportnytt", "Dokument inifrån", "Morden i Sandhamn", "Fotboll: Allsvenskan", "Väder", "Nattfilm", "Antikrundan", "Melodifestivalen", "Skavlan", "Nyheter"]
        start = (NOW // 1800) * 1800 - 3600
        out = ['<?xml version="1.0" encoding="UTF-8"?><tv>']
        names = ["svt1hd", "svt2hd", "tv4hd", "kanal5hd", "tv6", "sjuan", "tv8", "kunskapskanalen", "sportkanalen1", "sportkanalen2", "fotbolllive", "motorsporthd", "nyhetskanalen", "worldnews", "filmkanalen", "klassiker"]
        for ci, cid in enumerate(names):
            out.append(f'<channel id="{cid}.se"><display-name>{cid}</display-name></channel>')
            t = start - (ci % 3) * 900
            rnd = random.Random(ci)
            while t < start + 16 * 3600:
                dur = rnd.choice([1800, 2700, 3600, 5400])
                s = time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(t))
                e = time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(t + dur))
                title = rnd.choice(titles)
                out.append(f'<programme start="{s}" stop="{e}" channel="{cid}.se"><title>{title}</title><desc>Program om {title.lower()} & mycket mer i kvällens sändning.</desc></programme>')
                t += dur
        out.append("</tv>")
        return web.Response(text="\n".join(out), content_type="application/xml")

    @r.get("/live/{n}.ts")
    async def live(req):
        n = int(req.match_info["n"])
        path = os.path.join(media_dir, "hevc.ts" if n == 4 else "live.ts")
        resp = web.StreamResponse(headers={"Content-Type": "video/mp2t"})
        await resp.prepare(req)
        try:
            while True:
                with open(path, "rb") as f:
                    while chunk := f.read(188 * 700):
                        await resp.write(chunk)
                        await asyncio.sleep(0.1)
        except (ConnectionResetError, asyncio.CancelledError):
            pass
        return resp

    app.add_routes(r)
    return app


def make_media(d: str) -> None:
    def run(*args):
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", *args], check=True)

    if not os.path.exists(os.path.join(d, "vod", "index.m3u8")):
        os.makedirs(os.path.join(d, "vod"), exist_ok=True)
        run("-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=25:duration=60", "-f", "lavfi", "-i", "sine=frequency=330:duration=60",
            "-c:v", "libx264", "-preset", "veryfast", "-g", "50", "-c:a", "aac", "-f", "hls", "-hls_time", "4", "-hls_playlist_type", "vod",
            "-hls_segment_filename", os.path.join(d, "vod", "%05d.ts"), os.path.join(d, "vod", "index.m3u8"))
    if not os.path.exists(os.path.join(d, "vod9", "index.m3u8")):
        os.makedirs(os.path.join(d, "vod9"), exist_ok=True)
        run("-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=25:duration=70", "-f", "lavfi", "-i", "sine=frequency=330:duration=70",
            "-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-b:v", "1500k", "-g", "50", "-c:a", "libopus",
            "-f", "hls", "-hls_time", "4", "-hls_playlist_type", "vod", "-hls_segment_type", "fmp4",
            "-hls_segment_filename", os.path.join(d, "vod9", "%05d.m4s"), os.path.join(d, "vod9", "index.m3u8"))
    if not os.path.exists(os.path.join(d, "live.ts")):
        run("-f", "lavfi", "-i", "smptehdbars=size=1280x720:rate=25:duration=20", "-f", "lavfi", "-i", "sine=frequency=500:duration=20",
            "-vf", "drawtext=fontfile=" + FONT + ":text='JPTV LIVE %{pts\\:hms}':fontsize=48:fontcolor=white:x=40:y=40",
            "-c:v", "libx264", "-preset", "veryfast", "-g", "25", "-c:a", "mp2", "-f", "mpegts", os.path.join(d, "live.ts"))
    if not os.path.exists(os.path.join(d, "hevc.ts")):
        run("-f", "lavfi", "-i", "testsrc=size=1280x720:rate=25:duration=20", "-f", "lavfi", "-i", "sine=frequency=600:duration=20",
            "-c:v", "libx265", "-preset", "ultrafast", "-x265-params", "log-level=error", "-c:a", "mp2", "-f", "mpegts", os.path.join(d, "hevc.ts"))
    if not os.path.exists(os.path.join(d, "trailer.mp4")):
        run("-f", "lavfi", "-i", "mandelbrot=size=1280x720:rate=25", "-t", "20", "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p", "-movflags", "+faststart", os.path.join(d, "trailer.mp4"))
    if not os.path.exists(os.path.join(d, "theme.mp3")):
        run("-f", "lavfi", "-i", "sine=frequency=262:duration=15", "-c:a", "libmp3lame", os.path.join(d, "theme.mp3"))


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 32400
    media = sys.argv[2] if len(sys.argv) > 2 else os.path.join(tempfile.gettempdir(), "fake-plex-media")
    os.makedirs(media, exist_ok=True)
    make_media(media)
    build()
    web.run_app(make_app(media, port), port=port, access_log=None)
