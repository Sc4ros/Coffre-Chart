// Coffres (all containers, grouped by zone) and one container's page (its slots, like in game).
import { h, icon, fmt, compact, plural, store, debounce, norm, ago, where, dimName, toast, sheet } from '../util.js';
import { api, session } from '../api.js';
import { itemIcon, itemName, initAssets } from '../icons.js';
import { meter, showTip, hideTip, tipTitle, tipRow } from '../charts.js';
import { header, card, segmented, feed, empty, skeleton, errorBox, itemRow, placeText, distanceBadge, locateButton } from '../ui.js';

const TYPE_NAMES = { chest: 'Coffre', trapped_chest: 'Coffre piégé', barrel: 'Tonneau' };
export function typeName(t) {
  if (!t) return 'Coffre';
  if (TYPE_NAMES[t]) return TYPE_NAMES[t];
  if (t.endsWith('shulker_box')) return 'Boîte de Shulker';
  if (t.includes('copper_chest')) return 'Coffre en cuivre';
  return itemName(t);
}

function chestCard(c) {
  return h('a.card.chest-card', { href: '#/coffre/' + c.id },
    h('div.top',
      h('div.ic-box', itemIcon(c.type || 'chest')),
      h('div', { style: { minWidth: 0, flex: 1 } },
        h('div.nm', c.name, c.pub ? null : h('span.lock', { title: 'Privé' }, ' 🔒')),
        h('div.co', `${c.zone ? c.zone + ' · ' : ''}${placeText(c)}`))),
    meter(c.used, c.size),
    h('div.mini', c.top.length ? c.top.map((t) => h('span', { title: itemName(t.id) }, itemIcon(t.id, 's'), compact(t.n)))
      : h('span', 'Vide'), c.distinct > c.top.length ? h('span', `+${c.distinct - c.top.length}`) : null),
    h('div.foot', `${compact(c.items)} objets · modifié ${ago(c.updated)}${c.lastPlayer ? ' par ' + c.lastPlayer : ''}`, ' ', distanceBadge(c)));
}

// ───────── list ─────────

export function chestsPage(root, _params, query) {
  let sort = store.get('chestSort', 'zone');
  let q = query.get('zone') || '';
  let chests = null;
  let alive = true;
  const head = header('Coffres');
  const sub = h('div.sub', ' ');
  head.querySelector('.t').appendChild(sub);
  const input = h('input', { type: 'search', placeholder: 'Nom ou zone', value: q, 'aria-label': 'Filtrer les coffres', autocomplete: 'off' });
  const body = h('div', skeleton(2));
  root.append(head, h('div.toolbar', h('label.searchbox', icon('search'), input),
    segmented([['zone', 'Par zone'], ['near', 'Proches'], ['recent', 'Récents'], ['full', 'Pleins']], sort, (v) => { sort = v; store.set('chestSort', v); draw(); }, 'Trier')), body);
  input.addEventListener('input', debounce(() => { q = input.value; draw(); }, 120));

  async function load() {
    try {
      await initAssets();
      chests = await api('chests');
      if (alive) draw();
    } catch (e) {
      if (alive) body.replaceChildren(errorBox(e, load));
    }
  }

  function draw() {
    if (!chests) return;
    const zones = new Set(chests.map((c) => c.zone || ''));
    sub.textContent = `${plural(chests.length, 'coffre', 'coffres')} · ${plural([...zones].filter(Boolean).length, 'zone', 'zones')}`;
    if (!chests.length) {
      body.replaceChildren(card(null, empty('📦', 'Aucun coffre suivi', null,
        h('ol.steps', { style: { textAlign: 'left', display: 'inline-flex' } },
          h('li', 'En jeu, tape ', h('code', '/coffres')),
          h('li', 'Clique sur « Ajouter des coffres »'),
          h('li', 'Fais un clic droit sur tes coffres, tonneaux ou shulkers')))));
      return;
    }
    const nq = norm(q);
    let list = nq ? chests.filter((c) => norm(c.name).includes(nq) || norm(c.zone || '').includes(nq)) : chests.slice();
    const me = session.me;
    if (sort === 'recent') list.sort((a, b) => b.updated - a.updated);
    else if (sort === 'full') list.sort((a, b) => b.used / b.size - a.used / a.size);
    else if (sort === 'near') {
      list.sort((a, b) => {
        const wa = me.online ? where(me.pos, a) : null;
        const wb = me.online ? where(me.pos, b) : null;
        return (wa ? wa.d : 1e9) - (wb ? wb.d : 1e9) || a.name.localeCompare(b.name, 'fr');
      });
    } else {
      list.sort((a, b) => (a.zone ? 0 : 1) - (b.zone ? 0 : 1) || (a.zone || '').localeCompare(b.zone || '', 'fr')
        || a.name.localeCompare(b.name, 'fr', { numeric: true }));
    }

    if (!list.length) {
      body.replaceChildren(card(null, empty('🔎', `Aucun coffre ne correspond à « ${q} »`)));
      return;
    }
    const parts = [];
    if (sort === 'near' && !me.online) parts.push(h('p.note', { style: { margin: '0 2px 12px' } }, 'Connecte-toi en jeu pour trier par distance.'));
    if (sort === 'zone') {
      const groups = new Map();
      for (const c of list) {
        const z = c.zone || '';
        if (!groups.has(z)) groups.set(z, []);
        groups.get(z).push(c);
      }
      for (const [z, cs] of groups) {
        parts.push(h('div.zone-h', h('h2', z || 'Sans zone'), h('span', `${plural(cs.length, 'coffre', 'coffres')} · ${compact(cs.reduce((a, c) => a + c.items, 0))} objets`)));
        parts.push(h('div.chests', cs.map(chestCard)));
      }
    } else {
      parts.push(h('div.chests', list.map(chestCard)));
    }
    body.replaceChildren(...parts);
  }

  load();
  return { refresh: load, destroy() { alive = false; } };
}

