/* JPTV Web – detaljsidor, serier, sök, inställningar och inloggning. */
'use strict';
(function () {
  const { h } = JP;

  /* ---------- ledmotiv på seriesidor ---------- */

  JP.theme = (function () {
    const audio = new Audio();
    audio.loop = false;
    let fade = null, currentUrl = null;
    const volume = () => [0, 0.12, 0.25][(JP.status && JP.status.settings && JP.status.settings.themeLevel) ?? 1] || 0;
    function ramp(to, ms, done) {
      clearInterval(fade);
      const from = audio.volume, steps = Math.max(1, Math.round(ms / 50));
      let i = 0;
      fade = setInterval(() => {
        i++;
        audio.volume = Math.max(0, Math.min(1, from + ((to - from) * i) / steps));
        if (i >= steps) { clearInterval(fade); if (done) done(); }
      }, 50);
    }
    return {
      play(path) {
        const vol = volume();
        if (!path || !vol || JP.playerOpen) return;
        const url = '/plex' + path;
        if (currentUrl === url && !audio.paused) return;
        currentUrl = url;
        audio.src = url;
        audio.volume = 0;
        audio.play().then(() => ramp(vol, 1500)).catch(() => {});
      },
      stop(fast) {
        if (audio.paused) return;
        currentUrl = null;
        ramp(0, fast ? 150 : 900, () => audio.pause());
      },
    };
  })();

  /* ---------- delar som detaljsidan och seriesidan delar ---------- */

  function backdrop(item) {
    const im = h('img', { class: 'details-img', alt: '', src: JP.img(item.art || item.thumb, 1920, 1080) });
    im.onerror = () => im.remove();
    const video = h('video', { class: 'details-video', muted: true, playsinline: true });
    video.muted = true;
    const wrap = h('div', { class: 'details-bg' }, item.art || item.thumb ? im : null, video, h('div', { class: 'details-scrim' }));
    let timer = null;
    const start = () => {
      clearTimeout(timer);
      if (!JP.store.get('heroTrailers', true)) return;
      timer = setTimeout(async () => {
        const t = await JP.trailerFor(item);
        if (!t || JP.playerOpen || !wrap.isConnected || wrap.closest('[hidden]')) return;
        video.src = t.url;
        video.play().catch(() => {});
      }, 3000);
    };
    const stop = () => {
      clearTimeout(timer);
      wrap.classList.remove('trailer-on');
      if (video.getAttribute('src')) { video.pause(); video.removeAttribute('src'); video.load(); }
    };
    video.addEventListener('playing', () => wrap.classList.add('trailer-on'));
    video.addEventListener('ended', stop);
    video.addEventListener('error', stop);
    return { el: wrap, start, stop };
  }

  function titleBlock(item, title) {
    const box = h('div', { class: 'details-logo' }, h('h1', null, title));
    JP.logoFor(item).then((logo) => {
      if (!logo) return;
      const im = h('img', { src: JP.img(logo), alt: title });
      im.onload = () => box.replaceChildren(im);
    });
    return box;
  }

  function people(item) {
    const lines = [];
    if (item.directors && item.directors.length) lines.push(h('div', null, h('span', { class: 'dim' }, 'Regi '), item.directors.join(', ')));
    if (item.writers && item.writers.length) lines.push(h('div', null, h('span', { class: 'dim' }, 'Manus '), item.writers.join(', ')));
    if (item.studio) lines.push(h('div', null, h('span', { class: 'dim' }, 'Studio '), item.studio));
    return lines.length ? h('div', { class: 'credits' }, lines) : null;
  }

  function castRow(item) {
    if (!item.roles || !item.roles.length) return null;
    return h('section', { class: 'row cast' }, h('h2', null, 'Medverkande'),
      h('div', { class: 'strip' }, item.roles.map((r) => h('div', { class: 'person' },
        h('div', { class: 'person-img' }, r.thumb ? h('img', { src: JP.img(r.thumb, 160, 160), alt: '', loading: 'lazy' }) : h('span', null, (r.name || '?').charAt(0))),
        h('div', { class: 'person-name' }, r.name),
        r.role ? h('div', { class: 'person-role' }, r.role) : null))));
  }

  async function toggleWatched(item, btn) {
    const watched = !item.watched;
    await JP.post('/api/watched/' + item.key, { watched });
    item.watched = watched;
    item.offset = 0;
    JP.toast(watched ? 'Markerad som sedd' : 'Markerad som osedd');
    JP.invalidate(true);
  }

  /* ---------- film eller avsnitt ---------- */

  JP.route(/^\/item\/([^/]+)$/, async (v, [key]) => {
    v.el.append(JP.spinner());
    const item = await JP.api('/api/item/' + key);
    if (item.type === 'show' || item.type === 'season') {
      JP.go(JP.link(item));
      return;
    }
    if (item.type === 'collection') { JP.go('#/collection/' + key); return; }
    const bg = backdrop(item);
    const isEp = item.type === 'episode';
    const title = isEp ? item.show || item.title : item.title;
    const actions = h('div', { class: 'actions' });
    const resume = item.offset > 0 && item.duration && item.offset < item.duration - 30000;
    actions.append(h('button', { class: 'btn primary', onclick: () => JP.play(item) }, JP.icon('play'), resume ? `Fortsätt · ${JP.remaining(item)}` : 'Spela'));
    if (resume) actions.append(h('button', { class: 'btn', onclick: () => JP.play(item, { fromStart: true }) }, 'Från början'));
    if ((item.trailers && item.trailers.length) || isEp) {
      const tb = h('button', { class: 'btn', onclick: () => JP.playTrailer(item) }, JP.icon('trailer'), 'Trailer');
      if (isEp) { tb.hidden = true; JP.trailerFor(item).then((t) => { tb.hidden = !t; }); }
      actions.append(tb);
    }
    actions.append(h('button', { class: 'btn', onclick: (e) => toggleWatched(item, e.currentTarget) }, JP.icon('check'), item.watched ? 'Markera som osedd' : 'Markera som sedd'));
    if (resume) actions.append(h('button', { class: 'btn ghost', onclick: async () => { await JP.post('/api/deck/remove/' + item.key); JP.toast('Borttagen från Fortsätt titta'); JP.invalidate(); } }, 'Ta bort från Fortsätt titta'));
    if (isEp && item.showKey) actions.append(h('a', { class: 'btn ghost', href: '#/show/' + item.showKey + (item.seasonKey ? '?season=' + item.seasonKey : '') }, 'Till serien'));

    const body = h('div', { class: 'details-body' },
      titleBlock(item, title),
      isEp ? h('div', { class: 'details-sub' }, [JP.epCode(item), item.title].filter(Boolean).join(' · ')) : null,
      h('div', { class: 'details-meta' }, isEp
        ? [item.originallyAvailableAt ? new Date(item.originallyAvailableAt).toLocaleDateString('sv-SE') : null, item.contentRating, JP.duration(item.duration)].filter(Boolean).join(' · ')
        : JP.metaLine(item)),
      JP.badgeRow(item),
      item.tagline ? h('div', { class: 'tagline' }, item.tagline) : null,
      item.summary ? h('p', { class: 'summary' }, item.summary) : null,
      JP.progress(item) ? h('div', { class: 'details-progress' }, JP.progress(item), h('span', { class: 'dim' }, JP.remaining(item))) : null,
      actions,
      people(item));
    const below = h('div', { class: 'details-below' }, castRow(item), (item.related || []).map((r) => JP.rowEl(r)));
    v.el.replaceChildren(h('div', { class: 'details' }, bg.el, body), below);
    bg.start();
    if (isEp && item.theme) JP.theme.play(item.theme);
    v.onLeave(() => { bg.stop(); JP.theme.stop(); });
    v.onShow(() => { bg.start(); if (isEp && item.theme) JP.theme.play(item.theme); });
  }, { page: 'details' });

  /* ---------- serie: säsonger till vänster, avsnitt till höger ---------- */

  function episodeRow(ep) {
    const still = JP.img(ep.still || ep.art || ep.thumb, 400, 225);
    const thumb = h('button', { class: 'ep-still', 'aria-label': 'Spela ' + ep.title, onclick: () => JP.play(ep) },
      still ? h('img', { src: still, alt: '', loading: 'lazy' }) : null,
      h('span', { class: 'ep-play' }, JP.icon('play')),
      JP.progress(ep),
      ep.watched && !ep.offset ? h('span', { class: 'card-corner seen' }, JP.icon('check')) : null);
    const facts = [JP.duration(ep.duration), ep.offset ? JP.remaining(ep) : null, ep.originallyAvailableAt ? new Date(ep.originallyAvailableAt).toLocaleDateString('sv-SE') : null].filter(Boolean).join(' · ');
    return h('div', { class: 'ep' + (ep.watched ? ' watched' : '') },
      thumb,
      h('div', { class: 'ep-text' },
        h('a', { class: 'ep-title', href: '#/item/' + ep.key }, h('span', { class: 'ep-num' }, ep.episode != null ? ep.episode + '.' : ''), ep.title),
        h('div', { class: 'ep-facts' }, facts),
        ep.summary ? h('p', { class: 'ep-summary' }, ep.summary) : null));
  }

  JP.route(/^\/show\/([^/]+)$/, async (v, [key], q) => {
    v.el.append(JP.spinner());
    const show = await JP.api('/api/item/' + key);
    if (show.type !== 'show') { JP.go(JP.link(show)); return; }
    const seasons = show.seasons || [];
    const deck = show.onDeck;
    const bg = backdrop(show);
    const actions = h('div', { class: 'actions' });
    if (deck) {
      const resumeLabel = deck.offset ? 'Fortsätt' : 'Spela';
      actions.append(h('button', { class: 'btn primary', onclick: () => JP.play(deck) }, JP.icon('play'), `${resumeLabel} ${JP.epCode(deck)}`));
    }
    actions.append(h('button', { class: 'btn', onclick: async () => { const ep = await JP.api('/api/random/' + key); if (ep) JP.play(ep); } }, JP.icon('shuffle'), 'Slumpvis'));
    if (show.trailers && show.trailers.length) actions.append(h('button', { class: 'btn', onclick: () => JP.playTrailer(show) }, JP.icon('trailer'), 'Trailer'));
    actions.append(h('button', { class: 'btn', onclick: (e) => toggleWatched(show, e.currentTarget) }, JP.icon('check'), show.watched || (show.leafs && show.viewedLeafs >= show.leafs) ? 'Markera som osedd' : 'Markera alla som sedda'));

    const seasonCount = seasons.length;
    const meta = [show.year, show.contentRating, seasonCount ? seasonCount + (seasonCount === 1 ? ' säsong' : ' säsonger') : null, show.genres && show.genres.slice(0, 3).join(', ')].filter(Boolean).join(' · ');
    const summary = show.summary ? h('p', { class: 'summary clamp' }, show.summary) : null;
    if (summary) summary.addEventListener('click', () => summary.classList.toggle('clamp'));

    const seasonList = h('div', { class: 'season-list', role: 'tablist' });
    const episodes = h('div', { class: 'episodes' });
    const wanted = q.get('season') || (deck && deck.seasonKey) || (seasons.find((s) => (s.leafs || 0) > (s.viewedLeafs || 0)) || seasons[0] || {}).key;
    let token = 0;
    async function select(season, btn) {
      for (const b of seasonList.children) b.classList.toggle('on', b === btn);
      const my = ++token;
      episodes.replaceChildren(JP.spinner());
      try {
        const eps = await JP.api('/api/children/' + season.key);
        if (my !== token) return;
        episodes.replaceChildren(h('div', { class: 'episodes-head' }, h('h2', null, season.title), season.summary ? h('p', { class: 'dim' }, season.summary) : null), ...eps.map(episodeRow));
        const target = deck && eps.find((e) => e.key === deck.key);
        if (target) {
          const idx = eps.indexOf(target);
          const el = episodes.querySelectorAll('.ep')[idx];
          if (el) el.classList.add('next-up');
        }
      } catch (e) {
        episodes.replaceChildren(JP.errorBox(e, () => select(season, btn)));
      }
    }
    for (const s of seasons) {
      const left = (s.leafs || 0) - (s.viewedLeafs || 0);
      const btn = h('button', { class: 'season', role: 'tab', onclick: () => select(s, btn) },
        h('span', null, s.title),
        h('span', { class: 'season-count' }, left > 0 && s.viewedLeafs ? left + ' osedda' : left <= 0 && s.viewedLeafs ? 'sedd ✓' : (s.leafs || '') + (s.leafs ? ' avsnitt' : '')));
      seasonList.append(btn);
      if (s.key === wanted) setTimeout(() => select(s, btn));
    }
    if (!seasons.length) episodes.append(h('div', { class: 'empty' }, 'Inga avsnitt'));

    const body = h('div', { class: 'details-body' },
      titleBlock(show, show.title),
      h('div', { class: 'details-meta' }, meta),
      JP.badgeRow(show),
      summary,
      deck ? h('div', { class: 'next-hint' }, h('span', { class: 'dim' }, 'Nästa: '), `${JP.epCode(deck)} · ${deck.title}`, deck.offset ? h('span', { class: 'dim' }, ' · ' + JP.remaining(deck)) : null) : null,
      actions);
    v.el.replaceChildren(
      h('div', { class: 'details show' }, bg.el, body),
      h('div', { class: 'season-layout' }, seasonList, episodes),
      h('div', { class: 'details-below' }, castRow(show), (show.related || []).map((r) => JP.rowEl(r))));
    bg.start();
    JP.theme.play(show.theme);
    v.onLeave(() => { bg.stop(); JP.theme.stop(); });
    v.onShow(() => { bg.start(); JP.theme.play(show.theme); });
  }, { page: 'details' });

  /* ---------- sök ---------- */

  JP.route(/^\/search$/, async (v, _m, q) => {
    const input = h('input', { type: 'search', class: 'search-input', placeholder: 'Sök filmer, serier, avsnitt och kanaler', value: q.get('q') || '', autocomplete: 'off' });
    const results = h('div', { class: 'search-results' });
    v.el.append(h('div', { class: 'page' }, input, results));
    let token = 0;
    const run = JP.debounce(async () => {
      const text = input.value.trim();
      history.replaceState(null, '', '#/search' + (text ? '?q=' + encodeURIComponent(text) : ''));
      const my = ++token;
      if (!text) { results.replaceChildren(); return; }
      results.replaceChildren(JP.spinner());
      try {
        const data = await JP.api('/api/search?q=' + encodeURIComponent(text));
        if (my !== token) return;
        const parts = [];
        if (data.channels.length) {
          parts.push(h('section', { class: 'search-group' }, h('h2', null, 'Kanaler'),
            h('div', { class: 'channel-chips' }, data.channels.map((c) => h('a', { class: 'channel-chip', href: '#/live?ch=' + c.id },
              c.logo ? h('img', { src: c.logo, alt: '', loading: 'lazy', onerror: (e) => e.target.remove() }) : null,
              h('span', null, h('b', null, c.name), c.now ? h('small', null, c.now.title) : null))))));
        }
        for (const hub of data.hubs) parts.push(h('section', { class: 'search-group' }, h('h2', null, hub.title), JP.posterGrid(hub.items, false)));
        if (!parts.length) parts.push(h('div', { class: 'dim search-none' }, `Inget i Plex eller bland kanalerna för "${text}".`));
        // Det som saknas i Plex kan önskas.
        if (JP.status && JP.status.plex) parts.push(JP.wishSection(text));
        results.replaceChildren(...parts);
      } catch (e) {
        results.replaceChildren(JP.errorBox(e));
      }
    }, 300);
    input.addEventListener('input', run);
    v.onShow(() => input.focus());
    setTimeout(() => input.focus());
    if (input.value) run();
  }, { nocache: true });

  /* ---------- inställningar ---------- */

  function field(label, control, help) {
    return h('label', { class: 'field' }, h('span', { class: 'field-label' }, label), control, help ? h('span', { class: 'field-help' }, help) : null);
  }

  function ago(ms) {
    if (!ms) return 'aldrig';
    const min = Math.round((Date.now() - ms) / 60000);
    if (min < 1) return 'nyss';
    if (min < 60) return min + ' min sedan';
    const hrs = Math.round(min / 60);
    return hrs < 48 ? hrs + ' h sedan' : Math.round(hrs / 24) + ' dagar sedan';
  }

  function hwLine(hw) {
    if (!hw || !hw.checked) return h('p', { class: 'dim small' }, 'Intel QuickSync: kontrolleras …');
    if (hw.available) return h('p', { class: 'small' }, '✓ Intel QuickSync används för avflätning och omkodning av live-TV' + (hw.mode === 'bob' ? ' (enkel avflätning)' : '') + '.');
    return h('p', { class: 'dim small' }, 'Intel QuickSync används inte – processorn gör jobbet. ' + (hw.error || ''));
  }

  JP.route(/^\/settings$/, async (v) => {
    const st = await JP.refreshStatus();
    const s = st.settings;
    const save = async (patch, msg) => {
      try {
        const res = await JP.post('/api/settings', patch);
        JP.status.settings = res;
        if (msg) JP.toast(msg);
        return res;
      } catch (e) {
        JP.toast(e.message);
        throw e;
      }
    };

    // Plex
    const plexBox = h('section', { class: 'card-box' }, h('h2', null, 'Plex'));
    if (st.plex) {
      const uri = h('input', { type: 'text', value: st.server.uri, spellcheck: 'false' });
      plexBox.append(
        h('p', null, 'Inloggad. Server: ', h('b', null, st.server.name)),
        field('Serverns adress', uri, 'Hittas automatiskt. Ändra bara om servern har bytt adress och inte hittas.'),
        h('div', { class: 'actions' },
          h('button', { class: 'btn', onclick: async () => { await save({ serverUri: uri.value }, 'Adressen sparad'); } }, 'Spara adress'),
          h('button', { class: 'btn ghost', onclick: async () => { await JP.post('/api/logout'); await JP.refreshStatus(); JP.invalidate(); JP.go('#/login'); } }, 'Logga ut')));
    } else {
      plexBox.append(h('p', { class: 'dim' }, 'Inte inloggad.'), h('a', { class: 'btn primary', href: '#/login' }, 'Logga in på Plex'));
    }

    // Live-TV
    const m3u = h('input', { type: 'url', value: s.m3uUrl, placeholder: 'http://leverantör/get.php?username=…&password=…&type=m3u_plus', spellcheck: 'false' });
    const epg = h('input', { type: 'url', value: s.epgUrl, placeholder: st.iptv.epgUrl ? 'Automatiskt: ' + st.iptv.epgUrl.replace(/password=[^&]*/, 'password=…') : 'Adress till XMLTV-guide (gärna .xml.gz)', spellcheck: 'false' });
    const iptvState = h('div', { class: 'state' });
    const renderState = (i) => {
      const lines = [];
      if (i.loading) lines.push('Hämtar spellistan …');
      else if (i.error) lines.push(h('span', { class: 'err' }, 'Spellistan: ' + i.error));
      else if (i.channels) lines.push(`${i.channels} kanaler i ${i.groups} grupper · hämtad ${ago(i.loadedAt)}`);
      if (i.epgLoading) lines.push('Hämtar TV-guiden …');
      else if (i.epgError) lines.push(h('span', { class: 'err' }, 'Guiden: ' + i.epgError));
      else if (i.epgChannels) lines.push(`Guide för ${i.epgChannels} kanaler · hämtad ${ago(i.epgLoadedAt)}`);
      iptvState.replaceChildren(...lines.map((l) => h('div', null, l)));
    };
    renderState(st.iptv);
    let poll = null;
    const watch = () => {
      clearInterval(poll);
      poll = setInterval(async () => {
        const fresh = await JP.api('/api/status').catch(() => null);
        if (!fresh) return;
        renderState(fresh.iptv);
        if (!fresh.iptv.loading && !fresh.iptv.epgLoading) {
          clearInterval(poll);
          JP.status = fresh;
          JP.buildNav();
          JP.updateNav('/settings');
        }
      }, 1500);
    };
    v.onLeave(() => clearInterval(poll));
    if (st.iptv.loading || st.iptv.epgLoading) watch();
    const tvBox = h('section', { class: 'card-box' }, h('h2', null, 'Live-TV'),
      field('Spellista (M3U)', m3u, 'Samma adress som på TV:n. Bara live-kanalerna används; film och serier hoppas över.'),
      field('TV-guide (XMLTV)', epg, 'Lämna tomt för att hitta den automatiskt.'),
      h('div', { class: 'actions' },
        h('button', { class: 'btn primary', onclick: async () => { await save({ m3uUrl: m3u.value, epgUrl: epg.value }, 'Sparat – hämtar kanalerna'); watch(); } }, 'Spara'),
        h('button', { class: 'btn', onclick: async () => { await JP.post('/api/iptv/reload'); JP.toast('Hämtar på nytt'); watch(); } }, 'Uppdatera nu')),
      iptvState);

    // Uppspelning
    const quality = h('select', { onchange: (e) => save({ quality: +e.target.value }, 'Sparat') }, s.qualities.map((q, i) => h('option', { value: i, selected: i === s.quality }, q)));
    const liveTx = h('select', { onchange: (e) => save({ liveTranscode: e.target.value }, 'Sparat') },
      [['auto', 'Automatiskt (rekommenderas)'], ['always', 'Alltid'], ['never', 'Aldrig']].map(([k, l]) => h('option', { value: k, selected: k === s.liveTranscode }, l)));
    const deint = h('select', { onchange: (e) => save({ liveDeinterlace: e.target.value }, 'Sparat') },
      [['auto', 'Automatiskt – 50 bilder/s (bäst för sport)'], ['off', 'Av – som kanalen sänds']].map(([k, l]) => h('option', { value: k, selected: k === (s.liveDeinterlace || 'auto') }, l)));
    const themeSel = h('select', { onchange: (e) => save({ themeLevel: +e.target.value }, 'Sparat') },
      ['Av', 'Lågt', 'Mellan'].map((l, i) => h('option', { value: i, selected: i === s.themeLevel }, l)));
    const trailers = h('input', { type: 'checkbox', checked: JP.store.get('heroTrailers', true), onchange: (e) => JP.store.set('heroTrailers', e.target.checked) });
    const playBox = h('section', { class: 'card-box' }, h('h2', null, 'Uppspelning'),
      field('Kvalitet för Plex', quality, 'Filer som webbläsaren klarar spelas som de är upp till vald nivå; annat kodar Plex om.'),
      hwLine(st.hardware),
      field('Avfläta live-TV', deint, 'De flesta HD-kanaler sänds radsprångat (1080i): 50 halvbilder per sekund. I webbläsaren blir rörelser då ofta hackiga eller randiga. Avflätningen på Unraid gör dem till 50 hela bilder/s, som en TV gör – mjukare sport. Kostar processorkraft på Unraid; hinner den inte med visas kanalen som den sänds.'),
      field('Koda om live-TV', liveTx, 'Automatiskt: bilden skickas som den är, och kodas om bara för kanaler som webbläsaren inte kan visa (t.ex. HEVC).'),
      field('Ledmotiv på seriesidor', themeSel),
      h('label', { class: 'check big' }, trailers, 'Trailer i bakgrunden (i den här webbläsaren)'));

    const im = st.imdb || {};
    const imdbLine = im.loading ? 'IMDb-betyg: hämtas …'
      : im.count ? `IMDb-betyg för ${im.count.toLocaleString('sv-SE')} titlar · hämtade ${ago(im.updatedAt)} (uppdateras varje vecka)`
        : im.error ? 'IMDb-betyg: kunde inte hämtas (' + im.error + ')' : 'IMDb-betyg: hämtas strax';
    // Radarr och Sonarr: godkända önskemål skickas dit.
    const arrBox = h('section', { class: 'card-box' }, h('h2', null, 'Önskemål'),
      h('p', { class: 'dim' }, 'Önskade filmer och serier hamnar under Önskemål. Med Radarr (filmer) och Sonarr (serier) skickar OK titeln dit direkt; adressen och API-nyckeln finns i respektive app under Settings → General. Utan dem markerar OK bara önskemålet. Titeln läggs till med kvalitetsprofilen och rotmappen nedan och söks direkt; finns profilen eller mappen inte stoppar JPTV hellre än att gissa.'));
    for (const [kind, label] of [['radarr', 'Radarr'], ['sonarr', 'Sonarr']]) {
      const url = h('input', { type: 'text', value: s[kind + 'Url'] || '', placeholder: kind === 'radarr' ? 'http://192.168.x.x:7878' : 'http://192.168.x.x:8989', spellcheck: 'false' });
      const key = h('input', { type: 'password', value: '', placeholder: s[kind + 'Key'] ? 'Sparad – skriv en ny för att byta' : 'API-nyckel', autocomplete: 'off' });
      // Profil och rotmapp väljs med namn. Förslagen hämtas från Radarr/Sonarr.
      const profiles = h('datalist', { id: kind + '-profiles' });
      const roots = h('datalist', { id: kind + '-roots' });
      const profile = h('input', { type: 'text', value: s[kind + 'Profile'] || '', list: kind + '-profiles', spellcheck: 'false' });
      const root = h('input', { type: 'text', value: s[kind + 'Root'] || '', list: kind + '-roots', spellcheck: 'false' });
      const fill = (o) => {
        profiles.replaceChildren(...(o.profiles || []).map((n) => h('option', { value: n })));
        roots.replaceChildren(...(o.roots || []).map((n) => h('option', { value: n })));
      };
      if (s[kind + 'Url'] && s[kind + 'Key']) JP.api('/api/arr/options/' + kind).then(fill).catch(() => {});
      const state = h('span', { class: 'dim small' });
      arrBox.append(
        h('div', { class: 'arr-row' },
          field(label + ' – adress', url),
          field(label + ' – API-nyckel', key)),
        h('div', { class: 'arr-row' },
          field(label + ' – kvalitetsprofil', profile),
          field(label + ' – rotmapp', root)),
        profiles, roots,
        h('div', { class: 'actions' },
          h('button', { class: 'btn small', onclick: async () => {
            await save({ [kind + 'Url']: url.value, [kind + 'Key']: key.value, [kind + 'Profile']: profile.value, [kind + 'Root']: root.value });
            JP.api('/api/arr/options/' + kind).then(fill).catch(() => {});
            key.value = '';
            key.placeholder = 'Sparad – skriv en ny för att byta';
            state.textContent = 'Testar …';
            try { state.textContent = '✓ ' + (await JP.post('/api/arr/test/' + kind)).ok; } catch (e) { state.textContent = e.message; }
          } }, 'Spara och testa'),
          s[kind + 'Key'] ? h('button', { class: 'btn ghost small', onclick: async () => { await save({ [kind + 'Url']: '', [kind + 'Key']: '-' }, label + ' borttagen'); url.value = ''; state.textContent = ''; } }, 'Ta bort') : null,
          state));
    }

    const about = h('section', { class: 'card-box' }, h('h2', null, 'Om'),
      h('p', { class: 'dim' }, imdbLine),
      h('p', { class: 'dim' }, `JPTV Web ${st.version}. Live-TV använder en ström åt gången: startar du en kanal här stängs den som spelades i ett annat fönster. TV:n och mobilen räknas för sig hos leverantören.`),
      h('p', { class: 'dim' }, 'Tangenter i spelaren: mellanslag = paus, ← → = 10 s, ↑ ↓ = volym, F = helskärm, M = ljud av, Esc = tillbaka. I live-TV byter ↑ ↓ kanal.'));

    v.el.append(h('div', { class: 'page narrow' }, h('h1', null, 'Inställningar'), plexBox, tvBox, playBox, st.plex ? arrBox : null, about));
  }, { nocache: true });

  /* ---------- inloggning ---------- */

  JP.route(/^\/login$/, async (v) => {
    const box = h('div', { class: 'login-box' });
    v.el.append(h('div', { class: 'login' }, h('div', { class: 'brand big' }, h('span', null, 'JP'), 'TV'), box));
    let poll = null;
    v.onLeave(() => clearInterval(poll));
    const intro = () => {
      box.replaceChildren(
        h('h1', null, 'Välkommen'),
        h('p', { class: 'dim' }, 'Logga in på Plex för filmer och serier. Live-TV ställer du in under Inställningar.'),
        h('div', { class: 'actions center' },
          h('button', { class: 'btn primary', onclick: start }, 'Logga in på Plex'),
          h('a', { class: 'btn', href: '#/settings' }, 'Bara live-TV')));
    };
    const start = async () => {
      box.replaceChildren(JP.spinner('Hämtar kod …'));
      let pin;
      try {
        pin = await JP.post('/api/login/start');
      } catch (e) {
        box.replaceChildren(JP.errorBox(e, start));
        return;
      }
      box.replaceChildren(
        h('h1', null, 'Logga in på Plex'),
        h('p', null, 'Gå till ', h('a', { href: pin.url, target: '_blank', rel: 'noopener' }, 'plex.tv/link'), ' och skriv in koden:'),
        h('div', { class: 'pin' }, pin.code),
        h('p', { class: 'dim' }, 'Sidan går vidare av sig själv när koden är godkänd.'));
      clearInterval(poll);
      const started = Date.now();
      poll = setInterval(async () => {
        if (Date.now() - started > 15 * 60e3) { clearInterval(poll); intro(); return; }
        try {
          const r = await JP.api('/api/login/check?id=' + pin.id);
          if (r.done) {
            clearInterval(poll);
            box.replaceChildren(h('h1', null, 'Klart!'), h('p', null, 'Ansluten till ', h('b', null, r.server), '.'));
            await JP.refreshStatus();
            JP.invalidate();
            setTimeout(() => JP.go('#/'), 900);
          }
        } catch (e) {
          clearInterval(poll);
          box.replaceChildren(JP.errorBox(e, start));
        }
      }, 2000);
    };
    intro();
  }, { nocache: true });
})();
