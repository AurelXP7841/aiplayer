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
3. En jeu : `/ai on`, `/ai off`, `/ai tell <ordre>`, `/ai status`, `/ai reload`, `/ai ping` (teste LM Studio),
   **`/ai settings`** (ou `/ai config`) : ouvre l'écran de réglages. Il y a aussi un bouton **AI Player** en haut à gauche du menu Échap.
4. **Astuce** : après `/ai on`, appuie sur **Échap**. Tu peux alors utiliser souris/clavier ailleurs, le perso continue
   (en solo, le mixin empêche le menu Échap de geler le monde ; désactivable avec `keepRunningInPause=false`).
   Ne rouvre pas le jeu avec la souris pendant que l'IA joue : le mouvement de souris tourne la tête du perso.

**Écran de réglages** (`SettingsScreen`) : Host + Port du serveur, nom exact du modèle (colle-le dans le champ, ou « Fetch models »
puis `<` `>` pour parcourir ceux de LM Studio), **timeout en secondes (0 = illimité)**, température, max tokens, rayon de scan,
lignes de chat, actions par plan, prompt supplémentaire, **Thinking** (OFF par défaut ; `default` = non envoyé, ou low/medium/high/on),
et les interrupteurs (pensées, verbose, errance si l'IA ne répond pas, tourner dans le menu Échap, attaquer les joueurs).
**Save** écrit `config/aiplayer.json`, **Reset to defaults** recharge le preset par défaut (= les valeurs initiales de `Config.java`),
**Cancel** ferme sans rien changer. Le schéma (`http`/`https`) et le chemin de l'URL (`/api/v1/chat`) ne sont pas modifiés par l'écran.

Config (`aiplayer.json`) : `url`, `model`, `historySize`, `scanRadius`, `requestTimeoutSeconds` (**0 = attend sans limite** ; sinon
« échec » au bout de N secondes), `maxActionsPerPlan`, `showThoughts`, `allowAttackPlayers`, `keepRunningInPause`,
`temperature` (0.2-0.5 conseillé pour un 12B, -1 = non envoyé), `extraSystemPrompt`, `maxOutputTokens`, `reasoning` (**`"off"` par défaut**
= thinking coupé ; `""` = non envoyé, utile si LM Studio refuse le réglage pour ton modèle), `fallbackWander`, `verbose`, `configVersion`.
Les anciens `aiplayer.json` (sans `configVersion` ou < 2) passent automatiquement `reasoning` à `"off"` au premier chargement.

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
| `Config.java` | `config/aiplayer.json` ; les valeurs initiales des champs = preset par défaut (`Config.defaults()`). |
| `SettingsScreen.java` | Écran de réglages (widgets standards, sans `render()`). Ouvert par `/ai settings` ou `PauseMenuButton`. |
| `PauseMenuButton.java` | Bouton « AI Player » dans le menu Échap (`ScreenEvents.AFTER_INIT` + `Screens.getWidgets`, renommé depuis `getButtons` en 26.x). |

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

## 3 bis. GUIDE DE REPRISE (si tu es une IA qui continue ce projet, lis ça en premier)

**État** : v1.1.0 compile sur Minecraft 26.3 (CI vert) mais n'a **jamais été lancé en jeu**. Premier objectif réaliste : un humain
teste en jeu et rapporte les bugs. Ne prétends pas que ça marche sans retour de test.

**Comment le programme tourne (un tick = 1/20 s)** : `AiPlayerMod` -> `ClientTickEvents.START_CLIENT_TICK` -> `Controller.tick()` :
1. `AiInput.install(player)` (contrôle direct) ; remise à zéro de `forward/jump/sprint/use` ;
2. si le monde est gelé (`Compat.isPaused`) on s'arrête là ;
3. `runActions` : prend l'action courante (ou la suivante de `queue`), appelle `action.tick(controller)`. L'action pose des
   intentions (`c.forward = true`, `c.jump = true`…) ou appelle `Compat.*` (casser, attaquer, cliquer). Elle renvoie RUNNING/DONE/FAILED ;
4. `AiInput.tick()` (appelé par le jeu pendant `LocalPlayer.aiStep`) transforme ces intentions en mouvement ;
5. `maybeThink` : si plus rien à faire (ou interruption : dégâts, chat), `think()` envoie `Prompt.SYSTEM` + `Perception.build()` à LM Studio
   **en asynchrone** ; la réponse JSON revient sur le thread client (`onResponse`), est parsée par `Actions.parse` et remplit `queue`.
Les résultats (done / FAILED: raison) sont renvoyés à l'IA au tour suivant (`RESULTS OF YOUR LAST ACTIONS`).

**Ajouter une action (exemple : `smelt`)**
1. `Actions.java` : `public static class Smelt extends Action { tick(), describe(), éventuellement start()/stop()/allowsScreen() }`.
   Toujours `fail("raison claire et actionnable")` en cas d'échec ; nettoyer dans `stop()` (relâcher, vider le curseur…).
2. `Actions.parse()` : ajouter `case "smelt": return new Smelt(...)` ; ajouter le nom dans `Actions.TYPES` ; (alias éventuels dans `canonicalType`).
3. `Prompt.java` : documenter l'action + un exemple JSON, et si besoin une ligne dans les exemples de réponses. **Sans ça le modèle ne la connaît pas.**
4. Si elle appelle une API Minecraft nouvelle : l'ajouter dans `Compat.java`, pas ailleurs.
5. Mettre à jour la liste d'actions et l'historique de ce README.

**Boucle de travail avec le CI (aucun Minecraft local nécessaire)**
1. Modifier le code, `git push origin main`.
2. GitHub Actions compile (`gh run watch`, `gh run view --log-failed`). Une erreur `cannot find symbol` = API renommée en 26.x :
   inspecte le vrai jar avec `javap` (le workflow a une étape « Inspect Minecraft API » à adapter : ajouter des lignes `J nom.de.Classe | grep ...`),
   puis corrige dans `Compat.java`.
3. Le jar est sur la branche `build-output` : `git fetch origin build-output && git show origin/build-output:aiplayer-1.1.0.jar > aiplayer.jar`.
   (Les artefacts/releases Actions peuvent être inaccessibles depuis certains environnements : d'où cette branche.)
4. Une erreur 429 de Maven Central dans le CI est transitoire : relancer le job.

**Déboguer en jeu** : avec `verbose=true` (défaut) le chat affiche en gris `thinking...`, `reply received in X s`, la liste d'actions, ou l'erreur ; `/ai ping` teste la connexion ; la dernière requête et réponse brutes sont dans `.minecraft/aiplayer-debug/last_request.txt` et `last_reply.txt` (à coller dans une issue/à une IA). Aussi : `/ai status` (état, écran ouvert, pause, action courante) ; `showThoughts=true` affiche la pensée de l'IA dans le chat ;
les erreurs internes s'affichent dans le chat (`§c[AI] …`) et dans `logs/latest.log` ; le mod n'envoie jamais de commandes `/` dans le chat serveur.
Si l'IA ne répond pas : vérifier `url`/`model` dans `config/aiplayer.json`, que LM Studio tourne, et que le contexte du modèle est ≥ 16k.
Si le JSON est mal formé : baisser `temperature` (0.2-0.4) ; le mod tolère déjà synonymes d'actions et `pos:[x,y,z]`.

**Pièges connus**
- Ne jamais supposer `mc.screen == null` : utiliser `Compat.screen(mc)` / `Compat.isContainerScreen(mc)` ; ne jamais fermer le menu Échap.
- Ne pas simuler de touches (`KeyMapping.setDown`) pour bouger ; seule exception documentée : `keyUse` dans `Controller.applyUse()`.
- Ne pas bloquer le thread client avec l'appel HTTP (déjà async, rester ainsi) ; toute réponse du LLM est traitée via `mc.execute`.
- `maxActionsPerPlan` (8) limite la longueur d'un plan ; les actions de haut niveau (`collect`, `craft`) existent pour que 8 suffise.
- Le prompt est en anglais volontairement (meilleur suivi par les petits modèles) ; garder des consignes courtes, numérotées, avec exemples.

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
- v1.1 (suite) : écran de réglages (`/ai settings` + bouton du menu Échap) ; `Config.java`/`LlmClient.java` restaurés (supprimés par erreur de `main`) ;
  thinking coupé par défaut (`reasoning="off"`, migré dans les anciennes configs) ; `requestTimeoutSeconds=0` = pas de timeout ;
  message d'erreur explicite quand le timeout est atteint. Compile OK en CI sur 26.3, **non testé en jeu**.
