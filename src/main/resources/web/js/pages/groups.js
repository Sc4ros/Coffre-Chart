// Groups of friends: made once, then chosen for as many projects as wanted.
// A group project is seen only by the group (and the admins); its members can link their chests and check the build.
import { h, icon, plural, toast, sheet } from '../util.js';
import { api, session } from '../api.js';
import { segmented } from '../ui.js';

const HINTS = {
  all: 'Tout le serveur le voit et peut y lier ses coffres. Seuls toi et les admins pouvez le modifier.',
  group: 'Seuls les membres du groupe le voient, y lient leurs coffres et comptent avec leur inventaire.',
  solo: 'Seuls toi et les admins le voient.',
};

/**
 * "Commun · Groupe · Solo" with the list of the player's groups.
 * Returns { el, value() → { visibility, group } }.
 */
export function visibilityPicker({ visibility = 'all', group = null } = {}) {
  let vis = visibility;
  let groupId = group;
  let groups = null;
  const hint = h('div.note');
  const select = h('select.input', { 'aria-label': 'Groupe d\'amis' });
  const groupBox = h('div.vis-group');

  function drawGroups() {
    if (vis !== 'group') {
      groupBox.replaceChildren();
      return;
    }
    if (!groups) {
      groupBox.replaceChildren(h('p.note', 'Chargement des groupes…'));
      return;
    }
    const mine = groups.filter((g) => g.member || g.id === groupId);
    const manage = h('button.link-btn', { type: 'button', onclick: () => groupsSheet({ onChange: (d) => { groups = d.groups; if (d.created) groupId = d.created; drawGroups(); } }) },
      mine.length ? 'Gérer les groupes' : 'Créer un groupe');
    if (!mine.length) {
      groupBox.replaceChildren(h('p.note', 'Tu n\'as pas encore de groupe d\'amis. ', manage));
      return;
    }
    if (!mine.some((g) => g.id === groupId)) groupId = mine[0].id;
    select.replaceChildren(...mine.map((g) => h('option', { value: g.id }, `${g.name} (${plural(g.members.length, 'joueur', 'joueurs')})`)));
    select.value = groupId;
    groupBox.replaceChildren(select, h('div', { style: { marginTop: '6px' } }, manage));
  }
  select.addEventListener('change', () => { groupId = select.value; });

  function setVis(v) {
    vis = v;
    hint.textContent = HINTS[v];
    drawGroups();
  }

  api('groups').then((d) => { groups = d.groups; drawGroups(); }).catch(() => { groups = []; drawGroups(); });
  setVis(vis);
  const el = h('div.field', h('span', 'Qui le voit'),
    segmented([['all', 'Commun'], ['group', 'Groupe d\'amis'], ['solo', 'Solo']], vis, setVis, 'Qui le voit'),
    groupBox,
    h('div', { style: { marginTop: '8px' } }, hint));
  return {
    el,
    value() {
      if (vis === 'group' && !groupId) throw new Error('Choisis un groupe d\'amis (ou crée-en un).');
      return { visibility: vis, group: vis === 'group' ? groupId : null };
    },
  };
}

