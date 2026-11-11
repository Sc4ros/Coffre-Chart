// Small helpers shared by every page. All DOM is built with h(): text is always inserted
// as text nodes, so player-chosen names can never inject HTML.

const SVG_NS = 'http://www.w3.org/2000/svg';

function build(el, attrs, children) {
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (v == null || v === false) continue;
      if (k === 'class') el.setAttribute('class', Array.isArray(v) ? v.filter(Boolean).join(' ') : v);
      else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
      else if (k === 'dataset') Object.assign(el.dataset, v);
      else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
      else if (k === 'value' && 'value' in el) el.value = v;
      else el.setAttribute(k, v === true ? '' : v);
    }
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const c of children) {
    if (c == null || c === false || c === true) continue;
    if (Array.isArray(c)) append(el, c);
    else if (c instanceof Node) el.appendChild(c);
    else el.appendChild(document.createTextNode(String(c)));
  }
}

/** h('div.card#x', {attrs}, ...children) */
export function h(sel, attrs, ...children) {
  if (attrs != null && (typeof attrs !== 'object' || attrs instanceof Node || Array.isArray(attrs))) {
    children.unshift(attrs);
    attrs = null;
  }
  const m = sel.match(/^([a-z0-9-]+)?((?:[.#][\w-]+)*)$/i);
  const el = document.createElement(m && m[1] ? m[1] : 'div');
  if (m && m[2]) {
    for (const part of m[2].match(/[.#][\w-]+/g)) {
      if (part[0] === '.') el.classList.add(part.slice(1));
      else el.id = part.slice(1);
    }
  }
  return build(el, attrs, children);
}

export function s(tag, attrs, ...children) {
  return build(document.createElementNS(SVG_NS, tag), attrs, children);
}

/** Inline icon set (stroke icons in the iOS spirit). */
const PATHS = {
  home: 'M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6h-6v6H4a1 1 0 0 1-1-1z',
  grid: 'M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z',
  search: 'M10.5 18a7.5 7.5 0 1 1 0-15 7.5 7.5 0 0 1 0 15zM16 16l5 5',
  chest: 'M3 9V7a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v2M3 9h18v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM3 13h18M11 11h2v4h-2z',
  pulse: 'M3 12h4l3-7 4 14 3-7h4',
  users: 'M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM2 21v-1a6 6 0 0 1 6-6h2a6 6 0 0 1 6 6v1M16 3.5a4 4 0 0 1 0 7.5M22 21v-1a6 6 0 0 0-4-5.6',
  gear: 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z',
  back: 'M15 18l-6-6 6-6',
  chev: 'M9 6l6 6-6 6',
  pin: 'M12 22s7-6.2 7-12a7 7 0 1 0-14 0c0 5.8 7 12 7 12zM12 12.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5z',
  edit: 'M4 20h4L19 9l-4-4L4 16zM14 6l4 4',
  list: 'M8 6h13M8 12h13M8 18h13M3.5 6h.01M3.5 12h.01M3.5 18h.01',
  lock: 'M6 11h12v10H6zM8.5 11V7.5a3.5 3.5 0 0 1 7 0V11',
  gearsys: 'M4 7h10M18 7h2M4 17h4M12 17h8M16 4v6M10 14v6',
  arrow: 'M12 19V5M5 12l7-7 7 7',
  out: 'M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4M10 17l5-5-5-5M15 12H3',
  share: 'M12 15V3M8 7l4-4 4 4M5 12v7a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-7',
  table: 'M3 5h18v14H3zM3 10h18M3 15h18M9 5v14',
  chart: 'M4 20V10M10 20V4M16 20v-7M22 20H2',
  bolt: 'M13 2 4 14h7l-1 8 9-12h-7z',
  cube: 'M12 2.5 3.5 7v10l8.5 4.5 8.5-4.5V7zM3.5 7 12 11.5 20.5 7M12 11.5v10',
  link: 'M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1',
  upload: 'M12 16V4M7 9l5-5 5 5M4 16v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3',
  trash: 'M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3',
  copy: 'M9 9h11v11H9zM5 15H4V4h11v1',
  check: 'M5 12.5l4.5 4.5L19 7.5',
  plus: 'M12 5v14M5 12h14',
};

export function icon(name, cls) {
  const p = PATHS[name] || PATHS.grid;
  return s('svg', { viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': '1.9', 'stroke-linecap': 'round', 'stroke-linejoin': 'round', class: cls, 'aria-hidden': 'true' },
    s('path', { d: p }));
}

// ───────── numbers & time ─────────

const NF = new Intl.NumberFormat('fr-FR');
const CF = new Intl.NumberFormat('fr-FR', { notation: 'compact', maximumFractionDigits: 1 });
const PF = new Intl.NumberFormat('fr-FR', { style: 'percent', maximumFractionDigits: 0 });

export const fmt = (n) => NF.format(n ?? 0);
export const compact = (n) => (Math.abs(n) < 10000 ? NF.format(n) : CF.format(n));
export const pct = (x) => PF.format(isFinite(x) ? x : 0);
export const signed = (n) => (n > 0 ? '+' : n < 0 ? '−' : '') + fmt(Math.abs(n));

const TIME = new Intl.DateTimeFormat('fr-FR', { hour: '2-digit', minute: '2-digit' });
const DATE = new Intl.DateTimeFormat('fr-FR', { weekday: 'short', day: 'numeric', month: 'short' });
const DAY_LONG = new Intl.DateTimeFormat('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' });
const DAY_SHORT = new Intl.DateTimeFormat('fr-FR', { day: 'numeric', month: 'short' });
const WD = new Intl.DateTimeFormat('fr-FR', { weekday: 'short' });

function startOfDay(t) { const d = new Date(t); d.setHours(0, 0, 0, 0); return d.getTime(); }

export function ago(t) {
  const diff = Date.now() - t;
  if (diff < 45_000) return "à l'instant";
  if (diff < 3_600_000) return `il y a ${Math.round(diff / 60_000)} min`;
  const today = startOfDay(Date.now());
  if (t >= today) return `il y a ${Math.round(diff / 3_600_000)} h`;
  if (t >= today - 86_400_000) return `hier à ${TIME.format(t)}`;
  return `${DATE.format(t)} à ${TIME.format(t)}`;
}
export const time = (t) => TIME.format(t);
export function dayTitle(t) {
  const today = startOfDay(Date.now());
  const d = startOfDay(t);
  if (d === today) return "Aujourd'hui";
  if (d === today - 86_400_000) return 'Hier';
  const s = DAY_LONG.format(t);
  return s.charAt(0).toUpperCase() + s.slice(1);
}
export const dayShort = (t) => DAY_SHORT.format(t);
export const weekday = (t) => WD.format(t).replace('.', '');
export { startOfDay };

/** "20 stacks + 4" */
export function stacks(n, size = 64) {
  if (!size || size <= 1 || n < size) return null;
  const st = Math.floor(n / size);
  const rest = n % size;
  return `${fmt(st)} stack${st > 1 ? 's' : ''}${rest ? ` + ${rest}` : ''}`;
}

export function dimName(dim) {
  return { overworld: 'Monde normal', nether: 'Nether', end: 'End' }[dim] || 'Autre monde';
}

const DIRS = ['nord', 'nord-est', 'est', 'sud-est', 'sud', 'sud-ouest', 'ouest', 'nord-ouest'];
const SHORT = ['N', 'NE', 'E', 'SE', 'S', 'SO', 'O', 'NO'];
/** Distance and compass direction from a player position to a block. */
export function where(pos, c) {
  if (!pos || pos.world !== c.world) return null;
  const dx = c.x + 0.5 - pos.x;
  const dz = c.z + 0.5 - pos.z;
  const dy = c.y - Math.floor(pos.y);
  const d = Math.sqrt(dx * dx + dz * dz + dy * dy);
  const a = ((Math.atan2(dx, -dz) * 180 / Math.PI) + 360) % 360;
  const i = Math.round(a / 45) % 8;
  return { d: Math.round(d), dir: DIRS[i], short: SHORT[i], dy };
}

export function norm(str) {
  return String(str || '').toLowerCase().normalize('NFD').replace(/\p{M}/gu, '').trim();
}

// ───────── misc ─────────

export const store = {
  get(k, def) { try { const v = localStorage.getItem('coffres.' + k); return v == null ? def : JSON.parse(v); } catch { return def; } },
  set(k, v) { try { localStorage.setItem('coffres.' + k, JSON.stringify(v)); } catch { /* private mode */ } },
  del(k) { try { localStorage.removeItem('coffres.' + k); } catch { /* ignore */ } },
};

let toastEl;
let toastTimer;
export function toast(msg, err) {
  if (!toastEl) { toastEl = h('div.toast', { role: 'status' }); document.body.appendChild(toastEl); }
  toastEl.textContent = msg;
  toastEl.classList.toggle('err', !!err);
  requestAnimationFrame(() => toastEl.classList.add('on'));
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toastEl.classList.remove('on'), 3200);
}

export function debounce(fn, ms) {
  let t;
  return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
}

/** Stable color for a player name (avatar). */
export function playerColor(name) {
  let x = 0;
  for (const ch of String(name)) x = (x * 31 + ch.charCodeAt(0)) >>> 0;
  const hues = ['#0088FF', '#34C759', '#FF8D28', '#CB30E0', '#FF383C', '#00A3A8', '#6155F5', '#D9A300'];
  return hues[x % hues.length];
}

export function avatar(name, cls = 'pav') {
  const n = String(name || '?');
  return h('div', { class: cls, style: { background: playerColor(n) }, 'aria-hidden': 'true' }, n.slice(0, 1).toUpperCase());
}

export function plural(n, one, many) { return `${fmt(n)} ${n > 1 ? many : one}`; }

export function sheet(title, body, { onClose } = {}) {
  const bg = h('div.sheet-bg', { role: 'dialog', 'aria-modal': 'true', 'aria-label': title });
  const close = () => { bg.remove(); document.removeEventListener('keydown', esc); onClose && onClose(); };
  const esc = (e) => { if (e.key === 'Escape') close(); };
  bg.addEventListener('click', (e) => { if (e.target === bg) close(); });
  document.addEventListener('keydown', esc);
  bg.appendChild(h('div.sheet', h('h2', title), typeof body === 'function' ? body(close) : body));
  document.body.appendChild(bg);
  return close;
}
