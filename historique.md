# Historique du projet Coffres

Ce fichier raconte tout ce qui a été fait sur le plugin, pourquoi, et comment reprendre le travail.
**Si tu es une IA qui reçoit ce projet dans une nouvelle conversation : lis ce fichier en entier avant de toucher au code,
puis ajoute une entrée dans la « Chronologie » à chaque changement.**

Le mode d'emploi pour les joueurs et les admins est dans [`README.md`](README.md), la liste courte des versions dans
[`CHANGELOG.md`](CHANGELOG.md). Ici, on garde le reste : les demandes, les décisions, ce qui a été abandonné, les pièges.

---

## Où en est le projet

- **Version actuelle : 1.6.1** (`release/Coffres-1.6.1.jar`).
- Compile, et les **24 tests** passent (BuildTest 6, CoreTest 6, GroupTest 3, ProjectTest 7, VisibilityTest 2).
- Le **site** a été testé dans un navigateur avec les données de démonstration (`./gradlew runDev`), sur téléphone et PC.
- **Pas encore testé sur un vrai serveur Minecraft** : tout ce qui se passe en jeu (voir « Pas encore fait » en bas).

---

## Le projet en bref

- **Coffres** est un plugin **Paper 26.2** (`api-version: '26.1'`) qui tourne avec **Java 25**.
- Il suit le contenu des coffres, tonneaux et boîtes de Shulker que les joueurs ajoutent, et garde l'historique :
  qui a déposé ou pris quoi.
- Il embarque son propre **site web** pour téléphone et PC, servi par le serveur HTTP intégré à Java.
  Le site est en JavaScript sans framework, et le plugin n'a **aucune dépendance**.
- Tout est **en français** : messages en jeu, site, documentation.
- Le site permet :
  - de voir ses coffres, l'inventaire total et l'historique ;
  - de chercher un objet ;
  - de faire briller un coffre en jeu (« Localiser en jeu ») ;
  - de gérer des **projets de construction** à partir de plans Litematica ou schematic.

---

## Ce que l'utilisateur veut (à respecter)

1. **Livraison : toujours un zip GitHub complet**, `Coffres-github.zip`, posé à côté du dossier `Coffres/`.
   - Le dossier racine du zip s'appelle `Coffres/`, et `gradlew` doit y rester exécutable.
   - Le `.jar` compilé va dans `release/`, et l'ancien jar est supprimé.
   - Il faut mettre à jour à chaque fois : README, CHANGELOG, **ce fichier**, et la version dans `build.gradle.kts`.
2. **Confidentialité des coffres.** Un coffre est **privé dès qu'on l'ajoute** et ne doit jamais être visible par les
   autres, même une demi-seconde. Seul son propriétaire peut le rendre **commun**, depuis le site.
   Il n'y a aucune option « public » en jeu. La raison donnée par l'utilisateur :
   > « imaginons la personne qui à la base secrète quelque part, qui voit son coffre qui est en public pendant une
   > demi-seconde, et bah, y a des gens qui peuvent voir les coordonnées, y aller »
3. **Le site sépare « Mes coffres » et « Communs »** : les coffres des autres ne doivent pas être mélangés avec les siens.
4. **Le nom reste « Coffres ».** « Coffre-Chart » a été proposé puis abandonné par l'utilisateur.
5. Les explications pour l'utilisateur doivent être en français simple, sans jargon.
6. **Tenir ce fichier à jour** à chaque modification.

---

## Chronologie

Les dates sont à l'heure française.

### Avant le 11 novembre 2026 : version 1.0.0

Projet reçu dans un `Coffres-github.zip`. Il contenait déjà :
- le suivi des coffres et l'historique, avec détection des entonnoirs et autres machines ;
- le site (accueil, inventaire, recherche, coffres, activité, joueurs) et « Localiser en jeu » ;
- la connexion au site par un code envoyé dans le chat du jeu ;
- le kit de logo dans `branding/`.

À l'époque, les coffres étaient **publics par défaut** (réglage `tracking.public-by-default`).

### 11 novembre 2026 : version 1.1.0, les Projets de construction

**Demande :**
- envoyer un fichier Litematica ou schematic ;
- des projets communs ou solo ;
- le nombre total de blocs avec un graphique ;
- lier des coffres au projet ;
- voir ce qui est déjà réuni, avec un pourcentage.

