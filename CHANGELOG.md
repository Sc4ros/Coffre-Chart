# Changements

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
