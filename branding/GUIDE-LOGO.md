# Coffres : guide du logo

## 1. Le logo

**L'idée :** la roue de ton inventaire, aux six couleurs d'Apple, avec le bloc que tu cherches qui en sort.
La roue reprend l'écran principal de l'app (tous tes objets, comptés) et le bloc, c'est l'objet trouvé par la recherche.

Il existe en deux finitions :

- **Verre** (style Liquid Glass) : seulement pour l'icône d'app (raccourci iPhone, icône du site installé) et les grands visuels.
- **Plate** : pour tout le reste (en-tête du site, favicon, petites tailles, impression, autocollants, t-shirt).

| Version | Quand l'utiliser |
|---|---|
| Logo horizontal | En-tête du site, bannières, partout où il y a de la largeur |
| Logo empilé | Formats carrés ou hauts : affiche, t-shirt, autocollant |
| Symbole seul | Petites places, avatar, quand le nom est déjà écrit à côté |
| Nom seul | Rarement, quand le symbole est déjà visible ailleurs sur la page |

## 2. Les fichiers

```
icone-app/      Icône en verre : 1024 px arrondie + version plein cadre (pour iOS/Android, qui arrondissent eux-mêmes)
icones-web/     À copier à la racine du site du plugin : favicon, apple-touch-icon, icônes 192/512, manifest
                + head-snippet.html à coller dans le <head> de chaque page
logo/           Logos horizontal et empilé : couleur, couleur-sur-sombre, noir, blanc, bleu
symbole/        Symbole seul (mêmes versions) + version « petit » pour 32 px et moins
nom/            Le mot « Coffres » seul, noir et blanc
presentation/   Présentation (HTML + diapos PNG) et maquette d'écran d'accueil iPhone
```

Les SVG sont les fichiers de référence (nets à toutes les tailles). Le texte est vectorisé : aucune police à installer.

## 3. Zone de protection

Laisse autour du logo un espace vide au moins égal à **la largeur du bloc carré** du symbole, sur les quatre côtés.
Cet espace grandit et rétrécit avec le logo : ne le mesure pas en pixels fixes.

## 4. Tailles minimum

| Version | Écran | Impression |
|---|---|---|
| Logo horizontal | 120 px de large | 30 mm |
| Logo empilé | 64 px de large | 18 mm |
| Symbole | 16 px (en dessous de 32 px, prends les fichiers « petit » ou le favicon) | 8 mm |

## 5. Couleurs

Ce sont les couleurs système d'Apple (iOS, mode clair), dans l'ordre de l'anneau en partant du haut :

| Nom | HEX | RGB |
|---|---|---|
| Vert | `#34C759` | 52, 199, 89 |
| Jaune | `#FFCC00` | 255, 204, 0 |
| Orange | `#FF8D28` | 255, 141, 40 |
| Rouge | `#FF383C` | 255, 56, 60 |
| Violet | `#CB30E0` | 203, 48, 224 |
| Bleu | `#0088FF` | 0, 136, 255 |
| Encre (bloc, texte) | `#1C1C1E` | 28, 28, 30 |
| Blanc | `#FFFFFF` | 255, 255, 255 |

Le bleu `#0088FF` est la couleur principale : boutons, liens, version une couleur.
Fond conseillé pour l'app et le site : encre `#1C1C1E` (mode sombre) ou blanc.

**Fonds autorisés :** couleur sur blanc ou gris très clair · couleur-sur-sombre sur encre ou noir · noir sur fond clair · blanc sur fond sombre ou sur photo calme.

## 6. Typographie

- **Nom du logo :** Inter Display SemiBold, déjà vectorisé dans les fichiers.
- **Site web :** police système Apple (`-apple-system, BlinkMacSystemFont, "Inter", system-ui, sans-serif`). Sur iPhone, ça affiche automatiquement la police d'Apple.
- Inter est sous licence libre (SIL OFL), utilisable dans un logo. La police d'Apple (SF Pro) n'est pas autorisée dans un logo, d'où ce choix.

## 7. À ne pas faire

- Déformer, étirer ou faire pivoter le logo.
- Changer l'ordre ou les couleurs de l'anneau, ou déplacer le bloc.
- Mettre l'effet verre sur le logo à plat (en-tête, petites tailles) : le verre, c'est pour l'icône.
- Ajouter des ombres, des contours ou des dégradés au logo à plat.
- Le poser sur un fond chargé sans fond uni derrière.
- Retaper « Coffres » avec une police : utilise toujours les fichiers.

## 8. Notes techniques

- Entre deux segments de couleur, chaque segment déborde de 0,6° sous le suivant. C'est voulu : ça évite un fin trait blanc entre les couleurs.
- Les extrémités de l'anneau sont légèrement arrondies (rayon 6 sur 256) pour que le verre accroche la lumière.
- Le logo n'a pas fait l'objet d'une recherche d'antériorité de marque. Pour un serveur entre amis, ce n'est pas un problème. Pour un usage commercial, fais vérifier.
