"""JPTV Web: Plex och live-TV i webbläsaren på hemmanätet. Startas med `python -m app.main`."""
from __future__ import annotations

import asyncio
import json
import logging
import os
import random
import re
import tempfile
import time
from urllib.parse import quote, urlencode

import aiohttp
from aiohttp import web

from .iptv import LiveStream
from .library import Library
from .plex import QUALITIES, Plex, PlexError, public
from .store import Store
from .tv import Iptv

VERSION = os.environ.get("JPTV_VERSION", "dev")
STATIC = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "static")
PROXY_PREFIXES = ("video/:/transcode/universal/", "library/parts/", "library/metadata/", "photo/:/transcode")
log = logging.getLogger("jptv")

routes = web.RouteTableDef()
JPTV = web.AppKey("jptv", dict)


def ctx(request: web.Request) -> dict:
    return request.app[JPTV]


async def body(request: web.Request) -> dict:
    try:
        text = await request.text()
        return json.loads(text) if text else {}
    except ValueError:
        return {}


@web.middleware
async def errors(request: web.Request, handler):
    try:
        return await handler(request)
    except PlexError as e:
        return web.json_response({"error": str(e)}, status=502)
    except (aiohttp.ClientError, asyncio.TimeoutError):
        return web.json_response({"error": "Plex-servern svarar inte"}, status=504)


# ------------------------------------------------------------------ sidan


@routes.get("/")
async def index(request: web.Request):
    return web.Response(text=ctx(request)["index_html"], content_type="text/html", headers={"Cache-Control": "no-cache"})


@routes.get("/api/status")
async def status(request: web.Request):
    c = ctx(request)
    server = c["store"].get("server")
    return web.json_response(
        {
            "version": VERSION,
            "plex": bool(server),
            "server": server and {"name": server.get("name"), "uri": server.get("uri")},
            "iptv": c["iptv"].status(),
            "live": c["live"].status(),
            "settings": settings_public(c),
        }
    )


def settings_public(c: dict) -> dict:
    st = c["store"]
    return {
        "m3uUrl": st.get("m3u_url", ""),
        "epgUrl": st.get("epg_url", ""),
        "quality": st.get("quality", 1),
        "qualities": [q[0] for q in QUALITIES],
        "liveTranscode": st.get("live_transcode", "auto"),
        "themeLevel": st.get("theme_level", 1),
    }


@routes.post("/api/settings")
async def save_settings(request: web.Request):
    c = ctx(request)
    st = c["store"]
    b = await body(request)
    changes: dict = {}
    if "quality" in b:
        changes["quality"] = max(0, min(int(b["quality"]), len(QUALITIES) - 1))
    if b.get("liveTranscode") in ("auto", "always", "never"):
        changes["live_transcode"] = b["liveTranscode"]
    if "themeLevel" in b:
        changes["theme_level"] = max(0, min(int(b["themeLevel"]), 2))
    iptv_changed = False
    for key, name in (("m3uUrl", "m3u_url"), ("epgUrl", "epg_url")):
        if key in b:
            value = str(b[key] or "").strip()
            if value != st.get(name, ""):
                changes[name] = value
                iptv_changed = True
    if "serverUri" in b and b["serverUri"]:
        uri = str(b["serverUri"]).strip().rstrip("/")
        server = st.get("server")
        if not server:
            raise web.HTTPBadRequest(text="Logga in på Plex först")
        if not re.match(r"^https?://", uri):
            uri = "http://" + uri
        if not await c["plex"]._reachable(uri, server["token"]):
            return web.json_response({"error": "Plex svarar inte på den adressen"}, status=400)
        changes["server"] = server | {"uri": uri}
    if changes:
        st.set(**changes)
    if iptv_changed:
        asyncio.create_task(c["iptv"].refresh())
    return web.json_response(settings_public(c))


# ------------------------------------------------------------------ Plex-inloggning


@routes.post("/api/login/start")
async def login_start(request: web.Request):
    return web.json_response(await ctx(request)["plex"].start_login())


@routes.get("/api/login/check")
async def login_check(request: web.Request):
    c = ctx(request)
    server = await c["plex"].check_login(int(request.query["id"]))
    if server:
        c["library"].invalidate()
    return web.json_response({"done": bool(server), "server": server and server.get("name")})


@routes.post("/api/logout")
async def logout(request: web.Request):
    c = ctx(request)
    c["plex"].logout()
    c["library"].invalidate()
    return web.json_response({"ok": True})


# ------------------------------------------------------------------ bibliotek


