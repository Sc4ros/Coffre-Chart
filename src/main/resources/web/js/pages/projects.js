// Projets: send a building plan (.litematic, .schem, .schematic, .nbt), see every block it needs,
// link the chests where the materials are stored and follow the progress.
import { h, icon, fmt, compact, plural, store, debounce, norm, ago, toast, sheet, stacks } from '../util.js';
import { api, session } from '../api.js';
import { itemIcon, itemName, initAssets } from '../icons.js';
import { wheel, ring, progressBar, table, SERIES, OTHER } from '../charts.js';
import { header, card, chartCard, segmented, empty, skeleton, errorBox, placeText, locateButton } from '../ui.js';
import { typeName } from './chests.js';

const FORMATS = { litematic: 'Litematica', schem: 'WorldEdit', schematic: 'MCEdit', nbt: 'Structure' };
const ACCEPT = '.litematic,.schem,.schematic,.nbt';
const MB = 1024 * 1024;

function sizeText(p) {
  const [x, y, z] = p.size || [0, 0, 0];
  return x || y || z ? `${x} × ${y} × ${z}` : '';
}

function ownerText(p) {
  if (p.shared) return `Projet commun · par ${p.mine ? 'toi' : p.owner}`;
  return p.mine ? 'Projet solo · rien que toi' : `Projet solo de ${p.owner}`;
}

function fileSize(n) {
  return n >= MB ? `${(n / MB).toLocaleString('fr-FR', { maximumFractionDigits: 1 })} Mo` : `${Math.max(1, Math.round(n / 1024))} Ko`;
}

/** How many shulker boxes these items fill (27 stacks each). */
function shulkers(lines, key) {
  let slots = 0;
  for (const l of lines) slots += Math.ceil(key(l) / (l.st || 64));
  return slots / 27;
}

function shulkerText(n) {
  if (n < 0.5) return null;
  return `≈ ${n.toLocaleString('fr-FR', { maximumFractionDigits: 1 })} shulker${n >= 2 ? 's' : ''}`;
}

function missingOf(l) { return Math.max(0, l.need - l.have); }

// ───────── new project / replace the file ─────────