// ───────── one container ─────────

function slotGrid(c) {
  const bySlot = new Map(c.slots.map((sl) => [sl.s, sl]));
  const cells = [];
  for (let i = 0; i < c.size; i++) {
    const sl = bySlot.get(i);
    if (!sl) { cells.push(h('div.slot.empty', { 'aria-hidden': 'true' })); continue; }
    const label = `${sl.name ? '« ' + sl.name + ' » ' : ''}${itemName(sl.id)} ×${sl.n}`;
    const btn = h('button', { class: ['slot', sl.e ? 'glint' : '', sl.in ? 'has-in' : ''], type: 'button', 'aria-label': label },
      itemIcon(sl.id), sl.n > 1 ? h('span.cnt', String(sl.n)) : null);
    const show = (e) => showTip(e.clientX, e.clientY, tipTitle(sl.name || itemName(sl.id)),
      sl.name ? h('div.tr', itemName(sl.id)) : null,
      tipRow(null, `×${sl.n}`),
      sl.in ? h('div.tr', `contient ${plural(sl.in.reduce((a, x) => a + x.n, 0), 'objet', 'objets')} · touche pour voir`) : null,
      sl.e ? h('div.tr', '✨ Enchanté') : null);
    btn.addEventListener('pointerenter', show);
    btn.addEventListener('pointermove', show);
    btn.addEventListener('pointerleave', hideTip);
    btn.addEventListener('click', () => {
      hideTip();
      if (sl.in) {
        const agg = new Map();
        for (const x of sl.in) agg.set(x.id, (agg.get(x.id) || 0) + x.n);
        sheet(sl.name || itemName(sl.id), (close) => h('div',
          h('div.pop-items', [...agg].sort((a, b) => b[1] - a[1]).map(([id, n]) => h('a', {
            href: '#/objet/' + id, onclick: () => close(), style: { display: 'flex', alignItems: 'center', gap: '10px', color: 'var(--text)' },
          }, itemIcon(id), h('span', { style: { flex: 1 } }, itemName(id)), h('b.num', fmt(n))))),
          h('div.btns', h('button.btn.plain', { type: 'button', onclick: () => close() }, 'Fermer'))));
      } else {
        location.hash = '#/objet/' + sl.id;
      }
    });
    cells.push(btn);
  }
  return h('div.slots', { role: 'grid', 'aria-label': 'Contenu du coffre' }, cells);
}

