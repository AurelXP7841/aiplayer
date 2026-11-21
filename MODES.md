# Modes de combat

En plus du mode survie habituel, l'IA a des **modes de mission**. Dans ces modes, le prompt de survie est **remplacé** :
l'IA ne mine plus, ne craft plus, ne construit plus. Elle ne fait que sa mission.

| Commande | Effet |
|---|---|
| `/ai defend` | Garde la base : le centre est l'endroit où tu tapes la commande (rayon `defendRadius`, 20 par défaut). Elle tue les monstres qui approchent, puis revient au centre. Elle n'attaque jamais de joueur. |
| `/ai kill <joueur>` | Chasse ce joueur avec ce qu'elle a sur elle (meilleure arme choisie automatiquement, poings sinon). Ce joueur est le seul autorisé en attaque, même si `allowAttackPlayers=false`. |
| `/ai kill everyone` | IA « méchante » : tue tous les autres joueurs (le plus proche d'abord), puis n'importe quelle créature proche. |
| `/ai beafriend` | **Serviteur** : lit le chat et obéit à tous les joueurs (« suis-moi », « retourne à la base », « tue ce cheval »). Ne mine pas, ne craft pas, reste sur place sans ordre, ne va à la base que si on le lui demande. N'attaque un joueur que si un joueur le demande dans le chat. Voir plus bas. |
| `/ai normal` | Retour au mode survie (ses anciennes notes `memory` sont restaurées). `/ai off` fait pareil et rend la main. |

**Ajoute `chat` à la fin de n'importe laquelle** (sauf `beafriend`) : `/ai defend chat`, `/ai kill Steve chat`, `/ai kill everyone chat`.
L'IA parle alors pendant le combat (« Aïe ! », « Reviens ! », « Tu fais quoi ?! », taunts...), dans la langue du chat (français par défaut).
Dans ces modes, un `attack` qui arrive au bout de son `max_ticks` passe à l'action suivante du plan au lieu d'échouer : c'est ce qui permet d'intercaler des `say` entre deux attaques.

## Mode serviteur : `/ai beafriend`

- **Base fixe** : `Servant.BASE` dans `Servant.java` (x 4287947, y 84, z -7914829). Pour la changer, modifier ces trois nombres.
- **Ordres compris** (le modèle comprend le sens, pas seulement ces mots) : suivre (« suis-moi », « viens »), rester (« stop », « reste là »), retourner à la base (« retourne à la base », « rentre »), tuer une créature (« tue ce cheval »), tuer un joueur (« tue Steve »).
- **Sans ordre** : il attend sur place. Il ne retourne jamais à la base de lui-même (ni après une mort), et l'errance `fallbackWander` est désactivée dans ce mode.
- **Lecture du chat** : chaque tour contient une section `NEW CHAT MESSAGES` (messages pas encore montrés à l'IA). Un nouveau message d'un autre joueur déclenche une réflexion tout de suite. Les messages envoyés comme « message système » par certains serveurs sont lus aussi. Ses propres messages sont ignorés.
- **Attaque d'un joueur** : autorisée seulement si un autre joueur a écrit dans le chat un verbe d'attaque (kill, tue, attaque, frappe, bats, élimine...) avec le pseudo de la cible, il y a moins de 10 minutes. On ne peut pas lui demander de s'attaquer soi-même. « stop », « arrête », « annule », « laisse » retire toutes les autorisations. Ce filtre est dans le code (`Servant.mayAttack`), il s'applique même si `allowAttackPlayers=true`.
- **Créatures** : il peut attaquer une créature (cheval...) sur demande, et se défendre contre un monstre hostile qui le frappe.
- **Limites** : la base est très loin, le retour ne marche que par morceaux (chemin partiel sur les chunks chargés) ; le prompt lui dit de renvoyer le `move_to` en cas d'échec ou de timeout.

## Config (`config/aiplayer.json`)

| Clé | Rôle |
|---|---|
| `historySize` | **Nombre de messages de chat donnés à l'IA** (défaut 15). Baissé = moins de tokens, monté = plus de contexte. L'écran `/ai settings` accepte 1-50 ; dans le JSON tu peux aller plus haut. Seuls les `historySize` derniers messages sont envoyés. |
| `defendRadius` | Rayon protégé par `/ai defend` (blocs). |
| `defendPrompt`, `killPrompt`, `killAllPrompt` | Consignes de chaque mode. `""` = texte intégré (`ModePrompts.java`). Jokers : `{RADIUS}`, `{TARGET}`. |
| `chatPrompt` | Consignes ajoutées quand la commande finit par `chat`. `""` = texte intégré. |

Le prompt de `/ai beafriend` n'est pas configurable : il est dans `ModePrompts.java` (`SERVANT`).
Après modification du JSON : `/ai reload`.

## Fichiers

- `ModePrompts.java` : prompts des modes (base + mission + chat + règles, et `SERVANT` pour beafriend).
- `ModeHints.java` : faits calculés envoyés à l'IA (`MISSION HINTS` : id et distance de la cible, dernière position vue, menaces autour de la base).
- `Servant.java` : mode beafriend (base fixe, lecture du chat, permission d'attaquer un joueur, `MISSION HINTS` du serviteur : ids des joueurs et des créatures).
- `Controller.java` : `Mode`, `setMode`, `mayAttackPlayer`, `missionLine`, messages non lus de beafriend.
- `AiPlayerMod.java` : commandes `defend`, `kill`, `beafriend`, `normal`.
- `ModeAttack.java` : remplace `Actions.Attack` en mode combat (filtre `mayAttackPlayer`, fin de `max_ticks` = `DONE`).

Le mode n'est volontairement **pas** sauvegardé dans la config : au redémarrage du jeu l'IA est éteinte et en mode normal.
Compile vérifié par le CI uniquement, **non testé en jeu**. À utiliser sur des serveurs qui autorisent le PvP.
