"""Önskemål: filmer och serier som någon i familjen vill ha. Sparas i /config/requests.json."""
from __future__ import annotations

import json
import os
import threading
import time
import uuid

STATUSES = ("pending", "approved", "declined", "available")


class Wishes:
    def __init__(self, directory: str):
        self.path = os.path.join(directory, "requests.json")
        self._lock = threading.Lock()
        self.items: list[dict] = []
        try:
            with open(self.path, encoding="utf-8") as f:
                data = json.load(f)
            if isinstance(data, list):
                self.items = [w for w in data if isinstance(w, dict) and w.get("id")]
        except (OSError, ValueError):
            pass

    def _save(self) -> None:
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self.items, f, ensure_ascii=False, indent=1)
        os.replace(tmp, self.path)

    def find(self, guid: str | None) -> dict | None:
        """Senaste önskemålet för titeln (nekade räknas inte, då får man önska igen)."""
        if not guid:
            return None
        for w in reversed(self.items):
            if w.get("guid") == guid and w.get("status") != "declined":
                return w
        return None

    def get(self, wid: str) -> dict | None:
        return next((w for w in self.items if w["id"] == wid), None)

    def add(self, item: dict, by: str) -> tuple[dict, bool]:
        """Nytt önskemål, eller det som redan finns för titeln. Returnerar (önskemål, nytt?)."""
        with self._lock:
            existing = self.find(item.get("guid"))
            if existing:
                if by and by not in existing.get("also", []) and by != existing.get("by"):
                    existing.setdefault("also", []).append(by)
                    self._save()
                return existing, False
            w = {
                "id": uuid.uuid4().hex[:10],
                "guid": item.get("guid"),
                "key": item.get("key"),
                "type": item.get("type") if item.get("type") in ("movie", "show") else "movie",
                "title": str(item.get("title") or "")[:200],
                "year": item.get("year"),
                "summary": (item.get("summary") or "")[:600] or None,
                "thumb": item.get("thumb"),
                "art": item.get("art"),
                "by": (by or "Någon")[:40],
                "at": int(time.time() * 1000),
                "status": "pending",
                "note": None,
                "error": None,
            }
            self.items.append(w)
            self._save()
            return w, True

    def update(self, wid: str, **changes) -> dict | None:
        with self._lock:
            w = self.get(wid)
            if not w:
                return None
            if changes.get("status") and changes["status"] not in STATUSES:
                raise ValueError("okänd status")
            w.update(changes)
            if "status" in changes:
                w["handledAt"] = int(time.time() * 1000)
            self._save()
            return w

    def remove(self, wid: str) -> bool:
        with self._lock:
            before = len(self.items)
            self.items = [w for w in self.items if w["id"] != wid]
            if len(self.items) != before:
                self._save()
                return True
            return False

    def pending(self) -> int:
        return sum(1 for w in self.items if w["status"] == "pending")

    def mark_available(self, library: dict[str, str]) -> list[dict]:
        """library: Plex-guid -> titelns nyckel i Plex. Önskemål som dykt upp markeras som Finns nu."""
        done = []
        with self._lock:
            for w in self.items:
                if w["status"] in ("pending", "approved") and w.get("guid") in library:
                    w["status"] = "available"
                    w["libraryKey"] = library[w["guid"]]
                    w["handledAt"] = int(time.time() * 1000)
                    done.append(w)
            if done:
                self._save()
        return done

    def public(self) -> list[dict]:
        order = {"pending": 0, "approved": 1, "available": 2, "declined": 3}
        return sorted(self.items, key=lambda w: (order.get(w["status"], 9), -w.get("at", 0)))
