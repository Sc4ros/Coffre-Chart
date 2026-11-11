# Changements

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
