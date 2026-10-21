"""Inställningar och inloggning, sparade som JSON i /config (överlever omstart och uppdatering)."""
from __future__ import annotations

import json
import os
import threading

DEFAULTS = {
    "client_id": None,
    "account_token": None,
    "server": None,
    "m3u_url": "",
    "epg_url": "",
    # Index i plex.QUALITIES. 1 = 1080p 20 Mbit/s.
    "quality": 1,
    # auto: kopiera bilden, koda om bara om webbläsaren inte klarar kanalen. always / never.
    "live_transcode": "auto",
    # auto: radsprångade kanaler (1080i) avflätas till 50 bilder/s – mjukare sport. off: som de sänds.
    "live_deinterlace": "auto",
    # Ledmotiv på seriesidor: 0 = av, 1 = lågt, 2 = mellan.
    "theme_level": 1,
    "favorites": [],
    # Grupper (länder) som inte visas i live-TV, guiden och kanalsöket.
    "hidden_groups": [],
    # Radarr/Sonarr: godkända önskemål skickas dit (valfritt).
    "radarr_url": "",
    "radarr_key": "",
    "sonarr_url": "",
    "sonarr_key": "",
    "recent": [],
}


class Store:
    def __init__(self, directory: str):
        self.directory = directory
        os.makedirs(directory, exist_ok=True)
        self.path = os.path.join(directory, "settings.json")
        self._lock = threading.Lock()
        self.data = dict(DEFAULTS)
        try:
            with open(self.path, encoding="utf-8") as f:
                loaded = json.load(f)
            if isinstance(loaded, dict):
                self.data.update(loaded)
        except FileNotFoundError:
            pass
        except (OSError, ValueError):
            # Trasig fil: börja om men behåll en kopia.
            try:
                os.replace(self.path, self.path + ".broken")
            except OSError:
                pass

    def get(self, key: str, default=None):
        v = self.data.get(key, default)
        return default if v is None else v

    def set(self, **values) -> None:
        with self._lock:
            self.data.update(values)
            tmp = self.path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(self.data, f, ensure_ascii=False, indent=1)
            os.replace(tmp, self.path)

    def cache_path(self, name: str) -> str:
        d = os.path.join(self.directory, "cache")
        os.makedirs(d, exist_ok=True)
        return os.path.join(d, name)
