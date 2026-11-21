# AI Player — mod Fabric (client) : une IA locale joue à ta place

Mod **client** Fabric pour Minecraft 26.x (cible : **26.3**, Java 25). Une IA hébergée en local (LM Studio, modèle ~12B
type Gemma) reçoit ce que voit le joueur (blocs, entités, inventaire, chat…) et répond par une liste d'actions
(marcher, miner, récolter, crafter, taper, poser, parler…). Fonctionne en solo et sur serveur.

**Contrôle direct** : le mod ne simule AUCUNE touche clavier pour le déplacement. Il remplace l'`input` du joueur
(`AiInput`). Donc l'IA continue de jouer avec le **menu Échap ouvert**, la fenêtre sans focus, un inventaire ouvert…
et le clavier de l'humain n'a plus d'effet sur le perso tant que l'IA est ON.

---------------------------------------------------------------------------------------------------------------------

## 1. Utilisation (humain)

1. LM Studio : charge le modèle, démarre le serveur local (port 1234). **Mets le contexte à ≥ 16k tokens** (le prompt
   + la perception font ~5-8k tokens).
2. Lance le jeu une fois : `config/aiplayer.json` est créé.
3. En jeu : `/ai on`, `/ai off`, `/ai tell <ordre>`, `/ai status`, `/ai reload`.
4. **Astuce** : après `/ai on`, appuie sur **Échap**. Tu peux alors utiliser souris/clavier ailleurs, le perso continue
   (en solo, le mixin empêche le menu Échap de geler le monde ; désactivable avec `keepRunningInPause=false`).
   Ne rouvre pas le jeu avec la souris pendant que l'IA joue : le mouvement de souris tourne la tête du perso.

Config (`aiplayer.json`) : `url`, `model`, `historySize`, `scanRadius`, `requestTimeoutSeconds`, `maxActionsPerPlan`,
`showThoughts`, `allowAttackPlayers`, `keepRunningInPause`, `temperature` (0.2-0.5 conseillé pour un 12B, -1 = non envoyé),
`extraSystemPrompt`.

## 2. Build

**GitHub Actions** (`.github/workflows/build.yml`) compile à chaque push : le workflow prend les versions exactes
(loader, loom, fabric-api, wrapper Gradle) dans la branche `26.3` de `FabricMC/fabric-example-mod`, y superpose `src/`,
`build.gradle`, `settings.gradle`, `gradle.properties`, puis `./gradlew build` (JDK 25). Le jar est publié sur la branche
**`build-output`** (`git fetch origin build-output` puis `git show origin/build-output:aiplayer-1.1.0.jar > aiplayer.jar`)
et en artefact `aiplayer-jar`. Un tag `v*` crée aussi une Release GitHub.
Les `CHANGE_ME` de `gradle.properties` sont remplacés par le CI : ne pas les toucher.

Build local : génère un projet sur https://fabricmc.net/develop/template (26.3), copie `src/`, `build.gradle`, `settings.gradle`,
`gradle.properties` dedans (remplace les `CHANGE_ME`), `./gradlew build` -> `build/libs/aiplayer-1.1.0.jar` dans `.minecraft/mods/` avec Fabric API.

> **Compile OK sur 26.3** (vérifié par le CI). Changements d'API 26.x déjà gérés dans `Compat.java` : `ClickType` -> `ContainerInput`,
> `MultiPlayerGameMode.handleContainerInput`, `mc.screen` -> `mc.gui.screen()`, `displayClientMessage` -> `sendSystemMessage`,
> `interact(..., EntityHitResult, hand)`, `swing(hand, SwingAnimation, boolean)`. En cas de nouvelle version de MC, regarder d'abord
> `Compat.java` et `AiInput.java`. Pour inspecter l'API réelle : `javap -cp ~/.gradle/caches/fabric-loom/<ver>/minecraft-client.jar <classe>`.
> **Non testé en jeu** : seule la compilation est vérifiée.

