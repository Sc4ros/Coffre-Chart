// Accueil: totals, the wheel of every item, trends, players, zones and the latest activity.
import { h, icon, fmt, compact, pct, plural, store, avatar, ago, time, weekday, dayShort } from '../util.js';
import { api, session, currentView } from '../api.js';
import { itemIcon, itemName, initAssets } from '../icons.js';
import { wheel, timeChart, inOutChart, hbars, meter, table, SERIES, OTHER } from '../charts.js';
import { header, card, chartCard, segmented, feed, empty, skeleton, errorBox, viewEmpty } from '../ui.js';
import { projectsHomeCard } from './projects.js';

/** Keeps each item on the same wheel color from one visit to the next. */
function wheelColors(ids) {
  const saved = store.get('wheelColors', {});
  const out = {};
  const used = new Set();
  for (const id of ids) {
    if (saved[id] != null && !used.has(saved[id]) && saved[id] < SERIES.length) { out[id] = saved[id]; used.add(saved[id]); }
  }
  let next = 0;
  for (const id of ids) {
    if (out[id] != null) continue;
    while (used.has(next)) next++;
    out[id] = next;
    used.add(next);
  }
  store.set('wheelColors', out);
  return out;
}

export function statusActions() {
  const me = session.me;
  return h('div', { style: { display: 'flex', alignItems: 'center', gap: '10px' } },
    h('span.pill', h('span', { class: me.online ? 'dot on' : 'dot' }), me.online ? 'En jeu' : 'Hors jeu'),
    h('a.avatar-btn', { href: '#/reglages', 'aria-label': 'Réglages', title: 'Réglages' }, avatar(me.name)));
}

function tile(label, value, extra, detail) {
  return h('div.tile', h('div.l', label), h('div.v', value), extra || null, detail ? h('div.d', detail) : null);
}

function trendLabel(t, step, span) {
  if (span <= 2 * 86_400_000) return time(t);
  return `${weekday(t)} ${dayShort(t)}${step < 86_400_000 ? ' ' + time(t) : ''}`;
}

export default function home(root) {
  let days = store.get('homeDays', 7);
  let alive = true;
  const body = h('div', skeleton(3));
  const titles = { mine: 'Tes coffres', common: 'Coffres communs', all: 'Tous les coffres' };
  const head = header(titles[currentView()], {
    kicker: `Salut ${session.me.name}`,
    actions: statusActions(),
    view: (v) => { head.querySelector('h1').textContent = titles[v]; body.replaceChildren(skeleton(3)); load(); },
  });
  root.append(
    head,
    h('a.searchbox', { href: '#/chercher' }, icon('search'), h('span', 'Chercher un objet ou un coffre')),
    body);

  async function load() {
    try {
      await initAssets();
      const d = await api('overview?days=' + days);
      if (alive) body.replaceChildren(render(d));
    } catch (e) {
      if (alive) body.replaceChildren(errorBox(e, load));
    }
  }

  function render(d) {
    const k = d.kpi;
    if (k.chests === 0) {
      return h('div.grid.two', card(null, viewEmpty(currentView()), { cls: 'wide' }), projectsHomeCard(d.projects));
    }

    const hero = card(null, h('div.hero',
      h('div', h('div.big', fmt(k.items)), h('div.big-sub', `objets dans ${plural(k.chests, 'coffre', 'coffres')}`)),
      h('div.tiles',
        tile("Types d'objets", fmt(k.distinct)),
        tile('Remplissage', pct(k.slotsUsed / (k.slotsTotal || 1)), h('div.meter-wrap', meter(k.slotsUsed, k.slotsTotal, { label: false })),
          `${fmt(k.slotsUsed)} / ${fmt(k.slotsTotal)} cases`),
        tile('Dernières 24 h', h('span', h('span.good', '+' + compact(k.in24)), ' ', h('span.bad', '−' + compact(k.out24))), null, 'déposés · retirés'),
        tile('Joueurs actifs', fmt(k.players7), null, '7 derniers jours'))), { cls: 'wide' });

    // The wheel
    const ids = d.wheel.filter((w) => w.id !== '_other').map((w) => w.id);
    const colors = wheelColors(ids);
    const wheelData = d.wheel.map((w) => ({
      id: w.id,
      value: w.n,
      label: w.id === '_other' ? `Autres (${w.kinds} types)` : itemName(w.id),
      color: w.id === '_other' ? OTHER : SERIES[colors[w.id]],
      href: w.id === '_other' ? '#/inventaire' : '#/objet/' + w.id,
      icon: w.id === '_other' ? null : itemIcon(w.id, 's'),
    }));
    const wheelCard = card('La roue', d.wheel.length ? wheel(wheelData) : empty('🫙', 'Coffres vides', 'Les objets apparaîtront dès qu\'ils seront rangés.'),
      { cls: 'wide', more: { href: '#/inventaire', label: 'Tout l\'inventaire' } });

    // Trend
    const tr = d.trend;
    const span = tr.step * (tr.points.length - 1);
    const trend = chartCard('Évolution du stock',
      () => timeChart({ points: tr.points, from: tr.from, step: tr.step }),
      () => table(['Moment', 'Objets'], tr.points.map((v, i) => [trendLabel(tr.from + i * tr.step, tr.step, span), fmt(v)]).reverse()),
      { tools: segmented([[1, '24 h'], [7, '7 j'], [30, '30 j']], days, (v) => { days = v; store.set('homeDays', v); load(); }, 'Période') });

    const io = chartCard('Dépôts et retraits',
      () => inOutChart(d.daily),
      () => table(['Jour', 'Déposés', 'Retirés'], d.daily.map((x) => [`${weekday(x.day)} ${dayShort(x.day)}`, fmt(x.in), fmt(x.out)]).reverse()),
      { more: { href: '#/joueurs', label: 'Par joueur' } });

    const players = card('Joueurs actifs', d.players.length
      ? h('div.list', d.players.map((p) => h('a.row.plain', { href: '#/activite?joueur=' + encodeURIComponent(p.name), style: { padding: '8px 0' } },
        avatar(p.name),
        h('div.main-col', h('div.title', p.name), h('div.meta', `${plural(p.moves, 'action', 'actions')} · ${ago(p.last)}`)),
        h('div.end', h('div.io', h('span.good', '+' + compact(p.in)), h('span.bad', '−' + compact(p.out)))))))
      : h('p.note', `Personne n'a touché aux coffres ces ${days === 1 ? 'dernières 24 h' : days + ' derniers jours'}.`),
    { more: { href: '#/joueurs', label: 'Classement' } });

    const named = d.zones.filter((z) => z.zone);
    const zones = named.length ? card('Zones', hbars(d.zones.map((z) => ({
      label: z.zone || 'Sans zone',
      value: z.items,
      valueLabel: `${compact(z.items)} · ${plural(z.chests, 'coffre', 'coffres')}`,
      href: '#/coffres?zone=' + encodeURIComponent(z.zone || ''),
    }))), { more: { href: '#/coffres', label: 'Coffres' } })
      : card('Zones', h('p.note', 'Range tes coffres par zone (Base, Ferme, Mine…) avec /coffres zone <nom> en jeu ou depuis la page d\'un coffre.'));

    const recent = card('Activité récente', d.recent.length ? feed(d.recent) : h('p.note', { style: { padding: '0 16px 12px' } }, 'Rien pour le moment.'),
      { cls: 'flush wide', more: { href: '#/activite', label: 'Tout voir' } });

    return h('div.grid.two', hero, wheelCard, projectsHomeCard(d.projects), trend, io, players, zones, recent);
  }

  load();
  return { refresh: load, destroy() { alive = false; } };
}
