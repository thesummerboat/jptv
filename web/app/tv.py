"""Spellistan och guiden: hämtas från leverantören, sparas i /config och uppdateras av sig själva."""
from __future__ import annotations

import asyncio
import os
import time

import aiohttp

from .iptv import USER_AGENT, Epg, Playlist, guess_epg_url, parse_m3u, parse_xmltv, write_m3u
from .store import Store

PLAYLIST_MAX_AGE = 24 * 3600
EPG_MAX_AGE = 12 * 3600


class Iptv:
    def __init__(self, store: Store, http: aiohttp.ClientSession):
        self.store = store
        self.http = http
        self.playlist: Playlist | None = None
        self.epg: Epg | None = None
        self.loading = False
        self.epg_loading = False
        self.error: str | None = None
        self.epg_error: str | None = None
        self.loaded_at = 0.0
        self.epg_loaded_at = 0.0
        self._lock = asyncio.Lock()

    @property
    def playlist_file(self) -> str:
        return self.store.cache_path("playlist.m3u")

    @property
    def epg_file(self) -> str:
        return self.store.cache_path("epg.xml")

    def epg_url(self) -> str | None:
        return (self.store.get("epg_url") or "").strip() or guess_epg_url(self.store.get("m3u_url"), self.playlist)

    def status(self) -> dict:
        return {
            "configured": bool(self.store.get("m3u_url")),
            "channels": len(self.playlist.channels) if self.playlist else 0,
            "groups": len(self.playlist.groups) if self.playlist else 0,
            "loading": self.loading,
            "error": self.error,
            "epgChannels": self.epg.channel_count if self.epg else 0,
            "epgLoading": self.epg_loading,
            "epgError": self.epg_error,
            "epgUrl": self.epg_url(),
            "loadedAt": int(self.loaded_at * 1000),
            "epgLoadedAt": int(self.epg_loaded_at * 1000),
        }

    async def start(self) -> None:
        """Vid start: läs sparade kopior direkt, hämta nytt om de är gamla."""
        if os.path.exists(self.playlist_file):
            try:
                self.playlist = await asyncio.to_thread(_parse_file, self.playlist_file)
                self.loaded_at = os.path.getmtime(self.playlist_file)
            except Exception:
                self.playlist = None
        if self.playlist and os.path.exists(self.epg_file):
            try:
                self.epg = await asyncio.to_thread(parse_xmltv, self.epg_file, self.playlist)
                self.epg_loaded_at = os.path.getmtime(self.epg_file)
            except Exception:
                self.epg = None
        asyncio.create_task(self.loop())

    async def loop(self) -> None:
        while True:
            try:
                if self.store.get("m3u_url"):
                    now = time.time()
                    if not self.playlist or now - self.loaded_at > PLAYLIST_MAX_AGE:
                        await self.refresh()
                    elif self.epg_url() and (not self.epg or now - self.epg_loaded_at > EPG_MAX_AGE):
                        await self.refresh_epg()
                    elif self.epg and now - self.epg_loaded_at > 3 * 3600:
                        # Tidsfönstret flyttar sig: läs om sparad guide några gånger per dag.
                        self.epg = await asyncio.to_thread(parse_xmltv, self.epg_file, self.playlist)
            except Exception:
                pass
            await asyncio.sleep(600)

    async def refresh(self) -> None:
        url = (self.store.get("m3u_url") or "").strip()
        if not url:
            self.playlist, self.epg = None, None
            return
        async with self._lock:
            self.loading = True
            self.error = None
            try:
                tmp = self.playlist_file + ".download"
                await self._download(url, tmp)
                pl = await asyncio.to_thread(_parse_file, tmp)
                if not pl.channels:
                    raise RuntimeError("Spellistan innehöll inga live-kanaler")
                await asyncio.to_thread(_write_compact, pl, self.playlist_file)
                os.remove(tmp)
                self.playlist = pl
                self.loaded_at = time.time()
            except Exception as e:
                self.error = _message(e)
            finally:
                self.loading = False
        if self.playlist:
            await self.refresh_epg()

    async def refresh_epg(self) -> None:
        url = self.epg_url()
        if not url or not self.playlist:
            return
        self.epg_loading = True
        self.epg_error = None
        try:
            tmp = self.epg_file + ".download"
            await self._download(url, tmp, timeout=600)
            epg = await asyncio.to_thread(parse_xmltv, tmp, self.playlist)
            os.replace(tmp, self.epg_file)
            self.epg = epg
            self.epg_loaded_at = time.time()
        except Exception as e:
            self.epg_error = _message(e)
        finally:
            self.epg_loading = False

    async def _download(self, url: str, path: str, timeout: float = 300) -> None:
        async with self.http.get(
            url, headers={"User-Agent": USER_AGENT}, timeout=aiohttp.ClientTimeout(total=timeout, sock_read=60)
        ) as r:
            if r.status >= 400:
                raise RuntimeError(f"Leverantören svarade HTTP {r.status}")
            with open(path, "wb") as f:
                async for chunk in r.content.iter_chunked(1 << 16):
                    f.write(chunk)


def _parse_file(path: str) -> Playlist:
    with open(path, encoding="utf-8", errors="replace") as f:
        return parse_m3u(f)


def _write_compact(pl: Playlist, path: str) -> None:
    tmp = path + ".new"
    with open(tmp, "w", encoding="utf-8") as f:
        write_m3u(pl, f)
    os.replace(tmp, path)


def _message(e: Exception) -> str:
    if isinstance(e, aiohttp.ClientConnectorError):
        return "Fick ingen kontakt med leverantören"
    if isinstance(e, asyncio.TimeoutError):
        return "Leverantören svarade inte i tid"
    return str(e) or e.__class__.__name__