---------------------------------------------------------------------------------------------------------------------

## 3. Architecture (pour une IA qui reprend le projet)

Boucle : **perception -> LLM -> JSON -> file d'actions -> exécution tick par tick -> résultats -> perception…**

| Fichier | Rôle |
|---|---|
| `AiPlayerMod.java` | Entrypoint client : tick, chat, commandes `/ai …`. |
| `Controller.java` | Cerveau. File d'actions, mouvement voulu (`forward/jump/sprint/sneak/use`), boucle LLM (`maybeThink`/`think`/`onResponse`), respawn, aides (regard, équiper, meilleur outil). |
| `AiInput.java` | **Contrôle direct** : sous-classe de `ClientInput` installée dans `LocalPlayer.input`. Lit `Controller.forward/jump/sneak/sprint`. |
| `mixin/ScreenPauseMixin`, `PauseScreenMixin` | `isPauseScreen()` -> false tant que l'IA est ON (le monde ne gèle plus en solo avec Échap). |
| `Compat.java` | **Tous** les appels Minecraft fragiles d'une version à l'autre (attaque, minage, clics inventaire, début/fin d'usage d'objet, écran pause). |
| `Actions.java` | Parseur JSON -> `Action` + toutes les actions. |
| `MoveAction.java` + `Pathfinder.java` | Déplacement A* (saut 1 bloc, chute ≤3, évite lave/feu). |
| `Perception.java` | Monde -> texte compact (STATUS, INVENTORY, OPEN SCREEN, ENTITIES, **HINTS**, MAP, BLOCKS). |
| `Prompt.java` | Prompt système. **Source de vérité de ce que l'IA sait faire.** |
| `LlmClient.java` | HTTP LM Studio (`POST /api/v1/chat {model, system_prompt, input}`), extraction du JSON. |
| `Config.java` | `config/aiplayer.json`. |

### Principes à respecter
- **Pas de touches clavier simulées.** Mouvement = `AiInput`. Interactions = appels directs `mc.gameMode.*` via `Compat`.
  Seule exception : `keyUse` maintenu pendant un `use_item` **sans écran** (sinon le jeu annule le repas), voir `Controller.applyUse()`.
- **Aucune action ne doit supposer qu'il n'y a pas d'écran.** Un écran quelconque (menu Échap) peut être ouvert par l'humain.
  On ne ferme que les écrans *conteneur* (`Compat.closeContainerScreen`), jamais le menu Échap.
- **Une `Action` = machine à états** : `tick()` renvoie `RUNNING/DONE/FAILED`. Toujours donner une `failReason` claire
  et actionnable (c'est ce que le LLM lit au tour suivant).
- **Le LLM est petit (~12B)** : préférer des actions de haut niveau (`collect`, `craft`) aux micro-gestes, donner des
  faits calculés (`HINTS`) plutôt que de lui faire déduire, tolérer les erreurs de format (alias de types dans
  `Actions.canonicalType`, `pos:[x,y,z]` accepté dans `Actions.normalize`).
- **Toute nouvelle action** : 1) classe dans `Actions.java`, 2) `case` dans `parse()` + nom dans `Actions.TYPES`,
  3) documentée avec un exemple dans `Prompt.java`, 4) section dans ce README.

### Actions (voir `Prompt.java` pour le texte exact envoyé au modèle)
`move_to` (A*), `follow`, `look_at`, `sneak`, `jump`, `mine`, **`collect`**, `place`, `use_on`, `attack`, `interact`,
`equip`, `use_item`, `drop`, **`craft`**, `open_inventory`, `close_screen`, `click_slot`, `say`, `wait`.

- `collect {block,count,radius}` : cherche le bloc le plus proche *exposé*, le casse (via `Mine`), ramasse les drops
  (marche sur les `ItemEntity`), recommence. `block` accepte un id exact (`oak_log`) ou approchant (`log`, `ore`).