export function uploadSheet({ project, onDone } = {}) {
  const maxMb = (session.me && session.me.maxFileMb) || 20;
  sheet(project ? 'Remplacer le fichier' : 'Nouveau projet', (close) => {
    let file = null;
    let shared = true;
    const input = h('input.sr', { type: 'file', accept: ACCEPT });
    const dzText = h('div.dz-t', 'Choisis ou glisse ton fichier');
    const dzSub = h('div.dz-s', `.litematic · .schem · .schematic · .nbt — ${maxMb} Mo max`);
    const dz = h('label.dropzone', input, icon('upload'), dzText, dzSub);
    const name = h('input.input', { maxlength: 40, placeholder: 'Le nom écrit dans le fichier', autocomplete: 'off' });
    const err = h('p.err', { hidden: true });
    const pick = (f) => {
      if (!f) return;
      if (f.size > maxMb * MB) {
        err.textContent = `Ce fichier fait ${fileSize(f.size)} : le maximum est ${maxMb} Mo.`;
        err.hidden = false;
        return;
      }
      file = f;
      err.hidden = true;
      dz.classList.add('has');
      dzText.textContent = f.name;
      dzSub.textContent = `${fileSize(f.size)} · touche pour changer`;
      if (!project && !name.value) name.placeholder = f.name.replace(/\.[^.]+$/, '').replace(/_/g, ' ');
    };
    input.addEventListener('change', () => pick(input.files[0]));
    for (const ev of ['dragenter', 'dragover']) dz.addEventListener(ev, (e) => { e.preventDefault(); dz.classList.add('over'); });
    for (const ev of ['dragleave', 'drop']) dz.addEventListener(ev, (e) => { e.preventDefault(); dz.classList.remove('over'); });
    dz.addEventListener('drop', (e) => pick(e.dataTransfer && e.dataTransfer.files[0]));

    const hint = h('div.note');
    const setHint = () => {
      hint.textContent = shared
        ? 'Tout le serveur le voit et peut y lier ses coffres. Seuls toi et les admins pouvez le modifier.'
        : 'Seuls toi et les admins le voient.';
    };
    setHint();
    const save = h('button.btn', { type: 'submit' }, project ? 'Remplacer' : 'Créer le projet');
    const form = h('form',
      dz,
      project ? h('p.note', { style: { margin: '-4px 0 14px' } }, 'Les coffres liés restent liés. Seule la liste des blocs change.') : [
        h('label.field', h('span', 'Nom du projet (facultatif)'), name),
        h('div.field', h('span', 'Qui le voit'), segmented([[true, 'Commun'], [false, 'Solo']], shared, (v) => { shared = v; setHint(); }, 'Qui le voit'),
          h('div', { style: { marginTop: '8px' } }, hint)),
      ],
      err,
      h('div.btns', h('button.btn.plain', { type: 'button', onclick: () => close() }, 'Annuler'), save));
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      if (!file) {
        err.textContent = 'Choisis d\'abord un fichier.';
        err.hidden = false;
        return;
      }
      save.disabled = true;
      save.textContent = 'Lecture du plan…';
      const qs = new URLSearchParams({ file: file.name });
      if (!project) {
        qs.set('name', name.value.trim());
        qs.set('shared', shared ? '1' : '0');
      }
      try {
        const p = await api(project ? `project/${project.id}/file?${qs}` : `projects/upload?${qs}`, { method: 'POST', body: file });
        close();
        toast(project ? 'Fichier remplacé' : `Projet créé : ${plural(p.need, 'bloc', 'blocs')} à réunir`);
        if (onDone) onDone(p);
        else location.hash = '#/projet/' + p.id;
      } catch (ex) {
        err.textContent = ex.message;
        err.hidden = false;
        save.disabled = false;
        save.textContent = project ? 'Remplacer' : 'Créer le projet';
      }
    });
    return form;
  });
}

// ───────── list ─────────

function projectCard(p) {
  return h('a.card.project-card', { href: '#/projet/' + p.id },
    h('div.top',
      ring(p.ratio, { size: 66, stroke: 11 }),
      h('div', { style: { minWidth: 0, flex: 1 } },
        h('div.nm', p.name),
        h('div.co', ownerText(p)),
        h('div.co', `${fmt(p.have)} / ${fmt(p.need)} blocs · ${p.chests ? plural(p.chests, 'coffre lié', 'coffres liés') : 'aucun coffre lié'}`))),
    p.top.length
      ? h('div.mini', h('span.lbl', 'Manque'), p.top.map((t) => h('span', { title: itemName(t.id) }, itemIcon(t.id, 's'), compact(t.n))))
      : h('div.mini.done', icon('check'), 'Tous les blocs sont dans les coffres'),
    h('div.foot', [p.file, sizeText(p), `modifié ${ago(p.updated)}`].filter(Boolean).join(' · ')));
}

export function newProjectButton(cls = 'btn') {
  return h('button', { class: cls, type: 'button', onclick: () => uploadSheet() }, icon('plus'), 'Nouveau projet');
}

function emptyProjects() {
  return empty('🏗️', 'Aucun projet pour l\'instant',
    'Envoie le plan de ta construction : Coffres compte tous les blocs qu\'il faut. Lie ensuite les coffres où tu ranges les matériaux pour voir ce qu\'il manque.',
    h('div', newProjectButton(),
      h('p.note', { style: { marginTop: '12px' } }, 'Fichiers acceptés : .litematic (Litematica), .schem (WorldEdit), .schematic (ancien format), .nbt (bloc de structure).')));
}

