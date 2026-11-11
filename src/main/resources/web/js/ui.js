// Building blocks shared by the pages.
import { h, icon, fmt, ago, avatar, signed, dayTitle, startOfDay, where, dimName, toast } from './util.js';
import { itemIcon, itemName } from './icons.js';
import { locate, session } from './api.js';

export function header(title, { sub, kicker, back, actions } = {}) {
  return h('header',
    back ? h('button.back', { onclick: () => (history.length > 1 ? history.back() : (location.hash = back.href)) }, icon('back'), back.label) : null,
    h('div.head',
      h('div.t', kicker ? h('div.kicker', kicker) : null, h('h1', title), sub ? h('div.sub', sub) : null),
      actions || null));
}

export function card(title, body, { more, cls = '', tools } = {}) {
  return h('section', { class: 'card ' + cls },
    title || more || tools ? h('div.card-h', h('h2', title || ''), tools || null, more ? h('a.more', { href: more.href }, more.label) : null) : null,
    body);
}

export function segmented(options, value, onChange, label) {
  const box = h('div.seg', { role: 'tablist', 'aria-label': label || '' });
  for (const [v, l] of options) {
    const b = h('button', { type: 'button', role: 'tab', class: v === value ? 'on' : '', 'aria-selected': String(v === value) }, l);
    b.addEventListener('click', () => {
      for (const x of box.children) { x.classList.remove('on'); x.setAttribute('aria-selected', 'false'); }
      b.classList.add('on');
      b.setAttribute('aria-selected', 'true');
      onChange(v);
    });
    box.appendChild(b);
  }
  return box;
}

/** A chart card with a "Tableau" switch, so every number is also readable as text. */
export function chartCard(title, chartFn, tableFn, { tools, more, cls = '' } = {}) {
  let showTable = false;
  const body = h('div');
  const btn = h('button.icon-btn', { type: 'button', title: 'Voir en tableau', 'aria-label': 'Voir en tableau' }, icon('table'));
  const draw = () => {
    body.replaceChildren(showTable ? tableFn() : chartFn());
    btn.replaceChildren(icon(showTable ? 'chart' : 'table'));
    btn.title = showTable ? 'Voir le graphique' : 'Voir en tableau';
    btn.setAttribute('aria-label', btn.title);
  };
  btn.addEventListener('click', () => { showTable = !showTable; draw(); });
  draw();
  return card(title, body, { tools: h('div', { style: { display: 'flex', gap: '8px', alignItems: 'center' } }, tools || null, btn), more, cls });
}

export function empty(emoji, title, text, action) {
  return h('div.empty', h('div.ei', { 'aria-hidden': 'true' }, emoji), h('h3', title), text ? h('p', text) : null,
    action ? h('div', { style: { marginTop: '14px' } }, action) : null);
}

export function skeleton(n = 3) {
  return h('div.grid', Array.from({ length: n }, () => h('div.skel')));
}

export function errorBox(e, retry) {
  return h('div.card', empty('⚠️', 'Impossible de charger', e.message,
    retry ? h('button.btn.soft', { onclick: retry }, 'Réessayer') : null));
}

// ───────── events (history) ─────────

function chestLink(c) {
  return c.exists ? h('a', { href: '#/coffre/' + c.id }, '« ' + c.name + ' »') : h('b', '« ' + c.name + ' »');
}

function eventText(ev) {
  const who = ev.player;
  const c = chestLink(ev.chest);
  switch (ev.kind) {
    case 'p':
      if (ev.plus > 0 && ev.minus === 0) return [h('b', who), ' a déposé dans ', c];
      if (ev.minus > 0 && ev.plus === 0) return [h('b', who), ' a pris dans ', c];
      return [h('b', who), ' a rangé ', c];
    case 'a': return ['Mouvement automatique dans ', c, h('span.faint', ' (entonnoirs, golems…)')];
    case 'l': return [h('b', who || '?'), ' a ajouté ', c, ' au suivi'];
    case 'u': return [h('b', who || '?'), ' a retiré ', c, ' du suivi'];
    case 'b':
      if (who === 'Explosion') return ['💥 Explosion : ', c, ' détruit'];
      if (who) return [h('b', who), ' a cassé ', c];
      return [c, ' a disparu'];
    default: return [c];
  }
}

export function eventRow(ev, { showChest = true } = {}) {
  const sys = ev.kind === 'a' || !ev.uuid;
  const who = sys ? h('div.who.sys', icon(ev.kind === 'a' ? 'gearsys' : ev.kind === 'b' ? 'bolt' : 'chest')) : avatar(ev.player, 'who');
  const items = ev.items || [];
  const deltas = items.length ? h('div.deltas', items.map((d) => h('a.delta', { href: '#/objet/' + d.id, title: itemName(d.id) },
    itemIcon(d.id), h('span', { class: 'q ' + (d.d > 0 ? 'p' : 'm') }, signed(d.d)), h('span.nm', itemName(d.id)))),
  ev.more ? h('span.delta', h('span.nm', `+${ev.more} autres`)) : null) : null;
  return h('div.ev',
    who,
    h('div.body',
      h('div.line', showChest ? eventText(ev) : eventText({ ...ev, chest: { ...ev.chest, exists: false, name: ev.chest.name } })),
      h('div.when', ago(ev.t)),
      ev.kind === 'u' || ev.kind === 'l' ? null : deltas));
}

/** Events grouped by day with a title per day. */
export function feed(events, opts) {
  const out = [];
  let day = null;
  for (const ev of events) {
    const d = startOfDay(ev.t);
    if (opts && opts.days && d !== day) {
      day = d;
      out.push(h('div.day-h', dayTitle(ev.t)));
    }
    out.push(eventRow(ev, opts));
  }
  return h('div.feed', out);
}

// ───────── rows ─────────

export function itemRow(it, { end, meta, href } = {}) {
  return h('a.row', { href: href || '#/objet/' + it.id },
    h('div.ic-box', itemIcon(it.id)),
    h('div.main-col', h('div.title', itemName(it.id)), meta ? h('div.meta', meta) : null),
    end || h('div.end', h('div.v', fmt(it.n))),
    icon('chev', 'chev'));
}

export function placeText(c) {
  return `${c.x} · ${c.y} · ${c.z}${c.dim && c.dim !== 'overworld' ? ' · ' + dimName(c.dim) : ''}`;
}

/** "à 23 m au nord-est" when the player is online in the same world. */
export function distanceBadge(c) {
  const me = session.me;
  const w = me && me.online ? where(me.pos, c) : null;
  if (!w) return null;
  if (w.d <= 3) return h('span.dist', icon('pin'), 'juste ici');
  return h('span.dist', icon('pin'), `${fmt(w.d)} m · ${w.dir}${Math.abs(w.dy) > 3 ? (w.dy > 0 ? ` · ↑${w.dy}` : ` · ↓${-w.dy}`) : ''}`);
}

export function locateButton(ids, label = 'Localiser en jeu', cls = 'btn') {
  const b = h('button', { class: cls, type: 'button', title: label || 'Localiser en jeu', 'aria-label': label || 'Localiser en jeu' }, icon('pin'), label);
  b.addEventListener('click', async (e) => {
    e.preventDefault();
    e.stopPropagation();
    b.disabled = true;
    try {
      const r = await locate(ids);
      toast(r.message || 'Le coffre brille en jeu.');
    } catch (err) {
      toast(err.message, true);
    } finally {
      setTimeout(() => { b.disabled = false; }, 1500);
    }
  });
  return b;
}
