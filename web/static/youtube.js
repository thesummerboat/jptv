/* JPTV Web – YouTube utan Google-konto: dina kanaler, nytt från dem, sök och kanalsidor. */
'use strict';
(function () {
  const { h } = JP;

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
    return n + ' visningar';
  };

  /** Ett videokort (16:9) med längd, påbörjad/sedd och kanal. */
  JP.ytCard = function (v, opts = {}) {
    const a = h('a', { class: 'card wide yt-card' + (opts.grid ? ' in-grid' : ''), href: '#/yt/' + v.id, 'aria-label': v.title });
    a.addEventListener('click', (e) => { e.preventDefault(); JP.playYt(v.id); });
    const art = h('div', { class: 'card-art' });
    const img = h('img', { src: JP.ytImg(v.thumb), alt: '', loading: 'lazy', decoding: 'async', draggable: 'false' });
    img.addEventListener('error', () => { img.remove(); art.append(h('div', { class: 'card-fallback' }, v.title)); });
    art.append(img);
    if (v.duration) art.append(h('span', { class: 'yt-dur' }, JP.clock(v.duration)));
    if (v.watched) art.append(h('span', { class: 'card-corner seen' }, JP.icon('check')));
    if (v.position && v.duration) art.append(h('div', { class: 'progress' }, h('i', { style: { width: Math.min(100, (v.position / v.duration) * 100) + '%' } })));
    a.append(art);
    const sub = [opts.noChannel ? null : v.channel, JP.viewCount(v.views), JP.ago(v.published)].filter(Boolean).join(' · ');
    a.append(h('div', { class: 'card-text' }, h('div', { class: 'card-title yt-title' }, v.title), h('div', { class: 'card-sub' }, sub)));
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
    JP.invalidate();
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

  function searchBox(value) {
    const input = h('input', { type: 'search', class: 'search-input yt-search', placeholder: 'Sök på YouTube', value: value || '', spellcheck: 'false' });
    const form = h('form', { class: 'yt-search-form', onsubmit: (e) => { e.preventDefault(); const q = input.value.trim(); if (q) JP.go('#/youtube/search?q=' + encodeURIComponent(q)); } }, input);
    return form;
  }

  function videoRow(title, items, opts = {}) {
    if (!items.length) return null;
    const strip = h('div', { class: 'strip wide' }, items.map((v) => JP.ytCard(v, opts)));
    return h('section', { class: 'row' }, h('h2', null, title), h('div', { class: 'strip-wrap' }, strip));
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

  /* ---------- YouTube-sidan ---------- */

  JP.route(/^\/youtube$/, async (v) => {
    const page = h('div', { class: 'page wide yt-page' });
    page.append(h('div', { class: 'yt-head' }, h('h1', null, 'YouTube'), searchBox('')));
    const body = h('div');
    page.append(body);
    v.el.append(page);
    body.append(JP.spinner());
    let data;
    try {
      data = await JP.api('/api/yt/home');
    } catch (e) {
      body.replaceChildren(problem(e, () => { v.dirty = true; JP.current = null; JP.render(); }));
      return;
    }
    body.replaceChildren();
    if (!data.subs.length) {
      body.append(h('div', { class: 'empty' },
        h('div', { class: 'empty-title' }, 'Inga kanaler än'),
        h('div', { class: 'dim' }, 'Sök efter en kanal och tryck Prenumerera, eller hämta dina prenumerationer från FreeTube, NewPipe eller YouTube under Inställningar.'),
        h('div', { class: 'actions center' }, h('a', { class: 'btn ghost', href: '#/settings' }, 'Importera prenumerationer'))));
    }
    const cont = videoRow('Fortsätt titta', data.continue);
    if (cont) body.append(cont);
    if (data.subs.length) {
      body.append(h('section', { class: 'row' }, h('h2', null, 'Dina kanaler'),
        h('div', { class: 'strip yt-chans' }, data.subs.map(channelChip))));
    }
    if (data.feed.length) {
      body.append(h('h2', { class: 'yt-h2' }, 'Nytt från dina kanaler'), videoGrid(data.feed));
    } else if (data.subs.length) {
      body.append(h('div', { class: 'empty small' }, 'Inga nya videor hittades just nu.'));
    }
  });

  /* ---------- sök ---------- */

  JP.route(/^\/youtube\/search$/, async (v, _m, q) => {
    const query = q.get('q') || '';
    const page = h('div', { class: 'page wide yt-page' });
    page.append(h('div', { class: 'yt-head' }, h('h1', null, 'YouTube'), searchBox(query)));
    const body = h('div');
    page.append(body);
    v.el.append(page);
    if (!query) return;
    body.append(JP.spinner('Söker …'));
    let res;
    try {
      res = await JP.api('/api/yt/search?q=' + encodeURIComponent(query));
    } catch (e) {
      body.replaceChildren(problem(e, () => { v.dirty = true; JP.current = null; JP.render(); }));
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
      page.replaceChildren(problem(e, () => { v.dirty = true; JP.current = null; JP.render(); }));
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
    const row = h('section', { class: 'row' }, h('h2', null, h('a', { href: '#/youtube' }, 'Nytt från dina kanaler på YouTube')),
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
      const parts = [
        st.ytdlp ? 'yt-dlp ' + st.ytdlp : 'yt-dlp saknas',
        st.deno ? 'Deno finns' : 'Deno saknas',
        st.updatedAt ? 'uppdaterad ' + new Date(st.updatedAt).toLocaleString('sv-SE') : null,
        st.updating ? 'uppdaterar …' : null,
        st.updateError ? 'uppdateringen misslyckades: ' + st.updateError : null,
        `${st.subs} kanaler`,
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
        JP.invalidate();
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