**Fait : formats de plans lus**
- `.litematic` (toutes les régions) ;
- `.schem` Sponge v1, v2 et v3 ;
- `.schematic` MCEdit (anciens numéros de blocs traduits) ;
- `.nbt` (structures).

**Fait : lecture des plans** (`project/Nbt.java`, `SchematicReader.java`)
- Le NBT est lu sans bibliothèque, en détectant gzip et zlib.
- Des limites protègent la mémoire : 192 Mo une fois décompressé, 400 millions de blocs de volume, et au plus
  2 fichiers lus en même temps.
- Le fichier envoyé n'est pas gardé : seule la liste des blocs l'est, dans `projects.json`.

**Fait : conversion des blocs en objets à poser** (`project/BlockItems.java`)
- Les moitiés hautes (portes, lits, grandes fleurs) sont ignorées.
- Une dalle double compte 2 dalles.
- Bougies, cornichons de mer, œufs, fleurs et neige sont multipliés par leur nombre.
- Une torche murale compte comme une torche, et un pot de fleurs donne un pot plus la fleur.
- Une source d'eau ou de lave compte comme un seau. Les cultures donnent leurs graines.

**Fait : le reste**
- Avancement : `project/ProjectProgress.java` calcule ce qu'il faut, ce qu'on a, ce qui est ailleurs, et les stacks.
- Site : onglet **Projets** (`web/js/pages/projects.js`), avec la roue des matériaux, l'anneau de pourcentage, la liste
  des manquants à copier, et l'envoi par glisser-déposer.
