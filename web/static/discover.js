/* JPTV Web – Upptäck (som Seerr): trendande, populärt och kommande från TMDB, och önska det som saknas i Plex. */
'use strict';
(function () {
  const { h } = JP;

  const STATUS = {
    pending: 'Önskad · väntar på OK',
    approved: 'Godkänd · på väg',
    available: 'Finns nu i Plex',
    declined: 'Fick nej',
  };
  // Färger på genrekorten (som Seerr), i tur och ordning.
  const TINTS = ['#9b2335', '#6a3fb5', '#1f6f8b', '#a8441f', '#24406e', '#1d7a5f', '#b0477a', '#7c8f2a', '#3b4fb8', '#7a2f8f', '#2b7a8c', '#a23b3b', '#5a6b1e', '#8a4b2a'];

  /** TMDB-bilder går via servern; fulla TMDB-adresser (sparade i önskemålen) görs om dit. */
  JP.tmdbImg = function (url, size) {
    if (!url) return null;
    const m = /image\.tmdb\.org\/t\/p\/[^/]+(\/[A-Za-z0-9_-]+\.(?:jpg|jpeg|png|svg))$/.exec(url);
    if (m) return `/tmdb/img/${size || 'w342'}${m[1]}`;
    return url;
  };

  const link = (it) => `#/discover/${it.type === 'show' ? 'show' : 'movie'}/${it.tmdb}`;

  /** Sidhuvud för Önskemål-fliken: Upptäck, listan med önskemål, och sök. */
  JP.wishHead = function (active, query) {
    const st = JP.status || {};
    const tab = (key, href, label) => h('a', { class: 'tab' + (active === key ? ' on' : ''), href }, label);
    const input = h('input', { type: 'search', class: 'search-input d-search', value: query || '', autocomplete: 'off', spellcheck: 'false',
      placeholder: st.tmdb ? 'Sök filmer, serier och personer' : 'Önska en film eller serie – sök i Plex katalog' });
    const form = h('form', { class: 'd-search-form', onsubmit: (e) => {
      e.preventDefault();
      const q = input.value.trim();
      if (q) JP.go('#/discover/search?q=' + encodeURIComponent(q));
    } }, input);
    return h('div', { class: 'd-head' },
      h('h1', null, 'Önskemål'),
      st.tmdb ? form : null,
      h('div', { class: 'tabs' },
        tab('discover', '#/discover', 'Upptäck'),
        tab('requests', '#/requests', h('span', null, 'Önskemål', st.requests ? h('span', { class: 'nav-badge' }, st.requests) : null))));
  };

  /* ---------- byggstenar ---------- */

  function stateCorner(it) {
    if (it.libraryKey) return h('span', { class: 'd-state have', title: 'Finns i Plex' }, JP.icon('check'));
    const r = it.request;
    if (!r || r.status === 'declined') return null;
    if (r.status === 'available') return h('span', { class: 'd-state have', title: STATUS.available }, JP.icon('check'));
    if (r.status === 'approved') return h('span', { class: 'd-state ok', title: STATUS.approved }, JP.icon('right'));
    return h('span', { class: 'd-state wait', title: STATUS.pending }, JP.icon('star'));
  }

  /** Affisch med FILM/SERIE, Finns i Plex/önskad, och texten när man håller musen över. */
  function card(it, opts = {}) {
    const a = h('a', { class: 'card d-card' + (opts.grid ? ' in-grid' : ''), href: link(it), 'aria-label': it.title });
    const art = h('div', { class: 'card-art' });
    if (it.poster) {
      const im = h('img', { src: it.poster, alt: '', loading: 'lazy', decoding: 'async', draggable: 'false' });
      im.addEventListener('error', () => im.replaceWith(h('div', { class: 'card-fallback' }, it.title)));
      art.append(im);
    } else art.append(h('div', { class: 'card-fallback' }, it.title));
    art.append(h('span', { class: 'd-badge ' + it.type }, it.type === 'show' ? 'SERIE' : 'FILM'));
    const corner = stateCorner(it);
    if (corner) art.append(corner);
    art.append(h('div', { class: 'd-hover' },
      h('div', { class: 'd-hover-year' }, [it.year, it.rating ? '★ ' + it.rating.toLocaleString('sv-SE') : null].filter(Boolean).join(' · ')),
      h('div', { class: 'd-hover-title' }, it.title),
      it.overview ? h('div', { class: 'd-hover-text' }, it.overview) : null));
    a.append(art, h('div', { class: 'card-text' }, h('div', { class: 'card-title' }, it.title), h('div', { class: 'card-sub' }, it.year || '')));
    return a;
  }

  /** En rad som rullar i sidled, med pilar och "Visa alla". */
  function row(title, href, children, cls) {
    if (!children.length) return null;
    const strip = h('div', { class: 'strip ' + (cls || '') }, children);
    const scroll = (dir) => strip.scrollBy({ left: dir * strip.clientWidth * 0.85, behavior: 'smooth' });
    const left = h('button', { class: 'strip-arrow left', 'aria-label': 'Bakåt', onclick: () => scroll(-1) }, JP.icon('left'));
    const right = h('button', { class: 'strip-arrow right', 'aria-label': 'Framåt', onclick: () => scroll(1) }, JP.icon('right'));
    const update = () => {
      left.hidden = strip.scrollLeft < 8;
      right.hidden = strip.scrollLeft + strip.clientWidth >= strip.scrollWidth - 8;
    };
    strip.addEventListener('scroll', update, { passive: true });
    requestAnimationFrame(update);
    new ResizeObserver(update).observe(strip);
    return h('section', { class: 'row d-row' },
      h('h2', null, href ? h('a', { href }, title, h('span', { class: 'd-more' }, 'Visa alla ›')) : title),
      h('div', { class: 'strip-wrap' }, left, strip, right));
  }

  function genreTile(g, media, i) {
    const a = h('a', { class: 'd-genre', href: `#/discover/list/genre-${media}?id=${g.id}&t=${encodeURIComponent(g.name)}` });
    a.style.setProperty('--tint', TINTS[i % TINTS.length]);
    if (g.backdrop) a.append(h('img', { src: g.backdrop, alt: '', loading: 'lazy' }));
    a.append(h('span', null, g.name));
    return a;
  }

  function logoTile(o, what) {
    const a = h('a', { class: 'd-logo', href: `#/discover/list/${what}?id=${o.id}&t=${encodeURIComponent(o.name)}`, title: o.name });
    if (o.logo) {
      const im = h('img', { src: o.logo, alt: o.name, loading: 'lazy' });
      im.addEventListener('error', () => im.replaceWith(h('span', null, o.name)));
      a.append(im);
    } else a.append(h('span', null, o.name));
    return a;
  }

  function wishTile(w) {
    const href = w.tmdb ? `#/discover/${w.type === 'show' ? 'show' : 'movie'}/${w.tmdb}` : '#/requests';
    const src = w.thumb ? (/image\.tmdb\.org/.test(w.thumb) ? JP.tmdbImg(w.thumb, 'w342') : JP.img(w.thumb, 240, 360)) : null;
    return h('a', { class: 'd-wish', href },
      h('div', { class: 'd-wish-art' }, src ? h('img', { src, alt: '', loading: 'lazy' }) : null),
      h('div', { class: 'd-wish-text' },
        h('div', { class: 'dim small' }, w.year || ''),
        h('div', { class: 'd-wish-title' }, w.title),
        h('div', { class: 'dim small' }, 'Önskad av ' + [w.by, ...(w.also || [])].join(', ')),
        h('span', { class: 'd-pill ' + w.status }, STATUS[w.status] || w.status)));
  }

  function setupBox() {
    return h('div', { class: 'empty d-setup' },
      h('div', { class: 'empty-title' }, 'Upptäck behöver en TMDB-nyckel'),
      h('div', { class: 'dim' }, 'TMDB (themoviedb.org) är filmdatabasen som Seerr också använder. Nyckeln är gratis: skapa ett konto på themoviedb.org, gå till Inställningar → API och kopiera "API-nyckel" eller "API Read Access Token". Klistra in den under Inställningar → Önskemål här i JPTV.'),
      h('div', { class: 'actions center' },
        h('a', { class: 'btn primary', href: '#/settings' }, 'Till Inställningar'),
        h('a', { class: 'btn ghost', href: '#/requests' }, 'Önskemålen')));
  }

  function problem(e, retry) {
    return h('div', { class: 'empty' }, h('div', { class: 'empty-title' }, 'TMDB svarar inte som det ska'), h('div', { class: 'dim' }, e.message),
      h('div', { class: 'actions center' }, h('button', { class: 'btn', onclick: retry }, 'Försök igen'), h('a', { class: 'btn ghost', href: '#/settings' }, 'Inställningar')));
  }

  const rebuild = (v) => () => { v.dirty = true; JP.current = null; JP.render(); };

  /* ---------- Upptäck ---------- */

  JP.route(/^\/discover$/, async (v) => {
    const page = h('div', { class: 'page wide d-page' }, JP.wishHead('discover'));
    v.el.append(page);
    if (!JP.status || !JP.status.tmdb) { page.append(setupBox()); return; }
    const body = h('div', null, JP.spinner('Hämtar från TMDB …'));
    page.append(body);
    let data;
    try {
      data = await JP.api('/api/tmdb/home');
    } catch (e) {
      body.replaceChildren(problem(e, rebuild(v)));
      return;
    }
    const parts = [];
    if (data.recent.length && JP.card) parts.push(row('Nyligen tillagt i Plex', null, data.recent.map((i) => JP.card(i))));
    if (data.requests.length) parts.push(row('Senaste önskemålen', '#/requests', data.requests.map(wishTile), 'd-wishes'));
    let genreIndex = 0;
    for (const r of data.rows) {
      if (r.kind === 'titles') parts.push(row(r.title, `#/discover/list/${r.id}`, r.items.map((i) => card(i))));
      else if (r.kind === 'genres') parts.push(row(r.title, null, r.items.map((g) => genreTile(g, r.media, genreIndex++)), 'd-genres'));
      else if (r.kind === 'studios') parts.push(row(r.title, null, r.items.map((o) => logoTile(o, 'studio')), 'd-logos'));
      else if (r.kind === 'networks') parts.push(row(r.title, null, r.items.map((o) => logoTile(o, 'network')), 'd-logos'));
    }
    body.replaceChildren(...parts.filter(Boolean));
  });

  /* ---------- Visa alla (rullar vidare av sig själv) ---------- */

  const LIST_TITLES = {
    trending: 'Trendar just nu', 'popular-movies': 'Populära filmer', 'upcoming-movies': 'Kommande filmer',
    'popular-series': 'Populära serier', 'upcoming-series': 'Kommande serier',
  };

  function pagedGrid(fetchPage, onFirst) {
    const grid = h('div', { class: 'grid d-grid' });
    const more = h('div', { class: 'd-loadmore' }, JP.spinner());
    let page = 0, pages = 1, busy = false;
    const seen = new Set();
    const next = async () => {
      if (busy || page >= pages) return;
      busy = true;
      try {
        const data = await fetchPage(page + 1);
        if (page === 0 && onFirst) onFirst(data);
        page = data.page;
        pages = data.pages;
        // Listan kan flytta sig mellan sidorna hos TMDB: samma titel bara en gång.
        const fresh = data.items.filter((i) => !seen.has(i.type + i.tmdb) && seen.add(i.type + i.tmdb));
        grid.append(...fresh.map((i) => card(i, { grid: true })));
        if (page >= pages || !data.items.length) more.remove();
        // Syns slutet fortfarande (stor skärm, få titlar)? Då nästa sida direkt.
        else requestAnimationFrame(() => { if (more.isConnected && more.getBoundingClientRect().top < innerHeight + 800) next(); });
      } catch (e) {
        more.replaceChildren(h('div', { class: 'dim' }, e.message));
        pages = 0;
      }
      busy = false;
    };
    const io = new IntersectionObserver((entries) => { if (entries.some((e) => e.isIntersecting)) next(); }, { rootMargin: '800px' });
    io.observe(more);
    return { el: h('div', null, grid, more), stop: () => io.disconnect() };
  }

  JP.route(/^\/discover\/list\/([a-z-]+)$/, async (v, [what], q) => {
    const name = q.get('t');
    const heading = name
      ? ({ 'genre-movie': `Filmer · ${name}`, 'genre-tv': `Serier · ${name}`, studio: `Filmer från ${name}`, network: `Serier på ${name}` }[what] || name)
      : (LIST_TITLES[what] || 'Titlar');
    const page = h('div', { class: 'page wide d-page' }, JP.wishHead('discover'), h('h2', { class: 'd-list-title' }, heading));
    v.el.append(page);
    const id = q.get('id');
    const list = pagedGrid((p) => JP.api(`/api/tmdb/list/${what}?page=${p}${id ? '&id=' + id : ''}`));
    page.append(list.el);
    v.onLeave(list.stop);
  });

  /* ---------- sök ---------- */

  JP.route(/^\/discover\/search$/, async (v, _m, q) => {
    const query = q.get('q') || '';
    const page = h('div', { class: 'page wide d-page' }, JP.wishHead('discover', query));
    v.el.append(page);
    if (!JP.status || !JP.status.tmdb) { page.append(setupBox()); return; }
    if (!query) return;
    const people = h('div');
    page.append(people);
    const list = pagedGrid((p) => JP.api(`/api/tmdb/search?q=${encodeURIComponent(query)}&page=${p}`), (first) => {
      if (first.people.length) {
        people.replaceChildren(row('Personer', null, first.people.map(personTile), 'd-people'));
      }
      if (!first.items.length) list.el.prepend(h('div', { class: 'empty small' }, 'Inga filmer eller serier hittades.'));
    });
    page.append(h('h2', { class: 'd-list-title' }, 'Filmer och serier'), list.el);
    v.onLeave(list.stop);
  });

  function personTile(p) {
    return h('a', { class: 'd-person', href: '#/discover/person/' + p.id },
      h('div', { class: 'd-person-photo' }, p.photo ? h('img', { src: p.photo, alt: '', loading: 'lazy' }) : h('span', null, (p.name || '?').slice(0, 1))),
      h('div', { class: 'd-person-name' }, p.name),
      h('div', { class: 'dim small' }, p.role || p.department || ''));
  }

  /* ---------- titelsidan ---------- */

  const fmtDate = (d) => (d ? new Date(d + 'T12:00:00').toLocaleDateString('sv-SE', { year: 'numeric', month: 'long', day: 'numeric' }) : null);
  const LANG = { en: 'Engelska', sv: 'Svenska', da: 'Danska', no: 'Norska', fi: 'Finska', de: 'Tyska', fr: 'Franska', es: 'Spanska', it: 'Italienska', ja: 'Japanska', ko: 'Koreanska', zh: 'Kinesiska', hi: 'Hindi' };
  const STATUS_TMDB = {
    Released: 'Har haft premiär', 'Post Production': 'Efterbearbetning', 'In Production': 'Spelas in', Planned: 'Planerad',
    Rumored: 'Ryktas', Canceled: 'Inställd', 'Returning Series': 'Pågår', Ended: 'Avslutad', 'In production': 'Spelas in',
  };

  function actions(t, refresh) {
    const box = h('div', { class: 'actions d-actions' });
    if (t.libraryKey) {
      box.append(h('a', { class: 'btn primary', href: (t.type === 'show' ? '#/show/' : '#/item/') + t.libraryKey }, JP.icon('play'), 'Finns i Plex – öppna'));
    } else if (t.request && t.request.status !== 'declined') {
      box.append(h('span', { class: 'd-pill big ' + t.request.status }, STATUS[t.request.status] || t.request.status));
    } else {
      box.append(h('button', { class: 'btn primary', onclick: () => {
        JP.wishDialog({
          tmdb: t.tmdb, type: t.type, title: t.title, year: t.year, summary: t.overview, ids: t.ids,
          thumb: t.posterFull, art: t.backdropFull, request: t.request, libraryKey: t.libraryKey,
        }, (item) => { t.request = item.request; refresh(); });
      } }, JP.icon('star'), t.type === 'show' ? 'Önska serien' : 'Önska filmen'));
    }
    if (t.trailer && JP.status && JP.status.youtube) {
      box.append(h('button', { class: 'btn', onclick: () => JP.playYt(t.trailer.youtube, { trailer: true }) }, JP.icon('trailer'), 'Trailer'));
    }
    return box;
  }

  JP.route(/^\/discover\/(movie|show)\/(\d+)$/, async (v, [type, id]) => {
    const page = h('div', { class: 'd-title-page' }, JP.spinner());
    v.el.append(page);
    let t;
    try {
      t = await JP.api(`/api/tmdb/title/${type}/${id}`);
    } catch (e) {
      page.replaceChildren(h('div', { class: 'page' }, JP.wishHead('discover'), problem(e, rebuild(v))));
      return;
    }
    const meta = [t.certification, t.type === 'show'
      ? [t.seasons ? `${t.seasons} ${t.seasons === 1 ? 'säsong' : 'säsonger'}` : null, t.runtime ? `${t.runtime} min/avsnitt` : null].filter(Boolean).join(', ')
      : (t.runtime ? JP.duration(t.runtime * 60000) : null), t.genres.join(', ')].filter(Boolean);
    const actBox = h('div');
    const refresh = () => actBox.replaceChildren(actions(t, refresh));
    refresh();
    const streaming = t.providers.filter((p) => p.kind === 'Streaming' || p.kind === 'Gratis' || p.kind === 'Med reklam');
    const other = t.providers.filter((p) => !streaming.includes(p));
    const facts = [
      ['Status', STATUS_TMDB[t.status] || t.status],
      [t.type === 'show' ? 'Första avsnittet' : 'Premiär', fmtDate(t.date)],
      t.type === 'show' && t.lastDate ? ['Senaste avsnittet', fmtDate(t.lastDate)] : null,
      t.type === 'show' && t.episodes ? ['Avsnitt', `${t.episodes} i ${t.seasons} ${t.seasons === 1 ? 'säsong' : 'säsonger'}`] : null,
      [t.type === 'show' ? 'Skapad av' : 'Regi', t.makers.join(', ')],
      [t.type === 'show' ? 'Nätverk' : 'Studio', (t.type === 'show' ? t.networks : t.companies).join(', ')],
      ['Originaltitel', t.originalTitle !== t.title ? t.originalTitle : null],
      ['Originalspråk', LANG[t.language] || (t.language || '').toUpperCase()],
    ].filter((f) => f && f[1]);
    const hero = h('div', { class: 'd-hero' },
      t.backdrop ? h('img', { class: 'd-hero-bg', src: t.backdrop, alt: '' }) : null,
      h('div', { class: 'd-hero-inner' },
        h('div', { class: 'd-hero-poster' }, t.poster ? h('img', { src: t.poster, alt: '' }) : h('div', { class: 'card-fallback' }, t.title)),
        h('div', { class: 'd-hero-info' },
          h('div', { class: 'd-hero-badges' }, h('span', { class: 'd-badge inline ' + t.type }, t.type === 'show' ? 'SERIE' : 'FILM'),
            t.rating ? h('span', { class: 'd-rating' }, '★ ' + t.rating.toLocaleString('sv-SE'), h('span', { class: 'dim' }, ` · ${t.votes.toLocaleString('sv-SE')} röster på TMDB`)) : null),
          h('h1', null, t.title, t.year ? h('span', { class: 'dim' }, ` (${t.year})`) : null),
          meta.length ? h('div', { class: 'd-meta' }, meta.join(' · ')) : null,
          t.tagline ? h('div', { class: 'd-tagline' }, t.tagline) : null,
          actBox,
          t.overview ? h('p', { class: 'd-overview' }, t.overview) : null,
          streaming.length ? h('div', { class: 'd-providers' }, h('span', { class: 'dim small' }, 'Att se i Sverige:'),
            ...streaming.map((p) => h('span', { class: 'd-provider', title: p.name }, p.logo ? h('img', { src: p.logo, alt: p.name }) : p.name))) : null,
          other.length ? h('div', { class: 'dim small' }, 'Hyra eller köpa: ' + other.map((p) => p.name).join(', ')) : null)));
    const factBox = facts.length ? h('div', { class: 'd-facts' }, facts.map(([k, val]) => h('div', { class: 'd-fact' }, h('div', { class: 'dim small' }, k), h('div', null, val)))) : null;
    page.replaceChildren(...[
      hero,
      h('div', { class: 'page wide d-title-body' }, ...[
        factBox,
        t.cast.length ? row('Rollista', null, t.cast.map(personTile), 'd-people') : null,
        row('Rekommendationer', null, t.recommendations.map((i) => card(i))),
        row(t.type === 'show' ? 'Liknande serier' : 'Liknande filmer', null, t.similar.map((i) => card(i))),
      ].filter(Boolean)),
    ]);
  });

  /* ---------- personsidan ---------- */

  JP.route(/^\/discover\/person\/(\d+)$/, async (v, [id]) => {
    const page = h('div', { class: 'page wide d-page' }, JP.wishHead('discover'));
    v.el.append(page);
    const body = h('div', null, JP.spinner());
    page.append(body);
    let p;
    try {
      p = await JP.api('/api/tmdb/person/' + id);
    } catch (e) {
      body.replaceChildren(problem(e, rebuild(v)));
      return;
    }
    const born = [p.birthday ? 'Född ' + fmtDate(p.birthday) : null, p.place, p.deathday ? 'död ' + fmtDate(p.deathday) : null].filter(Boolean).join(' · ');
    const bio = h('p', { class: 'd-bio collapsed' }, p.biography || '');
    const more = p.biography && p.biography.length > 500
      ? h('button', { class: 'btn ghost small', onclick: () => { bio.classList.toggle('collapsed'); more.textContent = bio.classList.contains('collapsed') ? 'Visa mer' : 'Visa mindre'; } }, 'Visa mer')
      : null;
    body.replaceChildren(...[
      h('div', { class: 'd-person-head' },
        h('div', { class: 'd-person-big' }, p.photo ? h('img', { src: p.photo, alt: '' }) : null),
        h('div', null, h('h1', null, p.name), born ? h('div', { class: 'dim' }, born) : null, p.biography ? bio : null, more)),
      h('h2', { class: 'd-list-title' }, 'Filmer och serier'),
      h('div', { class: 'grid d-grid' }, p.items.map((i) => card(i, { grid: true }))),
    ].filter(Boolean));
  });
})();