@routes.get("/api/sections")
async def sections(request: web.Request):
    return web.json_response(await ctx(request)["plex"].sections())


@routes.get("/api/home")
async def home(request: web.Request):
    return web.json_response(await ctx(request)["library"].home())


@routes.get("/api/section/{key}/rows")
async def section_rows(request: web.Request):
    return web.json_response(await ctx(request)["library"].section(request.match_info["key"]))


SORTS = {"title": "titleSort", "added": "addedAt:desc", "year": "year:desc,titleSort", "rating": "audienceRating:desc", "release": "originallyAvailableAt:desc"}


@routes.get("/api/section/{key}/items")
async def section_items(request: web.Request):
    c = ctx(request)
    q = request.query
    key = request.match_info["key"]
    if q.get("collection"):
        items = await c["plex"].collection_items(q["collection"])
    else:
        sort = q.get("sort", "title")
        items = await c["plex"].all(
            key,
            sort="titleSort" if sort == "imdb" else SORTS.get(sort, "titleSort"),
            unwatched=q.get("unwatched") == "1",
            genre=q.get("genre") or None,
            cache=120,
        )
        if sort == "imdb":
            def imdb(i):
                return next((r["value"] for r in i.get("ratings", []) if r["src"] == "IMDb"), 0)
            items = sorted(items, key=lambda i: -imdb(i))
    return web.json_response({"items": [public(i) for i in items]})


@routes.get("/api/section/{key}/filters")
async def section_filters(request: web.Request):
    c = ctx(request)
    key = request.match_info["key"]
    genres, collections = await asyncio.gather(c["plex"].genres(key), c["plex"].collections(key), return_exceptions=True)
    return web.json_response(
        {
            "genres": genres if isinstance(genres, list) else [],
            "collections": [public(x) for x in collections] if isinstance(collections, list) else [],
        }
    )


@routes.get("/api/item/{key}")
async def item(request: web.Request):
    c = ctx(request)
    key = request.match_info["key"]
    meta = await c["plex"].metadata(key)
    if not meta:
        raise web.HTTPNotFound()
    out = public(meta)
    if meta["type"] == "show":
        seasons = [public(s) for s in await c["plex"].children(key) if s["type"] == "season"]
        out["seasons"] = seasons
    elif meta["type"] == "season":
        out["episodes"] = [public(e) for e in await c["plex"].children(key)]
    elif meta["type"] == "collection":
        out["items"] = [public(e) for e in await c["plex"].collection_items(key)]
    return web.json_response(out)


@routes.get("/api/children/{key}")
async def children(request: web.Request):
    return web.json_response([public(e) for e in await ctx(request)["plex"].children(request.match_info["key"])])


@routes.get("/api/next/{key}")
async def next_episode(request: web.Request):
    c = ctx(request)
    meta = await c["plex"].metadata(request.match_info["key"])
    nxt = await c["plex"].next_episode(meta) if meta else None
    return web.json_response(public(nxt) if nxt else None)


@routes.get("/api/random/{key}")
async def random_episode(request: web.Request):
    """Ett slumpat avsnitt ur serien (Slumpvis)."""
    eps = [e for e in await ctx(request)["plex"].all_leaves(request.match_info["key"]) if e["type"] == "episode"]
    return web.json_response(public(random.choice(eps)) if eps else None)


@routes.get("/api/search")
async def search(request: web.Request):
    q = request.query.get("q", "").strip()
    if not q:
        return web.json_response({"hubs": [], "channels": []})
    c = ctx(request)
    try:
        hubs = await c["plex"].search(q) if c["store"].get("server") else []
    except PlexError:
        hubs = []
    channels = []
    pl = c["iptv"].playlist
    if pl:
        ql = q.lower()
        now = int(time.time() * 1000)
        hidden = set(c["store"].get("hidden_groups", []))
        for ch in pl.channels:
            if ql in ch.name.lower() and ch.group not in hidden:
                channels.append(channel_json(c, ch, now))
                if len(channels) >= 30:
                    break
    return web.json_response({"hubs": hubs, "channels": channels})


@routes.post("/api/play/{key}")
async def play(request: web.Request):
    c = ctx(request)
    key = request.match_info["key"]
    b = await body(request)
    meta = await c["plex"].metadata(key)
    if not meta:
        raise web.HTTPNotFound()
    if meta.get("partId") and ("audio" in b or "subtitle" in b):
        await c["plex"].set_streams(meta["partId"], b.get("audio"), b.get("subtitle"))
        meta = await c["plex"].metadata(key) or meta
    quality = int(b.get("quality", c["store"].get("quality", 1)))
    if b.get("previous"):
        await c["plex"].stop_transcode(str(b["previous"]))
    started = await c["plex"].start_playback(key, quality)
    nxt = None
    if meta["type"] == "episode":
        try:
            n = await c["plex"].next_episode(meta)
            nxt = public(n) if n else None
        except Exception:
            nxt = None
    return web.json_response(started | {"item": public(meta), "next": nxt, "quality": quality})


