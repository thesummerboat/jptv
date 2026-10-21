/* JPTV Web – live-TV (en kanal åt gången, via servern) och TV-guiden. */
'use strict';
(function () {
  const { h } = JP;

  JP.LIVE_HLS_CONFIG = {
    enableWorker: true,
    liveSyncDurationCount: 3,
    liveMaxLatencyDurationCount: 8,
    backBufferLength: 20,
    maxBufferLength: 20,
    manifestLoadPolicy: { default: { maxTimeToFirstByteMs: 15000, maxLoadTimeMs: 20000, timeoutRetry: { maxNumRetry: 2, retryDelayMs: 0, maxRetryDelayMs: 0 }, errorRetry: { maxNumRetry: 3, retryDelayMs: 1000, maxRetryDelayMs: 4000 } } },
    playlistLoadPolicy: { default: { maxTimeToFirstByteMs: 15000, maxLoadTimeMs: 20000, timeoutRetry: { maxNumRetry: 3, retryDelayMs: 0, maxRetryDelayMs: 0 }, errorRetry: { maxNumRetry: 6, retryDelayMs: 1000, maxRetryDelayMs: 4000 } } },
    fragLoadPolicy: { default: { maxTimeToFirstByteMs: 15000, maxLoadTimeMs: 30000, timeoutRetry: { maxNumRetry: 3, retryDelayMs: 0, maxRetryDelayMs: 0 }, errorRetry: { maxNumRetry: 4, retryDelayMs: 500, maxRetryDelayMs: 4000 } } },
  };

  /* ---------- live-spelaren (ett enda video-element, så att bild i bild kan fortsätta) ---------- */

  const L = {
    video: null,
    holder: null,
    stream: null,
    session: null,
    channel: null,
    transcoding: false,
    token: 0,
    onState: null,
    checkTimer: null,
  };

  function liveVideo() {
    if (L.video) return L.video;
    L.video = h('video', { class: 'live-video', playsinline: true, autoplay: true });
    L.video.volume = JP.store.get('volume', 1);
    L.video.muted = JP.store.get('muted', false);
    L.video.addEventListener('volumechange', () => {
      if (L.autoMuted) return;
      JP.store.set('volume', L.video.volume);
      JP.store.set('muted', L.video.muted);
    });
    L.holder = h('div', { class: 'live-holder' });
    document.body.append(L.holder);
    L.holder.append(L.video);
    L.video.addEventListener('leavepictureinpicture', () => {
      if (!document.body.dataset.page || document.body.dataset.page !== 'live') stopLive();
    });
    L.video.addEventListener('playing', () => state('playing'));
    L.video.addEventListener('waiting', () => state('loading'));
    return L.video;
  }

  function state(s, detail) {
    if (L.onState) L.onState(s, detail);
  }

  function destroyStream() {
    clearTimeout(L.checkTimer);
    if (L.stream) { L.stream.destroy(); L.stream = null; }
  }

  async function stopLive() {
    L.token++;
    destroyStream();
    const session = L.session;
    L.session = null;
    L.channel = null;
    if (L.video) { L.video.pause(); L.video.removeAttribute('src'); L.video.load(); }
    if (session) JP.beacon('/api/live/stop', { session });
  }
  JP.stopLive = stopLive;
  window.addEventListener('pagehide', () => { if (L.session) JP.beacon('/api/live/stop', { session: L.session }); });

  async function tune(ch, opts = {}) {
    const video = liveVideo();
    const my = ++L.token;
    destroyStream();
    L.channel = ch;
    state('loading');
    const mode = (JP.status && JP.status.settings && JP.status.settings.liveTranscode) || 'auto';
    const transcode = opts.transcode ?? (mode === 'auto' && JP.store.get('tx:' + ch.id, false));
    let res;
    try {
      res = await JP.post('/api/live/start/' + ch.id, { transcode });
    } catch (e) {
      if (my === L.token) state('error', e.message);
      return;
    }
    if (my !== L.token) return;
    L.session = res.session;
    L.transcoding = res.transcoding;
    const retryTranscode = () => {
      if (my !== L.token || L.transcoding || mode !== 'auto') return false;
      JP.store.set('tx:' + ch.id, true);
      // Inte mitt i hls.js egen händelse: den stängs när vi byter ström.
      L.token++;
      setTimeout(() => tune(ch, { transcode: true }), 0);
      return true;
    };
    L.stream = JP.attachHls(video, res.url, {
      live: true,
      onAnyError: (data) => {
        if (/HEVC|Unsupported/i.test(String(data.reason || (data.error && data.error.message) || ''))) retryTranscode();
      },
      onCodecs: (data) => {
        // Kanaler i HEVC/MPEG-2: webbläsaren får bara ljudet. Då kodar servern om bilden.
        if (!data.video && data.audio) retryTranscode();
      },
      onFatal: (data) => {
        const code = data.response && data.response.code;
        if (code === 410) {
          if (my === L.token) state('taken');
          return true;
        }
        if (/Codec|Incompatible|fragParsing|bufferAppend/i.test(data.details || '') && retryTranscode()) return true;
        return false;
      },
      onError: () => { if (my === L.token) state('error', 'Strömmen avbröts'); },
    });
    JP.playWithSound(video, L);
    // Spelar men ingen bild efter en stund: koda om.
    L.checkTimer = setTimeout(() => {
      if (my === L.token && !video.paused && video.readyState >= 2 && video.videoWidth === 0) retryTranscode();
    }, 8000);
  }

  /* ---------- live-sidan ---------- */

  JP.route(/^\/live$/, async (v, _m, q) => {
    v.el.classList.add('live');
    const video = liveVideo();
    const slot = h('div', { class: 'live-slot' });
    const overlay = h('div', { class: 'live-overlay' });
    const favBtn = h('button', { class: 'p-btn', title: 'Favorit' }, JP.icon('star'));
    const pipBtn = h('button', { class: 'p-btn', title: 'Bild i bild – fortsätter när du bläddrar i Plex' }, JP.icon('pip'));
    const fullBtn = h('button', { class: 'p-btn', title: 'Helskärm (F)' }, JP.icon('full'));
    const muteBtn = h('button', { class: 'p-btn', title: 'Ljud av/på (M)' }, JP.icon(video.muted ? 'mute' : 'vol'));
    if (!document.pictureInPictureEnabled) pipBtn.hidden = true;
    const bar = h('div', { class: 'live-bar' }, h('div', { class: 'live-bar-name' }), h('div', { class: 'p-spacer' }), favBtn, muteBtn, pipBtn, fullBtn);
    const screen = h('div', { class: 'live-screen' }, slot, overlay, bar);
    const info = h('div', { class: 'live-info' });
    const groupList = h('div', { class: 'group-list' });
    const filter = h('input', { type: 'search', class: 'live-filter', placeholder: 'Sök kanal', autocomplete: 'off' });
    const listHead = h('div', { class: 'list-head' });
    const list = h('div', { class: 'channel-list', tabindex: '-1' });
    v.el.append(
      h('aside', { class: 'groups' }, filter, groupList),
      h('div', { class: 'channels' }, listHead, list),
      h('div', { class: 'live-side' }, screen, info));
    slot.append(video);

    let channels = [], current = null, group = null, refreshTimer = null;
    const st = await JP.api('/api/live/groups');
    if (!st.groups.length) {
      v.el.replaceChildren(h('div', { class: 'page' }, h('div', { class: 'empty' },
        h('div', { class: 'empty-title' }, st.status.loading ? 'Hämtar kanalerna …' : 'Inga kanaler än'),
        h('div', { class: 'dim' }, st.status.error || 'Lägg in spellistan under Inställningar.'),
        h('a', { class: 'btn primary', href: '#/settings' }, 'Inställningar'))));
      if (st.status.loading) setTimeout(() => { if (v.isCurrent()) { v.dirty = true; JP.current = null; JP.render(); } }, 3000);
      return;
    }

    function setOverlay(s, detail) {
      const name = L.channel ? L.channel.name : '';
      bar.querySelector('.live-bar-name').textContent = name;
      if (s === 'loading') overlay.replaceChildren(h('div', { class: 'spin' }), h('div', null, 'Startar ' + name + ' …'));
      else if (s === 'error') overlay.replaceChildren(h('div', { class: 'p-msg-title' }, 'Kanalen gick inte att spela'), h('div', { class: 'dim' }, detail || ''), h('button', { class: 'btn', onclick: () => current && tune(current) }, 'Försök igen'));
      else if (s === 'taken') overlay.replaceChildren(h('div', { class: 'p-msg-title' }, 'Live-TV spelas i ett annat fönster'), h('div', { class: 'dim' }, 'Bara en kanal åt gången.'), h('button', { class: 'btn', onclick: () => current && tune(current) }, 'Titta här i stället'));
      else if (s === 'idle') overlay.replaceChildren(h('div', { class: 'dim' }, 'Välj en kanal'));
      overlay.hidden = s === 'playing';
      for (const row of list.children) row.classList.toggle('playing', !!L.channel && row.dataset.id === L.channel.id);
    }
    L.onState = setOverlay;
    setOverlay(L.channel ? (L.video.paused ? 'loading' : 'playing') : 'idle');

    function progBar(p) {
      if (!p) return null;
      const now = Date.now();
      const pct = Math.min(100, Math.max(0, ((now - p.start) / (p.stop - p.start)) * 100));
      return h('div', { class: 'progress' }, h('i', { style: { width: pct + '%' } }));
    }

    function row(ch) {
      const r = h('button', { class: 'ch', dataset: { id: ch.id }, onclick: () => play(ch) },
        h('span', { class: 'ch-num' }, ch.number),
        h('span', { class: 'ch-logo' }, ch.logo ? h('img', { src: ch.logo, alt: '', loading: 'lazy', onerror: (e) => e.target.remove() }) : null),
        h('span', { class: 'ch-text' },
          h('span', { class: 'ch-name' }, ch.name, ch.fav ? h('span', { class: 'fav-dot', title: 'Favorit' }, '★') : null),
          h('span', { class: 'ch-now' }, ch.now ? ch.now.title : ''),
          progBar(ch.now),
          ch.next ? h('span', { class: 'ch-next' }, JP.hm(ch.next.start) + ' ' + ch.next.title) : null));
      if (L.channel && L.channel.id === ch.id) r.classList.add('playing');
      return r;
    }

    async function loadList(g, keepScroll) {
      group = g;
      JP.store.set('liveGroup', g);
      for (const b of groupList.children) b.classList.toggle('on', b.dataset.g === g);
      const qs = g === '★' ? 'fav=1' : g === '⟲' ? 'recent=1' : 'group=' + encodeURIComponent(g);
      const text = filter.value.trim();
      const url = text ? '/api/live/channels?q=' + encodeURIComponent(text) : '/api/live/channels?' + qs;
      const top = list.scrollTop;
      channels = await JP.api(url);
      listHead.replaceChildren(h('h2', null, text ? `Sök: ${text}` : g === '★' ? 'Favoriter' : g === '⟲' ? 'Senast sedda' : g), h('span', { class: 'dim' }, channels.length + ' kanaler'));
      list.replaceChildren(...channels.map(row));
      if (keepScroll) list.scrollTop = top;
      if (!channels.length) list.append(h('div', { class: 'empty small' }, g === '★' ? 'Inga favoriter än. Tryck på stjärnan när en kanal spelas.' : 'Inga kanaler'));
    }

    function groupButton(key, label, count) {
      return h('button', { class: 'group', dataset: { g: key }, onclick: () => { filter.value = ''; loadList(key); } }, h('span', null, label), h('span', { class: 'dim' }, count));
    }
    groupList.append(groupButton('★', '★ Favoriter', st.favorites || ''), groupButton('⟲', 'Senast sedda', st.recent || ''));
    for (const g of st.groups) groupList.append(groupButton(g.name, g.name, g.count));
    filter.addEventListener('input', JP.debounce(() => loadList(group), 250));

    async function renderInfo(ch) {
      favBtn.classList.toggle('on', !!ch.fav);
      let data;
      try { data = await JP.api('/api/live/epg/' + ch.id); } catch (e) { return; }
      if (!L.channel || L.channel.id !== ch.id) return;
      favBtn.classList.toggle('on', !!data.fav);
      const now = Date.now();
      const upcoming = data.programmes.filter((p) => p.stop > now).slice(0, 12);
      const cur = upcoming[0] && upcoming[0].start <= now ? upcoming.shift() : null;
      info.replaceChildren(
        h('div', { class: 'live-now' },
          h('div', { class: 'live-chan' }, data.logo ? h('img', { src: data.logo, alt: '', onerror: (e) => e.target.remove() }) : null, h('span', null, `${data.number} · ${data.name}`)),
          cur ? [h('h2', null, cur.title), h('div', { class: 'dim' }, `${JP.hm(cur.start)}–${JP.hm(cur.stop)} · ${Math.max(0, Math.round((cur.stop - now) / 60000))} min kvar`), progBar(cur), cur.desc ? h('p', null, cur.desc) : null]
            : h('div', { class: 'dim' }, 'Ingen guide för kanalen')),
        upcoming.length ? h('div', { class: 'upcoming' }, h('h3', null, 'Senare'), upcoming.map((p) => h('div', { class: 'up' }, h('span', { class: 'up-time' }, JP.hm(p.start)), h('span', null, p.title)))) : null);
    }

    function play(ch) {
      current = ch;
      if (!L.channel || L.channel.id !== ch.id || !L.session) tune(ch);
      setOverlay(L.channel && !L.video.paused && L.channel.id === ch.id && L.session ? 'playing' : 'loading');
      renderInfo(ch);
      history.replaceState(null, '', '#/live?ch=' + ch.id);
    }

    favBtn.addEventListener('click', async () => {
      if (!L.channel) return;
      const r = await JP.post('/api/live/fav/' + L.channel.id);
      favBtn.classList.toggle('on', r.fav);
      JP.toast(r.fav ? 'Tillagd i favoriter' : 'Borttagen från favoriter');
      loadList(group, true);
    });
    muteBtn.addEventListener('click', () => { video.muted = !video.muted; muteBtn.replaceChildren(JP.icon(video.muted ? 'mute' : 'vol')); });
    fullBtn.addEventListener('click', () => (document.fullscreenElement ? document.exitFullscreen() : screen.requestFullscreen()).catch(() => {}));
    pipBtn.addEventListener('click', () => (document.pictureInPictureElement ? document.exitPictureInPicture() : video.requestPictureInPicture()).catch(() => {}));
    screen.addEventListener('dblclick', () => fullBtn.click());

    const step = (dir) => {
      if (!channels.length) return;
      const idx = L.channel ? channels.findIndex((c) => c.id === L.channel.id) : -1;
      const next = channels[(idx + dir + channels.length) % channels.length];
      play(next);
      const el = list.querySelector(`[data-id="${next.id}"]`);
      if (el) el.scrollIntoView({ block: 'nearest' });
    };
    const onKey = (e) => {
      if (/input|select|textarea/i.test(e.target.tagName) || JP.playerOpen) return;
      if (e.key === 'ArrowUp' || e.key === 'PageUp') { e.preventDefault(); step(-1); }
      else if (e.key === 'ArrowDown' || e.key === 'PageDown') { e.preventDefault(); step(1); }
      else if (e.key === 'f' || e.key === 'F') fullBtn.click();
      else if (e.key === 'm' || e.key === 'M') muteBtn.click();
      else if (e.key === ' ') { e.preventDefault(); video.paused ? video.play() : video.pause(); }
    };
    document.addEventListener('keydown', onKey);
    refreshTimer = setInterval(() => { if (!filter.value) loadList(group, true); if (L.channel) renderInfo(L.channel); }, 60000);

    v.onLeave(() => {
      document.removeEventListener('keydown', onKey);
      clearInterval(refreshTimer);
      L.onState = null;
      L.holder.append(video);
      // Bild i bild fortsätter medan man bläddrar; annars stängs strömmen direkt.
      if (document.pictureInPictureElement !== video) stopLive();
    });

    // Starta: kanal från adressen, annars senaste gruppen.
    const wanted = q.get('ch');
    let startGroup = JP.store.get('liveGroup', null);
    let startChannel = null;
    if (wanted) {
      try {
        startChannel = await JP.api('/api/live/epg/' + wanted);
        startGroup = startChannel.group;
      } catch (e) { startChannel = null; }
    } else if (L.channel) {
      startChannel = L.channel;
      startGroup = L.channel.group;
    }
    if (!startGroup || (startGroup !== '★' && startGroup !== '⟲' && !st.groups.some((g) => g.name === startGroup))) startGroup = st.favorites ? '★' : st.groups[0].name;
    await loadList(startGroup);
    if (startChannel) {
      play(startChannel);
      const el = list.querySelector(`[data-id="${startChannel.id}"]`);
      if (el) el.scrollIntoView({ block: 'center' });
    } else {
      const el = groupList.querySelector('.on');
      if (el) el.scrollIntoView({ block: 'nearest' });
    }
  }, { nocache: true, page: 'live' });

  /* ---------- TV-guiden ---------- */

  const PX_PER_MIN = 6;

  JP.route(/^\/guide$/, async (v, _m, q) => {
    const st = await JP.api('/api/live/groups');
    if (!st.groups.length) {
      v.el.append(h('div', { class: 'page' }, h('div', { class: 'empty' }, h('div', { class: 'empty-title' }, 'Ingen guide än'), h('div', { class: 'dim' }, 'Lägg in spellistan under Inställningar.'))));
      return;
    }
    let group = q.get('g') || JP.store.get('guideGroup', null) || JP.store.get('liveGroup', null);
    if (!group || (group !== '★' && !st.groups.some((g) => g.name === group))) group = st.favorites ? '★' : st.groups[0].name;
    const select = h('select', { 'aria-label': 'Grupp', onchange: (e) => { JP.store.set('guideGroup', e.target.value); load(e.target.value); } },
      st.favorites ? h('option', { value: '★', selected: group === '★' }, '★ Favoriter') : null,
      st.groups.map((g) => h('option', { value: g.name, selected: g.name === group }, `${g.name} (${g.count})`)));
    const grid = h('div', { class: 'guide' });
    const pop = h('div', { class: 'guide-pop', hidden: true });
    v.el.append(h('div', { class: 'page wide' }, h('div', { class: 'section-title' }, h('h1', null, 'TV-guide'), select, h('a', { class: 'btn small', href: '#/live' }, JP.icon('tv'), 'Live-TV')), grid, pop));
    const closePop = (e) => { if (!pop.contains(e.target)) pop.hidden = true; };
    document.addEventListener('click', closePop);

    async function load(g) {
      grid.replaceChildren(JP.spinner());
      const now = Date.now();
      const from = Math.floor(now / 1800000) * 1800000 - 1800000;
      const to = from + 14 * 3600000;
      const qs = g === '★' ? 'fav=1' : 'group=' + encodeURIComponent(g);
      const data = await JP.api(`/api/live/guide?${qs}&from=${from}&to=${to}`);
      const width = ((to - from) / 60000) * PX_PER_MIN;
      const x = (t) => ((Math.max(from, Math.min(to, t)) - from) / 60000) * PX_PER_MIN;
      const times = h('div', { class: 'g-times', style: { width: width + 'px' } });
      for (let t = from; t < to; t += 1800000) times.append(h('div', { class: 'g-tick', style: { left: x(t) + 'px' } }, JP.hm(t)));
      const rows = data.channels.map((ch) => {
        const progs = h('div', { class: 'g-progs', style: { width: width + 'px' } });
        if (!ch.programmes.length) progs.append(h('div', { class: 'g-prog empty', style: { left: '0px', width: width + 'px' } }, 'Ingen guide'));
        for (const p of ch.programmes) {
          const live = p.start <= now && p.stop > now;
          const b = h('button', {
            class: 'g-prog' + (live ? ' live' : '') + (p.stop <= now ? ' past' : ''),
            style: { left: x(p.start) + 'px', width: Math.max(2, x(p.stop) - x(p.start) - 2) + 'px' },
            title: `${JP.hm(p.start)}–${JP.hm(p.stop)} ${p.title}`,
            onclick: (e) => {
              e.stopPropagation();
              if (live) { JP.go('#/live?ch=' + ch.id); return; }
              const r = b.getBoundingClientRect();
              pop.replaceChildren(
                h('div', { class: 'dim' }, `${JP.dayLabel(p.start)} ${JP.hm(p.start)}–${JP.hm(p.stop)} · ${ch.name}`),
                h('h3', null, p.title),
                p.desc ? h('p', null, p.desc) : null,
                h('a', { class: 'btn small', href: '#/live?ch=' + ch.id }, JP.icon('play'), 'Titta på kanalen nu'));
              pop.hidden = false;
              pop.style.left = Math.min(window.innerWidth - 380, Math.max(12, r.left)) + 'px';
              pop.style.top = Math.min(window.innerHeight - 220, r.bottom + 6) + 'px';
            },
          }, h('span', { class: 'g-txt' }, h('b', null, p.title), h('small', null, JP.hm(p.start))));
          progs.append(b);
        }
        return h('div', { class: 'g-row' },
          h('a', { class: 'g-chan', href: '#/live?ch=' + ch.id, title: ch.name },
            h('span', { class: 'ch-num' }, ch.number),
            ch.logo ? h('img', { src: ch.logo, alt: '', loading: 'lazy', onerror: (e) => e.target.remove() }) : null,
            h('span', { class: 'g-chan-name' }, ch.name)),
          progs);
      });
      const nowLine = h('div', { class: 'g-now', style: { left: `calc(var(--chan-w) + ${x(now)}px)` } });
      grid.replaceChildren(h('div', { class: 'g-inner' }, h('div', { class: 'g-head' }, h('div', { class: 'g-corner' }, JP.dayLabel(now)), times), rows, nowLine));
      grid.scrollLeft = x(Math.floor(now / 1800000) * 1800000);
    }
    await load(group);
    const timer = setInterval(() => { if (v.isCurrent()) load(select.value); }, 5 * 60000);
    v.onLeave(() => { clearInterval(timer); document.removeEventListener('click', closePop); });
  }, { nocache: true, page: 'guide' });
})();
