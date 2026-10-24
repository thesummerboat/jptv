"""
Håller yt-dlp laddat i en egen process, så att varje video slipper starta Python och läsa in
yt-dlp på nytt (det tar runt en halv sekund varje gång).

Protokoll, en JSON per rad:
  in:  {"id": 1, "args": ["--flat-playlist", "ytsearch5:katter"]}   (samma flaggor som för yt-dlp -J)
  ut:  {"ready": "2026.10.21"} först, sedan {"id": 1, "ok": {...}} eller {"id": 1, "error": "ERROR: ..."}

Flera förfrågningar körs samtidigt (trådar). yt-dlp får aldrig skriva i svarskanalen: allt den
skriver går till stderr.
"""
import json
import sys
import threading
from concurrent.futures import ThreadPoolExecutor

_out = sys.stdout
sys.stdout = sys.stderr
_lock = threading.Lock()

import yt_dlp  # noqa: E402  (efter omdirigeringen ovan)


class _Log:
    def __init__(self):
        self.errors = []

    def debug(self, msg):
        pass

    def info(self, msg):
        pass

    def warning(self, msg):
        pass

    def error(self, msg):
        self.errors.append(str(msg))


def _emit(obj) -> None:
    line = json.dumps(obj, ensure_ascii=False, default=str)
    with _lock:
        _out.write(line + "\n")
        _out.flush()


def _handle(req: dict) -> None:
    log = _Log()
    try:
        args = [a for a in req.get("args") or [] if a not in ("-J", "--dump-single-json")]
        parsed = yt_dlp.parse_options(args)
        if not parsed.urls:
            raise ValueError("ingen adress")
        opts = dict(parsed.ydl_opts)
        opts.update(quiet=True, no_warnings=True, noprogress=True, logger=log, simulate=True, ignoreerrors=False)
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(parsed.urls[0], download=False)
            if info is None:
                raise RuntimeError("ERROR: yt-dlp gav inget svar")
            _emit({"id": req.get("id"), "ok": ydl.sanitize_info(info)})
    except BaseException as e:  # noqa: BLE001 – allt ska tillbaka som fel, processen ska leva vidare
        text = "\n".join(log.errors) or str(e) or type(e).__name__
        _emit({"id": req.get("id"), "error": text})


def main() -> None:
    _emit({"ready": getattr(getattr(yt_dlp, "version", None), "__version__", "?")})
    pool = ThreadPoolExecutor(max_workers=4)
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except ValueError:
            continue
        pool.submit(_handle, req)
    pool.shutdown(wait=False)


if __name__ == "__main__":
    main()
