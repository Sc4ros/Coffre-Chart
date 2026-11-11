// Activité (full history with filters) and Joueurs (who deposits and takes the most).
import { h, fmt, compact, plural, store, avatar, ago, weekday, dayShort } from '../util.js';
import { api, session } from '../api.js';
import { itemIcon, itemName, initAssets } from '../icons.js';
import { inOutChart, table } from '../charts.js';
import { header, card, chartCard, segmented, feed, empty, skeleton, errorBox } from '../ui.js';

const KINDS = [['', 'Tout'], ['in', 'Dépôts'], ['out', 'Retraits'], ['auto', 'Automatique'], ['chest', 'Coffres ajoutés / retirés']];

export function activityPage(root, _params, query) {
  const f = {
    kind: query.get('type') || '',
    player: query.get('joueur') || '',
    item: query.get('objet') || '',
    chest: query.get('coffre') || '',
  };
  let events = [];
  let next = null;
  let alive = true;
  const chips = h('div.chips', { role: 'toolbar', 'aria-label': 'Filtres' });
  const list = h('div', skeleton(1));
  const more = h('button.btn.plain.block', { type: 'button', style: { marginTop: '12px', display: 'none' } }, 'Afficher plus');
  root.append(header('Activité', { sub: 'Tout ce qui entre et sort des coffres.' }), chips, list, more);

  function syncUrl() {
    const p = new URLSearchParams();
    if (f.kind) p.set('type', f.kind);
    if (f.player) p.set('joueur', f.player);
    if (f.item) p.set('objet', f.item);
    if (f.chest) p.set('coffre', f.chest);
    const qs = p.toString();
    history.replaceState(null, '', '#/activite' + (qs ? '?' + qs : ''));
  }

  function drawChips() {
    const out = KINDS.map(([k, l]) => h('button', { type: 'button', class: f.kind === k ? 'chip on' : 'chip', onclick: () => { f.kind = k; syncUrl(); drawChips(); load(); } }, l));
    const removable = (label, key) => h('button.chip.on', { type: 'button', 'aria-label': 'Retirer le filtre ' + label, onclick: () => { f[key] = ''; syncUrl(); drawChips(); load(); } }, label, h('span.x', '✕'));
    if (f.player) out.unshift(removable('Joueur : ' + f.player, 'player'));
    if (f.item) out.unshift(removable(itemName(f.item), 'item'));
    if (f.chest) out.unshift(removable('Ce coffre', 'chest'));
    chips.replaceChildren(...out);
  }

  function path(before) {
    const p = new URLSearchParams({ limit: '40' });
    if (f.kind) p.set('kind', f.kind);
    if (f.player) p.set('player', f.player);
    if (f.item) p.set('item', f.item);
    if (f.chest) p.set('chest', f.chest);
    if (before) p.set('before', before);
    return 'activity?' + p;
  }

  async function load() {
    try {
      await initAssets();
      drawChips();
      const d = await api(path());
      if (!alive) return;
      events = d.events;
      next = d.next;
      draw();
    } catch (e) {
      if (alive) list.replaceChildren(errorBox(e, load));
    }
  }

  more.addEventListener('click', async () => {
    more.disabled = true;
    try {
      const d = await api(path(next));
      events = events.concat(d.events);
      next = d.events.length ? d.next : null;
      draw();
    } catch (e) {
      list.appendChild(errorBox(e));
    } finally {
      more.disabled = false;
    }
  });

  function draw() {
    list.replaceChildren(events.length ? card(null, feed(events, { days: true }), { cls: 'flush' })
      : card(null, empty('🕊️', 'Rien à afficher', 'Aucun mouvement ne correspond à ces filtres.')));
    more.style.display = events.length >= 40 && next ? '' : 'none';
  }

  load();
  return { refresh: load, destroy() { alive = false; } };
}

export function playersPage(root) {
  let days = store.get('playersDays', 30);
  let alive = true;
  const body = h('div', skeleton(2));
  root.append(header('Joueurs', {
    sub: 'Qui dépose et qui prend.',
    actions: segmented([[7, '7 j'], [30, '30 j']], days, (v) => { days = v; store.set('playersDays', v); load(); }, 'Période'),
  }), body);

  async function load() {
    try {
      await initAssets();
      const d = await api('players?days=' + days);
      if (alive) body.replaceChildren(render(d));
    } catch (e) {
      if (alive) body.replaceChildren(errorBox(e, load));
    }
  }

  function render(d) {
    const online = session.me.players || [];
    const cards = [];
    cards.push(card('En ligne maintenant', online.length
      ? h('div.chips', online.map((n) => h('a.chip', { href: '#/activite?joueur=' + encodeURIComponent(n) }, avatar(n, 'pav'), n)))
      : h('p.note', 'Personne n\'est connecté au serveur.'), { cls: 'wide' }));
    cards.push(chartCard('Dépôts et retraits par jour',
      () => inOutChart(d.daily),
      () => table(['Jour', 'Déposés', 'Retirés'], d.daily.map((x) => [`${weekday(x.day)} ${dayShort(x.day)}`, fmt(x.in), fmt(x.out)]).reverse()),
      { cls: 'wide' }));
    cards.push(card('Classement', d.players.length ? h('div.list', d.players.map((p, i) => h('a.row', { href: '#/activite?joueur=' + encodeURIComponent(p.name) },
      h('span.rank', String(i + 1)),
      avatar(p.name),
      h('div.main-col',
        h('div.title', p.name),
        h('div.meta', `${plural(p.moves, 'action', 'actions')} · ${ago(p.last)}`),
        p.topIn.length ? h('div', { style: { display: 'flex', flexWrap: 'wrap', gap: '8px', marginTop: '4px', alignItems: 'center', fontSize: '12.5px', color: 'var(--text-2)' }, title: 'Ce qu\'il dépose le plus' },
          p.topIn.map((t) => h('span', { title: `${itemName(t.id)} : ${fmt(t.n)} déposés`, style: { display: 'inline-flex', alignItems: 'center', gap: '3px' } }, itemIcon(t.id, 's'), compact(t.n)))) : null),
      h('div.end', h('div.v.good', '+' + compact(p.in)), h('div.s.bad', '−' + compact(p.out))))))
      : h('p.note', { style: { padding: '0 16px 12px' } }, 'Personne n\'a touché aux coffres sur cette période.'),
    { cls: 'flush wide' }));
    return h('div.grid.two', cards);
  }

  load();
  return { refresh: load, destroy() { alive = false; } };
}