@routes.get("/api/trailer/{key}")
async def trailer(request: web.Request):
    c = ctx(request)
    meta = await c["plex"].metadata(request.match_info["key"])
    if meta and meta["type"] in ("episode", "season") and meta.get("showKey"):
        meta = await c["plex"].metadata(meta["showKey"])
    for t in (meta or {}).get("trailers") or []:
        part = t.get("partKey")
        if part and part.startswith("http"):
            return web.json_response({"url": part, "title": t.get("title"), "key": t["key"]})
        if part:
            return web.json_response({"url": "/plex" + part, "title": t.get("title"), "key": t["key"]})
    return web.json_response(None)


@routes.post("/api/timeline")
async def timeline(request: web.Request):
    b = await body(request)
    if b.get("key"):
        await ctx(request)["plex"].timeline(str(b["key"]), b.get("state", "playing"), int(b.get("time", 0)), int(b.get("duration", 0)), b.get("session"))
    return web.json_response({"ok": True})


@routes.post("/api/stop")
async def stop(request: web.Request):
    c = ctx(request)
    b = await body(request)
    if b.get("key"):
        await c["plex"].timeline(str(b["key"]), "stopped", int(b.get("time", 0)), int(b.get("duration", 0)), b.get("session"))
    if b.get("session"):
        await c["plex"].stop_transcode(str(b["session"]))
    c["library"].invalidate()
    return web.json_response({"ok": True})


@routes.post("/api/watched/{key}")
async def watched(request: web.Request):
    c = ctx(request)
    b = await body(request)
    await c["plex"].set_watched(request.match_info["key"], bool(b.get("watched", True)))
    c["library"].invalidate()
    return web.json_response({"ok": True})


@routes.post("/api/deck/remove/{key}")
async def deck_remove(request: web.Request):
    await ctx(request)["plex"].remove_from_deck(request.match_info["key"])
    return web.json_response({"ok": True})


# ------------------------------------------------------------------ Plex-proxy (video, bilder, ledmotiv)


@routes.get("/img")
async def image(request: web.Request):
    c = ctx(request)
    server = c["store"].get("server")
    path = request.query.get("p", "")
    if not server or not path:
        raise web.HTTPNotFound()
    w, h = request.query.get("w"), request.query.get("h")
    if w and h:
        url = f"{server['uri']}/photo/:/transcode?" + urlencode({"width": w, "height": h, "minSize": 1, "upscale": 1, "url": path})
    elif path.startswith("http"):
        raise web.HTTPFound(path)
    else:
        url = server["uri"] + path
    return await _proxy(request, url, server["token"], cache="public, max-age=604800")


@routes.get("/plex/{tail:.*}")
async def plex_proxy(request: web.Request):
    c = ctx(request)
    server = c["store"].get("server")
    tail = request.match_info["tail"]
    if not server or not tail.startswith(PROXY_PREFIXES):
        raise web.HTTPNotFound()
    url = f"{server['uri']}/{quote(tail, safe='/:')}"
    if request.query_string:
        url += "?" + request.query_string
    return await _proxy(request, url, server["token"], rewrite_base=server["uri"])


async def _proxy(request: web.Request, url: str, token: str, cache: str | None = None, rewrite_base: str | None = None):
    http: aiohttp.ClientSession = ctx(request)["http"]
    headers = {"X-Plex-Token": token, "Accept": request.headers.get("Accept", "*/*"), "Accept-Encoding": "identity"}
    for h in ("Range", "User-Agent", "If-None-Match", "If-Modified-Since"):
        if h in request.headers:
            headers[h] = request.headers[h]
    try:
        r = await http.get(url, headers=headers, timeout=aiohttp.ClientTimeout(total=None, sock_connect=10, sock_read=90))
    except (aiohttp.ClientError, asyncio.TimeoutError):
        raise web.HTTPBadGateway(text="Plex-servern svarar inte")
    try:
        ctype = r.headers.get("Content-Type", "")
        if rewrite_base and ("mpegurl" in ctype.lower() or url.split("?", 1)[0].endswith(".m3u8")):
            text = await r.text(errors="replace")
            text = text.replace(rewrite_base, "/plex")
            text = re.sub(r"([?&])X-Plex-Token=[^&\s\"]*&?", r"\1", text)
            return web.Response(status=r.status, text=text, content_type="application/vnd.apple.mpegurl", headers={"Cache-Control": "no-cache"})
        resp = web.StreamResponse(status=r.status)
        for h in ("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified"):
            if h in r.headers:
                resp.headers[h] = r.headers[h]
        resp.headers["Cache-Control"] = cache if cache and r.status == 200 else r.headers.get("Cache-Control", "no-cache")
        await resp.prepare(request)
        try:
            async for chunk in r.content.iter_chunked(1 << 16):
                await resp.write(chunk)
            await resp.write_eof()
        except (ConnectionResetError, aiohttp.ClientError, asyncio.CancelledError):
            pass
        return resp
    finally:
        r.release()