function editSheet(c, onSaved) {
  sheet('Modifier le coffre', (close) => {
    const name = h('input.input', { value: c.name, maxlength: 40, required: true, autocomplete: 'off' });
    const zone = h('input.input', { value: c.zone || '', maxlength: 32, placeholder: 'Base, Ferme, Mine…', list: 'zones-list', autocomplete: 'off' });
    const zl = h('datalist#zones-list');
    api('chests').then((all) => {
      for (const z of new Set(all.map((x) => x.zone).filter(Boolean))) zl.appendChild(h('option', { value: z }));
    }).catch(() => {});
    const pub = h('input', { type: 'checkbox', role: 'switch' });
    pub.checked = c.pub;
    const save = h('button.btn', { type: 'submit' }, 'Enregistrer');
    const form = h('form',
      h('label.field', h('span', 'Nom'), name),
      h('label.field', h('span', 'Zone'), zone, zl),
      h('div.switch-row', h('div', h('div', { style: { fontWeight: 600 } }, 'Visible par tout le serveur'),
        h('div.note', 'Sinon, seuls toi et les admins le voient sur le site.')), h('label.switch', pub, h('i'))),
      h('div.btns', h('button.btn.plain', { type: 'button', onclick: () => close() }, 'Annuler'), save));
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      save.disabled = true;
      try {
        const updated = await api('chest/' + c.id, { method: 'POST', body: { name: name.value, zone: zone.value, pub: pub.checked } });
        close();
        toast('Coffre enregistré');
        onSaved(updated);
      } catch (err) {
        toast(err.message, true);
        save.disabled = false;
      }
    });
    setTimeout(() => name.focus(), 50);
    return form;
  });
}

export function chestPage(root, [id]) {
  let alive = true;
  const body = h('div', skeleton(2));
  root.append(header('', { back: { label: 'Coffres', href: '#/coffres' } }), body);
  root.querySelector('.head').remove();

  async function load() {
    try {
      await initAssets();
      const c = await api('chest/' + id);
      if (alive) show(c);
    } catch (e) {
      if (alive) body.replaceChildren(errorBox(e, load));
    }
  }

  function show(c) {
    document.title = `${c.name} · Coffres`;
    const head = h('div.head',
      h('div.t',
        h('div.kicker', `${typeName(c.type)}${c.dbl ? ' double' : ''}${c.zone ? ' · ' + c.zone : ''}`),
        h('h1', c.name, c.pub ? null : h('span.lock', { title: 'Privé' }, ' 🔒')),
        h('div.sub', `${c.x} · ${c.y} · ${c.z} — ${dimName(c.dim)}`, ' ', distanceBadge(c))));
    const actions = h('div.actions',
      locateButton([c.id]),
      c.canEdit ? h('button.btn.plain', { type: 'button', onclick: () => editSheet(c, show) }, icon('edit'), 'Modifier') : null);

    const contents = card('Contenu', h('div', slotGrid(c), h('div', { style: { marginTop: '14px', maxWidth: '540px' } }, meter(c.used, c.size))),
      { tools: h('span.pill', `${compact(c.items)} objets`) });
    const totals = card('Total par objet', c.contents.length ? h('div.list', c.contents.map((it) => itemRow(it))) : h('p.note', { style: { padding: '0 16px 12px' } }, 'Ce coffre est vide.'), { cls: 'flush' });
    const info = card('Infos', h('dl.kv',
      h('dt', 'Ajouté par'), h('dd', c.owner || '?'),
      h('dt', 'Ajouté'), h('dd', ago(c.created)),
      h('dt', 'Dernier changement'), h('dd', ago(c.updated)),
      c.lastPlayer ? [h('dt', 'Dernier joueur'), h('dd', c.lastPlayer)] : null,
      h('dt', 'Visibilité'), h('dd', c.pub ? 'Tout le serveur' : 'Privé')));
    const history = card('Historique', c.recent.length ? feed(c.recent, { days: true }) : h('p.note', { style: { padding: '0 16px 12px' } }, 'Rien pour le moment.'),
      { cls: 'flush wide', more: { href: '#/activite?coffre=' + c.id, label: 'Tout voir' } });
    body.replaceChildren(head, actions, h('div.grid.two', h('div.wide', contents), totals, info, history));
  }

  load();
  return { refresh: load, destroy() { alive = false; } };
}
