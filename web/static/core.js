/* JPTV Web – grund: hjälpfunktioner, anrop till servern, navigering mellan sidor. */
'use strict';

const JP = (window.JP = {
  status: null,
  sections: [],
  routes: [],
  views: new Map(),
  current: null,
  local: {},
});

/* ---------- små hjälpare ---------- */

JP.h = function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (v === undefined || v === null || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
      else if (k === 'html') el.innerHTML = v;
      else if (k === 'text') el.textContent = v;
      else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2).toLowerCase(), v);
      else if (k === 'dataset') Object.assign(el.dataset, v);
      else el.setAttribute(k, v === true ? '' : v);
    }
  }
  const add = (c) => {
    if (c === null || c === undefined || c === false) return;
    if (Array.isArray(c)) c.forEach(add);
    else el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  };
  children.forEach(add);
  return el;
};
const h = JP.h;

JP.svg = function (path, cls) {
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  s.setAttribute('viewBox', '0 0 24 24');
  if (cls) s.setAttribute('class', cls);
  s.innerHTML = path;
  return s;
};

JP.icons = {
  play: '<path d="M7 4.5v15l13-7.5z" fill="currentColor" stroke="none"/>',
  pause: '<path d="M7 4.5h3.5v15H7zM13.5 4.5H17v15h-3.5z" fill="currentColor" stroke="none"/>',
  back: '<path d="M15 4 7 12l8 8"/>',
  left: '<path d="M15 5 8 12l7 7"/>',
  right: '<path d="M9 5l7 7-7 7"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7.5v.5"/>',
  check: '<path d="M5 12.5 10 17 19 7"/>',
  star: '<path d="M12 3.5l2.6 5.4 5.9.8-4.3 4.1 1 5.8L12 16.8l-5.2 2.8 1-5.8-4.3-4.1 5.9-.8z"/>',
  full: '<path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"/>',
  vol: '<path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4z" fill="currentColor"/><path d="M15.5 9a4.2 4.2 0 0 1 0 6M18 6.5a7.8 7.8 0 0 1 0 11"/>',
  mute: '<path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4z" fill="currentColor"/><path d="M16 9.5l5 5M21 9.5l-5 5"/>',
  gear: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>',
  rew: '<path d="M11.5 6.5 5 12l6.5 5.5zM19 6.5 12.5 12l6.5 5.5z" fill="currentColor"/>',
  fwd: '<path d="M12.5 6.5 19 12l-6.5 5.5zM5 6.5l6.5 5.5L5 17.5z" fill="currentColor"/>',
  next: '<path d="M6 5.5 15 12l-9 6.5z" fill="currentColor"/><path d="M18 5.5v13"/>',
  pip: '<rect x="3" y="5" width="18" height="14" rx="2"/><rect x="12" y="12" width="7" height="5" rx="1" fill="currentColor"/>',
  close: '<path d="M6 6l12 12M18 6 6 18"/>',
  trailer: '<rect x="3.5" y="5.5" width="17" height="13" rx="2"/><path d="M10 9.5v5l4.5-2.5z" fill="currentColor"/>',
  shuffle: '<path d="M4 7h3.5c4 0 5 10 9 10H20M4 17h3.5c1.6 0 2.7-1.6 3.6-3.6M14 9.4C14.9 8 15.8 7 17 7h3M17.5 4.5 20 7l-2.5 2.5M17.5 14.5 20 17l-2.5 2.5"/>',
  tv: '<rect x="3" y="5" width="18" height="12" rx="2"/><path d="M8 20.5h8"/>',
  guide: '<rect x="3" y="4.5" width="18" height="15" rx="2"/><path d="M3 9.5h18M9 9.5v10"/>',
  panel: '<rect x="3" y="4.5" width="18" height="15" rx="2"/><path d="M9.5 4.5v15"/><path d="M5.5 8.5h1.5M5.5 11.5h1.5M5.5 14.5h1.5"/>',
  eye: '<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="3"/>',
};
JP.icon = (name, cls) => JP.svg(JP.icons[name] || '', 'ico ' + (cls || ''));