# ------------------------------------------------------------------ live-TV


def channel_json(c: dict, ch, now: int) -> dict:
    out = ch.public()
    epg = c["iptv"].epg
    cur = nxt = None
    if epg:
        cur, nxt = epg.now_next(ch, now)
    out["now"] = cur.public() if cur else None
    out["next"] = nxt.public() if nxt else None
    out["fav"] = ch.id in set(c["store"].get("favorites", []))
    return out


@routes.get("/api/live/groups")
async def live_groups(request: web.Request):
    c = ctx(request)
    pl = c["iptv"].playlist
    favs = [f for f in c["store"].get("favorites", []) if pl and f in pl.by_id]
    recent = [r for r in c["store"].get("recent", []) if pl and r in pl.by_id]
    return web.json_response(
        {
            "groups": pl.groups if pl else [],
            "hidden": [g for g in c["store"].get("hidden_groups", []) if pl and any(x["name"] == g for x in pl.groups)],
            "favorites": len(favs),
            "recent": len(recent),
            "status": c["iptv"].status(),
        }
    )


def _channels_for(c: dict, q) -> list:
    pl = c["iptv"].playlist
    if not pl:
        return []
    if q.get("fav") == "1":
        return [pl.by_id[i] for i in c["store"].get("favorites", []) if i in pl.by_id]
    if q.get("recent") == "1":
        return [pl.by_id[i] for i in c["store"].get("recent", []) if i in pl.by_id]
    group = q.get("group")
    text = (q.get("q") or "").lower()
    if group:
        return [ch for ch in pl.channels if ch.group == group and (not text or text in ch.name.lower())][:3000]
    hidden = set(c["store"].get("hidden_groups", []))
    return [ch for ch in pl.channels if ch.group not in hidden and (not text or text in ch.name.lower())][:3000]


@routes.post("/api/live/hidden")
async def live_hidden(request: web.Request):
    """Vilka grupper (länder) som döljs. Tar hela listan."""
    c = ctx(request)
    b = await body(request)
    groups = [str(g) for g in b.get("groups", []) if isinstance(g, str)]
    c["store"].set(hidden_groups=sorted(set(groups)))
    return web.json_response({"hidden": c["store"].get("hidden_groups", [])})


@routes.get("/api/live/channels")
async def live_channels(request: web.Request):
    c = ctx(request)
    now = int(time.time() * 1000)
    return web.json_response([channel_json(c, ch, now) for ch in _channels_for(c, request.query)])


@routes.get("/api/live/guide")
async def live_guide(request: web.Request):
    c = ctx(request)
    q = request.query
    frm = int(q.get("from") or time.time() * 1000)
    to = int(q.get("to") or frm + 4 * 3600_000)
    epg = c["iptv"].epg
    out = []
    for ch in _channels_for(c, q)[:400]:
        progs = epg.window(ch, frm, to) if epg else []
        out.append(ch.public() | {"programmes": [p.public() for p in progs], "fav": ch.id in set(c["store"].get("favorites", []))})
    return web.json_response({"from": frm, "to": to, "channels": out})


@routes.get("/api/live/epg/{id}")
async def live_epg(request: web.Request):
    c = ctx(request)
    pl, epg = c["iptv"].playlist, c["iptv"].epg
    ch = pl.by_id.get(request.match_info["id"]) if pl else None
    if not ch:
        raise web.HTTPNotFound()
    now = int(time.time() * 1000)
    progs = epg.window(ch, now - 3600_000, now + 24 * 3600_000) if epg else []
    return web.json_response(channel_json(c, ch, now) | {"programmes": [p.public() for p in progs]})


