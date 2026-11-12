// Inventaire (every item), one item's page, and the search screen.
import { h, icon, fmt, compact, plural, store, debounce, norm, stacks, avatar, ago, where, time, weekday, dayShort } from '../util.js';
import { api, session, currentView } from '../api.js';
import { itemIcon, itemName, initAssets } from '../icons.js';
import { timeChart, segBar, table, progressBar, SERIES, OTHER } from '../charts.js';
import { header, card, chartCard, segmented, feed, empty, skeleton, errorBox, itemRow, placeText, distanceBadge, locateButton, viewEmpty } from '../ui.js';

function matches(it, q) {
  if (!q) return 0;
  const name = norm(itemName(it.id));
  if (name === q || it.id === q.replace(/ /g, '_')) return 4;
  if (name.startsWith(q)) return 3;
  if (name.split(/[\s'’-]+/).some((w) => w.startsWith(q))) return 2;
  if (name.includes(q) || it.id.includes(q.replace(/ /g, '_'))) return 1;
  return 0;
}

// ───────── Inventaire ─────────

export function inventoryPage(root, _params, query) {
  let sort = store.get('invSort', 'n');
  let view = store.get('invView', 'grid');
  let q = query.get('q') || '';
  let items = null;
  let alive = true;
  const sub = h('div.sub', ' ');
  const head = header('Inventaire', { view: () => { items = null; body.replaceChildren(skeleton(2)); load(); } });
  head.querySelector('.t').appendChild(sub);
  const input = h('input', { type: 'search', placeholder: 'Filtrer (ex. bois, minerai…)', value: q, 'aria-label': 'Filtrer les objets', autocomplete: 'off', enterkeyhint: 'search' });
  const body = h('div', skeleton(2));
  root.append(head,
    h('div.toolbar',
      h('label.searchbox', icon('search'), input),
      segmented([['n', 'Quantité'], ['a', 'A → Z'], ['c', 'Coffres']], sort, (v) => { sort = v; store.set('invSort', v); draw(); }, 'Trier'),
      segmented([['grid', 'Grille'], ['list', 'Liste']], view, (v) => { view = v; store.set('invView', v); draw(); }, 'Affichage')),
    body);
  input.addEventListener('input', debounce(() => { q = input.value; draw(); }, 120));

  async function load() {
    try {
      await initAssets();
      items = await api('items');
      if (alive) draw();
    } catch (e) {
      if (alive) body.replaceChildren(errorBox(e, load));
    }
  }

  function draw() {
    if (!items) return;
    const total = items.reduce((a, it) => a + it.n, 0);
    sub.textContent = `${plural(items.length, "type d'objet", "types d'objets")} · ${fmt(total)} au total`;
    const nq = norm(q);
    let list = nq ? items.filter((it) => matches(it, nq) > 0) : items.slice();
    if (sort === 'a') list.sort((a, b) => itemName(a.id).localeCompare(itemName(b.id), 'fr'));
    else if (sort === 'c') list.sort((a, b) => b.c - a.c || b.n - a.n);
    if (!items.length) {
      body.replaceChildren(card(null, viewEmpty(currentView())));
      return;
    }
    if (!list.length) {
      body.replaceChildren(card(null, empty('🔎', `Aucun objet ne correspond à « ${q} »`, 'Essaie un autre mot.')));
      return;
    }
    if (view === 'grid') {
      body.replaceChildren(h('div.items', list.map((it) => h('a.it', { href: '#/objet/' + it.id, title: `${itemName(it.id)} : ${fmt(it.n)}` },
        itemIcon(it.id, 'l'), h('div.n', compact(it.n)), h('div.nm', itemName(it.id))))));
    } else {
      body.replaceChildren(card(null, h('div.list', list.map((it) => itemRow(it, {
        meta: `dans ${plural(it.c, 'coffre', 'coffres')}${stacks(it.n, it.st) ? ' · ' + stacks(it.n, it.st) : ''}`,
      }))), { cls: 'flush' }));
    }
  }

  load();
  return { refresh: load, destroy() { alive = false; } };
}

// ───────── one item ─────────

export function itemPage(root, [id]) {
  let days = store.get('itemDays', 7);
  let alive = true;
  const body = h('div', skeleton(3));
  root.append(header('', { back: { label: 'Inventaire', href: '#/inventaire' } }), body);
  root.querySelector('.head').remove();

  async function load() {
    try {
      await initAssets();
      const d = await api(`item/${id}?days=${days}`);
      if (alive) body.replaceChildren(render(d));
      document.title = `${itemName(id)} · Coffres`;
    } catch (e) {
      if (alive) body.replaceChildren(errorBox(e, load));
    }
  }

  function render(d) {
    const name = itemName(id);
    const st = stacks(d.total, d.stack);
    const shulk = d.stack > 1 && d.total >= d.stack * 27 * 0.5 ? `≈ ${(d.total / (d.stack * 27)).toLocaleString('fr-FR', { maximumFractionDigits: 1 })} shulker(s)` : null;
    const top = h('div.item-head',
      h('div.ic-wrap', itemIcon(id, 'xl')),
      h('div', { style: { minWidth: 0 } },
        h('h1', name),
        h('div.tot', fmt(d.total)),
        h('div.eq', [st, shulk].filter(Boolean).join(' · ') || (d.total === 1 ? 'un seul exemplaire' : ''))));

    const me = session.me;
    const chests = d.chests.slice();
    if (me.online) {
      chests.sort((a, b) => {
        const wa = where(me.pos, a);
        const wb = where(me.pos, b);
        if (wa && wb) return wa.d - wb.d;
        if (wa) return -1;
        if (wb) return 1;
        return b.count - a.count;
      });
    }
    const actions = chests.length ? h('div.actions',
      locateButton(chests.slice(0, 8).map((c) => c.id), chests.length > 1 ? `Faire briller ${Math.min(8, chests.length)} coffres` : 'Localiser en jeu'),
      !me.online ? h('span.note', { style: { alignSelf: 'center' } }, 'Connecte-toi en jeu : le coffre s\'allumera en bleu à travers les murs.') : null) : null;

    const whereCard = card(`Où le trouver`, chests.length ? h('div.list', chests.map((c) => h('a.row', { href: '#/coffre/' + c.id },
      h('div.ic-box', itemIcon(c.type || 'chest')),
      h('div.main-col',
        h('div.title', c.name, c.pub ? null : h('span.lock', ' 🔒')),
        h('div.meta', `${c.zone ? c.zone + ' · ' : ''}${placeText(c)}`),
        distanceBadge(c)),
      h('div.end', h('div.v', fmt(c.count)), c.loose < c.count ? h('div.s', `dont ${fmt(c.count - c.loose)} en shulker`) : null),
      locateButton([c.id], '', 'icon-btn')))) : h('p.note', { style: { padding: '0 16px 12px' } }, 'Il n\'y en a plus dans les coffres suivis.'),
    { cls: 'flush wide' });

    const cards = [whereCard];
    if (d.projects && d.projects.length) {
      cards.push(card('Pour les projets', h('div.list', d.projects.map((p) => h('a.row.plain', { href: '#/projet/' + p.id, style: { padding: '8px 0' } },
        h('div.main-col',
          h('div.title', p.name),
          h('div.meta', p.have >= p.need ? `${fmt(p.need)} nécessaires · tout est là` : `${fmt(p.have)} / ${fmt(p.need)} · manque ${fmt(p.need - p.have)}`),
          progressBar(p.have, p.need)),
        icon('chev', 'chev')))), { cls: 'wide' }));
    }
    if (d.chests.length > 1) {
      const sorted = d.chests.slice().sort((a, b) => b.count - a.count);
      const parts = sorted.slice(0, 5).map((c, i) => ({ label: c.name, value: c.count, color: SERIES[i] }));
      const rest = sorted.slice(5).reduce((a, c) => a + c.count, 0);
      if (rest) parts.push({ label: `Autres (${sorted.length - 5})`, value: rest, color: OTHER });
      cards.push(card('Répartition', segBar(parts), { cls: 'wide' }));
    }
    const tr = d.trend;
    cards.push(chartCard('Évolution',
      () => timeChart({ points: tr.points, from: tr.from, step: tr.step, unit: name.toLowerCase() }),
      () => table(['Moment', 'Quantité'], tr.points.map((v, i) => {
        const t = tr.from + i * tr.step;
        return [tr.step < 86_400_000 * 0.9 ? `${weekday(t)} ${dayShort(t)} ${time(t)}` : `${weekday(t)} ${dayShort(t)}`, fmt(v)];
      }).reverse()),
      { tools: segmented([[1, '24 h'], [7, '7 j'], [30, '30 j']], days, (v) => { days = v; store.set('itemDays', v); load(); }, 'Période') }));

    cards.push(card('Qui en a bougé', d.players.length ? h('div.list', d.players.map((p) => h('a.row.plain', {
      href: `#/activite?joueur=${encodeURIComponent(p.name)}&objet=${id}`, style: { padding: '8px 0' },
    }, avatar(p.name), h('div.main-col', h('div.title', p.name), h('div.meta', ago(p.last))),
    h('div.end', h('div.io', p.in ? h('span.good', '+' + fmt(p.in)) : null, p.out ? h('span.bad', '−' + fmt(p.out)) : null)))))
      : h('p.note', 'Personne ne l\'a déposé ni pris sur cette période.')));

    cards.push(card('Derniers mouvements', d.recent.length ? feed(d.recent, { days: true }) : h('p.note', { style: { padding: '0 16px 12px' } }, 'Aucun mouvement enregistré.'),
      { cls: 'flush wide', more: { href: '#/activite?objet=' + id, label: 'Tout voir' } }));

    return h('div', top, actions, h('div.grid.two', cards));
  }

  load();
  return { refresh: load, destroy() { alive = false; } };
}

// ───────── Chercher ─────────

export function searchPage(root, _params, query) {
  let items = null;
  let chests = null;
  let alive = true;
  const input = h('input', { type: 'search', placeholder: 'Diamant, bûche, « Minerais »…', 'aria-label': 'Chercher', autocomplete: 'off', enterkeyhint: 'search', value: query.get('q') || '' });
  const results = h('div', skeleton(1));
  root.append(header('Chercher', { sub: 'Trouve un objet et le coffre où il est rangé.', view: () => { items = null; chests = null; load(); } }),
    h('label.searchbox.big', icon('search'), input), results);
  setTimeout(() => input.focus(), 50);
  input.addEventListener('input', debounce(draw, 80));
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      const first = results.querySelector('a.row');
      if (first) { remember(input.value); location.hash = first.getAttribute('href'); }
    }
  });

  function remember(q) {
    const t = q.trim();
    if (!t) return;
    const recent = store.get('recent', []).filter((x) => x.toLowerCase() !== t.toLowerCase());
    recent.unshift(t);
    store.set('recent', recent.slice(0, 8));
  }

  async function load() {
    try {
      await initAssets();
      [items, chests] = await Promise.all([api('items'), api('chests')]);
      if (alive) draw();
    } catch (e) {
      if (alive) results.replaceChildren(errorBox(e, load));
    }
  }

  function nearest(it) {
    const byId = new Map(chests.map((c) => [c.id, c]));
    const list = (it.w || []).map((cid) => byId.get(cid)).filter(Boolean);
    const me = session.me;
    let best = null;
    let bestD = Infinity;
    if (me.online) {
      for (const c of list) {
        const w = where(me.pos, c);
        if (w && w.d < bestD) { best = { c, w }; bestD = w.d; }
      }
    }
    if (best) return h('div.dist', icon('pin'), `${best.c.name} · ${fmt(best.w.d)} m ${best.w.short}`);
    return list[0] ? h('div.meta', `surtout dans ${list[0].name}${list[0].zone ? ' (' + list[0].zone + ')' : ''}`) : null;
  }

  function itemResult(it) {
    const row = h('a.row', { href: '#/objet/' + it.id },
      h('div.ic-box', itemIcon(it.id)),
      h('div.main-col', h('div.title', itemName(it.id)), h('div.meta', `dans ${plural(it.c, 'coffre', 'coffres')}`), nearest(it)),
      h('div.end', h('div.v', fmt(it.n))),
      icon('chev', 'chev'));
    row.addEventListener('click', () => remember(input.value));
    return row;
  }

  function draw() {
    if (!items) return;
    const q = norm(input.value);
    if (!q) {
      const recent = store.get('recent', []);
      results.replaceChildren(
        recent.length ? h('div', h('div.card-h', h('h2', 'Recherches récentes')),
          h('div.chips', recent.map((r) => h('button.chip', { type: 'button', onclick: () => { input.value = r; draw(); } }, r)))) : null,
        items.length ? card('Les plus stockés', h('div.items', items.slice(0, 12).map((it) => h('a.it', { href: '#/objet/' + it.id, onclick: () => remember(itemName(it.id)) },
          itemIcon(it.id, 'l'), h('div.n', compact(it.n)), h('div.nm', itemName(it.id)))))) : card(null, empty('📦', 'Rien à chercher pour l\'instant', 'Ajoute des coffres en jeu avec /coffres.')));
      return;
    }
    const foundItems = items.map((it) => [it, matches(it, q)]).filter(([, sc]) => sc > 0)
      .sort((a, b) => b[1] - a[1] || b[0].n - a[0].n).slice(0, 40).map(([it]) => it);
    const foundChests = chests.filter((c) => norm(c.name).includes(q) || norm(c.zone || '').includes(q)).slice(0, 20);
    const out = [];
    if (foundItems.length) {
      out.push(card(`Objets · ${foundItems.length}`, h('div.list', foundItems.map(itemResult)), { cls: 'flush' }));
    }
    if (foundChests.length) {
      out.push(card(`Coffres · ${foundChests.length}`, h('div.list', foundChests.map((c) => h('a.row', { href: '#/coffre/' + c.id, onclick: () => remember(input.value) },
        h('div.ic-box', itemIcon(c.type || 'chest')),
        h('div.main-col', h('div.title', c.name), h('div.meta', `${c.zone ? c.zone + ' · ' : ''}${placeText(c)}`), distanceBadge(c)),
        h('div.end', h('div.v', compact(c.items)), h('div.s', 'objets')),
        icon('chev', 'chev')))), { cls: 'flush' }));
    }
    if (!out.length) {
      out.push(card(null, empty('🔎', `Pas de « ${input.value.trim()} » dans les coffres`, 'Vérifie l\'orthographe, ou cherche un mot plus court (ex. « fer » au lieu de « lingot de fer »).')));
    }
    results.replaceChildren(h('div.grid', out));
  }

  load();
  return { refresh: load, destroy() { alive = false; } };
}