JP.api = async function (path, opts = {}) {
  const init = { method: opts.method || (opts.body ? 'POST' : 'GET'), headers: {} };
  if (opts.body !== undefined) {
    init.body = JSON.stringify(opts.body);
    init.headers['Content-Type'] = 'application/json';
  }
  if (opts.signal) init.signal = opts.signal;
  const r = await fetch(path, init);
  let data = null;
  const text = await r.text();
  try { data = text ? JSON.parse(text) : null; } catch (e) { data = null; }
  if (!r.ok) {
    const err = new Error((data && data.error) || text || 'Fel ' + r.status);
    err.status = r.status;
    throw err;
  }
  return data;
};

JP.post = (path, body) => JP.api(path, { method: 'POST', body: body || {} });

JP.beacon = function (path, body) {
  const data = JSON.stringify(body || {});
  if (!(navigator.sendBeacon && navigator.sendBeacon(path, new Blob([data], { type: 'text/plain' })))) {
    fetch(path, { method: 'POST', body: data, keepalive: true }).catch(() => {});
  }
};

/** Bild från Plex genom servern. w/h = skalas av Plex; utan = som den är (logotyper med genomskinlighet). */
JP.img = function (path, w, hgt) {
  if (!path) return null;
  if (!w && /^https?:/.test(path)) return path;
  let u = '/img?p=' + encodeURIComponent(path);
  if (w) u += '&w=' + w + '&h=' + hgt;
  return u;
};

JP.esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);

JP.duration = function (ms) {
  if (!ms) return '';
  const m = Math.round(ms / 60000);
  if (m < 60) return m + ' min';
  const hh = Math.floor(m / 60), mm = m % 60;
  return mm ? `${hh} h ${mm} min` : `${hh} h`;
};

JP.clock = function (secs) {
  secs = Math.max(0, Math.floor(secs || 0));
  const hh = Math.floor(secs / 3600), mm = Math.floor((secs % 3600) / 60), ss = secs % 60;
  const two = (n) => String(n).padStart(2, '0');
  return hh ? `${hh}:${two(mm)}:${two(ss)}` : `${mm}:${two(ss)}`;
};

JP.hm = (ms) => new Date(ms).toLocaleTimeString('sv-SE', { hour: '2-digit', minute: '2-digit' });

JP.dayLabel = function (ms) {
  const d = new Date(ms), now = new Date();
  const days = Math.round((new Date(d.toDateString()) - new Date(now.toDateString())) / 86400000);
  if (days === 0) return 'I dag';
  if (days === 1) return 'I morgon';
  if (days === -1) return 'I går';
  return d.toLocaleDateString('sv-SE', { weekday: 'long', day: 'numeric', month: 'short' });
};