@routes.post("/api/live/fav/{id}")
async def live_fav(request: web.Request):
    st = ctx(request)["store"]
    cid = request.match_info["id"]
    favs = list(st.get("favorites", []))
    if cid in favs:
        favs.remove(cid)
    else:
        favs.append(cid)
    st.set(favorites=favs)
    return web.json_response({"fav": cid in favs})


@routes.post("/api/live/start/{id}")
async def live_start(request: web.Request):
    c = ctx(request)
    pl = c["iptv"].playlist
    ch = pl.by_id.get(request.match_info["id"]) if pl else None
    if not ch:
        raise web.HTTPNotFound(text="Kanalen finns inte i spellistan")
    b = await body(request)
    mode = c["store"].get("live_transcode", "auto")
    transcode = mode == "always" or (mode == "auto" and bool(b.get("transcode")))
    try:
        session = await c["live"].start(ch, transcode_video=transcode)
    except RuntimeError as e:
        return web.json_response({"error": str(e)}, status=502)
    recent = [ch.id] + [r for r in c["store"].get("recent", []) if r != ch.id]
    c["store"].set(recent=recent[:12])
    now = int(time.time() * 1000)
    return web.json_response({"url": f"/live/{session}/index.m3u8", "session": session, "transcoding": transcode, "channel": channel_json(c, ch, now)})


@routes.post("/api/live/stop")
async def live_stop(request: web.Request):
    c = ctx(request)
    b = await body(request)
    live: LiveStream = c["live"]
    # Bara den som har strömmen får stänga den (ett annat fönster kan ha tagit över).
    if not b.get("session") or b.get("session") == live.session:
        await live.stop()
    return web.json_response({"ok": True})


@routes.get("/live/{session}/{name}")
async def live_file(request: web.Request):
    live: LiveStream = ctx(request)["live"]
    path = live.file(request.match_info["session"], request.match_info["name"])
    if not path:
        if request.match_info["session"] != live.session:
            return web.Response(status=410, text="Strömmen är stängd")
        raise web.HTTPNotFound()
    live.touch()
    if path.endswith(".m3u8"):
        with open(path, encoding="utf-8", errors="ignore") as f:
            return web.Response(text=f.read(), content_type="application/vnd.apple.mpegurl", headers={"Cache-Control": "no-cache"})
    try:
        with open(path, "rb") as f:
            data = f.read()
    except FileNotFoundError:
        raise web.HTTPNotFound()
    return web.Response(body=data, content_type="video/mp2t", headers={"Cache-Control": "no-cache"})


@routes.post("/api/iptv/reload")
async def iptv_reload(request: web.Request):
    asyncio.create_task(ctx(request)["iptv"].refresh())
    return web.json_response({"ok": True})


# ------------------------------------------------------------------ start


def render_index() -> str:
    with open(os.path.join(STATIC, "index.html"), encoding="utf-8") as f:
        html = f.read()
    files = [n for n in os.listdir(STATIC) if n.endswith((".js", ".css"))]
    stamp = str(int(max(os.path.getmtime(os.path.join(STATIC, n)) for n in files)))
    return html.replace("__V__", stamp)


def create_app(config_dir: str | None = None) -> web.Application:
    config_dir = config_dir or os.environ.get("JPTV_CONFIG", "/config")
    app = web.Application(middlewares=[errors], client_max_size=2 * 1024 * 1024)
    store = Store(config_dir)
    state: dict = {"store": store, "index_html": render_index()}
    app[JPTV] = state

    async def startup(app: web.Application):
        http = aiohttp.ClientSession(connector=aiohttp.TCPConnector(limit=64, ttl_dns_cache=300))
        state["http"] = http
        state["plex"] = Plex(store, http)
        state["library"] = Library(state["plex"])
        state["iptv"] = Iptv(store, http)
        state["live"] = LiveStream(os.environ.get("JPTV_LIVE_DIR") or os.path.join(tempfile.gettempdir(), "jptv-live"))
        state["watchdog"] = asyncio.create_task(state["live"].watchdog())
        await state["iptv"].start()
        asyncio.create_task(state["plex"].ensure_server())

    async def cleanup(app: web.Application):
        state["watchdog"].cancel()
        await state["live"].stop()
        await state["http"].close()

    app.on_startup.append(startup)
    app.on_cleanup.append(cleanup)
    app.add_routes(routes)
    app.router.add_static("/static", STATIC, append_version=False)
    return app


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    port = int(os.environ.get("PORT", "8099"))
    web.run_app(create_app(), port=port, access_log=None)


if __name__ == "__main__":
    main()
