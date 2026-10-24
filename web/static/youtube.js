/* JPTV Web – YouTube utan Google-konto: startsida med förslag, dina kanaler, historik, sök och kanalsidor. */
'use strict';
(function () {
  const { h } = JP;

  JP.icons.more = '<circle cx="12" cy="5.5" r="1.7" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1.7" fill="currentColor" stroke="none"/><circle cx="12" cy="18.5" r="1.7" fill="currentColor" stroke="none"/>';

  /** YouTubes bilder går via servern (webbläsaren pratar aldrig själv med Google). */
  JP.ytImg = (url) => (url ? '/yt/img?u=' + encodeURIComponent(url.startsWith('//') ? 'https:' + url : url) : '');

  JP.ago = function (ms) {
    if (!ms) return '';
    const s = Math.max(0, (Date.now() - ms) / 1000);
    const units = [[31536000, 'år', 'år'], [2592000, 'månad', 'månader'], [604800, 'vecka', 'veckor'], [86400, 'dag', 'dagar'], [3600, 'timme', 'timmar'], [60, 'minut', 'minuter']];
    for (const [sec, one, many] of units) {
      const n = Math.floor(s / sec);
      if (n >= 1) return `för ${n} ${n === 1 ? one : many} sedan`;
    }
    return 'nyss';
  };

  JP.viewCount = function (n) {
    if (n == null) return '';
    if (n >= 1e6) return (n / 1e6).toLocaleString('sv-SE', { maximumFractionDigits: 1 }) + ' mn visningar';
    if (n >= 1e3) return Math.round(n / 1e3).toLocaleString('sv-SE') + ' tn visningar';
    return n + (n === 1 ? ' visning' : ' visningar');
  };

  /** Andra YouTube-sidor visar gammalt efter en ändring (dold video, ny prenumeration): bygg om dem. */
  function staleYt() {
    for (const v of JP.views.values()) if (v.key.startsWith('/youtube') && v !== JP.current) v.dirty = true;
  }

  /* ---------- liten meny (⋮ på korten) ---------- */

  let openMenu = null;
  function closeMenu() {
    if (!openMenu) return;
    openMenu.el.remove();
    openMenu.anchor.setAttribute('aria-expanded', 'false');
    document.removeEventListener('pointerdown', openMenu.outside, true);
    document.removeEventListener('keydown', openMenu.key, true);
    window.removeEventListener('scroll', closeMenu, true);
    openMenu = null;
  }

  JP.ytMenu = function (anchor, items) {
    closeMenu();
    const el = h('div', { class: 'yt-menu', role: 'menu' }, items.filter(Boolean).map(([label, fn]) =>
      h('button', { role: 'menuitem', onclick: (e) => { e.preventDefault(); e.stopPropagation(); closeMenu(); fn(); } }, label)));
    document.body.append(el);
    const r = anchor.getBoundingClientRect();
    const w = el.offsetWidth, hgt = el.offsetHeight;
    el.style.left = Math.max(8, Math.min(window.innerWidth - w - 8, r.right - w)) + 'px';
    el.style.top = (r.bottom + 6 + hgt > window.innerHeight ? Math.max(8, r.top - hgt - 6) : r.bottom + 6) + 'px';
    anchor.setAttribute('aria-expanded', 'true');
    const outside = (e) => { if (!el.contains(e.target) && e.target !== anchor && !anchor.contains(e.target)) closeMenu(); };
    const key = (e) => { if (e.key === 'Escape') { e.stopPropagation(); closeMenu(); anchor.focus(); } };
    openMenu = { el, anchor, outside, key };
    document.addEventListener('pointerdown', outside, true);
    document.addEventListener('keydown', key, true);
    window.addEventListener('scroll', closeMenu, true);
    el.querySelector('button')?.focus();
  };

  async function dismiss(v, card) {
    try {
      await JP.post('/api/yt/dismiss', { id: v.id });
      card.classList.add('yt-gone');
      setTimeout(() => card.remove(), 250);
      JP.toast('Den föreslås inte igen');
      staleYt();
    } catch (e) { JP.toast(e.message); }
  }

  async function blockChannel(v) {
    try {
      await JP.post('/api/yt/block', { id: v.channelId, name: v.channel });
      document.querySelectorAll('.yt-card[data-ch="' + v.channelId + '"][data-suggested]').forEach((c) => {
        c.classList.add('yt-gone');
        setTimeout(() => c.remove(), 250);
      });
      JP.toast(`Inga förslag från ${v.channel || 'kanalen'} (ändras under Historik)`);
      staleYt();
    } catch (e) { JP.toast(e.message); }
  }

  async function forget(v, card) {
    try {
      await JP.api('/api/yt/history/' + v.id, { method: 'DELETE' });
      card.classList.add('yt-gone');
      setTimeout(() => card.remove(), 250);
      staleYt();
    } catch (e) { JP.toast(e.message); }
  }

  /**
   * Ett videokort (16:9) med längd, påbörjad/sedd, kanal, visningar och datum.
   * opts.suggested: ett förslag (menyn kan dölja det). opts.history: menyn tar bort ur historiken.
   */
  JP.ytCard = function (v, opts = {}) {
    const a = h('a', { class: 'card wide yt-card' + (opts.grid ? ' in-grid' : ''), href: '#/yt/' + v.id, 'aria-label': v.title, 'data-ch': v.channelId || '' });
    if (opts.suggested) a.dataset.suggested = '1';
    a.addEventListener('click', (e) => { e.preventDefault(); JP.playYt(v.id); });
    const art = h('div', { class: 'card-art' });
    const img = h('img', { src: JP.ytImg(v.thumb), alt: '', loading: 'lazy', decoding: 'async', draggable: 'false' });
    img.addEventListener('error', () => { img.remove(); art.append(h('div', { class: 'card-fallback' }, v.title)); });
    art.append(img);
    if (v.duration) art.append(h('span', { class: 'yt-dur' }, JP.clock(v.duration)));
    if (v.watched) art.append(h('span', { class: 'card-corner seen' }, JP.icon('check')));
    if (v.position && v.duration) art.append(h('div', { class: 'progress' }, h('i', { style: { width: Math.min(100, (v.position / v.duration) * 100) + '%' } })));
    a.append(art);
    const meta = [JP.viewCount(v.views), JP.ago(v.published)].filter(Boolean).join(' · ');
    const text = h('div', { class: 'card-text yt-text' },
      h('div', { class: 'card-title yt-title' }, v.title),
      opts.noChannel || !v.channel ? null : h('div', { class: 'card-sub' }, v.channel),
      meta ? h('div', { class: 'card-sub' }, meta) : null);
    const items = [
      opts.suggested ? ['Inte intresserad', () => dismiss(v, a)] : null,
      opts.suggested && v.channelId ? [`Föreslå inte ${v.channel || 'kanalen'}`, () => blockChannel(v)] : null,
      opts.history ? ['Ta bort ur historiken', () => forget(v, a)] : null,
      v.channelId && !opts.noChannel ? ['Gå till kanalen', () => JP.go('#/youtube/channel/' + v.channelId)] : null,
    ].filter(Boolean);
    if (items.length) {
      const btn = h('button', { class: 'yt-menu-btn', 'aria-label': 'Fler val', 'aria-haspopup': 'menu', 'aria-expanded': 'false' }, JP.icon('more'));
      btn.addEventListener('click', (e) => {
        e.preventDefault();
        e.stopPropagation();
        if (openMenu && openMenu.anchor === btn) closeMenu();
        else JP.ytMenu(btn, items);
      });
      text.append(btn);
    }
    a.append(text);
    return a;
  };

  function avatar(ch, size) {
    const box = h('div', { class: 'yt-avatar', style: { width: size + 'px', height: size + 'px' } });
    const letter = () => h('span', null, (ch.name || '?').slice(0, 1).toUpperCase());
    if (ch.avatar) {
      const img = h('img', { src: JP.ytImg(ch.avatar), alt: '', loading: 'lazy' });
      img.addEventListener('error', () => img.replaceWith(letter()));
      box.append(img);
    } else box.append(letter());
    return box;
  }

  function channelChip(ch) {
    return h('a', { class: 'yt-chan', href: '#/youtube/channel/' + ch.id }, avatar(ch, 72), h('div', { class: 'yt-chan-name' }, ch.name || 'Kanal'));
  }

  async function subscribe(ch, on) {
    if (on) await JP.post('/api/yt/subs', { id: ch.id, name: ch.name, avatar: ch.avatar });
    else await JP.api('/api/yt/subs/' + ch.id, { method: 'DELETE' });
    JP.toast(on ? `Du prenumererar på ${ch.name}` : `Prenumerationen på ${ch.name} är borttagen`);
    staleYt();
  }

  function subButton(ch, subscribed) {
    let on = !!subscribed;
    const btn = h('button', { class: 'btn small' + (on ? ' ghost' : ' primary') });
    const draw = () => {
      btn.className = 'btn small' + (on ? ' ghost' : ' primary');
      btn.textContent = on ? 'Prenumererar' : 'Prenumerera';
    };
    btn.addEventListener('click', async (e) => {
      e.preventDefault();
      e.stopPropagation();
      try {
        await subscribe(ch, !on);
        on = !on;
        draw();
      } catch (err) { JP.toast(err.message); }
    });
    draw();
    return btn;
  }

  /** Kanalförslag: profilbild, namn och Prenumerera. */
  function suggestTile(ch) {
    return h('a', { class: 'yt-suggest', href: '#/youtube/channel/' + ch.id }, avatar(ch, 76),
      h('div', { class: 'yt-chan-name' }, ch.name || 'Kanal'),
      h('div', { class: 'dim small' }, ch.videos > 1 ? `${ch.videos} förslag` : ''),
      subButton(ch, false));
  }

  function searchBox(value) {
    const input = h('input', { type: 'search', class: 'search-input yt-search', placeholder: 'Sök på YouTube', value: value || '', spellcheck: 'false' });
    const form = h('form', { class: 'yt-search-form', onsubmit: (e) => { e.preventDefault(); const q = input.value.trim(); if (q) JP.go('#/youtube/search?q=' + encodeURIComponent(q)); } }, input);
    return form;
  }

  /** Rubrik med sök och flikarna Hem, Prenumerationer och Historik. */
  function head(active, query) {
    const tab = (key, href, label) => h('a', { class: 'tab' + (active === key ? ' on' : ''), href }, label);
    return h('div', { class: 'yt-head' }, h('h1', null, h('a', { href: '#/youtube' }, 'YouTube')), searchBox(query),
      h('div', { class: 'tabs yt-tabs' }, tab('home', '#/youtube', 'Hem'), tab('subs', '#/youtube/subs', 'Prenumerationer'), tab('history', '#/youtube/history', 'Historik')));
  }

  /** En rad som rullar i sidled, med pilar som Plex-raderna. */
  function stripRow(title, children, cls) {
    if (!children.length) return null;
    const strip = h('div', { class: 'strip ' + (cls || 'wide') }, children);
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
    return h('section', { class: 'row yt-row' }, h('h2', null, title), h('div', { class: 'strip-wrap' }, left, strip, right));
  }

  function videoRow(title, items, opts = {}) {
    return stripRow(title, items.map((v) => JP.ytCard(v, opts)));
  }

  function videoGrid(items, opts = {}) {
    return h('div', { class: 'yt-grid' }, items.map((v) => JP.ytCard(v, Object.assign({ grid: true }, opts))));
  }

  function problem(e, retry) {
    return h('div', { class: 'empty' },
      h('div', { class: 'empty-title' }, 'YouTube svarar inte som det ska'),
      h('div', { class: 'dim' }, String((e && e.message) || e)),
      h('div', { class: 'actions center' },
        retry ? h('button', { class: 'btn', onclick: retry }, 'Försök igen') : null,
        h('a', { class: 'btn ghost', href: '#/settings' }, 'Inställningar → YouTube')));
  }

  const rebuild = (v) => () => { v.dirty = true; JP.current = null; JP.render(); };

  /* ---------- Hem: förslag ---------- */

  JP.route(/^\/youtube$/, async (v) => {
    const page = h('div', { class: 'page wide yt-page' });
    page.append(head('home', ''));
    const body = h('div');
    page.append(body);
    v.el.append(page);
    body.append(JP.spinner());
    // Förslagen tar längre tid första gången; resten av sidan visas så länge.
    const recsP = JP.api('/api/yt/recommended').catch((e) => ({ error: e }));
    let data;
    try {
      data = await JP.api('/api/yt/home');
    } catch (e) {
      body.replaceChildren(problem(e, rebuild(v)));
      return;
    }
    body.replaceChildren();
    const cont = videoRow('Fortsätt titta', data.continue);
    if (cont) body.append(cont);

    const recBox = h('section', { class: 'yt-recs' }, h('h2', { class: 'yt-h2' }, 'Rekommenderat för dig'), JP.spinner('Letar fram förslag …'));
    body.append(recBox);
    const fresh = data.feed.filter((x) => !x.watched).slice(0, 24);
    const feedRow = stripRow(h('span', { class: 'yt-h2-link' }, 'Nytt från dina kanaler', h('a', { href: '#/youtube/subs', class: 'dim small' }, 'Visa alla')),
      fresh.map((x) => JP.ytCard(x)));
    if (feedRow) body.append(feedRow);
    const later = h('div');
    body.append(later);

    const recs = await recsP;
    if (!document.body.contains(recBox)) return;
    if (recs.error) {
      recBox.replaceChildren(h('h2', { class: 'yt-h2' }, 'Rekommenderat för dig'),
        h('div', { class: 'empty small' }, 'Förslagen gick inte att hämta: ' + recs.error.message, h('div', { class: 'actions center' }, h('button', { class: 'btn small', onclick: rebuild(v) }, 'Försök igen'))));
      return;
    }
    if (!recs.recommended.length) {
      recBox.replaceChildren(h('div', { class: 'empty yt-welcome' },
        h('div', { class: 'empty-title' }, data.subs.length || data.continue.length ? 'Inga förslag än' : 'Välkommen till YouTube i JPTV'),
        h('div', { class: 'dim' }, 'Förslagen bygger på det du tittar på och kanalerna du följer, och räknas fram här hemma utan Google-konto. Titta på några videor eller prenumerera på kanaler, så fylls sidan.'),
        h('div', { class: 'actions center' },
          h('a', { class: 'btn primary', href: '#/youtube/search' }, 'Sök på YouTube'),
          h('a', { class: 'btn ghost', href: '#/settings' }, 'Importera prenumerationer'))));
    } else {
      const shown = 12;
      const grid = videoGrid(recs.recommended.slice(0, shown), { suggested: true });
      const more = recs.recommended.length > shown
        ? h('button', { class: 'btn ghost small yt-more', onclick: () => { grid.append(...recs.recommended.slice(shown).map((x) => JP.ytCard(x, { grid: true, suggested: true }))); more.remove(); } }, 'Visa fler förslag')
        : null;
      recBox.replaceChildren(...[h('h2', { class: 'yt-h2' }, 'Rekommenderat för dig'), grid, more].filter(Boolean));
    }
    for (const b of recs.because || []) {
      const row = stripRow(h('span', { class: 'yt-because' }, 'Eftersom du tittade på ', h('q', null, b.seed.title || 'en video')),
        b.videos.map((x) => JP.ytCard(x, { suggested: true })));
      if (row) later.append(row);
    }
    if (recs.channels && recs.channels.length) {
      later.append(stripRow('Kanaler du kanske gillar', recs.channels.map(suggestTile), 'yt-suggests'));
    }
  });

  /* ---------- Prenumerationer ---------- */

  JP.route(/^\/youtube\/subs$/, async (v) => {
    const page = h('div', { class: 'page wide yt-page' });
    page.append(head('subs', ''));
    const body = h('div');
    page.append(body);
    v.el.append(page);
    body.append(JP.spinner());
    let data;
    try {
      data = await JP.api('/api/yt/home');
    } catch (e) {
      body.replaceChildren(problem(e, rebuild(v)));
      return;
    }
    body.replaceChildren();
    if (!data.subs.length) {
      body.append(h('div', { class: 'empty' },
        h('div', { class: 'empty-title' }, 'Inga kanaler än'),
        h('div', { class: 'dim' }, 'Sök efter en kanal och tryck Prenumerera, eller hämta dina prenumerationer från FreeTube, NewPipe eller YouTube under Inställningar.'),
        h('div', { class: 'actions center' }, h('a', { class: 'btn ghost', href: '#/settings' }, 'Importera prenumerationer'))));
      return;
    }
    body.append(stripRow(`Dina kanaler (${data.subs.length})`, data.subs.map(channelChip), 'yt-chans'));
    if (data.feed.length) body.append(h('h2', { class: 'yt-h2' }, 'Nytt från dina kanaler'), videoGrid(data.feed));
    else body.append(h('div', { class: 'empty small' }, 'Inga nya videor hittades just nu.'));
  });

  /* ---------- Historik ---------- */

  JP.route(/^\/youtube\/history$/, async (v) => {
    const page = h('div', { class: 'page wide yt-page' });
    page.append(head('history', ''));
    const body = h('div');
    page.append(body);
    v.el.append(page);
    body.append(JP.spinner());
    let hist, st;
    try {
      [hist, st] = await Promise.all([JP.api('/api/yt/history'), JP.api('/api/yt/status')]);
    } catch (e) {
      body.replaceChildren(problem(e, rebuild(v)));
      return;
    }
    body.replaceChildren();
    let armed = false;
    const clear = h('button', { class: 'btn ghost small' }, 'Rensa historiken');
    clear.addEventListener('click', async () => {
      if (!armed) {
        armed = true;
        clear.textContent = 'Säker? Klicka igen';
        setTimeout(() => { armed = false; clear.textContent = 'Rensa historiken'; }, 4000);
        return;
      }
      try {
        await JP.api('/api/yt/history', { method: 'DELETE' });
        JP.toast('Historiken är rensad');
        staleYt();
        rebuild(v)();
      } catch (e) { JP.toast(e.message); }
    });
    body.append(h('div', { class: 'yt-history-head' },
      h('p', { class: 'dim' }, 'Det du har tittat på, på alla TV-apparater och här. Förslagen på Hem bygger på det, och allt sparas bara hos dig.'),
      hist.videos.length ? clear : null));
    body.append(hist.videos.length ? videoGrid(hist.videos, { history: true }) : h('div', { class: 'empty small' }, 'Inget sett än.'));

    if (st.blocked && st.blocked.length) {
      body.append(h('h2', { class: 'yt-h2' }, 'Kanaler som inte föreslås'),
        h('div', { class: 'yt-chan-list' }, st.blocked.map((b) => {
          const row = h('div', { class: 'yt-chan-row' }, h('div', { class: 'yt-chan-info' }, h('a', { class: 'yt-chan-title', href: '#/youtube/channel/' + b.id }, b.name || b.id)));
          row.append(h('button', { class: 'btn small ghost', onclick: async () => {
            try {
              await JP.api('/api/yt/block/' + b.id, { method: 'DELETE' });
              row.remove();
              staleYt();
              JP.toast(`${b.name || 'Kanalen'} kan föreslås igen`);
            } catch (e) { JP.toast(e.message); }
          } }, 'Föreslå igen'));
          return row;
        })));
    }
  });

  /* ---------- sök ---------- */

  JP.route(/^\/youtube\/search$/, async (v, _m, q) => {
    const query = q.get('q') || '';
    const page = h('div', { class: 'page wide yt-page' });
    page.append(head('', query));
    const body = h('div');
    page.append(body);
    v.el.append(page);
    if (!query) {
      page.querySelector('input').focus();
      return;
    }
    body.append(JP.spinner('Söker …'));
    let res;
    try {
      res = await JP.api('/api/yt/search?q=' + encodeURIComponent(query));
    } catch (e) {
      body.replaceChildren(problem(e, rebuild(v)));
      return;
    }
    body.replaceChildren();
    if (res.channels.length) {
      body.append(h('h2', { class: 'yt-h2' }, 'Kanaler'),
        h('div', { class: 'yt-chan-list' }, res.channels.map((ch) =>
          h('a', { class: 'yt-chan-row', href: '#/youtube/channel/' + ch.id }, avatar(ch, 56),
            h('div', { class: 'yt-chan-info' }, h('div', { class: 'yt-chan-title' }, ch.name),
              ch.subscribers ? h('div', { class: 'dim small' }, ch.subscribers.toLocaleString('sv-SE') + ' prenumeranter') : null),
            subButton(ch, ch.subscribed)))));
    }
    body.append(h('h2', { class: 'yt-h2' }, 'Videor'), res.videos.length ? videoGrid(res.videos) : h('div', { class: 'empty small' }, 'Inga videor hittades.'));
  });

  /* ---------- kanal ---------- */

  JP.route(/^\/youtube\/channel\/(UC[0-9A-Za-z_-]{22})$/, async (v, [id]) => {
    const page = h('div', { class: 'page wide yt-page' });
    v.el.append(page);
    page.append(JP.spinner());
    let ch;
    try {
      ch = await JP.api('/api/yt/channel/' + id);
    } catch (e) {
      page.replaceChildren(problem(e, rebuild(v)));
      return;
    }
    page.replaceChildren(
      h('div', { class: 'yt-chan-head' }, avatar(ch, 96),
        h('div', { class: 'yt-chan-info' }, h('h1', null, ch.name),
          ch.subscribers ? h('div', { class: 'dim' }, ch.subscribers.toLocaleString('sv-SE') + ' prenumeranter') : null,
          ch.description ? h('p', { class: 'dim yt-desc' }, ch.description) : null),
        subButton(ch, ch.subscribed)),
      h('h2', { class: 'yt-h2' }, 'Senaste videorna'),
      ch.videos.length ? videoGrid(ch.videos, { noChannel: true }) : h('div', { class: 'empty small' }, 'Inga videor.'));
  });

  /* ---------- hemskärmen: rad med nytt från kanalerna ---------- */

  /** Lägger "Nytt från dina kanaler" efter Fortsätt titta på hemskärmen (om det finns prenumerationer). */
  JP.ytHomeRow = async function (rows) {
    if (!JP.status || !JP.status.youtube) return;
    let data;
    try { data = await JP.api('/api/yt/home'); } catch (e) { return; }
    const items = data.feed.filter((x) => !x.watched).slice(0, 20);
    if (!items.length) return;
    const row = h('section', { class: 'row' }, h('h2', null, h('a', { href: '#/youtube/subs' }, 'Nytt från dina kanaler på YouTube')),
      h('div', { class: 'strip-wrap' }, h('div', { class: 'strip wide' }, items.map((x) => JP.ytCard(x)))));
    const first = rows.querySelector('.row');
    if (first && first.nextSibling) rows.insertBefore(row, first.nextSibling);
    else rows.append(row);
  };

  /* ---------- inställningar ---------- */

  JP.ytSettingsBox = function (s, save) {
    const box = h('section', { class: 'card-box' }, h('h2', null, 'YouTube'),
      h('p', { class: 'dim' }, 'YouTube utan Google-konto och utan reklam. Prenumerationerna sparas här och syns på alla TV-apparater. Videorna hämtas med yt-dlp, som uppdaterar sig själv varje natt när YouTube ändrar något.'));
    const state = h('div', { class: 'dim small' }, 'Hämtar status …');
    const result = h('span', { class: 'dim small' });
    const drawStatus = (st) => {
      const recs = st.recs || {};
      const parts = [
        st.ytdlp ? 'yt-dlp ' + st.ytdlp : 'yt-dlp saknas',
        st.deno ? 'Deno finns' : 'Deno saknas',
        st.updatedAt ? 'uppdaterad ' + new Date(st.updatedAt).toLocaleString('sv-SE') : null,
        st.updating ? 'uppdaterar …' : null,
        st.updateError ? 'uppdateringen misslyckades: ' + st.updateError : null,
        `${st.subs} kanaler`,
        recs.error && !recs.youtube ? 'förslag: ' + recs.error : null,
      ].filter(Boolean);
      state.textContent = parts.join(' · ');
    };
    JP.api('/api/yt/status').then(drawStatus).catch((e) => { state.textContent = e.message; });
    const quality = h('select', { class: 'select' },
      [[1080, '1080p (Full HD)'], [720, '720p'], [480, '480p']].map(([v, t]) => h('option', { value: v, selected: (s.youtubeMaxHeight || 1080) === v }, t)));
    quality.addEventListener('change', () => save({ youtubeMaxHeight: +quality.value }, 'Sparat'));
    const shorts = h('input', { type: 'checkbox', checked: !!s.youtubeShorts });
    shorts.addEventListener('change', () => save({ youtubeShorts: shorts.checked }, 'Sparat'));
    const file = h('input', { type: 'file', accept: '.json,.db,.csv,.opml,.xml,.txt', hidden: true });
    file.addEventListener('change', async () => {
      const f = file.files[0];
      if (!f) return;
      result.textContent = 'Läser …';
      try {
        const text = await f.text();
        const r = await fetch('/api/yt/import', { method: 'POST', body: text });
        const data = await r.json();
        if (!r.ok) throw new Error(data.error || 'Gick inte');
        result.textContent = `✓ ${data.added} nya kanaler (${data.found} i filen)`;
        staleYt();
      } catch (e) { result.textContent = e.message; }
      file.value = '';
    });
    box.append(
      state,
      h('div', { class: 'arr-row' },
        h('label', { class: 'field' }, h('span', { class: 'field-label' }, 'Högsta kvalitet'), quality),
        h('label', { class: 'field check' }, shorts, h('span', null, 'Visa Shorts i flödet'))),
      h('div', { class: 'actions' },
        h('button', { class: 'btn small', onclick: async () => {
          result.textContent = 'Testar (tar några sekunder) …';
          try { result.textContent = '✓ ' + (await JP.post('/api/yt/test')).ok; } catch (e) { result.textContent = e.message; }
        } }, 'Testa YouTube'),
        h('button', { class: 'btn small ghost', onclick: async () => {
          result.textContent = 'Uppdaterar yt-dlp …';
          try { const st = await JP.post('/api/yt/update'); drawStatus(st); result.textContent = st.updateError ? st.updateError : '✓ Klart'; } catch (e) { result.textContent = e.message; }
        } }, 'Uppdatera yt-dlp nu'),
        h('button', { class: 'btn small ghost', onclick: () => file.click() }, 'Importera prenumerationer …'),
        file, result),
      h('p', { class: 'dim small' }, 'Import: FreeTube (Inställningar → Dataregistrering → Exportera prenumerationer), NewPipe (Prenumerationer → Exportera), YouTube Takeout (subscriptions.csv) eller OPML.'));
    return box;
  };
})();
