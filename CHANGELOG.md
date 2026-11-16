# Changements

## 1.6.1 — Un graphique dans la fiche d'un bloc

- La fiche d'un bloc dans un projet montre un **anneau** : vert = déjà posé, bleu = dans les coffres liés, orange =
  sur les joueurs, gris = ce qui manque. Au centre, le pourcentage déjà **posé** (ou « réunis » si le projet n'est
  pas placé dans le monde).
- À côté, la légende avec le nombre et le pourcentage de chaque part (ils font toujours 100 % au total), et le détail :
  combien ne sont pas encore posés, combien sont en trop dans les coffres ou sur les joueurs, les stacks qui manquent.

## 1.6.0 — Chaque bloc dans son projet

- Sur la page d'un projet, **cliquer sur un bloc** ouvre sa fiche **dans ce projet** (avant, on arrivait sur la page
  générale de l'objet) :
  - il faut / déjà posés / dans les coffres liés / sur les joueurs / manque, avec les stacks et ce qui est en trop ;
  - **qui le porte** : les membres en ligne qui en ont sur eux, et combien ;
  - les **coffres liés** qui en contiennent (avec ce qui est rangé en shulker) ;
  - les **autres coffres** (pas liés au projet) qui en ont, pour savoir où aller le chercher ;
  - un bouton pour faire briller chaque coffre en jeu, et un lien « Voir partout sur le serveur ».
- Cartes des projets : posé, coffres et joueurs avec leurs couleurs, sur deux lignes au lieu d'un texte coupé sur
  téléphone ; le nom du fichier se coupe sans cacher la date.
- Une fenêtre ouverte (fiche d'un bloc, groupes…) se ferme quand on change de page ou qu'on fait « retour ».

## 1.5.1 — Inventaires partout

- Correctif : les blocs que portent les membres d'un projet n'étaient comptés que si le projet était **placé dans le
  monde** et que le joueur était **à moins de 64 blocs** du chantier. Ils comptent maintenant **partout**, que le projet
  soit placé ou non.
- Tout l'inventaire est lu : main gauche, **shulkers** et **bundles** compris (aussi une shulker tenue en main gauche).
- Sur le site, la tuile et la colonne « Inventaires » s'affichent pour tous les projets, avec qui porte quoi.
- Réglage : `projects.inventory-radius` est remplacé par `projects.count-inventories: true`.

## 1.5.0 — Groupes d'amis

- Un projet peut maintenant être **Commun** (tout le serveur), **Groupe d'amis** ou **Solo** (toi seul).
- **Groupes réutilisables** : bouton **Groupes** sur la page des projets. On crée un groupe une fois (« Les potes »),
  on y ajoute des joueurs déjà venus sur le serveur, puis on le choisit pour autant de projets qu'on veut.
  Ajouter quelqu'un au groupe lui ouvre tous ses projets.
- Un projet de groupe n'est vu que par les membres du groupe (et les admins). Les membres peuvent **lier leurs coffres**
  et **revérifier** les blocs posés ; seul le créateur du projet (ou un admin) le renomme, le place, remplace le
  fichier ou le supprime. Seul le créateur du groupe (ou un admin) ajoute, retire, renomme ou supprime le groupe ;
  chaque membre peut le **quitter**. Supprimer un groupe fait passer ses projets en solo.
- Les coffres privés restent privés : un coffre privé lié à un projet de groupe ne compte que pour son propriétaire,
  comme avant.
- **Inventaires près du chantier** : seuls les membres du projet comptent. Projet commun = tout le monde, projet de
  groupe = les membres du groupe, projet solo = son créateur.
- En jeu, les menus affichent « Groupe « Les potes » » et suivent les mêmes règles.
- Téléphone : les boutons en haut de la page des projets passent sous le titre au lieu de l'écraser.

## 1.4.0 — Les inventaires comptent aussi

- Nouvelle ligne **« Dans les inventaires »** pour les projets placés dans le monde : les blocs du plan que portent les
  joueurs **en ligne et près de la construction** (64 blocs autour, réglable) comptent dans l'avancement, shulkers
  comprises. Avant, sortir des blocs d'un coffre lié faisait baisser le pourcentage tant qu'ils n'étaient pas posés.
- L'ordre de calcul : d'abord ce qui est **posé**, puis les **coffres liés**, puis les **inventaires**. Un bloc en trop
  dans une poche ne compte jamais.
- Le site : une part **orange** dans l'anneau et dans la légende, une tuile « Dans les inventaires » (avec qui les
  porte), des barres en trois couleurs, « 5 posés + 0 en coffre + 6 sur les joueurs / 16 », une colonne « Inventaires »
  dans le tableau, et dans la carte « Dans le monde » : « Sur les joueurs près du chantier : Steve 46 · Alex 12 ».
- En jeu : les menus et le message de fin de vérification affichent aussi le pourcentage dans les inventaires.
- Nouveau réglage `projects.inventory-radius` (64 ; 0 = ne pas compter les inventaires).

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
