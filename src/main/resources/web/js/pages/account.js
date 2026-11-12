// Réglages (theme, install on the home screen, logout) and the login screen.
import { h, icon, avatar, toast } from '../util.js';
import { api, session, setToken, clearToken } from '../api.js';
import { header, card, segmented } from '../ui.js';

let installPrompt = null;
window.addEventListener('beforeinstallprompt', (e) => { e.preventDefault(); installPrompt = e; });

const isIOS = /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
const isStandalone = () => window.matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;

export function settingsPage(root) {
  const me = session.me;
  const profile = card(null, h('div', { style: { display: 'flex', alignItems: 'center', gap: '14px' } },
    avatar(me.name, 'pav l'),
    h('div', { style: { minWidth: 0 } },
      h('div', { style: { fontSize: '20px', fontWeight: 700 } }, me.name, me.admin ? h('span.badge', { style: { marginLeft: '8px', verticalAlign: 'middle' } }, 'Admin') : null),
      h('div.note', h('span', { class: me.online ? 'dot on' : 'dot' }), ' ', me.online ? 'Connecté au serveur' : 'Pas connecté au serveur en ce moment'),
      h('div.note', `${me.server} · Minecraft ${me.version}`))));

  const theme = card('Apparence', h('div', { style: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: '12px', flexWrap: 'wrap' } },
    h('span', 'Thème'),
    segmented([['auto', 'Auto'], ['light', 'Clair'], ['dark', 'Sombre']], window.coffresTheme ? window.coffresTheme.get() : 'auto',
      (v) => window.coffresTheme && window.coffresTheme.set(v), 'Thème')));

  let installBody;
  if (isStandalone()) {
    installBody = h('p', '✅ Coffres est déjà installé sur cet appareil.');
  } else if (isIOS) {
    installBody = h('ol.steps',
      h('li', 'Ouvre ce site dans ', h('b', 'Safari')),
      h('li', 'Touche le bouton ', h('b', 'Partager '), icon('share', 'ic s'), ' en bas de l\'écran'),
      h('li', 'Choisis ', h('b', '« Sur l\'écran d\'accueil »'), ' puis ', h('b', 'Ajouter')),
      h('li', 'L\'icône Coffres apparaît avec tes apps. Tu devras te reconnecter une fois dedans.'));
  } else {
    const btn = installPrompt ? h('button.btn', {
      type: 'button',
      onclick: async () => { installPrompt.prompt(); await installPrompt.userChoice; installPrompt = null; },
    }, 'Installer Coffres') : null;
    installBody = h('div', btn,
      h('ol.steps', { style: { marginTop: btn ? '12px' : 0 } },
        h('li', h('b', 'Android (Chrome) : '), 'menu ⋮ → « Installer l\'application » ou « Ajouter à l\'écran d\'accueil »'),
        h('li', h('b', 'PC (Chrome, Edge) : '), 'icône d\'installation à droite de la barre d\'adresse'),
        h('li', h('b', 'iPhone : '), 'Safari → Partager → « Sur l\'écran d\'accueil »')));
  }
  const install = card('Installer l\'appli', installBody);

  const cmds = [
    ['/coffres', 'ouvre le menu en jeu'],
    ['/coffres ajouter', 'mode ajout : clic droit sur tes coffres'],
    ['/coffres web', 'un lien qui te connecte au site'],
    ['/coffres chercher diamant', 'trouve un objet et fait briller le coffre'],
    ['/coffres projet', "tes projets de construction et les blocs qu'il manque"],
    ['/coffres projet lier …', 'clic droit sur des coffres pour les lier à un projet'],
    ['/coffres nom …', 'renomme le coffre que tu regardes'],
    ['/coffres zone …', 'range-le dans une zone'],
    ['/coffres prive', 'remettre en privé un coffre commun (« tout » pour tous tes coffres)'],
  ];
  const game = card('En jeu', h('div', { style: { display: 'flex', flexDirection: 'column', gap: '10px' } },
    cmds.map(([c, d]) => h('div', h('code', c), h('div.note', { style: { marginTop: '3px' } }, d)))));

  const out = h('button.btn.danger.block', {
    type: 'button',
    onclick: async () => {
      try { await api('auth/logout', { method: 'POST' }); } catch { /* already logged out */ }
      clearToken();
      location.hash = '#/connexion';
    },
  }, icon('out'), 'Se déconnecter');

  root.append(header('Réglages'), h('div.grid.two', profile, theme, install, game, h('div.wide', out)));
}