JP.toast = function (msg, ms = 3200) {
  const t = document.getElementById('toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(JP.toast._t);
  JP.toast._t = setTimeout(() => t.classList.remove('show'), ms);
};

JP.debounce = function (fn, ms) {
  let t;
  const d = (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
  d.cancel = () => clearTimeout(t);
  return d;
};

JP.store = {
  get(k, def) { try { const v = localStorage.getItem('jptv.' + k); return v === null ? def : JSON.parse(v); } catch (e) { return def; } },
  set(k, v) { try { localStorage.setItem('jptv.' + k, JSON.stringify(v)); } catch (e) { /* privat läge */ } },
};

JP.spinner = (text) => h('div', { class: 'loading' }, h('div', { class: 'spin' }), text ? h('div', null, text) : null);

JP.errorBox = (err, retry) =>
  h('div', { class: 'empty' },
    h('div', { class: 'empty-title' }, 'Något gick fel'),
    h('div', { class: 'dim' }, String((err && err.message) || err)),
    retry ? h('button', { class: 'btn', onclick: retry }, 'Försök igen') : null);

/* ---------- titlar ---------- */

JP.link = function (item) {
  switch (item.type) {
    case 'movie':
    case 'episode':
    case 'clip':
      return '#/item/' + item.key;
    case 'show':
      return '#/show/' + item.key;
    case 'season':
      return item.showKey ? `#/show/${item.showKey}?season=${item.key}` : '#/show/' + item.key;
    case 'collection':
      return '#/collection/' + item.key;
    default:
      return '#/item/' + item.key;
  }
};

JP.playable = (item) => item && (item.type === 'movie' || item.type === 'episode');

JP.epCode = (item) => (item.season != null && item.episode != null ? `S${item.season} E${item.episode}` : '');

/** Raden under titeln: år, åldersgräns, längd, genrer. */
JP.metaLine = function (item) {
  const parts = [];
  if (item.type === 'episode') {
    if (item.show) parts.push(item.show);
    const code = JP.epCode(item);
    if (code) parts.push(code);
  }
  if (item.year && item.type !== 'episode') parts.push(item.year);
  if (item.contentRating) parts.push(item.contentRating);
  if (item.type === 'show' && item.leafs) parts.push(item.leafs + ' avsnitt');
  else if (item.duration && item.type !== 'show') parts.push(JP.duration(item.duration));
  if (item.genres && item.genres.length) parts.push(item.genres.slice(0, 3).join(', '));
  return parts.join(' · ');
};

JP.badgeRow = function (item, extra) {
  const row = h('div', { class: 'badges' });
  for (const r of item.ratings || []) row.append(h('span', { class: 'rating ' + r.src.toLowerCase().replace(/\s/g, '-') }, r.text));
  for (const b of item.badges || []) row.append(h('span', { class: 'badge' }, b));
  if (extra) row.append(extra);
  return row.childNodes.length ? row : null;
};

JP.progress = function (item) {
  if (!item.offset || !item.duration) return null;
  const pct = Math.min(100, (item.offset / item.duration) * 100);
  return h('div', { class: 'progress' }, h('i', { style: { width: pct + '%' } }));
};

JP.remaining = (item) => (item.offset && item.duration ? JP.duration(item.duration - item.offset) + ' kvar' : '');

/* ---------- navigering ---------- */

JP.route = function (re, fn, opts = {}) {
  JP.routes.push({ re, fn, opts });
};

JP.parseHash = function () {
  const raw = location.hash.replace(/^#/, '') || '/';
  const [path, qs] = raw.split('?');
  return { path: path || '/', query: new URLSearchParams(qs || ''), key: raw };
};

JP.go = (hash) => { if (location.hash !== hash) location.hash = hash; else JP.render(); };

/** Sidor sparas en stund, så att Tillbaka visar samma läge (rullning, utvalt) direkt. */
JP.render = async function () {
  const { path, query, key } = JP.parseHash();
  if (JP.overlayRoute(path, query)) return;
  if (JP.current && JP.current.key === key) return;

  const prev = JP.current;
  if (prev) {
    prev.scrollY = window.scrollY;
    prev.leave.forEach((f) => { try { f(); } catch (e) { console.error(e); } });
    if (prev.nocache) { prev.el.remove(); JP.views.delete(prev.key); }
    else prev.el.hidden = true;
  }

  let route = null, match = null;
  for (const r of JP.routes) {
    match = path.match(r.re);
    if (match) { route = r; break; }
  }
  if (!route) { JP.go('#/'); return; }

  let v = JP.views.get(key);
  if (v && (v.dirty || Date.now() - v.at > 10 * 60e3)) {
    v.el.remove();
    JP.views.delete(key);
    v = null;
  }
  document.body.dataset.page = route.opts.page || path.split('/')[1] || 'home';
  if (v) {
    v.el.hidden = false;
    JP.current = v;
    window.scrollTo(0, v.scrollY || 0);
    v.show.forEach((f) => f());
  } else {
    v = { key, path, el: h('div', { class: 'view' }), at: Date.now(), leave: [], show: [], nocache: !!route.opts.nocache, scrollY: 0 };
    v.onLeave = (f) => v.leave.push(f);
    v.onShow = (f) => v.show.push(f);
    v.isCurrent = () => JP.current === v;
    document.getElementById('main').append(v.el);
    JP.views.set(key, v);
    JP.current = v;
    window.scrollTo(0, 0);
    try {
      await route.fn(v, match.slice(1), query);
    } catch (e) {
      console.error(e);
      v.el.replaceChildren(h('div', { class: 'page' }, JP.errorBox(e, () => { v.dirty = true; JP.current = null; JP.render(); })));
    }
  }
  JP.updateNav(path);
  // Högst åtta sparade sidor.
  const keys = [...JP.views.keys()];
  while (keys.length > 8) {
    const k = keys.shift();
    const old = JP.views.get(k);
    if (old && old !== JP.current) { old.el.remove(); JP.views.delete(k); }
  }
};

/** Efter uppspelning: sidor med Fortsätt titta och sett-markeringar byggs om (den som syns direkt). */
JP.invalidate = function (rerenderCurrent) {
  for (const v of JP.views.values()) v.dirty = true;
  if (rerenderCurrent && JP.current && !JP.current.nocache) {
    const cur = JP.current;
    const keep = window.scrollY;
    cur.leave.forEach((f) => { try { f(); } catch (e) { console.error(e); } });
    cur.el.remove();
    JP.views.delete(cur.key);
    JP.current = null;
    JP.render().then(() => window.scrollTo(0, keep));
  }
};

JP.overlays = [];
JP.overlay = (re, open, close) => JP.overlays.push({ re, open, close, active: false });

// Tillbaka till fliken efter en stund: Fortsätt titta och sett-markeringar kan ha ändrats på en annan
// TV. Plex-sidan som syns byggs om; övriga sparade sidor byggs om när man går till dem.
(function () {
  let hiddenAt = 0;
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) { hiddenAt = Date.now(); return; }
    const away = hiddenAt && Date.now() - hiddenAt > 60e3;
    hiddenAt = 0;
    if (!away) return;
    const overlay = JP.overlays.some((o) => o.active);
    const path = JP.current ? JP.current.path : '';
    const plexPage = /^\/($|section\/|show\/|item\/|collection\/|youtube)/.test(path);
    JP.invalidate(!overlay && plexPage);
  });
})();
JP.overlayRoute = function (path, query) {
  let handled = false;
  for (const o of JP.overlays) {
    const m = path.match(o.re);
    if (m) {
      o.active = true;
      o.open(m.slice(1), query);
      handled = true;
    } else if (o.active) {
      o.active = false;
      o.close();
    }
  }
  return handled;
};

