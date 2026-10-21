# JPTV Web: Plex och live-TV i webbläsaren, för Unraid (eller annan Docker-värd) på hemmanätet.
FROM python:3.12-slim

# Intel-drivrutinen (non-free) ger QuickSync för avflätning/omkodning av live-TV på t.ex. N100.
RUN sed -i 's/^Components: main$/Components: main non-free non-free-firmware/' /etc/apt/sources.list.d/debian.sources \
    && apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg ca-certificates tzdata intel-media-va-driver-non-free vainfo \
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
    LIBVA_DRIVER_NAME=iHD \
    PYTHONUNBUFFERED=1

VOLUME /config
EXPOSE 8099
HEALTHCHECK --interval=60s --timeout=5s CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8099/api/status', timeout=4)" || exit 1
CMD ["python", "-m", "app.main"]