/** The sheet to create groups and choose who is in them. */
export function groupsSheet({ onChange } = {}) {
  sheet('Groupes d\'amis', (close) => {
    const body = h('div', h('p.note', 'Chargement…'));
    let data = null;
    let open = null;

    async function call(path, payload, okText) {
      try {
        const d = await api(path, { method: 'POST', body: payload || {} });
        data = d;
        if (okText) toast(okText);
        if (onChange) onChange(d);
        draw();
        return d;
      } catch (e) {
        toast(e.message, true);
        return null;
      }
    }

    function memberRow(g, m) {
      const me = session.me && m.uuid === session.me.uuid;
      return h('div.member',
        h('span', { class: m.online ? 'dot on' : 'dot', title: m.online ? 'En ligne' : 'Hors ligne' }),
        h('span.nm', m.name, me ? h('span.faint', ' (toi)') : null),
        m.owner ? h('span.badge', 'créateur') : null,
        !m.owner && (g.canEdit || me)
          ? h('button.icon-btn', { type: 'button', 'aria-label': me ? 'Quitter le groupe' : `Retirer ${m.name}`, title: me ? 'Quitter le groupe' : 'Retirer',
            onclick: () => {
              if (!confirm(me ? `Quitter le groupe « ${g.name} » ? Tu ne verras plus ses projets.` : `Retirer ${m.name} du groupe « ${g.name} » ?`)) return;
              call(`group/${g.id}/remove`, { uuid: m.uuid }, me ? 'Tu as quitté le groupe' : `${m.name} a été retiré`);
            } }, icon('trash'))
          : null);
    }

    function groupCard(g) {
      const isOpen = open === g.id;
      const head = h('button.group-head', { type: 'button', 'aria-expanded': String(isOpen), onclick: () => { open = isOpen ? null : g.id; draw(); } },
        h('div', h('div.nm', g.name),
          h('div.meta', `${plural(g.members.length, 'joueur', 'joueurs')} · ${plural(g.projects, 'projet', 'projets')}${g.mine ? '' : ` · par ${g.owner}`}`)),
        icon('chev', isOpen ? 'chev open' : 'chev'));
      if (!isOpen) return h('div.group', head);
      const parts = [h('div.members', g.members.map((m) => memberRow(g, m)))];
      if (g.canEdit) {
        const input = h('input.input', { list: 'known-players', placeholder: 'Pseudo d\'un joueur', autocomplete: 'off', 'aria-label': 'Pseudo à ajouter' });
        const add = h('form.add-member', input, h('button.btn.sm', { type: 'submit' }, icon('plus'), 'Ajouter'));
        add.addEventListener('submit', async (e) => {
          e.preventDefault();
          const name = input.value.trim();
          if (!name) return;
          await call(`group/${g.id}/add`, { name }, `${name} a été ajouté`);
        });
        parts.push(add, h('p.note', 'Seuls les joueurs déjà venus sur le serveur peuvent être ajoutés.'));
        parts.push(h('div.group-actions',
          h('button.btn.plain.sm', { type: 'button', onclick: () => {
            const n = prompt('Nouveau nom du groupe', g.name);
            if (n && n.trim()) call(`group/${g.id}`, { name: n.trim() }, 'Groupe renommé');
          } }, icon('edit'), 'Renommer'),
          h('button.btn.danger.sm', { type: 'button', onclick: () => {
            const more = g.projects ? ` Ses ${plural(g.projects, 'projet passera', 'projets passeront')} en solo.` : '';
            if (confirm(`Supprimer le groupe « ${g.name} » ?${more}`)) call(`group/${g.id}/delete`, null, 'Groupe supprimé');
          } }, icon('trash'), 'Supprimer')));
      }
      return h('div.group.open', head, h('div.group-body', parts));
    }

    function draw() {
      const nameInput = h('input.input', { maxlength: 32, placeholder: 'Nom du nouveau groupe', autocomplete: 'off', 'aria-label': 'Nom du groupe' });
      const create = h('form.add-member', nameInput, h('button.btn.sm', { type: 'submit' }, icon('plus'), 'Créer'));
      create.addEventListener('submit', async (e) => {
        e.preventDefault();
        const name = nameInput.value.trim();
        if (!name) return;
        const d = await call('groups/create', { name }, `Groupe « ${name} » créé`);
        if (d && d.created) { open = d.created; draw(); }
      });
      body.replaceChildren(
        h('p.note', { style: { marginBottom: '12px' } }, 'Crée un groupe une fois, puis choisis-le pour tes projets : seuls ses membres les verront.'),
        create,
        data.groups.length ? h('div.groups', data.groups.map(groupCard)) : h('p.note', { style: { padding: '8px 2px' } }, 'Aucun groupe pour l\'instant.'),
        h('datalist#known-players', data.players.map((n) => h('option', { value: n }))),
        h('div.btns', { style: { marginTop: '16px' } }, h('button.btn', { type: 'button', onclick: () => close() }, 'Fermer')));
    }

    api('groups').then((d) => { data = d; open = d.groups.length === 1 ? d.groups[0].id : null; draw(); })
      .catch((e) => body.replaceChildren(h('p.err', e.message)));
    return body;
  });
}
