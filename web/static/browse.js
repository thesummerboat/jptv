/* JPTV Web – affischer, rader, den stora rutan överst (som på TV:n) och biblioteken. */
'use strict';
(function () {
  const { h } = JP;

  /* ---------- kort ---------- */

  JP.card = function (item, opts = {}) {
    const wide = opts.kind === 'wide';
    const grid = opts.kind === 'grid';
    const src = wide ? JP.img(item.still || item.art || item.thumb, 480, 270) : JP.img(item.thumb, 240, 360);
    const a = h('a', {
      class: 'card' + (wide ? ' wide' : '') + (grid ? ' in-grid' : ''),
      href: JP.link(item),
      'aria-label': item.type === 'episode' ? `${item.show || ''} ${JP.epCode(item)} ${item.title}` : item.title,
      dataset: { key: item.key },
    });
    const art = h('div', { class: 'card-art' });
    if (src) {
      const im = h('img', { src, alt: '', loading: 'lazy', decoding: 'async', draggable: 'false' });
      im.addEventListener('error', () => { im.remove(); art.append(h('div', { class: 'card-fallback' }, item.show || item.title)); });
      art.append(im);
    } else art.append(h('div', { class: 'card-fallback' }, item.show || item.title));
    const unwatched = item.type === 'show' || item.type === 'season' ? (item.leafs || 0) - (item.viewedLeafs || 0) : 0;
    if (item.watched && (item.type === 'movie' || item.type === 'episode') && !item.offset) art.append(h('span', { class: 'card-corner seen' }, JP.icon('check')));
    else if (unwatched > 0 && item.viewedLeafs > 0) art.append(h('span', { class: 'card-corner count' }, unwatched));
    if (item.label) art.append(h('span', { class: 'card-label' }, item.label));
    const prog = JP.progress(item);
    if (prog) art.append(prog);
    if (JP.playable(item)) {
      art.append(
        h('button', {
          class: 'card-play',
          title: item.offset ? 'Fortsätt' : 'Spela',
          'aria-label': 'Spela',
          onclick: (e) => { e.preventDefault(); e.stopPropagation(); JP.play(item); },
        }, JP.icon('play')),
      );
    }
    a.append(art);
    if (wide) {
      a.append(h('div', { class: 'card-text' },
        h('div', { class: 'card-title' }, item.type === 'episode' ? item.show || item.title : item.title),
        h('div', { class: 'card-sub' }, item.type === 'episode' ? [JP.epCode(item), item.title].filter(Boolean).join(' · ') : JP.remaining(item) || item.year || '')));
    } else if (grid) {
      a.append(h('div', { class: 'card-text' }, h('div', { class: 'card-title' }, item.title), h('div', { class: 'card-sub' }, item.year || item.label || '')));
    }
    if (opts.onFocus) {
      a.addEventListener('mouseenter', () => opts.onFocus(item, a));
      a.addEventListener('focus', () => opts.onFocus(item, a));
    }
    return a;
  };

  JP.rowEl = function (row, onFocus) {
    const strip = h('div', { class: 'strip' + (row.kind === 'wide' ? ' wide' : '') });
    for (const item of row.items) strip.append(JP.card(item, { kind: row.kind, onFocus }));
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
    return h('section', { class: 'row' }, h('h2', null, row.title), h('div', { class: 'strip-wrap' }, left, strip, right));
  };

  /* ---------- logotyper och trailers (hämtas när de behövs) ---------- */

  /** Full information om en titel (logotyp, alla betyg, märken), hämtas en gång per titel. */
  const full = new Map();
  JP.fullItem = function (key) {
    if (!full.has(key)) full.set(key, JP.api('/api/item/' + key).catch(() => null));
    return full.get(key);
  };

  JP.logoFor = async function (item) {
    if (item.logo) return item.logo;
    const key = (item.type === 'episode' || item.type === 'season') && item.showKey ? item.showKey : item.key;
    const m = await JP.fullItem(key);
    return (m && m.logo) || null;
  };

  /**
   * Plex listor (hemskärmens rader) har ofta bara Rotten Tomatoes; IMDb-betyget och märken som
   * HDR och Atmos finns i titelns fulla information. Den hämtas när titeln visas i den stora rutan.
   */
  JP.fullRatings = async function (item) {
    if (!['movie', 'show', 'episode'].includes(item.type)) return null;
    if ((item.ratings || []).some((r) => r.src === 'IMDb')) return null;
    const m = await JP.fullItem(item.key);
    if (!m || !(m.ratings || []).length) return null;
    return { ratings: m.ratings, badges: (m.badges || []).length ? m.badges : item.badges };
  };

  const trailers = new Map();
  JP.trailerFor = function (item) {
    const key = (item.type === 'episode' || item.type === 'season') && item.showKey ? item.showKey : item.key;
    if (!trailers.has(key)) trailers.set(key, JP.api('/api/trailer/' + key).catch(() => null));
    return trailers.get(key);
  };

  JP.playTrailer = async function (item) {
    const t = await JP.trailerFor(item);
    if (!t) { JP.toast('Ingen trailer hittades'); return; }
    JP.openUrlPlayer(t.url, (item.show || item.title) + ' – trailer');
  };

  /* ---------- den stora rutan ---------- */

  JP.Hero = function (opts = {}) {
    const imgA = h('img', { class: 'hero-img', alt: '' });
    const imgB = h('img', { class: 'hero-img', alt: '' });
    const video = h('video', { class: 'hero-video', muted: true, playsinline: true, preload: 'none' });
    video.muted = true;
    const muteBtn = h('button', { class: 'hero-mute', hidden: true, title: 'Ljud på/av' }, JP.icon('mute'));
    const bg = h('div', { class: 'hero-bg' }, imgA, imgB, video, h('div', { class: 'hero-scrim' }));
    const info = h('div', { class: 'hero-info' });
    const el = h('div', { class: 'hero' + (opts.compact ? ' compact' : '') }, bg, info, muteBtn);
    let front = imgA, current = null, featured = [], index = 0, mode = 'featured';
    let rotation = null, paused = false, stopped = false, trailerStarted = 0, trailerTimer = null, shownAt = 0;

    muteBtn.addEventListener('click', () => {
      video.muted = !video.muted;
      muteBtn.replaceChildren(JP.icon(video.muted ? 'mute' : 'vol'));
    });
    info.addEventListener('mouseenter', () => { paused = true; });
    info.addEventListener('mouseleave', () => { paused = false; });

    function setBackground(url) {
      if (!url) { imgA.classList.remove('on'); imgB.classList.remove('on'); return; }
      if (front.dataset.src === url && front.classList.contains('on')) return;
      const back = front === imgA ? imgB : imgA;
      const pre = new Image();
      pre.onload = () => {
        if (back.dataset.want !== url) return;
        back.src = url;
        back.dataset.src = url;
        back.classList.add('on');
        front.classList.remove('on');
        front = back;
      };
      back.dataset.want = url;
      pre.src = url;
    }

    function stopTrailer() {
      clearTimeout(trailerTimer);
      trailerStarted = 0;
      el.classList.remove('trailer-on');
      muteBtn.hidden = true;
      if (video.getAttribute('src')) {
        video.pause();
        video.removeAttribute('src');
        video.load();
      }
    }

    function scheduleTrailer(item) {
      stopTrailer();
      if (!JP.store.get('heroTrailers', true) || stopped || !item || item.type === 'collection') return;
      trailerTimer = setTimeout(async () => {
        const t = await JP.trailerFor(item);
        if (!t || current !== item || stopped || JP.playerOpen) return;
        video.src = t.url;
        video.muted = true;
        muteBtn.replaceChildren(JP.icon('mute'));
        video.play().catch(() => {});
      }, 3000);
    }
    video.addEventListener('playing', () => {
      if (!video.getAttribute('src')) return;
      trailerStarted = Date.now();
      el.classList.add('trailer-on');
      muteBtn.hidden = false;
    });
    video.addEventListener('ended', () => { stopTrailer(); if (mode === 'featured') next(1); });
    video.addEventListener('error', stopTrailer);

    function renderInfo(item) {
      const title = item.type === 'episode' ? item.show || item.title : item.title;
      const logoBox = h('div', { class: 'hero-logo' }, h('h1', null, title));
      const sub = item.type === 'episode' ? h('div', { class: 'hero-sub' }, [JP.epCode(item), item.title].filter(Boolean).join(' · ')) : null;
      const meta = h('div', { class: 'hero-meta' }, item.type === 'episode' ? [item.contentRating, JP.duration(item.duration)].filter(Boolean).join(' · ') : JP.metaLine(item));
      const summary = item.summary ? h('p', { class: 'hero-summary' }, item.summary) : null;
      const badges = JP.badgeRow(item) || h('div', { class: 'badges' });
      const children = [item.label ? h('div', { class: 'hero-kicker' }, item.label) : null, logoBox, sub, meta, badges, summary];
      if (mode === 'featured' && featured.length) {
        const actions = h('div', { class: 'hero-actions' });
        if (item.type === 'movie' || item.type === 'episode') {
          actions.append(h('button', { class: 'btn primary', onclick: () => JP.play(item) }, JP.icon('play'), item.offset ? 'Fortsätt' : 'Spela'));
          actions.append(h('a', { class: 'btn', href: JP.link(item) }, JP.icon('info'), 'Mer info'));
        } else {
          actions.append(h('a', { class: 'btn primary', href: JP.link(item) }, JP.icon('play'), 'Till serien'));
          actions.append(h('button', { class: 'btn', onclick: () => JP.playTrailer(item) }, JP.icon('trailer'), 'Trailer'));
        }
        if (featured.length > 1) {
          const dots = h('div', { class: 'hero-dots' },
            h('button', { class: 'dot-arrow', 'aria-label': 'Föregående', onclick: () => next(-1) }, JP.icon('left')),
            featured.map((f, i) => h('button', { class: 'dot' + (i === index ? ' on' : ''), 'aria-label': f.title, onclick: () => { index = i; showFeatured(); } })),
            h('button', { class: 'dot-arrow', 'aria-label': 'Nästa', onclick: () => next(1) }, JP.icon('right')));
          actions.append(dots);
        }
        children.push(actions);
      }
      info.replaceChildren(...children.filter(Boolean));
      info.classList.remove('fade');
      void info.offsetWidth;
      info.classList.add('fade');
      JP.logoFor(item).then((logo) => {
        if (!logo || current !== item) return;
        const im = h('img', { src: JP.img(logo), alt: title });
        im.onload = () => logoBox.replaceChildren(im);
      });
      JP.fullRatings(item).then((more) => {
        if (!more || current !== item) return;
        const row = JP.badgeRow(Object.assign({}, item, more));
        if (row && badges.isConnected) badges.replaceWith(row);
      });
    }

    function show(item, m) {
      if (!item) return;
      if (current && current.key === item.key && mode === m) return;
      current = item;
      mode = m;
      shownAt = Date.now();
      renderInfo(item);
      setBackground(JP.img(item.art || item.thumb, 1920, 1080));
      scheduleTrailer(item);
    }

    function showFeatured() {
      if (featured.length) show(featured[index % featured.length], 'featured');
    }

    function next(dir) {
      if (!featured.length) return;
      index = (index + dir + featured.length) % featured.length;
      mode = '';
      showFeatured();
    }

    function startRotation() {
      clearInterval(rotation);
      rotation = setInterval(() => {
        if (stopped || paused || mode !== 'featured' || featured.length < 2 || JP.playerOpen) return;
        const age = Date.now() - shownAt;
        if (trailerStarted) {
          if (Date.now() - trailerStarted < 25000) return;
        } else if (age < 9000) return;
        next(1);
      }, 1000);
    }

    return {
      el,
      setFeatured(list) {
        featured = list || [];
        index = 0;
        showFeatured();
        startRotation();
      },
      preview(item) { show(item, 'preview'); },
      back() { if (featured.length) { mode = ''; showFeatured(); } },
      stop() { stopped = true; stopTrailer(); clearInterval(rotation); },
      resume() {
        stopped = false;
        startRotation();
        if (current) scheduleTrailer(current);
      },
      get item() { return current; },
    };
  };

  /* ---------- hemskärmen och biblioteken: rutan överst, rader under ---------- */

  JP.browse = async function (v, load, header) {
    v.el.classList.add('browse');
    const hero = JP.Hero();
    const rows = h('div', { class: 'rows' });
    v.el.append(hero.el, rows);
    if (header) rows.append(header);
    const spin = JP.spinner();
    rows.append(spin);
    v.onLeave(() => hero.stop());
    v.onShow(() => hero.resume());

    let data;
    try {
      data = await load();
    } catch (e) {
      spin.replaceWith(JP.errorBox(e, () => { v.dirty = true; JP.current = null; JP.render(); }));
      return;
    }
    spin.remove();
    if (!data.rows.length) {
      rows.append(h('div', { class: 'empty' }, h('div', { class: 'empty-title' }, 'Här är det tomt än så länge'), h('div', { class: 'dim' }, 'Plex hittade inga titlar.')));
      return;
    }
    const back = JP.debounce(() => hero.back(), 700);
    const focus = JP.debounce((item) => hero.preview(item), 180);
    const onFocus = (item) => { back.cancel(); focus(item); };
    for (const r of data.rows) rows.append(JP.rowEl(r, onFocus));
    rows.addEventListener('mouseleave', () => { focus.cancel(); back(); });
    hero.setFeatured(data.featured.length ? data.featured : data.rows[0].items.slice(0, 5));
  };

  JP.route(/^\/$/, async (v) => {
    await JP.browse(v, () => JP.api('/api/home'));
    // Nytt från YouTube-kanalerna som en rad efter Fortsätt titta.
    const rows = v.el.querySelector('.rows');
    if (rows && JP.ytHomeRow) JP.ytHomeRow(rows);
  });

  /* ---------- bibliotek ---------- */

  function sectionOf(key) {
    return JP.sections.find((s) => s.key === key) || { key, title: 'Bibliotek', type: 'movie' };
  }

  function sectionTabs(sec, active) {
    return h('div', { class: 'tabs' },
      h('a', { class: 'tab' + (active === 'browse' ? ' on' : ''), href: '#/section/' + sec.key }, 'Bläddra'),
      h('a', { class: 'tab' + (active === 'all' ? ' on' : ''), href: `#/section/${sec.key}/all` }, 'Alla titlar'));
  }

  JP.route(/^\/section\/([^/]+)$/, async (v, [key]) => {
    const sec = sectionOf(key);
    const genres = h('div', { class: 'chips' });
    const header = h('div', { class: 'section-head' }, h('div', { class: 'section-title' }, h('h1', null, sec.title), sectionTabs(sec, 'browse')), genres);
    JP.api(`/api/section/${key}/filters`).then((f) => {
      for (const g of f.genres.slice(0, 24)) genres.append(h('a', { class: 'chip', href: `#/section/${key}/all?genre=${encodeURIComponent(g.id)}` }, g.title));
      if (f.collections.length) genres.append(h('a', { class: 'chip', href: `#/section/${key}/all?view=collections` }, 'Samlingar'));
    }).catch(() => {});
    await JP.browse(v, () => JP.api(`/api/section/${key}/rows`), header);
  });

  const SORTS = [
    ['title', 'A–Ö'],
    ['added', 'Nyligen tillagt'],
    ['year', 'År'],
    ['release', 'Premiär'],
    ['imdb', 'IMDb-betyg'],
    ['rating', 'Publikbetyg'],
  ];

  JP.letterOf = function (item) {
    const t = (item.titleSort || item.title || '').trim().toUpperCase();
    const c = t.normalize('NFC').charAt(0);
    if (!c) return '#';
    if ('ÅÄÖ'.includes(c)) return c;
    const plain = c.normalize('NFD').replace(/[̀-ͯ]/g, '');
    return /[A-Z]/.test(plain) ? plain : '#';
  };

  JP.posterGrid = function (items, withLetters) {
    const grid = h('div', { class: 'grid' });
    const firstByLetter = new Map();
    for (const item of items) {
      const c = JP.card(item, { kind: 'grid' });
      if (withLetters) {
        const l = JP.letterOf(item);
        if (!firstByLetter.has(l)) firstByLetter.set(l, c);
      }
      grid.append(c);
    }
    if (!withLetters || items.length < 40) return grid;
    const letters = ['#', ...'ABCDEFGHIJKLMNOPQRSTUVWXYZÅÄÖ'];
    const rail = h('div', { class: 'letter-rail' },
      letters.map((l) => h('button', {
        class: firstByLetter.has(l) ? '' : 'off',
        disabled: !firstByLetter.has(l),
        onclick: () => {
          const target = firstByLetter.get(l);
          if (target) window.scrollTo({ top: target.getBoundingClientRect().top + window.scrollY - 140, behavior: 'smooth' });
        },
      }, l)));
    return h('div', { class: 'grid-wrap' }, grid, rail);
  };

  JP.route(/^\/section\/([^/]+)\/all$/, async (v, [key], q) => {
    const sec = sectionOf(key);
    const params = {
      sort: q.get('sort') || 'title',
      genre: q.get('genre') || '',
      unwatched: q.get('unwatched') === '1',
      collection: q.get('collection') || '',
      view: q.get('view') || '',
    };
    const go = (patch) => {
      const p = Object.assign({}, params, patch);
      const qs = new URLSearchParams();
      if (p.sort !== 'title') qs.set('sort', p.sort);
      if (p.genre) qs.set('genre', p.genre);
      if (p.unwatched) qs.set('unwatched', '1');
      if (p.collection) qs.set('collection', p.collection);
      if (p.view) qs.set('view', p.view);
      JP.go(`#/section/${key}/all` + (qs.toString() ? '?' + qs : ''));
    };
    const sortSel = h('select', { 'aria-label': 'Sortering', onchange: (e) => go({ sort: e.target.value, collection: '', view: '' }) },
      SORTS.map(([k, label]) => h('option', { value: k, selected: k === params.sort }, label)));
    const genreSel = h('select', { 'aria-label': 'Genre', onchange: (e) => go({ genre: e.target.value, collection: '', view: '' }) }, h('option', { value: '' }, 'Alla genrer'));
    const unwatched = h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: params.unwatched, onchange: (e) => go({ unwatched: e.target.checked }) }), 'Bara osedda');
    const collBtn = h('a', { class: 'chip' + (params.view === 'collections' ? ' on' : ''), href: `#/section/${key}/all?view=collections` }, 'Samlingar');
    const count = h('span', { class: 'dim count' });
    const toolbar = h('div', { class: 'toolbar' }, sortSel, genreSel, unwatched, collBtn, count);
    const body = h('div', null, JP.spinner());
    v.el.append(h('div', { class: 'page' }, h('div', { class: 'section-title' }, h('h1', null, sec.title), sectionTabs(sec, 'all')), toolbar, body));

    JP.api(`/api/section/${key}/filters`).then((f) => {
      for (const g of f.genres) genreSel.append(h('option', { value: g.id, selected: g.id === params.genre }, g.title));
      if (!f.collections.length) collBtn.remove();
      if (params.view === 'collections') {
        count.textContent = f.collections.length + ' samlingar';
        body.replaceChildren(JP.posterGrid(f.collections, false));
      }
    }).catch(() => {});
    if (params.view === 'collections') return;
    const qs = new URLSearchParams({ sort: params.sort });
    if (params.genre) qs.set('genre', params.genre);
    if (params.unwatched) qs.set('unwatched', '1');
    if (params.collection) qs.set('collection', params.collection);
    const data = await JP.api(`/api/section/${key}/items?${qs}`);
    count.textContent = data.items.length + ' titlar';
    body.replaceChildren(data.items.length ? JP.posterGrid(data.items, params.sort === 'title') : h('div', { class: 'empty' }, h('div', { class: 'empty-title' }, 'Inga titlar'), h('div', { class: 'dim' }, 'Prova ett annat urval.')));
  });

  JP.route(/^\/collection\/([^/]+)$/, async (v, [key]) => {
    const body = h('div', null, JP.spinner());
    v.el.append(h('div', { class: 'page' }, body));
    const c = await JP.api('/api/item/' + key);
    body.replaceChildren(
      h('div', { class: 'section-title' }, h('h1', null, c.title)),
      c.summary ? h('p', { class: 'dim lead' }, c.summary) : null,
      JP.posterGrid(c.items || [], false));
  });
})();
