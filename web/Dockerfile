# JPTV Web: Plex och live-TV i webbläsaren, för Unraid (eller annan Docker-värd) på hemmanätet.
FROM python:3.12-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg ca-certificates tzdata \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /srv
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
COPY static ./static
COPY tests ./tests

ARG VERSION=dev
ENV JPTV_VERSION=$VERSION \
    JPTV_CONFIG=/config \
    PORT=8099 \
    TZ=Europe/Stockholm \
    PYTHONUNBUFFERED=1

VOLUME /config
EXPOSE 8099
HEALTHCHECK --interval=60s --timeout=5s CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8099/api/status', timeout=4)" || exit 1
CMD ["python", "-m", "app.main"]