- En jeu : `/coffres projet` (menu avec barres d'avancement) et `/coffres projet lier <nom>` + clic droit.
- Réglages `projects.max-file-mb` (20) et `projects.max-per-player` (15).
- Exemples dans `docs/exemples/` : une petite maison en `.litematic` et en `.schem`, de 411 blocs.

**Vérifié :** les deux exemples donnent 411 blocs ; avec des coffres liés, l'avancement affiche 54 % ; un mauvais
fichier donne un message d'erreur clair en français.

### 11 novembre 2026 : nom du plugin (abandonné)

L'utilisateur a demandé des idées de nom, a dit « Coffre-Chart », puis : « abandonne ce que je t'ai dit avant ».
**Rien n'a été renommé.**

### 11 novembre 2026 : « Localiser en jeu » s'éteint quand on ouvre le coffre

**Question :** quand est-ce que la surbrillance s'arrête ? Avant, c'était au bout de 60 secondes seulement.

**Demande :** qu'elle s'arrête quand on ouvre le coffre.

**Fait :**
- `Locator.opened(player, chestId)` est appelé depuis `ChestTracker.onOpen`.
- Il éteint le coffre ouvert. Quand plusieurs coffres brillent, chacun s'éteint quand on l'ouvre.
- Les 60 secondes restent une limite maximale.

### 11 novembre 2026 : première idée de visibilité (abandonnée et annulée)

L'utilisateur voulait d'abord qu'on ne voie que l'activité des coffres liés aux projets communs. Le travail a commencé
(un champ dans `ProjectStore`), puis l'utilisateur a dit d'abandonner. **Ce changement a été annulé** : `ProjectStore`
est revenu exactement comme en 1.1.0.

À ce moment-là, la réponse à « comment passer ses coffres de public à privé » était `/coffres prive`, en regardant
le coffre.

### 11 et 12 novembre 2026 : version 1.2.0, coffres privés, « Mes coffres » et « Communs »

**Demande :**
- les coffres sont automatiquement privés, sans option « public » ;
- le site a une partie personnelle et une partie « coffres communs » ;
- on rend un coffre commun depuis le site.

**Fait : un coffre est toujours privé à l'ajout**
- Dans `ChestTracker`, un nouveau coffre a `c.pub = false`.
- Le réglage `public-by-default` a été supprimé.
- `/coffres public` n'existe plus : la commande renvoie vers le site.

**Fait : sens de `pub`**
- `StoredChest.pub` veut maintenant dire **« commun »**.
- Seul le **propriétaire** peut le passer à `true`, même un admin ne peut pas. `WebServer` répond 403 sinon.

**Fait : migration unique** (`DataStore`)
- `chests.json` a maintenant `"version": 2`.
- Au chargement d'un fichier plus ancien, `migratePrivate()` met **tous les coffres en privé**, une seule fois.
- Test : `VisibilityTest.containersFromOlderVersionsBecomePrivateOnce`.

**Fait : les parties du site** (`AuthManager.Session`, `Api.java`)
- Chaque requête a une vue : `mine` (Mes coffres), `common` (Communs), ou aucune (tout ce qu'on a le droit de voir).
- Les admins ont en plus une partie **Tous**.
- `Api.visible(c, s)` applique la vue. `Api.visibleAll(c, s)` (`pub || admin || propriétaire`) sert pour les projets.
- L'historique, la page Joueurs, les graphiques et les coffres retirés passent tous par `visibleIds`.
  L'activité d'un coffre privé n'est donc **jamais** visible par les autres joueurs.

**Fait : côté site**
- Un sélecteur « Mes coffres (n) / Communs (n) / Tous » apparaît en haut de l'accueil, de l'inventaire, de la
  recherche, des coffres, de l'activité et des joueurs.
- Le choix est retenu sur l'appareil (`currentView()` et `withView()` dans `web/js/api.js`).
- Sur la page d'un coffre :
  - un bouton **Rendre commun** demande une confirmation qui annonce que tout le serveur verra sa position, son
    contenu et son historique ;
  - un bouton **Rendre privé** fait l'inverse ;
  - un badge « Commun » ou un cadenas indique l'état.

**Fait : commandes en jeu et projets**
- `/coffres prive` met en privé le coffre qu'on regarde, et `/coffres prive tout` met tous ses coffres en privé.
- Dans un projet commun, les coffres privés de quelqu'un ne comptent que pour lui.

**Vérifié :**
- connecté en joueur normal sur le site de démo, « Mes coffres » et « Communs » sont bien séparés ;
- **Rendre commun** puis **Rendre privé** fonctionnent ;
- rendre commun le coffre d'un autre est refusé (403) ;
- 15 tests passent.

### 12 novembre 2026 : questions sur la mise à jour (réponses, pas de code)

**Question :** peut-on juste remplacer le jar, et est-ce que ça change les données des joueurs ?

**Réponse :** oui, il suffit de remplacer le jar.
- Arrêter le serveur, supprimer l'ancien jar, mettre le nouveau et redémarrer (pas de `/reload`).
- Tout est gardé : coffres, contenu, noms, zones, historique, projets, connexions au site, `config.yml`.
- Seul changement : au premier démarrage en 1.2.x, tous les coffres passent en privé, une seule fois.
- Conseil donné : copier `plugins/Coffres/` avant.
- La ligne `public-by-default` restée dans un vieux `config.yml` est simplement ignorée.

**Question :** les autres joueurs voient-ils l'activité de mes coffres privés ?

**Réponse :** non, sauf les admins dans la partie « Tous ».
- Attention : rendre un coffre commun montre aussi tout son historique passé.
- Le remettre en privé cache de nouveau cet historique.

### 12 novembre 2026 : version 1.2.1, correctif d'affichage

**Problème signalé, avec une capture iPhone :** sur la page d'un coffre, les cases vides étaient plus hautes que les
autres, et la grille se déformait aussi sur PC.

**Cause :** les cases vides avaient la classe CSS `empty`, déjà utilisée par le bloc « rien ici » (`.empty`, avec une
marge intérieure de 36 px). Cette marge agrandissait les cases.

**Fait :**
- Les cases vides s'appellent maintenant `.slot.blank` (`app.css` et `chests.js`).
- Une bande floutée de la hauteur de la barre d'état (`body::before`, `env(safe-area-inset-top)`) empêche la page de
  passer sous l'heure quand le site est installé sur iPhone. Elle ne prend aucune place dans un navigateur normal.

**Vérifié :** sur le site de démo, toutes les cases sont carrées et égales, en 375 px de large comme en 1366 px. La
bande a été vérifiée avec une fausse barre d'état, pas sur un vrai iPhone. 15 tests passent.

### 13 novembre 2026 : création de ce fichier

L'utilisateur a demandé de tout noter dans `historique.md` pour pouvoir le redonner dans une autre conversation.
Le fichier est dans le zip, à la racine du projet. **Le code et le jar n'ont pas changé : on reste en 1.2.1.**

### 15 et 16 novembre 2026 : version 1.3.0, les blocs déjà posés

**Demande :** que les projets comptent aussi les blocs déjà posés dans le monde, pas seulement ceux des coffres liés.
On donne la position et la rotation du plan dans le monde, le serveur regarde quels blocs sont posés, et le site
affiche le pourcentage posé et le pourcentage dans les coffres.

**Choix faits avec l'utilisateur (questions posées avant de coder) :**
- placement **sur le site et en jeu** (formulaire monde + x/y/z + rotation + miroir, bouton « Utiliser ma position en
  jeu », et `/coffres projet placer <nom> [rotation] [miroir]`) ;
- un bloc compte s'il est du **bon type** au bon endroit, **sans vérifier l'orientation** (sinon on risque de ne jamais
  atteindre 100 %) ;
- vérification **automatique + bouton** : complète quand on place / revérifie / toutes les 10 min, puis en direct ;
- affichage **posé + coffres + manque** : ce qui est posé n'est plus demandé dans les coffres.

**Fait : garder le plan complet** (`project/Plan.java`, `SchematicReader.java`)
- Avant, seul le nombre de blocs était gardé. Maintenant, pendant la lecture, un `Plan.Builder` garde la position de
  chaque bloc qui demande un objet (pas l'air, pas la moitié haute des portes…).
- Format en mémoire : positions triées (`int[]`, index `(y × sz + z) × sx + x`) + numéro d'état (`char[]`), soit environ
  6 octets par bloc. Limite `projects.max-check-blocks` (10 millions par défaut) : au-delà, pas de vérification,
  les matériaux marchent toujours.
- Fichier `plugins/Coffres/plans/<id>.plan` (gzip, fait maison). `Project.planBlocks` dit combien de blocs sont gardés
  (0 = pas de vérification, avec la raison dans `Project.planNote`).
- Litematica : les positions sont relatives à **l'origine du schéma** (celle du menu de placement de Litematica),
  y compris les régions à taille négative. Sponge / structures : origine = coin le plus bas.
- `.schematic` MCEdit : **pas de vérification** (on n'a que des anciens numéros de blocs), message clair sur le site.

**Fait : l'emplacement** (`model/Placement.java`)
- `world, x, y, z, rot (0/90/180/270), mirror (none/lr/fb)`, gardé dans `projects.json`.
- Même calcul que Minecraft et Litematica : miroir d'abord (`lr` = Left-Right inverse z, `fb` = Front-Back inverse x),
  puis rotation horaire autour de l'origine (90° : `(x, z) → (−z, x)`). `toPlan` est l'inverse exact (testé).

**Fait : les blocs posés** (`project/Build.java`, `store/PlanStore.java`, `game/BuildScanner.java`)
- `Build` = un plan placé : un `BitSet` des blocs posés et le compte par état. `placedItems()` retransforme en objets
  avec les mêmes règles que la liste des matériaux (dalle double = 2 dalles…).
- `PlanStore` garde en mémoire seulement les projets placés, et enregistre les blocs posés dans `plans/<id>.placed`
  avec une « signature » de l'emplacement : si on déplace le projet, on repart de zéro.
- `BuildScanner` (fil principal) : vérification complète à `projects.check-blocks-per-tick` blocs par tick (20 000),
  sans jamais charger de zone ; les blocs des zones non chargées gardent leur dernier état et sont comptés dans
  « unloaded ». Événements suivis (place, casse, seaux, feu, explosions, pistons, neige/glace, feuilles, entités…) :
  la position et les blocs juste au-dessus/en dessous sont revus au tick suivant.
- Au démarrage, les plans des projets placés sont chargés en arrière-plan puis revérifiés.

**Fait : le calcul** (`project/ProjectProgress.java`)
- `Line(id, need, placed, have, elsewhere, stack)` : `placed` ≤ need ; `useful = min(have, need − placed)` ;
  `missing = need − placed − have`. `percent` = posé + coffres ; `placedPercent`, `chestPercent` en plus.

**Fait : le site** (`web/js/pages/projects.js`, `charts.js`, `app.css`)
- Anneau à deux couleurs (`ring2` : vert = posé, bleu = coffres) + légende, tuile « Déjà posés », barres de blocs en
  deux couleurs, « 19 posés + 12 en coffre / 45 ».
- Carte **« Dans le monde »** : monde, origine, rotation, blocs posés, état de la vérification (barre pendant qu'elle
  tourne, la page se recharge toute seule), avertissement pour les zones non chargées, boutons **Revérifier** et
  **Déplacer** (le formulaire a aussi **Retirer du monde**).
- Routes `POST /api/project/{id}/placement` et `/scan` ; `GameBridge` a `worlds()` et `rescan()`.

**Fait : en jeu** : `/coffres projet placer` et `/coffres projet verifier` (avec complétion), message de fin de
vérification avec les pourcentages, menus avec « X % posé · Y % dans les coffres ».

**Démo** : `./gradlew runDev` crée en plus le projet « Petite maison » (le `.litematic` d'exemple) placé dans le monde ;
le faux jeu « construit » 10 % de plus à chaque « Revérifier ».

**Vérifié :**
- 6 nouveaux tests (`BuildTest`) : rotation/miroir réversibles, les 411 blocs de la maison retrouvés depuis le plan,
  le `.litematic` et le `.schem` d'exemple mettent exactement les mêmes blocs aux mêmes places, sauvegarde du plan et
  des blocs posés, calcul posé + coffres.
- Sur le site de démo (PC et téléphone 375 px) : placer avec sa position, saisir des coordonnées, changer de monde,
  revérifier, retirer du monde (on retombe sur les 54 % d'avant), ancien projet sans plan (message + bouton).
- **Pas testé sur un vrai serveur Paper** : le scan dans le monde, les événements et les commandes en jeu.

**Piège rencontré :** dans `projects.js`, une variable `built` existait déjà (page construite) ; en déclarer une autre
plus bas cassait toute la page (« Cannot access 'built' before initialization »). Renommée `finished`.

### 16 novembre 2026 : version 1.4.0, les inventaires comptent aussi

**Retour de l'utilisateur après avoir publié la 1.3.0 :** « tout marche correctement », mais sur une grosse
construction le total est passé de 54 % à 52 % quand ils ont posé des blocs, ce qui leur semblait illogique.

**Explication donnée :** le calcul posé + coffres ne baisse pas quand on pose un bloc sorti d'un coffre lié. La baisse
venait des blocs sortis des coffres et encore dans les inventaires (non comptés), ou de blocs posés qui ne collent pas
au plan (mauvais endroit, mauvais type, zone non chargée).

**Demande :** ajouter une ligne « dans les inventaires », et une part pour elle dans le graphique.

**Fait :**
- `Build.setCarried / carried / carriers` : les objets du plan portés par les joueurs près du chantier.
- `BuildScanner.inventories()`, une fois par seconde : joueurs en ligne du même monde, hors spectateurs, à moins de
  `projects.inventory-radius` blocs (64) de la boîte du chantier ; inventaire + main gauche, shulkers et bundles lus
  avec `Snapshot` ; seuls les objets dont le plan a besoin sont gardés.
- `ProjectProgress.Line` a `carried` ; `inInventories() = min(carried, need − placed − useful)`. Ordre : posé, coffres,
  inventaires. `carriedRatio`, `carriedPercent` ; `chestPercent` est maintenant calculé sur posé + coffres.
- Site : `ring2(..., {inv})` ajoute un arc orange (`--warn`), légende « Inventaires », tuile « Dans les inventaires »
  (pourcentage + qui), 6 tuiles sur 3 colonnes (`.tiles.six`), barres à 3 couleurs, colonne du tableau, ligne
  « Sur les joueurs près du chantier » dans la carte « Dans le monde ».
- Menus en jeu et message de fin de vérification : pourcentage des inventaires.
- Choix fait sans demander : seulement les projets **placés** dans le monde (il faut savoir où est le chantier), et
  seulement les joueurs **près** du chantier.

**Vérifié :** nouveau cas dans `BuildTest` (posé 5 + 12 en poche pour 10 → 5 comptés, 0 manquant) ; 21 tests passent.
Sur le site de démo : 39 % posé + 18 % coffres + 14 % inventaires = 71 %, anneau à trois couleurs, tuiles lisibles.
**Pas testé sur un vrai serveur Paper.**

### 16 novembre 2026 : version 1.5.0, les groupes d'amis

**Demande :** que seuls les membres du projet comptent avec leur inventaire, et pouvoir faire des projets avec des
groupes d'amis : « soit avec tout le monde, soit avec des groupes d'amis, soit tout seul ».

**Choix faits avec l'utilisateur :**
- groupes **réutilisables** (créés une fois, choisis pour plusieurs projets), pas des membres projet par projet ;
- **ajout direct** par le créateur du groupe (joueur déjà venu sur le serveur), pas d'invitation ; on peut quitter ;
- un coffre **privé** lié à un projet de groupe ne compte **que pour son propriétaire** (comme avant) ;
- les membres peuvent **lier leurs coffres et revérifier** ; le reste (nom, placement, fichier, suppression, groupe)
  reste au créateur du projet ou aux admins.

**Fait :**
- `model/Group` (créateur + `members`), `store/GroupStore` → `groups.json` : groupes et pseudos des joueurs déjà venus
  (`seen()` à chaque connexion, au démarrage pour les joueurs en ligne, et rempli au départ avec les sessions du site).
  Limites : 20 groupes par joueur, 50 membres par groupe. `GroupStore` ne prend jamais d'autre verrou.
- `Project.group` : `shared` = commun ; sinon `group` = groupe ; sinon solo. `ProjectStore.setVisibility`,
  `clearGroup` (groupe supprimé → projets en solo), `countInGroup`.
- `Api.canSee` / `canLink` ne sont plus statiques (ils ont besoin des groupes) ; `visibility()` → "all", "group",
  "solo" ; `groups()` pour le site. `WebServer` : `checkVisibility`, routes `groups`, `groups/create`,
  `group/{id}[/add|/remove|/delete]`, `vis` + `group` à l'envoi d'un plan, `{visibility, group}` dans « Modifier ».
- `ProjectsInGame.canSee/canLink/visibilityText/people` (plus statiques), menus mis à jour.
- `BuildScanner.inventories()` : seulement les joueurs de `people(projet)` (null = tout le monde pour un projet commun).
- Site : `js/pages/groups.js` (`visibilityPicker`, `groupsSheet`), bouton **Groupes** sur la page des projets,
  « Groupe « … » · par … » sur les cartes. Correctif téléphone : les boutons d'en-tête passent sous le titre.
- Démo : Alex a un groupe « Les potes » avec Steve, et sa ferme à fer est un projet de ce groupe.

**Vérifié :** `GroupTest` (pseudos, ajout, changement de pseudo suivi, qui voit un projet de groupe, suppression du
groupe, retour en commun) ; 23 tests passent. Sur le site de démo : créer un groupe, ajouter Lea_mc et Alex, refus de
« Bob » jamais venu, passer la petite maison en groupe ; connecté en Alex (non admin) : il voit les projets de ses
groupes, peut lier (`canLink`), ne peut pas modifier le projet de Steve (403), ne peut pas quitter son propre groupe.
**Pas testé sur un vrai serveur Paper.**

### 16 novembre 2026 : version 1.6.1, un graphique dans la fiche d'un bloc

**Demande (avec une capture du « Béton blanc ») :** « mettre une charte de ce qui est posé et ce qui n'est pas posé,
parce que là on a que des nombres, c'est pas trop visuel ».

**Fait :** dans `itemSheet`, les tuiles sont remplacées par un bloc `.ish-chart` : `ring2` (même anneau que le projet,
nouvelle option `text` pour le centre = % posé, ou % réuni si le projet n'est pas placé) et une légende `.ish-part`
(couleur, nom, nombre, %, détail). Les pourcentages sont arrondis par « plus grands restes » pour faire 100 %, sans
jamais arrondir à 100 % une part incomplète ; une part non nulle sous 1 % affiche « < 1 % ».

**Vérifié :** site de démo PC et téléphone (bûche de chêne : 37 % posé + 63 % coffres ; dalle en sapin : 0 % posé,
34 % joueurs, 66 % manque). 24 tests passent. **Pas testé sur un vrai serveur.**

### 16 novembre 2026 : version 1.6.0, la fiche d'un bloc dans son projet

**Demande :** dans un projet, cliquer sur un bloc menait à la page générale de l'objet ; on ne savait pas qui avait ce
bloc dans son inventaire, ni combien étaient posés. « Ça devrait mettre le bloc dans ce projet-là. » Puis :
« Améliore le site si tu peux aussi. »

**Fait :**
- `Build.Carrier` garde maintenant le détail par objet (`what`), rempli par `BuildScanner.inventories()`.
- `Api.projectItem(session, projet, objet)` + route `GET /api/project/{id}/item/{objet}` : chiffres de la ligne,
  coffres liés visibles qui en ont (`chests`), 10 autres coffres visibles non liés (`others`, `othersCount`), joueurs
  qui le portent (`who`). Mêmes règles de visibilité que le reste (coffres privés des autres jamais montrés).
- Site : `itemSheet(p, l)` dans `projects.js` ; les lignes de blocs sont des boutons qui l'ouvrent (lien « Voir partout
  sur le serveur » vers l'ancienne page).
- Améliorations : `sheet()` (util.js) se ferme au changement de page (`hashchange`), sinon la fenêtre restait par-dessus
  la nouvelle page ; cartes de projet avec pastilles de couleur (posé / coffres / joueurs) qui passent à la ligne, et
  pied de carte où seul le nom du fichier est coupé.

**Vérifié :** `GroupTest` complété (qui porte quoi, bloc hors projet → 404) ; 24 tests passent. Site de démo, PC et
téléphone : dalles en sapin (Steve 40), bûche de chêne (coffre lié « Bois » + coffre « Shulkers » non lié), « null »
parasite corrigé, retour arrière qui ferme la fenêtre. **Pas testé sur un vrai serveur.**

### 16 novembre 2026 : version 1.5.1, les inventaires comptent partout

**Retour :** « mon ami Arthur a de la terre cuite qu'il faut et ça ne la met pas dans l'inventaire », avec l'idée que
seuls les objets sortis d'un coffre du projet seraient comptés, et la demande de regarder aussi dans les shulkers.

**Cause trouvée :** l'inventaire était bien lu directement (shulkers et bundles compris, quelle que soit l'origine des
objets). Mais il ne comptait que pour un projet **placé dans le monde**, et seulement pour les joueurs **à moins de
64 blocs** du chantier. Arthur était donc hors de cette zone, ou le projet n'était pas placé.

**Fait :**
- Les objets portés ne sont plus rangés dans `Build` mais dans `PlanStore` (`Carried`, `carried(id)`, `setCarried`,
  `keepCarried`), pour **tous** les projets, placés ou non.
- `BuildScanner.inventories()` tourne chaque seconde même sans projet placé. Chaque joueur en ligne (sauf spectateur)
  est lu une fois (rangement + main gauche, shulkers et bundles dedans), puis compté pour chaque projet dont il est
  membre (`people()`), sans limite de distance ni de monde. On ne garde que les objets de `Project.materials`.
- Réglage `projects.count-inventories` (true par défaut) à la place de `inventory-radius` (l'ancienne clé est ignorée).
- Site : anneau à 3 couleurs, tuile « Dans les inventaires », colonne « Inventaires » et « Sur les joueurs en ligne »
  pour tous les projets ; « Posé » seulement pour un projet placé. `carriers` est maintenant à la racine du détail.

**Vérifié :** nouveau test `GroupTest.inventoriesCountEvenWhenTheProjectIsNotPlaced` ; 24 tests passent. Site de démo :
petite maison retirée du monde, les 58 blocs de Steve et Alex restent comptés (14 %). **Pas testé sur un vrai serveur.**
- Pas fait (non demandé) : compter le coffre de l'Ender des joueurs.

---

## Notes techniques pour reprendre

### Compiler

- Le projet vise Java 25 (`JavaLanguageVersion.of(25)` dans `build.gradle.kts`, à **laisser à 25** dans le zip livré).
- Sur le PC de l'utilisateur, seul le **JDK 26** est installé. Pour compiler là-bas :
  1. copier le projet dans un dossier temporaire ;
  2. dans la copie, remplacer `JavaLanguageVersion.of(25)` par `of(26)` ;
  3. lancer `./gradlew clean build --no-daemon` ;
  4. le jar reste compilé pour Java 25 grâce à `options.release = 25`.
- Vérifier que le fichier `.class` commence par la version 69 (Java 25), puis copier le jar dans `release/`.
- GitHub Actions (`.github/workflows/build.yml`) compile à chaque push et joint le jar aux Releases.

### Voir le site sans Minecraft

- `./gradlew runDev` lance `fr.coffres.dev.DevServer` (dans `src/test`) avec les fausses données de `MockData` :
  17 coffres, 5 joueurs, 3 projets, de l'historique et des mouvements en direct.
- Le site tourne sur le port 8125. La console affiche un lien de connexion `#/connexion?code=XXXXXX` pour Steve.
  On peut aussi demander un code pour Steve ou Alex sur la page de connexion.
- Les fichiers du site sont relus à chaque requête : on les modifie, puis on recharge la page.
- Quand on arrête la tâche Gradle, le processus Java du DevServer peut rester ouvert et garder le port 8125.
  Il faut l'arrêter par son numéro de processus, en vérifiant que c'est bien lui.

### Où est quoi

- Le README, section « Organisation du code », décrit les dossiers. Les points importants :
  - `store/DataStore.java` : coffres, historique (`history.jsonl`), coffres retirés, migration (`FORMAT = 2`).
  - `store/ProjectStore.java` : `projects.json`.
  - `web/Api.java` : toutes les réponses du site et les règles de visibilité.
  - `web/WebServer.java` : les routes, l'envoi des plans, le refus 403 pour « commun » si on n'est pas propriétaire.
  - `web/AuthManager.java` : codes, jetons (seule leur empreinte SHA-256 est gardée), `Session.view(...)`.
  - `track/ChestTracker.java` : ajout et retrait de coffres, ouverture (qui éteint « Localiser »), comparaison du contenu.
  - `game/Locator.java` : la surbrillance (copie lumineuse visible par un seul joueur).
  - `src/main/resources/web/` : le site.
    - `js/api.js` : appels à l'API et vue choisie.
    - `js/ui.js` : en-têtes, sélecteur de vue.
    - `js/pages/*.js` : une page par fichier.
    - `app.css` : les styles.
    - `sw.js` : le service worker. Il va d'abord chercher les pages sur le réseau, donc pas besoin de changer sa
      version à chaque mise à jour.
- **Ordre des verrous :** toujours `DataStore` d'abord, puis `ProjectStore`, pour éviter les blocages.

### Pièges déjà rencontrés

- **Les classes CSS sont globales.** `.empty` est le bloc « rien ici ». Ne pas réutiliser des noms génériques
  (`empty`, `top`, `row`…) pour autre chose sans vérifier dans `app.css`. C'était le bug de la 1.2.1.
- `replaceChildren(...)` avec des valeurs `null` affiche le texte « null » : filtrer avec `.filter(Boolean)`.
- Dans les fichiers JS, une apostrophe française dans une chaîne entre `'…'` casse le script. Utiliser `"…"` ou `\'`,
  puis vérifier avec `node --check`.
- Pour fabriquer le zip, utiliser un script qui garde `gradlew` exécutable et ignore `build/` et `.gradle/`.
  Comparer ensuite le zip décompressé avec le dossier (`diff -rq`).

---

## Pas encore fait, pas encore testé

- **Rien n'a été essayé sur un vrai serveur Paper.** À tester en jeu :
  - les groupes (1.5.0) : un membre voit les projets du groupe dans `/coffres projet`, peut lier ses coffres ;
  - les inventaires près du chantier (1.4.0) : que le pourcentage ne baisse plus quand on sort des blocs ;
  - les blocs déjà posés (1.3.0) : `/coffres projet placer`, que l'origine et la rotation collent bien à Litematica,
    la mise à jour en direct quand on pose/casse, le temps de calcul sur un gros plan ;
  - les menus et `/coffres projet` ;
  - le mode liaison à un projet ;
  - l'extinction de « Localiser » à l'ouverture ;
  - `/coffres prive` et `/coffres prive tout` ;
  - la migration en privé sur de vraies données.
- La bande sous la barre d'état n'a pas été vue sur un vrai iPhone. Le site n'a été testé que dans Chromium.
- Blocs posés : l'orientation n'est volontairement pas vérifiée. Les changements faits par WorldEdit ne sont vus qu'à
  la vérification suivante (toutes les 10 minutes ou avec « Revérifier »).
- Groupes : pas de commande en jeu pour les gérer (tout se fait sur le site).
- Idée non demandée et non faite : afficher en jeu le contour du projet placé, ou les blocs manquants en surbrillance.
- Idée non demandée et non faite : un bouton pour rendre communs plusieurs coffres d'un coup. Aujourd'hui, c'est un
  par un sur la page de chaque coffre.
