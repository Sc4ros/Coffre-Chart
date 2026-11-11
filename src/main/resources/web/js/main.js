// App shell: navigation (sidebar on computers, tab bar on phones), routing and live refresh.
import { h, icon, avatar } from './util.js';
import { api, loggedIn, session } from './api.js';
import { initAssets } from './icons.js';
import { hideTip } from './charts.js';
import home from './pages/home.js';
import { inventoryPage, itemPage, searchPage } from './pages/items.js';
import { chestsPage, chestPage } from './pages/chests.js';
import { activityPage, playersPage } from './pages/activity.js';
import { settingsPage, loginPage } from './pages/account.js';
import { projectsPage, projectPage } from './pages/projects.js';

const NAV = [
  ['', 'Accueil', 'home'],
  ['inventaire', 'Inventaire', 'grid'],
  ['chercher', 'Chercher', 'search'],
  ['projets', 'Projets', 'cube'],
  ['coffres', 'Coffres', 'chest'],
  ['activite', 'Activité', 'pulse'],
];

const ROUTES = [
  [/^$/, home],
  [/^inventaire$/, inventoryPage],
  [/^objet\/([a-z0-9_]+)$/, itemPage],
  [/^chercher$/, searchPage],
  [/^projets$/, projectsPage],
  [/^projet\/([a-z0-9]+)$/, projectPage],
  [/^coffres$/, chestsPage],
  [/^coffre\/([a-z0-9]+)$/, chestPage],
  [/^activite$/, activityPage],
  [/^joueurs$/, playersPage],
  [/^reglages$/, settingsPage],
];

const app = document.getElementById('app');
let shell = null;
let pageEl = null;
let current = null;
let rev = 0;

function parseHash() {
  const raw = location.hash.replace(/^#\/?/, '');
  const [path, qs] = raw.split('?');
  return { path: decodeURIComponent(path || '').replace(/\/$/, ''), query: new URLSearchParams(qs || '') };
}

function buildShell() {
  pageEl = h('div.page');
  const side = h('aside.side', { 'aria-label': 'Navigation' },
    h('a', { href: '#/' },
      h('img.logo.only-light', { src: '/img/logo-clair.svg', alt: 'Coffres' }),
      h('img.logo.only-dark', { src: '/img/logo-sombre.svg', alt: 'Coffres' })),
    h('div.srv', session.me ? session.me.server : ''),
    NAV.map(([p, l, i]) => h('a.nav', { href: '#/' + p, dataset: { p } }, icon(i), l)),
    h('a.nav', { href: '#/joueurs', dataset: { p: 'joueurs' } }, icon('users'), 'Joueurs'),
    h('div.grow'),
    h('a.me', { href: '#/reglages', dataset: { p: 'reglages' } }, avatar(session.me ? session.me.name : '?'),
      h('div', h('div', { style: { fontWeight: 600 } }, session.me ? session.me.name : ''),
        h('div', { style: { fontSize: '12.5px', color: 'var(--text-2)' } }, 'Réglages'))));
  const tabbar = h('nav.tabbar', { 'aria-label': 'Navigation' },
    NAV.map(([p, l, i]) => h('a.tab', { href: '#/' + p, dataset: { p } }, icon(i), l)));
  shell = h('div.shell', side, h('main.main', pageEl), tabbar);
  app.replaceChildren(shell);
}

function markNav(path) {
  const top = path.split('/')[0];
  const map = { objet: 'inventaire', coffre: 'coffres', projet: 'projets', joueurs: 'joueurs', reglages: 'reglages' };
  const key = map[top] ?? top;
  for (const a of document.querySelectorAll('[data-p]')) a.classList.toggle('on', a.dataset.p === key || (key === 'joueurs' && a.dataset.p === 'activite' && a.classList.contains('tab')));
}

async function route() {
  hideTip();
  const { path, query } = parseHash();
  if (current && current.destroy) current.destroy();
  current = null;

  if (path === 'connexion' || !loggedIn()) {
    shell = null;
    app.replaceChildren();
    current = loginPage(app, query) || null;
    return;
  }
  if (!session.me) {
    try {
      session.me = await api('me');
      rev = session.me.rev;
    } catch {
      return; // api() already redirected to the login page if needed
    }
  }
  if (!shell) buildShell();
  markNav(path);
  let page = null;
  let params = [];
  for (const [re, fn] of ROUTES) {
    const m = path.match(re);
    if (m) { page = fn; params = m.slice(1); break; }
  }
  pageEl.replaceChildren();
  window.scrollTo(0, 0);
  if (!page) { location.hash = '#/'; return; }
  current = page(pageEl, params, query) || null;
  document.title = (document.querySelector('.head h1') || {}).textContent ? `${document.querySelector('.head h1').textContent} · Coffres` : 'Coffres';
}

/** Re-renders the visible page when something changed in game (checked every few seconds). */
async function poll() {
  if (document.visibilityState !== 'visible' || !loggedIn() || !shell) return;
  try {
    const me = await api('me');
    const changed = me.rev !== rev;
    session.me = me;
    rev = me.rev;
    if (changed && current && current.refresh) current.refresh();
  } catch { /* offline: try again later */ }
}

window.addEventListener('hashchange', route);
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') poll(); });
setInterval(poll, 5000);

if ('serviceWorker' in navigator && window.isSecureContext && location.hostname !== 'localhost') {
  navigator.serviceWorker.register('/sw.js').catch(() => {});
}

export function resetShell() { shell = null; }

initAssets();
route();
