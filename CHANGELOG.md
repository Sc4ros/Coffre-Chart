# Changements

## 1.3.0 — Blocs déjà posés

- **Un projet peut être placé dans le monde** : monde, coordonnées de l'origine, rotation (0°, 90°, 180°, 270°) et
  miroir, comme dans Litematica. Sur le site (bouton **Placer dans le monde**, ou **Utiliser ma position en jeu**) ou en
  jeu avec `/coffres projet placer <nom> [rotation] [miroir]` (le plan commence au bloc où tu te tiens).
- Coffres **regarde quels blocs du plan sont déjà posés** : une vérification complète quand on place le projet, quand
  on appuie sur **Revérifier** (ou `/coffres projet verifier <nom>`) et toutes les 10 minutes, puis **en direct** à
  chaque bloc posé, cassé, brûlé, explosé ou poussé près de la construction. Un bloc compte s'il est **du bon type**
  (le sens d'un escalier n'est pas vérifié). Les zones du monde qui ne sont pas chargées ne sont jamais chargées exprès.
- L'avancement devient **posé + dans les coffres + manquant** : un anneau vert et bleu, une tuile « Déjà posés », et
  pour chaque bloc « 19 posés + 12 en coffre / 45 ». Ce qui est déjà posé n'est plus demandé dans les coffres, ni dans
  la liste des manquants à copier. Pareil dans les menus en jeu.
- Le plan complet est maintenant gardé dans `plugins/Coffres/plans/` (environ 6 octets par bloc). Les projets créés
  avant la 1.3.0 doivent **renvoyer leur fichier une fois** (Modifier → Remplacer le fichier) pour être vérifiés.
- Les `.schematic` d'avant la 1.13 ne permettent pas cette vérification (les numéros de blocs ne suffisent pas) :
  ils comptent toujours les matériaux comme avant.
- Nouveaux réglages : `projects.check-blocks-per-tick`, `projects.recheck-minutes`, `projects.max-check-blocks`.

## 1.2.1 — Correctif d'affichage

- Page d'un coffre : les cases vides étaient plus grandes que les autres et la grille se déformait (téléphone et PC).
  Toutes les cases sont de nouveau carrées et de la même taille.
- Site installé sur iPhone : la page ne passe plus sous l'heure et la batterie quand on la fait défiler.

## 1.2.0 — Coffres privés, « Mes coffres » et « Communs »

- **Un coffre est toujours privé quand on l'ajoute** : seul celui qui l'a ajouté (et les admins) le voit. L'option
  « public » a disparu du jeu et le réglage `tracking.public-by-default` n'existe plus.
- **À la mise à jour, tous les coffres déjà suivis deviennent privés** (ils étaient publics par défaut avant).
- Sur le site, deux parties séparées en haut des pages : **Mes coffres** (seulement les tiens) et **Communs**
  (les coffres partagés), plus **Tous** pour les admins. Accueil, inventaire, recherche, coffres, activité et joueurs
  suivent la partie choisie.
- Bouton **Rendre commun** sur la page d'un coffre, avec un avertissement (tout le serveur verra sa position).
  Seul celui qui a ajouté le coffre peut le rendre commun. **Rendre privé** sur le site, ou `/coffres prive` en jeu,
  et `/coffres prive tout` pour tous ses coffres.
- « Localiser en jeu » : le coffre s'éteint dès qu'on l'ouvre (60 s maximum, comme avant).

## 1.1.0 — Projets de construction

- **Nouvel onglet « Projets »** sur le site : envoie un plan `.litematic` (Litematica), `.schem` (WorldEdit 7+,
  versions 1 à 3), `.schematic` (MCEdit / ancien WorldEdit) ou `.nbt` (blocs de structure, Create).
- Coffres compte **tous les blocs nécessaires** : graphique des matériaux, total, stacks et shulkers.
  Les portes, lits et grandes fleurs comptent une fois, les dalles doubles deux fois, les torches murales sont des
  torches, les cultures donnent leurs graines, etc.
- **Projets communs ou solo**, avec les mêmes règles de visibilité que les coffres.
- **Coffres liés** à un projet (sur le site ou en jeu avec `/coffres projet lier <nom>` + clic droit) :
  **pourcentage réuni** en direct, ce qu'il manque bloc par bloc, ce qui se trouve **ailleurs** dans d'autres coffres,
  liste des manquants à copier.
- En jeu : `/coffres projet` (menu avec barres d'avancement), bouton « Projets » dans `/coffres`, clic sur un bloc
  manquant = les coffres qui en ont brillent.
- Accueil : les projets en cours. Page d'un objet : les projets qui en ont besoin.
- Nouveaux réglages `projects.max-file-mb` et `projects.max-per-player`, nouveau fichier `projects.json`.
- Les fichiers envoyés sont lus avec des limites (taille, décompression, volume) pour protéger la mémoire du serveur,
  et ne sont pas gardés : seule la liste des blocs l'est.

## 1.0.0

- Première version : suivi des coffres, historique, site pour téléphone et PC, « Localiser en jeu ».
