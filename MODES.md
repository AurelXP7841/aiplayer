# Modes de combat

En plus du mode survie habituel, l'IA a des **modes de mission**. Dans ces modes, le prompt de survie est **remplacé** :
l'IA ne mine plus, ne craft plus, ne construit plus. Elle ne fait que sa mission.

| Commande | Effet |
|---|---|
| `/ai defend` | Garde la base : le centre est l'endroit où tu tapes la commande (rayon `defendRadius`, 20 par défaut). Elle tue les monstres qui approchent, puis revient au centre. Elle n'attaque jamais de joueur. |
| `/ai kill <joueur>` | Chasse ce joueur avec ce qu'elle a sur elle (meilleure arme choisie automatiquement, poings sinon). Ce joueur est le seul autorisé en attaque, même si `allowAttackPlayers=false`. |
| `/ai kill everyone` | IA « méchante » : tue tous les autres joueurs (le plus proche d'abord), puis n'importe quelle créature proche. |
| `/ai normal` | Retour au mode survie (ses anciennes notes `memory` sont restaurées). `/ai off` fait pareil et rend la main. |

**Ajoute `chat` à la fin de n'importe laquelle** : `/ai defend chat`, `/ai kill Steve chat`, `/ai kill everyone chat`.
L'IA parle alors pendant le combat (« Aïe ! », « Reviens ! », « Tu fais quoi ?! », taunts...), dans la langue du chat (français par défaut).
Dans ces modes, un `attack` qui arrive au bout de son `max_ticks` passe à l'action suivante du plan au lieu d'échouer : c'est ce qui permet d'intercaler des `say` entre deux attaques.

## Config (`config/aiplayer.json`)

| Clé | Rôle |
|---|---|
| `historySize` | **Nombre de messages de chat donnés à l'IA** (défaut 15). Baissé = moins de tokens, monté = plus de contexte. L'écran `/ai settings` accepte 1-50 ; dans le JSON tu peux aller plus haut. Seuls les `historySize` derniers messages sont envoyés. |
| `defendRadius` | Rayon protégé par `/ai defend` (blocs). |
| `defendPrompt`, `killPrompt`, `killAllPrompt` | Consignes de chaque mode. `""` = texte intégré (`ModePrompts.java`). Jokers : `{RADIUS}`, `{TARGET}`. |
| `chatPrompt` | Consignes ajoutées quand la commande finit par `chat`. `""` = texte intégré. |

Après modification du JSON : `/ai reload`.

## Fichiers

- `ModePrompts.java` : prompts des modes (base + mission + chat + règles).
- `ModeHints.java` : faits calculés envoyés à l'IA (`MISSION HINTS` : id et distance de la cible, dernière position vue, menaces autour de la base).
- `Controller.java` : `Mode`, `setMode`, `mayAttackPlayer`, `missionLine`.
- `AiPlayerMod.java` : commandes `defend`, `kill`, `normal`.
- `ModeAttack.java` : remplace `Actions.Attack` en mode combat (filtre `mayAttackPlayer`, fin de `max_ticks` = `DONE`).

Le mode n'est volontairement **pas** sauvegardé dans la config : au redémarrage du jeu l'IA est éteinte et en mode normal.
Compile vérifié par le CI uniquement, **non testé en jeu**. À utiliser sur des serveurs qui autorisent le PvP.