export function loginPage(app, query) {
  let step = 'name';
  let sentTo = '';
  const err = h('div.err', { role: 'alert' });
  const form = h('form', { novalidate: true });
  const srv = h('div.srv', ' ');
  app.replaceChildren(h('div.login', h('div.login-card',
    h('img.appicon', { src: '/img/icone-app.png', alt: '' }),
    h('h1', 'Coffres'),
    h('p.tag', 'Tout ce qu\'il y a dans tes coffres, partout.'),
    form,
    srv)));

  fetch('/api/info').then((r) => r.json()).then((i) => { srv.textContent = `${i.server} · Minecraft ${i.version}`; }).catch(() => {});

  async function verify(code) {
    err.textContent = '';
    try {
      const r = await api('auth/verify', { method: 'POST', body: { code } });
      setToken(r.token);
      session.me = null;
      toast(`Bienvenue ${r.name} !`);
      location.hash = '#/';
    } catch (e) {
      err.textContent = e.message;
      step = 'code';
      draw();
    }
  }

  function draw() {
    if (step === 'name') {
      const name = h('input.input', { name: 'username', placeholder: 'Pseudo Minecraft', autocomplete: 'username', autocapitalize: 'off', autocorrect: 'off', spellcheck: 'false', required: true, maxlength: 17 });
      const btn = h('button.btn.block', { type: 'submit' }, 'Recevoir mon code');
      form.replaceChildren(
        h('label.field', h('span', 'Ton pseudo Minecraft'), name),
        err, btn,
        h('p.help', 'Tu dois être connecté au serveur : ton code s\'affichera dans le chat du jeu.'),
        h('p.help', 'Tu as déjà un code ? ', h('a', { href: '#', onclick: (e) => { e.preventDefault(); step = 'code'; err.textContent = ''; draw(); } }, 'Le saisir')),
        h('p.help', 'Astuce : en jeu, tape ', h('code', '/coffres web'), ' pour recevoir un lien qui te connecte directement.'));
      form.onsubmit = async (e) => {
        e.preventDefault();
        if (!name.value.trim()) { err.textContent = 'Écris ton pseudo.'; return; }
        btn.disabled = true;
        err.textContent = '';
        try {
          const r = await api('auth/request', { method: 'POST', body: { name: name.value.trim() } });
          sentTo = r.name;
          step = 'code';
          draw();
        } catch (ex) {
          err.textContent = ex.message;
          btn.disabled = false;
        }
      };
      setTimeout(() => name.focus(), 50);
    } else {
      const code = h('input.input.code-input', { inputmode: 'numeric', autocomplete: 'one-time-code', placeholder: '000000', maxlength: 7, required: true, 'aria-label': 'Code à 6 chiffres' });
      const btn = h('button.btn.block', { type: 'submit' }, 'Se connecter');
      form.replaceChildren(
        h('label.field', h('span', sentTo ? `Code envoyé dans le chat du jeu à ${sentTo}` : 'Code à 6 chiffres (dans le chat du jeu)'), code),
        err, btn,
        h('p.help', h('a', { href: '#', onclick: (e) => { e.preventDefault(); step = 'name'; err.textContent = ''; draw(); } }, '← Changer de pseudo / renvoyer un code')));
      code.addEventListener('input', () => {
        const digits = code.value.replace(/\D/g, '');
        if (digits.length === 6) verify(digits);
      });
      form.onsubmit = (e) => { e.preventDefault(); verify(code.value); };
      setTimeout(() => code.focus(), 50);
    }
  }

  const preset = query.get('code');
  draw();
  if (preset) {
    history.replaceState(null, '', '#/connexion');
    verify(preset);
  }
  return null;
}