- `craft {grid,count}` : `grid` = lignes `[["oak_planks","oak_planks"],["oak_planks","oak_planks"]]` (ou liste plate de 4/9),
  `""` = case vide. Grille 2x2 du joueur sans écran (via `inventoryMenu`), 3x3 si une table de craft est ouverte.
  Un clic par tick ; vérifie les ingrédients avant ; en cas d'échec ou d'arrêt `stop()` remet curseur et grille dans l'inventaire.
- `use_item` : démarre l'usage en direct (`Compat.startUse`), finit tout seul (manger) ou relâche après `ticks` (arc/bouclier).

---------------------------------------------------------------------------------------------------------------------

## 4. Zones fragiles / à vérifier en premier sur 26.3

1. `AiInput` : `ClientInput.tick()` (signature, a changé entre 1.21.3 et 1.21.11), `keyPresses`, `moveVector`,
   `Input(forward, backward, left, right, jump, shift, sprint)`, `Vec2`. Si `tick()` a changé de signature, adapter ici.
2. `Compat.isPaused` (`Minecraft.isPaused()`), `Compat.startUse/releaseUse` (`MultiPlayerGameMode.useItem/releaseUsingItem`),
   `Compat.isFood` (`DataComponents.FOOD`).
3. Mixin : `Screen.isPauseScreen()` (PauseScreen en hérite en 26.3 ; `Gui.isPausing()` l'appelle). Si le monde se gèle quand même avec Échap, les noms ont
   changé : vérifier dans les logs `Mixin apply failed` / `@Inject … could not find any targets`.
4. `Compat.click` -> `MultiPlayerGameMode.handleInventoryMouseClick`, enum `ClickType`.
5. `Actions.Craft` : indices de slots (`InventoryMenu` : 0 résultat, 1-4 grille, 5-8 armure, 9-35 inv, 36-44 barre, 45 main
   secondaire ; `CraftingMenu` : 0 résultat, 1-9 grille, 10-36 inv, 37-45 barre).
6. `gradle.properties` contient encore `CHANGE_ME` pour loader/loom/fabric-api.

## 5. Limites connues
- Pas de pathfinding à travers des blocs (il ne creuse pas tout seul : `collect`/`mine` un bloc à la fois).
- Coffres / fours = `click_slot` à la main (un 12B a besoin des exemples du prompt).
- Échelles, vitres/escaliers/dalles complexes, bateaux, élytres : non gérés finement.
- Mouvement de **souris** de l'humain : non bloqué (il tourne la tête du perso quand aucun écran n'est ouvert).
- Pas de notion de jour/nuit dans la perception (API d'horloge instable en 26.x) : à ajouter dans `Perception.hints`.
- Anti-cheat de serveurs : paquets normaux, mais reste un bot.

## 6. Idées pour la suite
- Bloquer la souris humaine (mixin sur `MouseHandler.turnPlayer`) quand l'IA est ON et qu'aucun écran n'est ouvert.
- Action `smelt` (four) et `store`/`take` (coffre) de haut niveau, comme `craft`.
- Heure de la journée + niveau de lumière dans `HINTS`, détection d'abri.
- Mémoire structurée (base, coffres) plutôt qu'un texte libre de 600 caractères.
- Sortie JSON contrainte (structured output LM Studio) pour supprimer les erreurs de format du 12B.

## 7. Historique
- v1.0.0 : version initiale (touches clavier simulées, actions de base).
- v1.1 : contrôle direct (`AiInput`), Échap/fenêtre sans focus OK, mixins anti-pause ; actions `collect` et `craft` ;
  `use_item` direct ; alias d'actions et `pos:[x,y,z]` tolérés ; `memory` vide ne l'efface plus ; section HINTS dans la
  perception ; menu Échap ignoré par la perception et jamais fermé ; prompt réécrit pour un 12B (recettes, exemples,
  priorités) ; options `keepRunningInPause` et `temperature`.
