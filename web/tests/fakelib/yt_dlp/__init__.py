"""Låtsas-yt-dlp som bibliotek (för yt-dlp-processen i testerna): samma svar som fake_ytdlp.py."""
import os
import sys
from types import SimpleNamespace

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
import fake_ytdlp  # noqa: E402

version = SimpleNamespace(__version__="2099.02.02")


class DownloadError(Exception):
    pass


def parse_options(argv):
    argv = list(argv)
    urls = [a for a in argv if not a.startswith("-") and ("://" in a or a.startswith("ytsearch"))]
    return SimpleNamespace(urls=urls, ydl_opts={"_args": argv}, options=None, parser=None)


class YoutubeDL:
    def __init__(self, params=None):
        self.params = params or {}

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def extract_info(self, url, download=False):
        if url.endswith("v=hangforeve"):
            import time
            time.sleep(30)
        code, out, err = fake_ytdlp.respond(self.params["_args"])
        if code:
            self.params["logger"].error(err.strip())
            raise DownloadError(err.strip())
        out["_worker_pid"] = os.getpid()
        return out

    @staticmethod
    def sanitize_info(info):
        return info