JP.updateNav = function (path) {
  for (const a of document.querySelectorAll('#nav a')) {
    const target = a.getAttribute('href').replace(/^#/, '');
    const active = target === '/' ? path === '/' : path === target || path.startsWith(target + '/');
    a.classList.toggle('active', active);
  }
};

JP.buildNav = function () {
  const nav = document.getElementById('nav');
  nav.replaceChildren();
  const st = JP.status || {};
  if (st.plex) {
    nav.append(h('a', { href: '#/' }, 'Hem'));
    for (const s of JP.sections) nav.append(h('a', { href: '#/section/' + s.key }, s.title));
  }
  if (st.youtube) nav.append(h('a', { href: '#/youtube' }, 'YouTube'));
  if (st.iptv && st.iptv.configured) {
    nav.append(h('a', { href: '#/live' }, 'Live-TV'));
    nav.append(h('a', { href: '#/guide' }, 'TV-guide'));
  }
  if (st.plex) {
    nav.append(h('a', { href: '#/requests' }, 'Önskemål', h('span', { class: 'nav-badge', hidden: !st.requests }, st.requests || '')));
  }
};

JP.refreshStatus = async function () {
  JP.status = await JP.api('/api/status');
  if (JP.status.plex) {
    try { JP.sections = await JP.api('/api/sections'); } catch (e) { JP.sections = []; }
  } else JP.sections = [];
  JP.buildNav();
  return JP.status;
};

JP.start = async function () {
  const tick = () => { document.getElementById('clock').textContent = JP.hm(Date.now()); };
  tick();
  setInterval(tick, 15000);
  window.addEventListener('scroll', () => document.body.classList.toggle('scrolled', window.scrollY > 8), { passive: true });
  document.addEventListener('keydown', (e) => {
    if (e.key === '/' && !/input|textarea|select/i.test(document.activeElement.tagName) && !JP.playerOpen) {
      e.preventDefault();
      JP.go('#/search');
    }
  });
  try {
    await JP.refreshStatus();
  } catch (e) {
    document.getElementById('main').replaceChildren(h('div', { class: 'page' }, JP.errorBox('Får ingen kontakt med JPTV-servern', () => location.reload())));
    return;
  }
  const st = JP.status;
  const { path } = JP.parseHash();
  if (!st.plex && !(st.iptv && st.iptv.configured) && path !== '/settings') location.hash = '#/login';
  else if (!st.plex && path === '/') location.hash = '#/live';
  window.addEventListener('hashchange', JP.render);
  JP.render();
};