export function projectsPage(root) {
  let alive = true;
  let list = null;
  const head = header('Projets', { sub: 'Les blocs qu\'il faut pour tes constructions, et ceux déjà rangés', actions: newProjectButton() });
  const body = h('div', skeleton(2));
  root.append(head, body);

  async function load() {
    try {
      await initAssets();
      list = await api('projects');
      if (alive) draw();
    } catch (e) {
      if (alive) body.replaceChildren(errorBox(e, load));
    }
  }

  function draw() {
    if (!list.length) {
      body.replaceChildren(card(null, emptyProjects()));
      return;
    }
    const running = list.filter((p) => p.percent < 100);
    const done = list.filter((p) => p.percent >= 100);
    const parts = [];
    if (running.length) parts.push(h('div.projects', running.map(projectCard)));
    if (done.length) {
      parts.push(h('div.zone-h', h('h2', 'Prêts à construire'), h('span', plural(done.length, 'projet', 'projets'))));
      parts.push(h('div.projects', done.map(projectCard)));
    }
    body.replaceChildren(...parts);
  }

  load();
  return { refresh: load, destroy() { alive = false; } };
}

// ───────── one project ─────────

function tile(label, value, detail, cls) {
  return h('div.tile', h('div.l', label), h('div', { class: 'v ' + (cls || '') }, value), detail ? h('div.d', detail) : null);
}

function materialRow(l) {
  const miss = missingOf(l);
  const done = miss === 0;
  const st = stacks(miss, l.st);
  const avail = Math.min(miss, l.elsewhere);
  return h('a.row.mat', { href: '#/objet/' + l.id },
    h('div.ic-box', itemIcon(l.id)),
    h('div.main-col',
      h('div.title', itemName(l.id)),
      h('div.meta', `${fmt(l.have)} / ${fmt(l.need)}${done ? '' : ` · manque ${fmt(miss)}${st ? ` (${st})` : ''}`}`),
      progressBar(l.have, l.need)),
    h('div.end',
      done ? h('span.ok-pill', icon('check'), 'OK') : h('div.v', `${Math.floor((l.have / l.need) * 100)} %`),
      !done && avail > 0 ? h('div.s.avail', { title: 'Dans d\'autres coffres, pas liés au projet' }, `${compact(avail)} ailleurs`) : null),
    icon('chev', 'chev'));
}

function shoppingSheet(p) {
  const lines = p.materials.filter((l) => missingOf(l) > 0).sort((a, b) => missingOf(b) - missingOf(a));
  const text = [`Projet « ${p.name} » — il manque ${fmt(p.missing)} blocs`, '']
    .concat(lines.map((l) => {
      const miss = missingOf(l);
      const st = stacks(miss, l.st);
      return `- ${fmt(miss)} × ${itemName(l.id)}${st ? ` (${st})` : ''}`;
    })).join('\n');
  sheet('Ce qu\'il manque', (close) => {
    const ta = h('textarea.shop', { readonly: true, 'aria-label': 'Liste des blocs manquants' });
    ta.value = lines.length ? text : 'Il ne manque rien : tous les blocs sont dans les coffres liés.';
    const copy = h('button.btn', { type: 'button' }, icon('copy'), 'Copier');
    copy.addEventListener('click', async () => {
      let ok = false;
      try { await navigator.clipboard.writeText(ta.value); ok = true; } catch { /* not allowed over http */ }
      if (!ok) {
        try { ta.focus(); ta.select(); ok = document.execCommand('copy'); } catch { ok = false; }
      }
      toast(ok ? 'Liste copiée : colle-la sur Discord ou dans le chat' : 'Sélectionne le texte pour le copier', !ok);
    });
    return h('div',
      h('p.note', { style: { marginBottom: '10px' } }, `${plural(lines.length, 'sorte de bloc', 'sortes de blocs')}, la plus grosse quantité en premier.`),
      ta,
      h('div.btns', h('button.btn.plain', { type: 'button', onclick: () => close() }, 'Fermer'), copy));
  });
}

