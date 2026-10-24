/* JPTV Web – önska filmer och serier som saknas i Plex, och sidan där önskemålen godkänns. */
'use strict';
(function () {
  const { h } = JP;

  const STATUS = {
    pending: 'Önskad · väntar på OK',
    approved: 'Godkänd · på väg',
    available: 'Finns nu i Plex',
    declined: 'Nej',
  };

  // Affischer från Upptäck är TMDB-adresser (via servern); övriga från Plex katalog.
  const poster = (path, w = 240, hh = 360) => (!path ? null : /image\.tmdb\.org/.test(path) && JP.tmdbImg ? JP.tmdbImg(path, w > 200 ? 'w342' : 'w185') : JP.img(path, w, hh));

  /** Vem som önskar: frågas en gång och sparas i webbläsaren. */
  JP.wisher = () => JP.store.get('name', '');

  function closeModal(m) {
    m.remove();
    document.removeEventListener('keydown', m._esc);
  }

  function modal(...children) {
    const box = h('div', { class: 'modal-box', role: 'dialog' }, ...children);
    const m = h('div', { class: 'modal', onclick: (e) => { if (e.target === m) closeModal(m); } }, box);
    m._esc = (e) => { if (e.key === 'Escape') closeModal(m); };
    document.addEventListener('keydown', m._esc);
    document.body.append(m);
    return m;
  }

  /** Dialog för en titel ur Plex katalog: önska, eller visa hur det gått. */
  JP.wishDialog = function (item, onDone) {
    const nameInput = h('input', { type: 'text', value: JP.wisher(), placeholder: 'Ditt namn', maxlength: '40', autocomplete: 'given-name' });
    const status = item.request ? h('div', { class: 'wish-status ' + item.request.status }, STATUS[item.request.status] || item.request.status) : null;
    const actions = h('div', { class: 'actions' });
    let m;
    if (item.libraryKey) {
      actions.append(h('a', { class: 'btn primary', href: item.type === 'show' ? '#/show/' + item.libraryKey : '#/item/' + item.libraryKey, onclick: () => closeModal(m) }, JP.icon('play'), 'Finns i Plex – öppna'));
    } else if (!item.request || item.request.status === 'declined') {
      const go = h('button', { class: 'btn primary' }, JP.icon('star'), item.type === 'show' ? 'Önska serien' : 'Önska filmen');
      go.addEventListener('click', async () => {
        const by = nameInput.value.trim();
        if (!by) { nameInput.focus(); JP.toast('Skriv ditt namn först'); return; }
        JP.store.set('name', by);
        go.disabled = true;
        try {
          const r = await JP.post('/api/requests', Object.assign({}, item, { by }));
          item.request = { id: r.request.id, status: r.request.status };
          JP.toast(r.new ? `${item.title} är önskad` : `${item.title} var redan önskad – du står med`);
          closeModal(m);
          JP.refreshBadge();
          if (onDone) onDone(item);
        } catch (e) {
          go.disabled = false;
          JP.toast(e.message);
        }
      });
      actions.append(go);
    }
    actions.append(h('button', { class: 'btn ghost', onclick: () => closeModal(m) }, 'Stäng'));
    m = modal(
      h('div', { class: 'wish-dialog' },
        h('div', { class: 'wish-poster' }, item.thumb ? h('img', { src: poster(item.thumb, 300, 450), alt: '' }) : null),
        h('div', { class: 'wish-text' },
          h('div', { class: 'dim small' }, item.type === 'show' ? 'Serie' : 'Film'),
          h('h2', null, item.title, item.year ? h('span', { class: 'dim' }, ` (${item.year})`) : null),
          status,
          item.summary ? h('p', { class: 'summary' }, item.summary) : null,
          !item.libraryKey && (!item.request || item.request.status === 'declined')
            ? h('label', { class: 'field' }, h('span', { class: 'field-label' }, 'Vem önskar?'), nameInput) : null,
          actions)));
  };

  function wishCard(item, refresh) {
    const art = h('div', { class: 'card-art' },
      item.thumb ? h('img', { src: poster(item.thumb), alt: '', loading: 'lazy', onerror: (e) => e.target.remove() }) : h('div', { class: 'card-fallback' }, item.title));
    if (item.request) art.append(h('span', { class: 'card-label wish-' + item.request.status }, STATUS[item.request.status]));
    else if (item.libraryKey) art.append(h('span', { class: 'card-label' }, 'Finns i Plex'));
    else art.append(h('span', { class: 'card-wish' }, JP.icon('star'), 'Önska'));
    return h('button', { class: 'card in-grid wish-card', onclick: () => JP.wishDialog(item, refresh) },
      art,
      h('div', { class: 'card-text' }, h('div', { class: 'card-title' }, item.title), h('div', { class: 'card-sub' }, [item.year, item.type === 'show' ? 'Serie' : 'Film'].filter(Boolean).join(' · '))));
  }

  /** "Inte i Plex – önska": titlar ur Plex katalog som saknas på servern. */
  JP.wishSection = function (query, opts = {}) {
    const box = h('section', { class: 'search-group wish-group' }, h('h2', null, opts.title || 'Inte i Plex – önska'), JP.spinner());
    const load = async () => {
      try {
        const data = await JP.api('/api/discover?q=' + encodeURIComponent(query));
        const items = data.results.filter((r) => opts.all || !r.libraryKey);
        const grid = h('div', { class: 'grid' }, items.map((i) => wishCard(i, load)));
        box.replaceChildren(h('h2', null, opts.title || 'Inte i Plex – önska'),
          items.length ? grid : h('div', { class: 'dim' }, 'Hittade inget mer i Plex katalog.'));
      } catch (e) {
        box.replaceChildren(h('h2', null, opts.title || 'Inte i Plex – önska'), h('div', { class: 'dim' }, e.message));
      }
    };
    load();
    return box;
  };

  /* ---------- räknaren i menyn ---------- */

  JP.refreshBadge = async function () {
    try {
      const st = await JP.api('/api/status');
      JP.status.requests = st.requests;
    } catch (e) { return; }
    const badge = document.querySelector('#nav .nav-badge');
    if (badge) {
      badge.textContent = JP.status.requests || '';
      badge.hidden = !JP.status.requests;
    }
  };
  setInterval(() => { if (JP.status && JP.status.plex) JP.refreshBadge(); }, 60000);

  /* ---------- sidan Önskemål ---------- */

  function ago(ms) {
    const min = Math.round((Date.now() - ms) / 60000);
    if (min < 1) return 'nyss';
    if (min < 60) return min + ' min sedan';
    const hrs = Math.round(min / 60);
    if (hrs < 36) return hrs + ' h sedan';
    return new Date(ms).toLocaleDateString('sv-SE', { day: 'numeric', month: 'short' });
  }

  /* ---------- Radarrs och Sonarrs status på godkända önskemål ---------- */

  function dur(sec) {
    const min = Math.max(1, Math.round(sec / 60));
    if (min < 60) return min + ' min';
    const hrs = Math.floor(min / 60), rest = min % 60;
    if (hrs < 24) return hrs + ' h' + (rest && hrs < 10 ? ' ' + rest + ' min' : '');
    return Math.round(hrs / 24) + ' dygn';
  }

  function day(iso) {
    const d = new Date(iso);
    if (isNaN(d)) return '';
    const opts = { day: 'numeric', month: 'short' };
    if (d.getFullYear() !== new Date().getFullYear()) opts.year = 'numeric';
    return d.toLocaleDateString('sv-SE', opts);
  }

  function since(iso) {
    const d = new Date(iso);
    if (isNaN(d)) return '';
    const min = Math.round((Date.now() - d) / 60000);
    if (min < 1) return 'nyss';
    if (min < 60) return 'för ' + min + ' min sedan';
    const clock = d.toLocaleTimeString('sv-SE', { hour: '2-digit', minute: '2-digit' });
    const days = Math.round((new Date(new Date().toDateString()) - new Date(d.toDateString())) / 86400000);
    if (days === 0) return 'i dag ' + clock;
    if (days === 1) return 'i går ' + clock;
    if (days < 7) return 'för ' + days + ' dagar sedan';
    return day(iso);
  }

  /** Statusraden: "Laddar ner 45 % · klar om 12 min", förlopp, Radarrs förklaring och en ledtråd. */
  function arrView(st, name) {
    const bits = [st.text];
    if (st.eta != null) bits.push('klar om ' + dur(st.eta));
    if (st.release && st.release.at) bits.push(st.release.label + ' ' + day(st.release.at));
    if (st.searchedAt) bits.push('senast sökt ' + since(st.searchedAt));
    return [
      h('div', { class: 'arr-line' }, h('span', { class: 'arr-dot' }),
        h('span', null, bits.filter(Boolean).join(' · ')),
        st.link ? h('a', { class: 'arr-link', href: st.link, target: '_blank', rel: 'noopener' }, 'Öppna i ' + name) : null),
      st.progress != null ? h('div', { class: 'arr-bar' }, h('i', { style: { width: Math.round(st.progress * 100) + '%' } })) : null,
      st.messages && st.messages.length ? h('ul', { class: 'arr-msg' }, st.messages.map((m) => h('li', null, m))) : null,
      st.hint ? h('div', { class: 'arr-hint' }, st.hint) : null,
    ].filter(Boolean);
  }

  JP.route(/^\/requests$/, async (v) => {
    const input = h('input', { type: 'search', class: 'search-input', placeholder: 'Önska en film eller serie – sök i Plex katalog', autocomplete: 'off' });
    const found = h('div');
    const list = h('div', { class: 'wish-list' }, JP.spinner());
    const head = h('div', { class: 'dim wish-arr' });
    // Med TMDB söker man i sidhuvudet (Upptäck); annars i Plex katalog här.
    const tmdb = JP.status && JP.status.tmdb;
    v.el.append(h('div', { class: 'page narrow wide-ish' }, JP.wishHead ? JP.wishHead('requests') : h('div', { class: 'section-title' }, h('h1', null, 'Önskemål')),
      head, tmdb ? null : input, found, list));

    let token = 0;
    input.addEventListener('input', JP.debounce(() => {
      const q = input.value.trim();
      const my = ++token;
      if (!q) { found.replaceChildren(); return; }
      if (my === token) found.replaceChildren(JP.wishSection(q, { title: 'Träffar i Plex katalog', all: true }));
    }, 350));

    // Statusen från Radarr/Sonarr hämtas efter listan och ritas in i raderna (sparas mellan omritningar).
    const arrState = {};
    const slots = new Map();
    let cfg = {};
    let arrTimer = 0;
    let arrBusy = false;

    function paint(id) {
      const s = slots.get(id);
      const st = arrState[id];
      if (!s || !st) return;
      const name = s.w.type === 'show' ? 'Sonarr' : 'Radarr';
      s.info.className = 'arr-st arr-' + st.state;
      s.info.replaceChildren(...arrView(st, name));
      s.act.replaceChildren(...arrButtons(s.w, st));
    }

    async function loadArr() {
      if (arrBusy || !v.isCurrent()) return;
      arrBusy = true;
      try {
        const data = await JP.api('/api/requests/arr');
        for (const k of Object.keys(arrState)) delete arrState[k];
        Object.assign(arrState, data.items || {});
        for (const id of slots.keys()) paint(id);
        // Följ nedladdningar lite tätare än listan.
        clearTimeout(arrTimer);
        if (Object.values(arrState).some((st) => ['downloading', 'searching', 'importing'].includes(st.state))) {
          arrTimer = setTimeout(loadArr, 10000);
        }
      } catch (e) {
        /* listan fungerar ändå */
      } finally {
        arrBusy = false;
      }
    }

    function later(ms) { setTimeout(() => { if (v.isCurrent()) loadArr(); }, ms); }

    function arrButtons(w, st) {
      const out = [];
      const run = (btn, path, after) => async () => {
        btn.disabled = true;
        try {
          const r = await JP.post(path);
          JP.toast(`${w.title}: ${r.ok || (r.request && r.request.note) || 'klart'}`);
        } catch (e) {
          JP.toast(e.message);
          btn.disabled = false;
          return;
        }
        if (after) after();
        later(3000);
        later(12000);
      };
      const searching = () => {
        arrState[w.id] = Object.assign({}, st, { state: 'searching', text: 'Söker…', actions: [], messages: [], hint: null, searchedAt: null, eta: null, progress: null, release: null });
        paint(w.id);
      };
      for (const a of st.actions || []) {
        if (a === 'search') {
          const b = h('button', { class: 'btn small' }, st.state === 'waiting' ? 'Sök ändå' : 'Sök igen');
          b.addEventListener('click', run(b, `/api/requests/${w.id}/search`, searching));
          out.push(b);
        } else if (a === 'retry') {
          // Tar bort nedladdningen: två klick, så det inte händer av misstag.
          const label = 'Släng och sök annan';
          const b = h('button', { class: 'btn small', title: 'Tar bort nedladdningen (även i nedladdaren), svartlistar släppet så att det inte tas igen, och letar efter ett annat.' }, label);
          let armed = 0;
          const go = run(b, `/api/requests/${w.id}/retry`, searching);
          b.addEventListener('click', () => {
            if (Date.now() - armed < 4000) { armed = 0; go(); return; }
            armed = Date.now();
            b.textContent = 'Säker? Klicka igen';
            b.classList.add('danger');
            setTimeout(() => { if (armed && Date.now() - armed >= 4000) { armed = 0; b.textContent = label; b.classList.remove('danger'); } }, 4100);
          });
          out.push(b);
        } else if (a === 'scan') {
          const b = h('button', { class: 'btn small', title: 'Be Plex leta efter nya filer' }, JP.icon('refresh'), 'Skanna Plex');
          b.addEventListener('click', async () => {
            b.disabled = true;
            b.classList.add('busy');
            await JP.scanLibrary();
            if (v.isCurrent()) load().catch(() => {});
          });
          out.push(b);
        } else if (a === 'resend') {
          const b = h('button', { class: 'btn small' }, 'Skicka igen');
          b.addEventListener('click', run(b, `/api/requests/${w.id}/approve`, () => load()));
          out.push(b);
        }
      }
      return out;
    }

    async function load() {
      const data = await JP.api('/api/requests');
      cfg = { movie: data.radarr, show: data.sonarr };
      slots.clear();
      const arr = [data.radarr ? 'Radarr' : null, data.sonarr ? 'Sonarr' : null].filter(Boolean);
      head.replaceChildren(arr.length
        ? `OK skickar filmer${data.radarr ? ' till Radarr' : ''}${data.radarr && data.sonarr ? ' och' : ''}${data.sonarr ? ' serier till Sonarr' : ''}.`
        : h('span', null, 'OK markerar bara önskemålet. Lägg in Radarr/Sonarr under ', h('a', { href: '#/settings' }, 'Inställningar'), ' så skickas det dit direkt.'));
      const groups = [['pending', 'Väntar på OK'], ['approved', 'Godkända'], ['available', 'Finns nu'], ['declined', 'Nej']];
      const parts = [];
      for (const [st, title] of groups) {
        const rows = data.items.filter((w) => w.status === st);
        if (!rows.length) continue;
        parts.push(h('h2', { class: 'wish-heading' }, title, h('span', { class: 'dim' }, ' ' + rows.length)));
        for (const w of rows) parts.push(row(w));
      }
      list.replaceChildren(...(parts.length ? parts : [h('div', { class: 'empty' }, h('div', { class: 'empty-title' }, 'Inga önskemål än'), h('div', { class: 'dim' }, 'Sök ovan, eller i vanliga sökningen – det som saknas i Plex kan önskas.'))]));
      JP.status.requests = data.pending;
      JP.refreshBadge();
      if (slots.size) loadArr();
    }

    function row(w) {
      const act = h('div', { class: 'wish-actions' });
      const busy = (btn, fn) => async () => {
        btn.disabled = true;
        try { await fn(); } catch (e) { JP.toast(e.message); }
        await load();
      };
      if (w.status === 'pending') {
        const ok = h('button', { class: 'btn primary small' }, JP.icon('check'), 'OK');
        ok.addEventListener('click', busy(ok, async () => {
          const r = await JP.post(`/api/requests/${w.id}/approve`);
          JP.toast(`${w.title}: ${r.request.note || 'godkänd'}`);
        }));
        const no = h('button', { class: 'btn small' }, 'Nej');
        no.addEventListener('click', busy(no, () => JP.post(`/api/requests/${w.id}/decline`)));
        act.append(ok, no);
      }
      if (w.status === 'available' && w.libraryKey) {
        act.append(h('a', { class: 'btn small', href: (w.type === 'show' ? '#/show/' : '#/item/') + w.libraryKey }, JP.icon('play'), 'Öppna'));
      }
      // Godkänd och Radarr/Sonarr inställd: plats för deras status och knappar.
      let info = w.note && w.status !== 'pending' ? h('div', { class: 'small' }, w.note) : null;
      if (w.status === 'approved' && cfg[w.type]) {
        info = h('div', { class: 'arr-st' }, info);
        const arrAct = h('span', { class: 'arr-acts' });
        act.append(arrAct);
        slots.set(w.id, { w, info, act: arrAct });
        paint(w.id);
      }
      const del = h('button', { class: 'p-btn', title: 'Ta bort önskemålet' }, JP.icon('close'));
      del.addEventListener('click', busy(del, () => JP.api(`/api/requests/${w.id}`, { method: 'DELETE' })));
      act.append(del);
      const who = [w.by, ...(w.also || [])].join(', ');
      return h('div', { class: 'wish-row ' + w.status },
        h('div', { class: 'wish-thumb' }, w.thumb ? h('img', { src: poster(w.thumb, 120, 180), alt: '', loading: 'lazy' }) : null),
        h('div', { class: 'wish-info' },
          h('div', { class: 'wish-title' }, w.title, w.year ? h('span', { class: 'dim' }, ` (${w.year})`) : null, h('span', { class: 'wish-type' }, w.type === 'show' ? 'Serie' : 'Film')),
          h('div', { class: 'dim small' }, `Önskad av ${who} · ${ago(w.at)}`),
          info,
          w.error ? h('div', { class: 'small err' }, w.error) : null),
        act);
    }

    await load();
    const timer = setInterval(() => { if (v.isCurrent()) load().catch(() => {}); }, 30000);
    v.onLeave(() => { clearInterval(timer); clearTimeout(arrTimer); });
  }, { nocache: true, page: 'requests' });
})();
