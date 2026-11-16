// Projets: send a building plan (.litematic, .schem, .schematic, .nbt), see every block it needs,
// link the chests where the materials are stored and follow the progress.
import { h, icon, fmt, compact, plural, store, debounce, norm, ago, toast, sheet, stacks, dimName, avatar } from '../util.js';
import { api, session } from '../api.js';
import { itemIcon, itemName, initAssets } from '../icons.js';
import { wheel, ring, ring2, progressBar, table, SERIES, OTHER } from '../charts.js';
import { header, card, chartCard, segmented, empty, skeleton, errorBox, placeText, locateButton, distanceBadge } from '../ui.js';
import { typeName } from './chests.js';
import { visibilityPicker, groupsSheet } from './groups.js';

const FORMATS = { litematic: 'Litematica', schem: 'WorldEdit', schematic: 'MCEdit', nbt: 'Structure' };
const ACCEPT = '.litematic,.schem,.schematic,.nbt';
const MB = 1024 * 1024;

function sizeText(p) {
  const [x, y, z] = p.size || [0, 0, 0];
  return x || y || z ? `${x} × ${y} × ${z}` : '';
}

function ownerText(p) {
  if (p.shared) return `Projet commun · par ${p.mine ? 'toi' : p.owner}`;
  if (p.visibility === 'group' && p.group) return `Groupe « ${p.group.name} » · par ${p.mine ? 'toi' : p.owner}`;
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

/** Still to find once what is placed and what is in the linked chests are counted. */
function missingOf(l) { return Math.max(0, l.need - (l.placed || 0) - usefulOf(l) - (l.inv || 0)); }
/** What the chests bring (items beyond what is still to place do not count). */
function usefulOf(l) { return Math.max(0, Math.min(l.have, l.need - (l.placed || 0))); }

const ROTATIONS = [[0, '0°'], [90, '90° ↻'], [180, '180°'], [270, '90° ↺']];
const MIRRORS = [['none', 'Aucun'], ['lr', 'Gauche-droite'], ['fb', 'Avant-arrière']];

function rotText(at) {
  const r = { 0: 'aucune', 90: '90° horaire', 180: '180°', 270: '90° anti-horaire' }[at.rot] || 'aucune';
  const m = { lr: ' · miroir gauche-droite', fb: ' · miroir avant-arrière' }[at.mirror] || '';
  return r + m;
}

/** "Steve, Alex" or "personne n'en porte". */
function carriersText(p) {
  const list = p.carriers || [];
  if (!list.length) return 'personne n\'en porte';
  const names = list.slice(0, 2).map((c) => c.name).join(', ');
  return list.length > 2 ? `${names} +${list.length - 2}` : names;
}

/** The ring of a project: placed, in the chests, in the inventories. */
function projectRing(p, opts) {
  return p.inWorld || p.carried ? ring2(p.placedRatio, p.chestRatio, { ...opts, inv: p.carriedRatio }) : ring(p.ratio, opts);
}

// ───────── new project / replace the file ─────────

export function uploadSheet({ project, onDone } = {}) {
  const maxMb = (session.me && session.me.maxFileMb) || 20;
  sheet(project ? 'Remplacer le fichier' : 'Nouveau projet', (close) => {
    let file = null;
    const picker = project ? null : visibilityPicker({ visibility: 'all' });
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

    const save = h('button.btn', { type: 'submit' }, project ? 'Remplacer' : 'Créer le projet');
    const form = h('form',
      dz,
      project ? h('p.note', { style: { margin: '-4px 0 14px' } }, 'Les coffres liés restent liés. Seule la liste des blocs change.') : [
        h('label.field', h('span', 'Nom du projet (facultatif)'), name),
        picker.el,
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
      let vis = null;
      if (picker) {
        try { vis = picker.value(); } catch (ex) { err.textContent = ex.message; err.hidden = false; return; }
      }
      save.disabled = true;
      save.textContent = 'Lecture du plan…';
      const qs = new URLSearchParams({ file: file.name });
      if (!project) {
        qs.set('name', name.value.trim());
        qs.set('vis', vis.visibility);
        if (vis.group) qs.set('group', vis.group);
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
      projectRing(p, { size: 66, stroke: 11 }),
      h('div', { style: { minWidth: 0, flex: 1 } },
        h('div.nm', p.name),
        h('div.co', ownerText(p)),
        p.inWorld || p.carried
          ? h('div.co.parts',
            p.inWorld ? h('span', h('i.pl'), `${p.placedPercent} % posé`) : null,
            h('span', h('i.ch'), `${p.chestPercent} % coffres`),
            p.carried ? h('span', h('i.iv'), `${p.carriedPercent} % sur les joueurs`) : null)
          : h('div.co', `${fmt(p.have)} / ${fmt(p.need)} blocs · ${p.chests ? plural(p.chests, 'coffre lié', 'coffres liés') : 'aucun coffre lié'}`))),
    p.top.length
      ? h('div.mini', h('span.lbl', 'Manque'), p.top.map((t) => h('span', { title: itemName(t.id) }, itemIcon(t.id, 's'), compact(t.n))))
      : h('div.mini.done', icon('check'), p.placed >= p.need ? 'Construction terminée' : 'Tous les blocs sont réunis'),
    h('div.foot', h('span.file', p.file), [sizeText(p), `modifié ${ago(p.updated)}`].filter(Boolean).map((t) => h('span', ' · ' + t))));
}

export function newProjectButton(cls = 'btn') {
  return h('button', { class: cls, type: 'button', onclick: () => uploadSheet() }, icon('plus'), 'Nouveau projet');
}

function groupsButton(onChange) {
  return h('button.btn.plain', { type: 'button', onclick: () => groupsSheet({ onChange }) }, icon('users'), 'Groupes');
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
  const head = header('Projets', { sub: 'Les blocs qu\'il faut pour tes constructions, et ceux déjà rangés',
    actions: h('div.actions', groupsButton(() => load()), newProjectButton()) });
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

/** One block as seen in this project: placed, in which linked chests, who carries it, where else it is. */
function itemSheet(p, l) {
  sheet(itemName(l.id), (close) => {
    const body = h('div.item-sheet', h('p.note', 'Chargement…'));
    const chestRow = (c) => h('a.row', { href: '#/coffre/' + c.id, onclick: () => close() },
      h('div.ic-box', itemIcon(c.type || 'chest')),
      h('div.main-col',
        h('div.title', c.name, c.pub ? null : h('span.lock', ' 🔒')),
        h('div.meta', `${c.zone ? c.zone + ' · ' : ''}${placeText(c)}`),
        distanceBadge(c)),
      h('div.end', h('div.v', fmt(c.count)), c.loose < c.count ? h('div.s', `dont ${fmt(c.count - c.loose)} en shulker`) : null),
      locateButton([c.id], '', 'icon-btn'));
    const section = (title, content) => h('div.ish-sec', h('h3', title), content);

    api(`project/${p.id}/item/${l.id}`).then((d) => {
      const extraChests = d.have - d.useful;
      const extraCarried = d.carried - d.inv;
      const avail = Math.min(d.missing, d.elsewhere);
      const toPlace = Math.max(0, d.need - d.placed);
      // Shares of the plan rounded so that they always add up to 100 % (largest remainders).
      const shares = (() => {
        const vals = { pl: d.inWorld ? d.placed : 0, ch: d.useful, iv: d.inv, mi: d.missing };
        const keys = Object.keys(vals);
        const raw = keys.map((k) => (d.need ? (vals[k] / d.need) * 100 : 0));
        const out = raw.map(Math.floor);
        let left = d.need ? 100 - out.reduce((a, b) => a + b, 0) : 0;
        keys.map((k, i) => i).sort((a, b) => (raw[b] - out[b]) - (raw[a] - out[a]))
          // Never round up to 100 % something that is not complete.
          .forEach((i) => { if (left > 0 && raw[i] > out[i] && out[i] + 1 < 100) { out[i]++; left--; } });
        return Object.fromEntries(keys.map((k, i) => [k, out[i]]));
      })();
      // One line of the legend: colour, what it is, how many, share of the plan, and a detail under it.
      const part = (cls, label, n, detail) => h('div.ish-part',
        h('i', { class: cls }),
        h('div.main-col', h('div.lbl', label), detail ? h('div.d', detail) : null),
        h('div.n', fmt(n)),
        h('div.p', n > 0 && shares[cls || 'mi'] === 0 ? '< 1 %' : `${shares[cls || 'mi']} %`));
      const done = d.missing === 0;
      body.replaceChildren(...[
        h('div.ish-head', h('div.ic-box', itemIcon(d.id)),
          h('div.main-col', h('div.note', `Dans le projet « ${d.project} »`),
            h('div.ish-need', h('b', fmt(d.need)), ` à poser en tout${stacks(d.need, d.st) ? ` · ${stacks(d.need, d.st)}` : ''}`)),
          done ? h('span.ok-pill', icon('check'), d.placed >= d.need ? 'Posé' : 'OK') : null),
        h('div.ish-chart',
          ring2(d.need ? d.placed / d.need : 0, d.need ? d.useful / d.need : 0, {
            size: 132, stroke: 12, inv: d.need ? d.inv / d.need : 0,
            text: d.inWorld ? `${shares.pl} %` : `${shares.pl + shares.ch + shares.iv} %`,
            label: d.inWorld ? 'posé' : 'réunis',
          }),
          h('div.ish-parts',
            d.inWorld ? part('pl', 'Déjà posés', d.placed, d.placed >= d.need ? 'tout est posé !' : `${fmt(toPlace)} pas encore posés`) : null,
            part('ch', 'Dans les coffres liés', d.useful,
              extraChests > 0 ? `${fmt(d.have)} rangés, dont ${fmt(extraChests)} en trop` : stacks(d.useful, d.st)),
            part('iv', 'Sur les joueurs', d.inv,
              extraCarried > 0 ? `${fmt(d.carried)} portés, dont ${fmt(extraCarried)} en trop` : stacks(d.inv, d.st)),
            part('', 'Manque', d.missing,
              done ? 'rien !' : [stacks(d.missing, d.st), avail ? `${fmt(avail)} ailleurs` : null].filter(Boolean).join(' · ') || null))),
        section('Sur les joueurs en ligne', d.who.length
          ? h('div.list', d.who.map((w) => h('div.row.plain',
            avatar(w.name),
            h('div.main-col', h('div.title', w.name), stacks(w.n, d.st) ? h('div.meta', stacks(w.n, d.st)) : null),
            h('div.end', h('div.v', fmt(w.n))))))
          : h('p.note', 'Aucun membre du projet en ligne n\'en porte en ce moment.')),
        section(`Dans les coffres liés${d.chests.length ? ` · ${d.chests.length}` : ''}`, d.chests.length
          ? h('div.list', d.chests.map(chestRow))
          : h('p.note', p.chests ? 'Aucun coffre lié n\'en contient.' : 'Aucun coffre n\'est lié à ce projet.')),
        d.others.length ? section(`Ailleurs, dans des coffres pas liés · ${d.othersCount}`, h('div',
          h('p.note', { style: { marginBottom: '6px' } }, d.canLink ? 'Ils ne comptent pas pour le projet. Lie-les au projet (bouton « Lier des coffres ») pour les compter.' : 'Ils ne comptent pas pour le projet.'),
          h('div.list', d.others.map(chestRow)),
          d.othersCount > d.others.length ? h('p.note', `… et ${d.othersCount - d.others.length} autres.`) : null)) : null,
        h('div.btns', { style: { marginTop: '16px' } },
          h('a.btn.plain', { href: '#/objet/' + d.id, onclick: () => close() }, 'Voir partout sur le serveur'),
          h('button.btn', { type: 'button', onclick: () => close() }, 'Fermer'))].filter(Boolean));
    }).catch((e) => body.replaceChildren(h('p.err', e.message)));
    return body;
  });
}

function materialRow(l, p) {
  const miss = missingOf(l);
  const done = miss === 0;
  const st = stacks(miss, l.st);
  const avail = Math.min(miss, l.elsewhere);
  const placed = l.placed || 0;
  const inv = l.inv || 0;
  const got = placed + usefulOf(l) + inv;
  const counts = placed || inv
    ? [placed ? `${fmt(placed)} posé${placed > 1 ? 's' : ''}` : null, `${fmt(l.have)} en coffre`, inv ? `${fmt(inv)} sur les joueurs` : null]
      .filter(Boolean).join(' + ') + ` / ${fmt(l.need)}`
    : `${fmt(l.have)} / ${fmt(l.need)}`;
  return h('button.row.mat', { type: 'button', onclick: () => itemSheet(p, l) },
    h('div.ic-box', itemIcon(l.id)),
    h('div.main-col',
      h('div.title', itemName(l.id)),
      h('div.meta', `${counts}${done ? '' : ` · manque ${fmt(miss)}${st ? ` (${st})` : ''}`}`),
      progressBar(usefulOf(l), l.need, placed, inv)),
    h('div.end',
      done ? h('span.ok-pill', icon('check'), placed >= l.need ? 'Posé' : 'OK') : h('div.v', `${Math.floor((got / l.need) * 100)} %`),
      !done && avail > 0 ? h('div.s.avail', { title: 'Dans d\'autres coffres, pas liés au projet' }, `${compact(avail)} ailleurs`) : null),
    icon('chev', 'chev'));
}

function shoppingSheet(p) {
  const lines = p.materials.filter((l) => missingOf(l) > 0).sort((a, b) => missingOf(b) - missingOf(a));
  const text = [`Projet « ${p.name} » — il manque ${fmt(p.missing)} blocs${p.inWorld ? ` (${p.placedPercent} % déjà posé)` : ''}`, '']
    .concat(lines.map((l) => {
      const miss = missingOf(l);
      const st = stacks(miss, l.st);
      return `- ${fmt(miss)} × ${itemName(l.id)}${st ? ` (${st})` : ''}`;
    })).join('\n');
  sheet('Ce qu\'il manque', (close) => {
    const ta = h('textarea.shop', { readonly: true, 'aria-label': 'Liste des blocs manquants' });
    ta.value = lines.length ? text : 'Il ne manque rien : tous les blocs sont posés ou dans les coffres liés.';
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
      p.visibility !== 'solo' ? h('p.note.hint', icon('lock'), `Tes coffres privés ne comptent que pour toi. Pour que ${p.visibility === 'group' ? 'le groupe les voie' : 'tout le monde les voie'} dans ce projet, rends-les communs depuis leur page.`) : null,
      h('label.searchbox', icon('search'), input),
      listBox,
      h('p.note', { style: { marginBottom: '10px' } }, 'En jeu, tu peux aussi faire ', h('code', `/coffres projet lier ${p.name}`), ' puis un clic droit sur les coffres.'),
      h('div.btns', h('button.btn.plain', { type: 'button', onclick: () => close() }, 'Annuler'), save));
  });
}

function editSheet(p, onSaved) {
  sheet('Modifier le projet', (close) => {
    const name = h('input.input', { value: p.name, maxlength: 40, required: true, autocomplete: 'off' });
    const picker = visibilityPicker({ visibility: p.visibility || (p.shared ? 'all' : 'solo'), group: p.group ? p.group.id : null });
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
      picker.el,
      h('button.btn.plain.block', { type: 'button', style: { marginBottom: '10px' }, onclick: () => { close(); uploadSheet({ project: p, onDone: onSaved }); } },
        icon('upload'), 'Remplacer le fichier'),
      del,
      h('div.btns', { style: { marginTop: '16px' } }, h('button.btn.plain', { type: 'button', onclick: () => close() }, 'Annuler'), save));
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      let vis;
      try { vis = picker.value(); } catch (ex) { toast(ex.message, true); return; }
      save.disabled = true;
      try {
        const updated = await api('project/' + p.id, { method: 'POST', body: { name: name.value, visibility: vis.visibility, group: vis.group } });
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
  const worldBox = h('div');
  const chartBox = h('div');
  let poll = null;
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
      body.replaceChildren(headBox, actionsBox, h('div.grid.two', h('div.wide', heroBox), h('div.wide', worldBox), h('div.wide', chartBox), matsCard, h('div.wide', chestsBox)));
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
    const finished = p.inWorld && p.placed >= p.need;
    heroBox.replaceChildren(card(null, h('div',
      h('div.proj-hero',
        h('div.proj-ring',
          ring2(p.placedRatio, p.chestRatio, { size: 136, stroke: 11, inv: p.carriedRatio, label: finished ? 'construit !' : p.percent >= 100 ? 'prêt !' : 'réunis' }),
          h('div.legend3', p.inWorld ? h('span', h('i.pl'), 'Posé') : null, h('span', h('i.ch'), 'Coffres'), h('span', h('i.iv'), 'Inventaires'), h('span', h('i'), 'Manque'))),
        h('div.tiles.six',
          tile('Il faut', fmt(p.need), needSh || plural(p.kinds, 'sorte de bloc', 'sortes de blocs')),
          p.inWorld ? tile('Déjà posés', fmt(p.placed), `${p.placedPercent} % du plan`, 'placed') : null,
          tile('Dans les coffres', fmt(p.have), `${p.chestPercent} % · ${p.chests ? plural(p.chests, 'coffre lié', 'coffres liés') : 'aucun coffre lié'}`),
          tile('Dans les inventaires', fmt(p.carried), `${p.carriedPercent} % · ${carriersText(p)}`, 'inv'),
          tile('Manquants', fmt(p.missing), p.missing ? (availElsewhere ? `dont ${compact(availElsewhere)} ailleurs` : missSh) : 'rien !', p.missing ? '' : 'good'),
          tile('Sortes complètes', `${fmt(p.doneKinds)} / ${fmt(p.kinds)}`, null, p.doneKinds === p.kinds ? 'good' : ''))),
      p.chests === 0 && p.canLink ? h('p.note.hint', icon('link'), 'Lie les coffres où tu ranges les matériaux : leur contenu sera compté automatiquement, en direct.') : null,
      availElsewhere > 0 && p.chests > 0 ? h('p.note.hint', `${compact(availElsewhere)} blocs qui manquent sont déjà dans d'autres coffres du serveur : regarde « ailleurs » dans la liste, ou lie ces coffres au projet.`) : null,
      p.carriers && p.carriers.length ? h('p.carriers', 'Sur les joueurs en ligne : ',
        ...p.carriers.flatMap((c, i) => [i ? ' · ' : '', h('b', c.name), ` ${fmt(c.n)}`])) : null,
      p.hiddenChests ? h('p.note.hint', icon('lock'), `${plural(p.hiddenChests, 'coffre privé lié', 'coffres privés liés')} par d'autres joueurs ${p.hiddenChests > 1 ? 'ne sont pas comptés' : 'n\'est pas compté'} pour toi.`) : null)));

    // The chart of materials
    const top = p.materials.slice(0, 8);
    const rest = p.materials.slice(8);
    const restN = rest.reduce((a, l) => a + l.need, 0);
    const wheelData = top.map((l, i) => ({ id: l.id, value: l.need, label: itemName(l.id), color: SERIES[i], href: '#/objet/' + l.id, icon: itemIcon(l.id, 's') }));
    if (restN) wheelData.push({ id: '_other', value: restN, label: `Autres (${rest.length} sortes)`, color: OTHER });
    chartBox.replaceChildren(chartCard('Matériaux',
      () => wheel(wheelData, { centerLabel: 'blocs' }),
      () => table(p.inWorld ? ['Bloc', 'Il faut', 'Posés', 'Coffres', 'Inventaires', 'Manque'] : ['Bloc', 'Il faut', 'Coffres', 'Inventaires', 'Manque'],
        p.materials.map((l) => (p.inWorld
          ? [itemName(l.id), fmt(l.need), fmt(l.placed || 0), fmt(l.have), fmt(l.inv || 0), fmt(missingOf(l))]
          : [itemName(l.id), fmt(l.need), fmt(l.have), fmt(l.inv || 0), fmt(missingOf(l))])))));

    drawWorld(p);

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

  // The "Dans le monde" card: where the plan is built and which blocks are already placed.
  function drawWorld(p) {
    clearTimeout(poll);
    const b = p.build;
    if (b && b.scanning && alive) poll = setTimeout(load, 1500);
    let content;
    if (!p.checkable) {
      content = h('div.world-box',
        h('p.note', p.planNote),
        p.canEdit ? h('button.btn.soft.sm', { type: 'button', style: { marginTop: '10px' }, onclick: () => uploadSheet({ project: p, onDone: set }) },
          icon('upload'), 'Remplacer le fichier') : null);
    } else if (!p.placement) {
      content = h('div.world-box',
        h('p.note', 'Dis où la construction se trouve dans le monde : Coffres regarde quels blocs du plan sont déjà posés, '
          + 'en direct, et ne les demande plus dans les coffres.'),
        p.canEdit
          ? h('button.btn.sm', { type: 'button', style: { marginTop: '12px' }, onclick: () => placementSheet(p, set) }, icon('pin'), 'Placer dans le monde')
          : h('p.note', { style: { marginTop: '8px' } }, 'Seul le créateur du projet (ou un admin) peut le placer.'));
    } else {
      const at = p.placement;
      const facts = h('div.world-facts',
        tile('Monde', dimName(/_nether$/.test(at.world) ? 'nether' : /_the_end$/.test(at.world) ? 'end' : 'overworld'), at.world),
        tile('Origine', `${at.x} ${at.y} ${at.z}`, 'x y z'),
        tile('Rotation', rotText(at).split(' · ')[0], at.mirror && at.mirror !== 'none' ? rotText(at).split(' · ')[1] : 'sans miroir'),
        tile('Blocs posés', b ? `${fmt(b.placedBlocks)} / ${fmt(b.blocks)}` : '—', b ? `${Math.floor((b.placedBlocks / Math.max(1, b.blocks)) * 100)} % des blocs` : null, 'placed'));
      let status;
      if (!b) status = h('p.note.hint', 'Le plan de ce projet n\'a pas pu être chargé. Renvoie son fichier.');
      else if (b.scanning) {
        status = h('div.scan-line', h('span', 'Vérification…'), progressBar(Math.round(b.progress * 1000), 1000), h('span', `${Math.floor(b.progress * 100)} %`));
      } else {
        status = h('div.scan-line', icon('check'), h('span', b.lastScan ? `Vérifié ${ago(b.lastScan)}, puis suivi en direct` : 'En attente de vérification'));
      }
      content = h('div.world-box',
        facts,
        status,

        b && !b.scanning && b.unloaded > 0 ? h('p.note.hint', icon('pin'),
          `${plural(b.unloaded, 'bloc n\'a', 'blocs n\'ont')} pas pu être vérifié${b.unloaded > 1 ? 's' : ''} : personne n'était près de cette partie de la construction. `
          + 'Approche-toi en jeu, puis appuie sur « Revérifier ».') : null,
        h('div.world-actions',
          p.canLink && b ? h('button.btn.soft.sm', { type: 'button', disabled: b.scanning, onclick: async (e) => {
            e.currentTarget.disabled = true;
            try { set(await api(`project/${p.id}/scan`, { method: 'POST' })); toast('Vérification lancée'); } catch (err) { toast(err.message, true); }
          } }, icon('bolt'), 'Revérifier') : null,
          p.canEdit ? h('button.btn.plain.sm', { type: 'button', onclick: () => placementSheet(p, set) }, icon('pin'), 'Déplacer') : null));
    }
    worldBox.replaceChildren(card('Dans le monde', content, { cls: 'flush' }));
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
        : filter === 'missing' ? '🎉 Il ne manque rien : tous les blocs sont posés ou dans les coffres liés !'
          : filter === 'done' ? 'Aucune sorte de bloc n\'est encore complète.' : 'Aucun bloc.';
      matsList.replaceChildren(h('p.note', { style: { padding: '6px 16px 14px' } }, msg));
      return;
    }
    const rows = list.slice(0, shown).map((l) => materialRow(l, data));
    if (list.length > shown) {
      rows.push(h('button.row.plain.more-row', { type: 'button', onclick: () => { shown = Infinity; drawMaterials(); } },
        h('div.main-col', h('div.title', { style: { color: 'var(--accent-text)', textAlign: 'center' } }, `Afficher les ${fmt(list.length - shown)} autres`))));
    }
    matsList.replaceChildren(...rows);
  }

  load();
  return { refresh: load, destroy() { alive = false; clearTimeout(poll); } };
}

// ───────── placing a project in the world ─────────

function placementSheet(p, onSaved) {
  sheet(p.placement ? 'Déplacer le projet' : 'Placer dans le monde', (close) => {
    const at = p.placement || {};
    let rot = at.rot || 0;
    let mirror = at.mirror || 'none';
    const worlds = p.worlds && p.worlds.length ? p.worlds : ['world'];
    const world = h('select.input', { 'aria-label': 'Monde' }, worlds.map((w) => h('option', { value: w }, w)));
    world.value = at.world && worlds.includes(at.world) ? at.world : worlds[0];
    const num = (v, label) => h('input.input', { type: 'number', inputmode: 'numeric', step: 1, value: v ?? '', 'aria-label': label, required: true });
    const x = num(at.x, 'x');
    const y = num(at.y, 'y');
    const z = num(at.z, 'z');
    const err = h('p.err', { hidden: true });
    const save = h('button.btn', { type: 'submit' }, 'Enregistrer');
    const here = h('button.btn.soft.block', { type: 'button' }, icon('pin'), 'Utiliser ma position en jeu');

    async function send(body, btn) {
      btn.disabled = true;
      err.hidden = true;
      try {
        const updated = await api(`project/${p.id}/placement`, { method: 'POST', body: { ...body, rot, mirror } });
        close();
        toast('Projet placé : vérification des blocs posés…');
        onSaved(updated);
      } catch (e) {
        err.textContent = e.message;
        err.hidden = false;
        btn.disabled = false;
      }
    }
    here.addEventListener('click', () => send({ here: true }, here));

    const remove = p.placement ? h('button.btn.danger.block', { type: 'button', style: { marginTop: '10px' } }, icon('trash'), 'Retirer du monde') : null;
    if (remove) {
      remove.addEventListener('click', async () => {
        if (!confirm('Ne plus suivre les blocs posés pour ce projet ? Seuls les coffres compteront.')) return;
        remove.disabled = true;
        try {
          const updated = await api(`project/${p.id}/placement`, { method: 'POST', body: { remove: true } });
          close();
          toast('Le projet n\'est plus placé dans le monde');
          onSaved(updated);
        } catch (e) {
          toast(e.message, true);
          remove.disabled = false;
        }
      });
    }

    const form = h('form',
      h('details.place-help',
        h('summary', 'Quelles coordonnées mettre ?'),
        h('p.note', p.format === 'litematic'
          ? 'Litematica : ouvre le menu de placement du schéma et recopie les coordonnées de l\'origine, ainsi que la même rotation et le même miroir.'
          : 'Le coin de la construction qui a les plus petits x, y et z (le coin du bas, côté nord-ouest), quand elle n\'est pas tournée.'),
        h('p.note', 'Le plus simple : tiens-toi en jeu sur ce bloc, puis appuie sur « Utiliser ma position en jeu ». '
          + 'Tu peux aussi taper /coffres projet placer ' + p.name + ' en jeu.')),
      h('div.field', h('span', 'Rotation'), segmented(ROTATIONS, rot, (v) => { rot = v; }, 'Rotation')),
      h('div.field', h('span', 'Miroir'), segmented(MIRRORS, mirror, (v) => { mirror = v; }, 'Miroir')),
      here,
      h('div.or-line', 'ou'),
      h('label.field', h('span', 'Monde'), world),
      h('div.field', h('span', 'Origine'), h('div.coords',
        h('label', h('span', 'X'), x), h('label', h('span', 'Y'), y), h('label', h('span', 'Z'), z))),
      err,
      remove,
      h('div.btns', { style: { marginTop: '16px' } }, h('button.btn.plain', { type: 'button', onclick: () => close() }, 'Annuler'), save));
    form.addEventListener('submit', (e) => {
      e.preventDefault();
      const vals = [x, y, z].map((i) => Number(i.value));
      if ([x, y, z].some((i) => i.value.trim() === '') || vals.some((v) => !Number.isInteger(v))) {
        err.textContent = 'Indique les coordonnées x, y et z (des nombres entiers), ou utilise ta position en jeu.';
        err.hidden = false;
        return;
      }
      send({ world: world.value, x: vals[0], y: vals[1], z: vals[2] }, save);
    });
    return form;
  });
}

/** Projects strip for the home page. */
export function projectsHomeCard(projects) {
  if (!projects || !projects.length) {
    return card('Projets', h('div.home-proj-empty',
      h('p.note', 'Envoie le plan d\'une construction (.litematic, .schem…) : Coffres compte les blocs qu\'il faut et ce qu\'il manque dans les coffres.'),
      newProjectButton('btn soft sm')), { cls: 'wide' });
  }
  return card('Projets', h('div.home-proj', projects.map((p) => h('a.hp', { href: '#/projet/' + p.id },
    projectRing(p, { size: 52, stroke: 12 }),
    h('div', { style: { minWidth: 0 } },
      h('div.nm', p.name),
      h('div.meta', p.inWorld && p.placed >= p.need ? 'construction terminée'
        : p.missing ? `manque ${compact(p.missing)} blocs${p.inWorld ? ` · ${p.placedPercent} % posé` : ''}` : 'tous les blocs sont prêts'))))),
  { cls: 'wide', more: { href: '#/projets', label: 'Tous les projets' } });
}