function linkSheet(p, onSaved) {
  sheet('Coffres du projet', (close) => {
    const sel = new Set(p.candidates.filter((c) => c.linked).map((c) => c.id));
    const input = h('input', { type: 'search', placeholder: 'Nom ou zone', 'aria-label': 'Filtrer les coffres', autocomplete: 'off' });
    const listBox = h('div.link-list');
    const save = h('button.btn', { type: 'button' });
    const label = () => { save.textContent = `Enregistrer (${sel.size})`; };

    function row(c) {
      const box = h('input', { type: 'checkbox' });
      box.checked = sel.has(c.id);
      box.addEventListener('change', () => { if (box.checked) sel.add(c.id); else sel.delete(c.id); label(); });
      return h('label.link-row',
        box,
        h('div.ic-box', itemIcon(c.type || 'chest')),
        h('div.main-col',
          h('div.title', c.name, c.pub ? null : h('span.lock', ' 🔒')),
          h('div.meta', `${typeName(c.type)} · ${placeText(c)}`)),
        c.useful > 0 ? h('span.badge', `${compact(c.useful)} utiles`) : h('span.faint', { style: { fontSize: '12.5px' } }, 'rien d\'utile'));
    }

    function draw() {
      const nq = norm(input.value);
      const cs = p.candidates.filter((c) => !nq || norm(c.name).includes(nq) || norm(c.zone || '').includes(nq));
      if (!cs.length) {
        listBox.replaceChildren(h('p.note', { style: { padding: '12px 4px' } }, p.candidates.length ? 'Aucun coffre ne correspond.' : 'Aucun coffre suivi pour l\'instant. En jeu : /coffres ajouter.'));
        return;
      }
      const groups = new Map();
      for (const c of cs) {
        const z = c.zone || '';
        if (!groups.has(z)) groups.set(z, []);
        groups.get(z).push(c);
      }
      // zones holding the most useful blocks first
      const zones = [...groups.entries()].map(([z, list]) => [z, list.sort((a, b) => b.useful - a.useful || a.name.localeCompare(b.name, 'fr', { numeric: true }))])
        .sort((a, b) => b[1].reduce((s, c) => s + c.useful, 0) - a[1].reduce((s, c) => s + c.useful, 0));
      const out = [];
      for (const [z, list] of zones) {
        const all = h('button.link-btn', { type: 'button' });
        const allOn = () => list.every((c) => sel.has(c.id));
        all.textContent = allOn() ? 'Aucun' : 'Tous';
        all.addEventListener('click', () => {
          const on = !allOn();
          for (const c of list) { if (on) sel.add(c.id); else sel.delete(c.id); }
          label();
          draw();
        });
        out.push(h('div.link-zone', h('span', z || 'Sans zone'), all));
        out.push(...list.map(row));
      }
      listBox.replaceChildren(...out);
    }

    input.addEventListener('input', debounce(draw, 100));
    save.addEventListener('click', async () => {
      save.disabled = true;
      try {
        const updated = await api(`project/${p.id}/chests`, { method: 'POST', body: { chests: [...sel] } });
        close();
        toast(`${plural(updated.chests, 'coffre lié', 'coffres liés')} · projet à ${updated.percent} %`);
        onSaved(updated);
      } catch (e) {
        toast(e.message, true);
        save.disabled = false;
      }
    });
    label();
    draw();
    return h('div',
      h('p.note', { style: { marginBottom: '12px' } }, 'Coche les coffres où sont rangés les matériaux : leur contenu compte pour le projet. Ceux qui ont déjà des blocs utiles sont en haut.'),
      p.shared ? h('p.note.hint', icon('lock'), 'Tes coffres privés ne comptent que pour toi. Pour que tout le monde les voie dans ce projet, rends-les communs depuis leur page.') : null,
      h('label.searchbox', icon('search'), input),
      listBox,
      h('p.note', { style: { marginBottom: '10px' } }, 'En jeu, tu peux aussi faire ', h('code', `/coffres projet lier ${p.name}`), ' puis un clic droit sur les coffres.'),
      h('div.btns', h('button.btn.plain', { type: 'button', onclick: () => close() }, 'Annuler'), save));
  });
}

