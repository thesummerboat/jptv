/* JPTV Web – spelaren för Plex (och trailers): hls.js, hoppa över intro, nästa avsnitt, spårval. */
'use strict';
(function () {
  const { h } = JP;

  const HLS_CONFIG = {
    enableWorker: true,
    maxBufferLength: 40,
    maxMaxBufferLength: 120,
    backBufferLength: 60,
    // Plex kodar om på begäran: första biten kan dröja några sekunder.
    manifestLoadPolicy: { default: { maxTimeToFirstByteMs: 30000, maxLoadTimeMs: 60000, timeoutRetry: { maxNumRetry: 2, retryDelayMs: 0, maxRetryDelayMs: 0 }, errorRetry: { maxNumRetry: 4, retryDelayMs: 1000, maxRetryDelayMs: 8000 } } },
    playlistLoadPolicy: { default: { maxTimeToFirstByteMs: 30000, maxLoadTimeMs: 60000, timeoutRetry: { maxNumRetry: 2, retryDelayMs: 0, maxRetryDelayMs: 0 }, errorRetry: { maxNumRetry: 4, retryDelayMs: 1000, maxRetryDelayMs: 8000 } } },
    fragLoadPolicy: { default: { maxTimeToFirstByteMs: 45000, maxLoadTimeMs: 120000, timeoutRetry: { maxNumRetry: 4, retryDelayMs: 0, maxRetryDelayMs: 0 }, errorRetry: { maxNumRetry: 6, retryDelayMs: 1000, maxRetryDelayMs: 8000 } } },
  };
  JP.HLS_CONFIG = HLS_CONFIG;

  /** Spelar en HLS-adress (eller vanlig fil) i ett video-element. Returnerar ett objekt att stänga med. */
  JP.attachHls = function (video, url, opts = {}) {
    const isHls = /\.m3u8(\?|$)/.test(url);
    if (isHls && window.Hls && Hls.isSupported()) {
      const hls = new Hls(Object.assign({}, opts.live ? JP.LIVE_HLS_CONFIG : HLS_CONFIG, { startPosition: opts.start ?? -1 }));
      let netRetries = 0, mediaRetries = 0;
      hls.on(Hls.Events.ERROR, (_e, data) => {
        if (opts.onAnyError) opts.onAnyError(data);
        if (!data.fatal) return;
        if (opts.onFatal && opts.onFatal(data) === true) return;
        if (data.type === Hls.ErrorTypes.NETWORK_ERROR && netRetries < 4) {
          netRetries++;
          setTimeout(() => hls.startLoad(), 1000 * netRetries);
        } else if (data.type === Hls.ErrorTypes.MEDIA_ERROR && mediaRetries < 2) {
          mediaRetries++;
          if (mediaRetries === 2) hls.swapAudioCodec();
          hls.recoverMediaError();
        } else if (opts.onError) {
          opts.onError(data);
        }
      });
      hls.on(Hls.Events.FRAG_LOADED, () => { netRetries = 0; });
      if (opts.onCodecs) hls.on(Hls.Events.BUFFER_CODECS, (_e, data) => opts.onCodecs(data));
      hls.loadSource(url);
      hls.attachMedia(video);
      return { hls, destroy: () => hls.destroy() };
    }
    // Safari spelar HLS själv; vanliga filer (trailers) spelas direkt.
    video.src = url;
    if (opts.start > 0) video.addEventListener('loadedmetadata', () => { video.currentTime = opts.start; }, { once: true });
    if (opts.onError) video.addEventListener('error', () => opts.onError({ details: 'mediaError' }), { once: true });
    return { hls: null, destroy: () => { video.removeAttribute('src'); video.load(); } };
  };

  /** Webbläsaren kan vägra ljud utan ett klick först: spela då utan ljud och säg till. */
  JP.playWithSound = function (video, state) {
    video.play().catch((e) => {
      if (!e || e.name !== 'NotAllowedError') return;
      if (state) state.autoMuted = true;
      video.muted = true;
      video.play().catch(() => {});
      if (state) setTimeout(() => { state.autoMuted = false; }, 0);
      JP.toast('Ljudet är av tills du klickar på högtalaren');
    });
  };

  /* ---------- gemensamt skal för spelaren ---------- */

  function buildShell() {
    const video = h('video', { class: 'p-video', playsinline: true, preload: 'auto' });
    const spinner = h('div', { class: 'p-spin' }, h('div', { class: 'spin' }));
    const message = h('div', { class: 'p-msg', hidden: true });
    const backBtn = h('button', { class: 'p-btn', title: 'Tillbaka (Esc)', 'aria-label': 'Tillbaka' }, JP.icon('back'));
    const title = h('div', { class: 'p-title' });
    const top = h('div', { class: 'p-top' }, backBtn, title);
    const skip = h('button', { class: 'p-skip', hidden: true }, 'Hoppa över intro');
    const nextCard = h('div', { class: 'p-next', hidden: true });
    const played = h('div', { class: 'p-played' });
    const buffered = h('div', { class: 'p-buffered' });
    const knob = h('div', { class: 'p-knob' });
    const hoverTime = h('div', { class: 'p-hover-time', hidden: true });
    const seek = h('div', { class: 'p-seek', role: 'slider', 'aria-label': 'Position', tabindex: '-1' }, h('div', { class: 'p-track' }, buffered, played, knob), hoverTime);
    const playBtn = h('button', { class: 'p-btn', title: 'Spela/paus (mellanslag)' }, JP.icon('play'));
    const rewBtn = h('button', { class: 'p-btn', title: '10 s bakåt (←)' }, JP.icon('rew'));
    const fwdBtn = h('button', { class: 'p-btn', title: '10 s framåt (→)' }, JP.icon('fwd'));
    const volBtn = h('button', { class: 'p-btn', title: 'Ljud av/på (M)' }, JP.icon('vol'));
    const vol = h('input', { type: 'range', min: '0', max: '1', step: '0.05', class: 'p-vol', 'aria-label': 'Volym' });
    const time = h('div', { class: 'p-time' });
    const nextBtn = h('button', { class: 'p-btn', title: 'Nästa avsnitt (N)', hidden: true }, JP.icon('next'));
    const menuBtn = h('button', { class: 'p-btn', title: 'Ljud, undertexter och kvalitet' }, JP.icon('gear'));
    const pipBtn = h('button', { class: 'p-btn', title: 'Bild i bild' }, JP.icon('pip'));
    const fullBtn = h('button', { class: 'p-btn', title: 'Helskärm (F)' }, JP.icon('full'));
    if (!document.pictureInPictureEnabled) pipBtn.hidden = true;
    const menu = h('div', { class: 'p-menu', hidden: true });
    const controls = h('div', { class: 'p-controls' }, playBtn, rewBtn, fwdBtn, volBtn, vol, time, h('div', { class: 'p-spacer' }), nextBtn, menuBtn, pipBtn, fullBtn);
    const bottom = h('div', { class: 'p-bottom' }, seek, controls);
    const el = h('div', { class: 'player', hidden: true }, video, spinner, message, top, skip, nextCard, bottom, menu);
    document.body.append(el);
    return { el, video, spinner, message, backBtn, title, top, skip, nextCard, seek, played, buffered, knob, hoverTime, playBtn, rewBtn, fwdBtn, volBtn, vol, time, nextBtn, menuBtn, pipBtn, fullBtn, menu, bottom };
  }

  let ui = null;
  const P = {
    mode: null, // 'plex' | 'url'
    item: null,
    session: null,
    next: null,
    stream: null,
    duration: 0,
    decision: null,
    quality: null,
    timelineTimer: null,
    lastState: null,
    nextDismissed: false,
    countdown: null,
    idleTimer: null,
    loadToken: 0,
  };

  function ensureUi() {
    if (ui) return ui;
    ui = buildShell();
    const v = ui.video;
    v.volume = JP.store.get('volume', 1);
    v.muted = JP.store.get('muted', false);
    ui.vol.value = v.muted ? 0 : v.volume;

    ui.backBtn.addEventListener('click', closeFromUi);
    ui.playBtn.addEventListener('click', togglePlay);
    ui.rewBtn.addEventListener('click', () => seekBy(-10));
    ui.fwdBtn.addEventListener('click', () => seekBy(10));
    ui.volBtn.addEventListener('click', () => { v.muted = !v.muted; });
    ui.vol.addEventListener('input', () => { v.volume = +ui.vol.value; v.muted = v.volume === 0; });
    ui.fullBtn.addEventListener('click', toggleFull);
    ui.pipBtn.addEventListener('click', () => { (document.pictureInPictureElement ? document.exitPictureInPicture() : v.requestPictureInPicture()).catch(() => {}); });
    ui.menuBtn.addEventListener('click', (e) => { e.stopPropagation(); ui.menu.hidden ? openMenu() : (ui.menu.hidden = true); });
    ui.nextBtn.addEventListener('click', () => playNext());
    ui.skip.addEventListener('click', skipIntro);
    v.addEventListener('click', () => { if (!ui.menu.hidden) { ui.menu.hidden = true; return; } togglePlay(); });
    v.addEventListener('dblclick', toggleFull);
    v.addEventListener('play', () => { updatePlayBtn(); report('playing'); });
    v.addEventListener('pause', () => { updatePlayBtn(); report('paused'); showControls(); });
    v.addEventListener('waiting', () => { ui.spinner.hidden = false; });
    v.addEventListener('playing', () => { ui.spinner.hidden = true; ui.message.hidden = true; });
    v.addEventListener('canplay', () => { ui.spinner.hidden = true; });
    v.addEventListener('timeupdate', onTime);
    v.addEventListener('progress', drawBuffer);
    v.addEventListener('volumechange', () => {
      ui.volBtn.replaceChildren(JP.icon(v.muted || v.volume === 0 ? 'mute' : 'vol'));
      ui.vol.value = v.muted ? 0 : v.volume;
      if (P.autoMuted) return;
      JP.store.set('volume', v.volume);
      JP.store.set('muted', v.muted);
    });
    v.addEventListener('ended', () => {
      if (P.mode === 'plex' && P.next) playNext();
      else if (P.mode) closeFromUi();
    });

    // Spolning med musen.
    let dragging = false;
    const posAt = (e) => {
      const r = ui.seek.getBoundingClientRect();
      return Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)) * (P.duration || v.duration || 0);
    };
    ui.seek.addEventListener('pointerdown', (e) => { dragging = true; ui.seek.setPointerCapture(e.pointerId); v.currentTime = posAt(e); onTime(); });
    ui.seek.addEventListener('pointermove', (e) => {
      const t = posAt(e);
      const r = ui.seek.getBoundingClientRect();
      ui.hoverTime.hidden = false;
      ui.hoverTime.textContent = JP.clock(t);
      ui.hoverTime.style.left = Math.min(r.width - 30, Math.max(30, e.clientX - r.left)) + 'px';
      if (dragging) { v.currentTime = t; onTime(); }
    });
    ui.seek.addEventListener('pointerup', () => { dragging = false; });
    ui.seek.addEventListener('pointerleave', () => { ui.hoverTime.hidden = true; });

    ui.el.addEventListener('mousemove', showControls);
    ui.el.addEventListener('click', (e) => { if (!ui.menu.contains(e.target) && e.target !== ui.menuBtn) ui.menu.hidden = true; });
    document.addEventListener('fullscreenchange', () => ui.el.classList.toggle('full', document.fullscreenElement === ui.el));
    document.addEventListener('keydown', onKey);
    return ui;
  }

  function onKey(e) {
    if (!JP.playerOpen || P.mode === 'live') return;
    if (/input|select|textarea/i.test(e.target.tagName) && e.target !== ui.vol) return;
    const v = ui.video;
    let handled = true;
    switch (e.key) {
      case ' ': case 'k': case 'K': togglePlay(); break;
      case 'ArrowLeft': case 'j': case 'J': seekBy(-10); break;
      case 'ArrowRight': case 'l': case 'L': seekBy(10); break;
      case 'ArrowUp': v.muted = false; v.volume = Math.min(1, v.volume + 0.1); break;
      case 'ArrowDown': v.volume = Math.max(0, v.volume - 0.1); break;
      case 'f': case 'F': toggleFull(); break;
      case 'm': case 'M': v.muted = !v.muted; break;
      case 'n': case 'N': if (P.next) playNext(); break;
      case 's': case 'S': if (!ui.skip.hidden) skipIntro(); break;
      case 'Escape':
        if (!ui.menu.hidden) ui.menu.hidden = true;
        else if (!document.fullscreenElement) closeFromUi();
        break;
      default: handled = false;
    }
    if (handled) { e.preventDefault(); showControls(); }
  }

  function showControls() {
    if (!ui) return;
    ui.el.classList.add('show-ui');
    clearTimeout(P.idleTimer);
    P.idleTimer = setTimeout(() => {
      if (!ui.video.paused && ui.menu.hidden) ui.el.classList.remove('show-ui');
    }, 3000);
  }

  function togglePlay() {
    const v = ui.video;
    if (v.paused) v.play().catch(() => {});
    else v.pause();
  }

  function updatePlayBtn() {
    ui.playBtn.replaceChildren(JP.icon(ui.video.paused ? 'play' : 'pause'));
  }

  function seekBy(s) {
    const v = ui.video;
    const max = P.duration || v.duration || 0;
    v.currentTime = Math.max(0, Math.min(max - 1, v.currentTime + s));
    onTime();
  }

  function toggleFull() {
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    else ui.el.requestFullscreen().catch(() => {});
  }

  function drawBuffer() {
    const v = ui.video, d = P.duration || v.duration;
    if (!d || !v.buffered.length) return;
    let end = 0;
    for (let i = 0; i < v.buffered.length; i++) if (v.buffered.start(i) <= v.currentTime + 1) end = Math.max(end, v.buffered.end(i));
    ui.buffered.style.width = Math.min(100, (end / d) * 100) + '%';
  }

  function onTime() {
    const v = ui.video;
    const d = P.duration || (isFinite(v.duration) ? v.duration : 0);
    const t = v.currentTime;
    const pct = d ? Math.min(100, (t / d) * 100) : 0;
    ui.played.style.width = pct + '%';
    ui.knob.style.left = pct + '%';
    ui.time.textContent = d ? `${JP.clock(t)} / ${JP.clock(d)}` : JP.clock(t);
    if (P.mode !== 'plex' || !P.item) return;
    const ms = t * 1000;
    const intro = (P.item.markers || []).find((m) => m.type === 'intro');
    ui.skip.hidden = !(intro && ms >= intro.start && ms < intro.end - 1500);
    // Nästa avsnitt: vid eftertexterna, annars 30 s före slutet.
    if (P.next && !P.nextDismissed) {
      const credits = (P.item.markers || []).filter((m) => m.type === 'credits');
      const final = credits.find((m) => m.final) || credits.sort((a, b) => b.start - a.start)[0];
      const at = final && final.start > (P.item.duration || d * 1000) * 0.6 ? final.start : (d - 30) * 1000;
      if (d && ms >= at && ui.nextCard.hidden) showNextCard();
      else if (ms < at - 2000 && !ui.nextCard.hidden) hideNextCard();
    }
  }

  function skipIntro() {
    const intro = (P.item.markers || []).find((m) => m.type === 'intro');
    if (intro) ui.video.currentTime = intro.end / 1000 + 0.2;
    ui.skip.hidden = true;
  }

  function showNextCard() {
    const n = P.next;
    let left = 10;
    const count = h('span', null, left);
    ui.nextCard.replaceChildren(
      h('div', { class: 'p-next-img' }, n.still || n.thumb ? h('img', { src: JP.img(n.still || n.thumb, 320, 180), alt: '' }) : null),
      h('div', { class: 'p-next-text' },
        h('div', { class: 'dim' }, 'Nästa avsnitt om ', count, ' s'),
        h('div', { class: 'p-next-title' }, `${JP.epCode(n)} · ${n.title}`),
        h('div', { class: 'p-next-actions' },
          h('button', { class: 'btn primary small', onclick: () => playNext() }, JP.icon('play'), 'Spela nu'),
          h('button', { class: 'btn small', onclick: () => { P.nextDismissed = true; hideNextCard(); } }, 'Avbryt'))));
    ui.nextCard.hidden = false;
    clearInterval(P.countdown);
    P.countdown = setInterval(() => {
      if (ui.video.paused) return;
      left--;
      count.textContent = left;
      if (left <= 0) playNext();
    }, 1000);
  }

  function hideNextCard() {
    clearInterval(P.countdown);
    ui.nextCard.hidden = true;
  }

  function report(state) {
    if (P.mode !== 'plex' || !P.item) return;
    if (state === P.lastState && state !== 'playing') return;
    P.lastState = state;
    JP.post('/api/timeline', { key: P.item.key, state, time: Math.round(ui.video.currentTime * 1000), duration: P.item.duration, session: P.session }).catch(() => {});
  }

  function stopPlex(beacon) {
    clearInterval(P.timelineTimer);
    hideNextCard();
    if (P.stream) { P.stream.destroy(); P.stream = null; }
    if (P.mode === 'plex' && P.item) {
      const body = { key: P.item.key, time: Math.round((ui.video.currentTime || 0) * 1000), duration: P.item.duration, session: P.session };
      if (beacon) JP.beacon('/api/stop', body);
      else JP.post('/api/stop', body).catch(() => {});
    }
    P.item = null;
    P.session = null;
  }
  window.addEventListener('pagehide', () => { if (P.mode === 'plex') stopPlex(true); });

  function setTitle(item) {
    if (item.type === 'episode') {
      ui.title.replaceChildren(h('div', { class: 'p-title-main' }, item.show || ''), h('div', { class: 'p-title-sub' }, `${JP.epCode(item)} · ${item.title}`));
    } else {
      ui.title.replaceChildren(h('div', { class: 'p-title-main' }, item.title), item.year ? h('div', { class: 'p-title-sub' }, String(item.year)) : null);
    }
  }

  function showError(text, retry) {
    ui.spinner.hidden = true;
    ui.message.hidden = false;
    ui.message.replaceChildren(h('div', { class: 'p-msg-title' }, 'Det gick inte att spela'), h('div', { class: 'dim' }, text),
      h('div', { class: 'actions center' }, retry ? h('button', { class: 'btn primary', onclick: retry }, 'Försök igen') : null, h('button', { class: 'btn', onclick: closeFromUi }, 'Tillbaka')));
    showControls();
  }

  /** Startar (eller startar om) Plex-uppspelningen. opts: start (s), audio, subtitle, quality. */
  async function loadPlex(key, opts = {}) {
    const my = ++P.loadToken;
    ui.spinner.hidden = false;
    ui.message.hidden = true;
    ui.menu.hidden = true;
    const previous = P.session;
    if (P.stream) { P.stream.destroy(); P.stream = null; }
    clearInterval(P.timelineTimer);
    let data;
    try {
      data = await JP.post('/api/play/' + key, {
        quality: opts.quality ?? P.quality ?? undefined,
        audio: opts.audio,
        subtitle: opts.subtitle,
        previous: previous || undefined,
      });
    } catch (e) {
      if (my === P.loadToken) showError(e.message, () => loadPlex(key, opts));
      return;
    }
    if (my !== P.loadToken) return;
    const item = data.item;
    P.mode = 'plex';
    P.item = item;
    P.session = data.session;
    P.next = data.next;
    P.quality = data.quality;
    P.decision = data.decision;
    P.duration = (item.duration || 0) / 1000;
    P.nextDismissed = false;
    P.lastState = null;
    hideNextCard();
    ui.nextBtn.hidden = !P.next;
    setTitle(item);
    let start = opts.start;
    if (start === undefined) {
      const resume = item.offset > 0 && item.offset < item.duration - 30000;
      start = opts.fromStart || !resume ? 0 : item.offset / 1000;
    }
    P.stream = JP.attachHls(ui.video, data.url, {
      start,
      onError: (d) => showError(d && d.response && d.response.code ? `Plex svarade ${d.response.code}` : 'Strömmen avbröts', () => loadPlex(key, { start: ui.video.currentTime || start })),
    });
    JP.playWithSound(ui.video, P);
    P.timelineTimer = setInterval(() => { if (!ui.video.paused) report('playing'); }, 10000);
    showControls();
  }

  function playNext() {
    const n = P.next;
    if (!n) return;
    hideNextCard();
    stopPlex(false);
    history.replaceState(null, '', '#/play/' + n.key);
    loadPlex(n.key, { fromStart: true });
  }

  function openMenu() {
    const item = P.item;
    const sections = [];
    if (P.mode === 'plex' && item) {
      if (item.audio && item.audio.length > 1) {
        sections.push(h('div', { class: 'p-menu-sec' }, h('h3', null, 'Ljud'),
          item.audio.map((a) => h('button', { class: a.selected ? 'on' : '', onclick: () => loadPlex(item.key, { audio: a.id, start: ui.video.currentTime }) }, a.title))));
      }
      const subs = item.subtitles || [];
      const anySub = subs.some((s) => s.selected);
      sections.push(h('div', { class: 'p-menu-sec' }, h('h3', null, 'Undertexter'),
        h('button', { class: anySub ? '' : 'on', onclick: () => loadPlex(item.key, { subtitle: 0, start: ui.video.currentTime }) }, 'Av'),
        subs.map((s) => h('button', { class: s.selected ? 'on' : '', onclick: () => loadPlex(item.key, { subtitle: s.id, start: ui.video.currentTime }) }, s.title)),
        subs.length ? null : h('div', { class: 'dim small' }, 'Filen har inga undertexter')));
      const qualities = (JP.status && JP.status.settings && JP.status.settings.qualities) || [];
      sections.push(h('div', { class: 'p-menu-sec' }, h('h3', null, 'Kvalitet'),
        qualities.map((q, i) => h('button', { class: i === P.quality ? 'on' : '', onclick: () => loadPlex(item.key, { quality: i, start: ui.video.currentTime }) }, q))));
      const d = P.decision || {};
      const word = (x) => (x === 'copy' || x === 'directplay' ? 'som den är' : x === 'transcode' ? 'kodas om' : null);
      const info = [word(d.video) && 'Bild ' + word(d.video), word(d.audio) && 'ljud ' + word(d.audio)].filter(Boolean).join(', ');
      if (info) sections.push(h('div', { class: 'p-menu-info dim' }, info));
    }
    ui.menu.replaceChildren(...sections);
    ui.menu.hidden = false;
  }

  /* ---------- öppna och stänga ---------- */

  function openShell(mode) {
    ensureUi();
    if (!JP.playerOpen && JP.current) JP.current.leave.forEach((f) => { try { f(); } catch (e) { /* */ } });
    // Live-TV i bild-i-bild stängs när något annat startar (som på TV:n: aldrig två strömmar samtidigt).
    const pip = document.pictureInPictureElement;
    if (pip && pip.classList.contains('live-video')) document.exitPictureInPicture().catch(() => {});
    JP.theme.stop(true);
    JP.playerOpen = true;
    P.mode = mode;
    ui.el.hidden = false;
    ui.el.dataset.mode = mode;
    document.body.classList.add('playing');
    ui.seek.hidden = false;
    ui.menuBtn.hidden = mode !== 'plex';
    ui.rewBtn.hidden = ui.fwdBtn.hidden = false;
    showControls();
  }

  function closeShell() {
    if (!ui || ui.el.hidden) return;
    if (P.mode === 'plex') stopPlex(false);
    else if (P.stream) { P.stream.destroy(); P.stream = null; }
    ui.video.pause();
    ui.video.removeAttribute('src');
    ui.video.load();
    if (document.fullscreenElement === ui.el) document.exitFullscreen().catch(() => {});
    if (document.pictureInPictureElement === ui.video) document.exitPictureInPicture().catch(() => {});
    ui.el.hidden = true;
    ui.skip.hidden = true;
    ui.message.hidden = true;
    document.body.classList.remove('playing');
    JP.playerOpen = false;
    const wasPlex = P.mode === 'plex';
    P.mode = null;
    if (wasPlex) JP.invalidate(true);
    else if (JP.current) JP.current.show.forEach((f) => f());
  }

  function closeFromUi() {
    if (JP.playerReturn) {
      JP.playerReturn = null;
      history.back();
    } else {
      location.hash = '#/';
    }
  }

  JP.play = function (item, opts = {}) {
    JP.pendingPlay = { key: item.key, fromStart: !!opts.fromStart };
    JP.playerReturn = location.hash || '#/';
    location.hash = '#/play/' + item.key + (opts.fromStart ? '?start=0' : '');
  };

  JP.overlay(/^\/play\/([^/]+)$/, ([key], q) => {
    openShell('plex');
    if (P.item && P.item.key === key) return;
    ui.title.replaceChildren();
    ui.played.style.width = '0%';
    ui.time.textContent = '';
    loadPlex(key, { fromStart: q.get('start') === '0' });
  }, closeShell);

  JP.openUrlPlayer = function (url, title) {
    JP.playerReturn = location.hash || '#/';
    location.hash = '#/trailer?u=' + encodeURIComponent(url) + '&t=' + encodeURIComponent(title || '');
  };

  JP.overlay(/^\/trailer$/, (_m, q) => {
    openShell('url');
    const url = q.get('u');
    ui.title.replaceChildren(h('div', { class: 'p-title-main' }, q.get('t') || 'Trailer'));
    ui.nextBtn.hidden = true;
    P.duration = 0;
    ui.spinner.hidden = false;
    if (P.stream) P.stream.destroy();
    P.stream = JP.attachHls(ui.video, url, { onError: () => showError('Trailern kunde inte spelas i webbläsaren') });
    ui.video.play().catch(() => {});
  }, closeShell);
})();