function editSheet(p, onSaved) {
  sheet('Modifier le projet', (close) => {
    const name = h('input.input', { value: p.name, maxlength: 40, required: true, autocomplete: 'off' });
    const shared = h('input', { type: 'checkbox', role: 'switch' });
    shared.checked = p.shared;
    const save = h('button.btn', { type: 'submit' }, 'Enregistrer');
    const del = h('button.btn.danger.block', { type: 'button' }, icon('trash'), 'Supprimer le projet');
    del.addEventListener('click', async () => {
      if (!confirm(`Supprimer le projet « ${p.name} » ? Les coffres et leur contenu ne sont pas touchés.`)) return;
      try {
        await api(`project/${p.id}/delete`, { method: 'POST' });
        close();
        toast('Projet supprimé');
        location.hash = '#/projets';
      } catch (e) {
        toast(e.message, true);
      }
    });
    const form = h('form',
      h('label.field', h('span', 'Nom'), name),
      h('div.switch-row', h('div', h('div', { style: { fontWeight: 600 } }, 'Projet commun'),
        h('div.note', 'Tout le serveur le voit et peut y lier ses coffres. Sinon, seuls toi et les admins le voient.')), h('label.switch', shared, h('i'))),
      h('button.btn.plain.block', { type: 'button', style: { marginBottom: '10px' }, onclick: () => { close(); uploadSheet({ project: p, onDone: onSaved }); } },
        icon('upload'), 'Remplacer le fichier'),
      del,
      h('div.btns', { style: { marginTop: '16px' } }, h('button.btn.plain', { type: 'button', onclick: () => close() }, 'Annuler'), save));
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      save.disabled = true;
      try {
        const updated = await api('project/' + p.id, { method: 'POST', body: { name: name.value, shared: shared.checked } });
        close();
        toast('Projet enregistré');
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

export function projectPage(root, [id]) {
  let alive = true;
  let data = null;
  let filter = store.get('projFilter', 'missing');
  let sort = store.get('projSort', 'missing');
  let shown = 120;

  root.append(header('', { back: { label: 'Projets', href: '#/projets' } }));
  root.querySelector('.head').remove();
  const body = h('div', skeleton(3));
  root.append(body);

  // These parts are built once so the search field keeps focus when the page refreshes itself.
  const headBox = h('div');
  const actionsBox = h('div.actions.proj-actions');
  const heroBox = h('div');
  const chartBox = h('div');
  const chestsBox = h('div');
  const matsList = h('div.list');
  const matsCount = h('span.pill');
  const search = h('input', { type: 'search', placeholder: 'Chercher un bloc', 'aria-label': 'Chercher un bloc', autocomplete: 'off' });
  search.addEventListener('input', debounce(() => { shown = 120; drawMaterials(); }, 120));
  const matsCard = card('Blocs', h('div',
    h('div.toolbar', { style: { padding: '0 16px' } },
      h('label.searchbox', icon('search'), search),
      segmented([['missing', 'Manquants'], ['all', 'Tout'], ['done', 'Complets']], filter, (v) => { filter = v; shown = 120; store.set('projFilter', v); drawMaterials(); }, 'Filtrer'),
      segmented([['missing', 'Manque'], ['need', 'Quantité'], ['name', 'A–Z']], sort, (v) => { sort = v; store.set('projSort', v); drawMaterials(); }, 'Trier')),
    matsList), { cls: 'flush wide', tools: matsCount });
  let built = false;

  async function load() {
    try {
      await initAssets();
      const d = await api('project/' + id);
      if (!alive) return;
      data = d;
      show();
    } catch (e) {
      if (alive && !built) body.replaceChildren(errorBox(e, load));
      else if (alive && e.status === 404) { toast(e.message, true); location.hash = '#/projets'; }
    }
  }

  function set(d) {
    data = d;
    show();
  }

  function show() {
    const p = data;
    document.title = `${p.name} · Coffres`;
    if (!built) {
      built = true;
      body.replaceChildren(headBox, actionsBox, h('div.grid.two', h('div.wide', heroBox), h('div.wide', chartBox), matsCard, h('div.wide', chestsBox)));
    }

    headBox.replaceChildren(h('div.head',
      h('div.t',
        h('div.kicker', `${ownerText(p)}${FORMATS[p.format] ? ' · ' + FORMATS[p.format] : ''}`),
        h('h1', p.name),
        h('div.sub', [p.file, sizeText(p), p.author ? `par ${p.author}` : null].filter(Boolean).join(' · ')))));

    const linkedIds = p.linked.map((c) => c.id);
    actionsBox.replaceChildren(...[
      p.canLink ? h('button.btn', { type: 'button', onclick: () => linkSheet(p, set) }, icon('link'), 'Lier des coffres') : null,
      linkedIds.length ? locateButton(linkedIds.slice(0, 8), 'Faire briller', 'btn soft') : null,
      h('button.btn.plain', { type: 'button', onclick: () => shoppingSheet(p) }, icon('list'), 'Ce qui manque'),
      p.canEdit ? h('button.btn.plain', { type: 'button', onclick: () => editSheet(p, set) }, icon('edit'), 'Modifier') : null,
    ].filter(Boolean));

    // Hero: the ring and the four numbers
    const miss = p.materials.filter((l) => missingOf(l) > 0);
    const availElsewhere = miss.reduce((a, l) => a + Math.min(missingOf(l), l.elsewhere), 0);
    const needSh = shulkerText(shulkers(p.materials, (l) => l.need));
    const missSh = shulkerText(shulkers(miss, missingOf));
    heroBox.replaceChildren(card(null, h('div',
      h('div.proj-hero',
        ring(p.ratio, { size: 136, stroke: 11, label: p.percent >= 100 ? 'prêt !' : 'réunis' }),
        h('div.tiles',
          tile('Il faut', fmt(p.need), needSh || plural(p.kinds, 'sorte de bloc', 'sortes de blocs')),
          tile('Dans les coffres', fmt(p.have), p.chests ? plural(p.chests, 'coffre lié', 'coffres liés') : 'aucun coffre lié'),
          tile('Manquants', fmt(p.missing), p.missing ? (availElsewhere ? `dont ${compact(availElsewhere)} ailleurs` : missSh) : 'rien !', p.missing ? '' : 'good'),
          tile('Sortes complètes', `${fmt(p.doneKinds)} / ${fmt(p.kinds)}`, null, p.doneKinds === p.kinds ? 'good' : ''))),
      p.chests === 0 && p.canLink ? h('p.note.hint', icon('link'), 'Lie les coffres où tu ranges les matériaux : leur contenu sera compté automatiquement, en direct.') : null,
      availElsewhere > 0 && p.chests > 0 ? h('p.note.hint', `${compact(availElsewhere)} blocs qui manquent sont déjà dans d'autres coffres du serveur : regarde « ailleurs » dans la liste, ou lie ces coffres au projet.`) : null,
      p.hiddenChests ? h('p.note.hint', icon('lock'), `${plural(p.hiddenChests, 'coffre privé lié', 'coffres privés liés')} par d'autres joueurs ${p.hiddenChests > 1 ? 'ne sont pas comptés' : 'n\'est pas compté'} pour toi.`) : null)));

    // The chart of materials
    const top = p.materials.slice(0, 8);
    const rest = p.materials.slice(8);
    const restN = rest.reduce((a, l) => a + l.need, 0);
    const wheelData = top.map((l, i) => ({ id: l.id, value: l.need, label: itemName(l.id), color: SERIES[i], href: '#/objet/' + l.id, icon: itemIcon(l.id, 's') }));
    if (restN) wheelData.push({ id: '_other', value: restN, label: `Autres (${rest.length} sortes)`, color: OTHER });
    chartBox.replaceChildren(chartCard('Matériaux',
      () => wheel(wheelData, { centerLabel: 'blocs' }),
      () => table(['Bloc', 'Il faut', 'Coffres', 'Manque'], p.materials.map((l) => [itemName(l.id), fmt(l.need), fmt(l.have), fmt(missingOf(l))]))));

    // Linked chests
    chestsBox.replaceChildren(card('Coffres liés', p.linked.length
      ? h('div.list', p.linked.map((c) => h('a.row', { href: '#/coffre/' + c.id },
        h('div.ic-box', itemIcon(c.type || 'chest')),
        h('div.main-col', h('div.title', c.name, c.pub ? null : h('span.lock', ' 🔒')), h('div.meta', `${c.zone ? c.zone + ' · ' : ''}${placeText(c)}`)),
        h('div.end', h('div.v', compact(c.useful)), h('div.s', 'utiles')),
        icon('chev', 'chev'))))
      : h('div', { style: { padding: '0 16px 14px' } },
        h('p.note', 'Aucun coffre lié. Choisis ceux où sont rangés les matériaux : Coffres compte ce qu\'ils contiennent.'),
        p.canLink ? h('button.btn.soft.sm', { type: 'button', style: { marginTop: '10px' }, onclick: () => linkSheet(p, set) }, icon('link'), 'Lier des coffres') : null),
    { cls: 'flush', tools: p.canLink && p.linked.length ? h('button.link-btn', { type: 'button', onclick: () => linkSheet(p, set) }, 'Modifier') : null }));

    drawMaterials();
  }

  function drawMaterials() {
    if (!data) return;
    const nq = norm(search.value);
    let list = data.materials.filter((l) => {
      const miss = missingOf(l);
      if (filter === 'missing' && miss === 0) return false;
      if (filter === 'done' && miss > 0) return false;
      return !nq || norm(itemName(l.id)).includes(nq) || l.id.includes(nq.replace(/ /g, '_'));
    });
    if (sort === 'missing') list.sort((a, b) => missingOf(b) - missingOf(a) || b.need - a.need);
    else if (sort === 'need') list.sort((a, b) => b.need - a.need);
    else list.sort((a, b) => itemName(a.id).localeCompare(itemName(b.id), 'fr'));
    matsCount.textContent = plural(list.length, 'sorte', 'sortes');
    if (!list.length) {
      const msg = nq ? `Aucun bloc ne correspond à « ${search.value} ».`
        : filter === 'missing' ? '🎉 Il ne manque rien : tous les blocs sont dans les coffres liés !'
          : filter === 'done' ? 'Aucune sorte de bloc n\'est encore complète.' : 'Aucun bloc.';
      matsList.replaceChildren(h('p.note', { style: { padding: '6px 16px 14px' } }, msg));
      return;
    }
    const rows = list.slice(0, shown).map(materialRow);
    if (list.length > shown) {
      rows.push(h('button.row.plain.more-row', { type: 'button', onclick: () => { shown = Infinity; drawMaterials(); } },
        h('div.main-col', h('div.title', { style: { color: 'var(--accent-text)', textAlign: 'center' } }, `Afficher les ${fmt(list.length - shown)} autres`))));
    }
    matsList.replaceChildren(...rows);
  }

  load();
  return { refresh: load, destroy() { alive = false; } };
}

/** Projects strip for the home page. */
export function projectsHomeCard(projects) {
  if (!projects || !projects.length) {
    return card('Projets', h('div.home-proj-empty',
      h('p.note', 'Envoie le plan d\'une construction (.litematic, .schem…) : Coffres compte les blocs qu\'il faut et ce qu\'il manque dans les coffres.'),
      newProjectButton('btn soft sm')), { cls: 'wide' });
  }
  return card('Projets', h('div.home-proj', projects.map((p) => h('a.hp', { href: '#/projet/' + p.id },
    ring(p.ratio, { size: 52, stroke: 12 }),
    h('div', { style: { minWidth: 0 } },
      h('div.nm', p.name),
      h('div.meta', p.missing ? `manque ${compact(p.missing)} blocs` : 'tous les blocs sont prêts'))))),
  { cls: 'wide', more: { href: '#/projets', label: 'Tous les projets' } });
}
