# Euclesia — Archipelago Protocol Reference

Documentation des commandes du protocole Archipelago échangées entre le mod Fabric et le serveur.

- Le contenu du `slot_data` (reçu dans `Connected`) est documenté dans [`slot_data.md`](slot_data.md).
- Les IDs d'items/locations dérivent des bases décrites dans `slot_data.md` (§ « Schéma des IDs »).
- Les exemples d'IDs/noms ci-dessous utilisent les valeurs réelles d'Euclesia.
- Tous les messages sont des objets JSON encapsulés dans un tableau (`[ {…}, {…} ]`) ; le champ `cmd`
  identifie la commande. Les types et énumérations partagés sont regroupés en fin de document
  (§ [Types & énumérations communs](#types--énumérations-communs)).

---

## Mod → Serveur

### `Connect`
Connexion initiale au serveur. Premier packet envoyé après réception de `RoomInfo`.
```json
{
    "cmd": "Connect",
    "game": "Minecraft",
    "name": "PlayerName",
    "password": "",
    "version": {"major": 0, "minor": 6, "build": 0, "class": "Version"},
    "items_handling": 7,
    "tags": ["DeathLink"],
    "uuid": "random-uuid",
    "slot_data": true
}
```
| Champ | Type | Description / valeurs possibles |
|-------|------|---------------------------------|
| `cmd` | `str` | Toujours `"Connect"`. |
| `game` | `str` | Nom du jeu. Toujours `"Minecraft"` pour Euclesia. |
| `name` | `str` | Nom du slot (doit correspondre au `name` du YAML). |
| `password` | `str` | Mot de passe de la room ; `""` si aucun. |
| `version` | [NetworkVersion](#networkversion) | Version AP minimale supportée par le client. |
| `items_handling` | `int` | Flags des items à recevoir. Voir [items_handling](#items_handling). `7` = tout. |
| `tags` | `list[str]` | Étiquettes de capacité. Voir [Tags](#tags). `[]` si aucune. |
| `uuid` | `str` | Identifiant unique persistant du client. |
| `slot_data` | `bool` | Si `true`, la réponse `Connected` contiendra le `slot_data`. |

---

### `ConnectUpdate`
Met à jour les tags ou `items_handling` après connexion. Les deux champs sont optionnels.
```json
{
    "cmd": "ConnectUpdate",
    "tags": ["DeathLink"],
    "items_handling": 7
}
```
| Champ | Type | Description / valeurs possibles |
|-------|------|---------------------------------|
| `cmd` | `str` | Toujours `"ConnectUpdate"`. |
| `tags` | `list[str]` | Nouvelle liste de [Tags](#tags) (remplace l'ancienne). |
| `items_handling` | `int` | Nouveaux flags [items_handling](#items_handling). |

---

### `LocationChecks`
Signale un ou plusieurs checks complétés (advancement obtenu, mob tué).
```json
{
    "cmd": "LocationChecks",
    "locations": [15470592, 15470593]
}
```
| Champ | Type | Description / valeurs possibles |
|-------|------|---------------------------------|
| `cmd` | `str` | Toujours `"LocationChecks"`. |
| `locations` | `list[int]` | IDs de locations checkées (clés de `locations`/`tracked_mobs` du slot_data). |

---

### `LocationScouts`
Demande le contenu de locations sans les checker (peut créer un hint).
```json
{
    "cmd": "LocationScouts",
    "locations": [15470592, 15470593],
    "create_as_hint": 0
}
```
| Champ | Type | Description / valeurs possibles |
|-------|------|---------------------------------|
| `cmd` | `str` | Toujours `"LocationScouts"`. |
| `locations` | `list[int]` | IDs des locations à scouter. |
| `create_as_hint` | `int` | `0` = aucun hint ; `1` = crée un hint **persistant** (même si déjà trouvé) ; `2` = idem mais ne broadcast que les nouveaux hints (toujours présents dans `LocationInfo`). |

Réponse : [`LocationInfo`](#locationinfo).

---

### `StatusUpdate`
Met à jour l'état du joueur.
```json
{
    "cmd": "StatusUpdate",
    "status": 30
}
```
| Champ | Type | Description / valeurs possibles |
|-------|------|---------------------------------|
| `cmd` | `str` | Toujours `"StatusUpdate"`. |
| `status` | `int` | [ClientStatus](#clientstatus) : `0` inconnu, `5` connecté, `10` prêt, `20` en jeu, `30` objectif atteint (victoire). |

> Ne pas envoyer `30` tant que les conditions de victoire (voir `slot_data.md`) ne sont pas remplies.

---

### `Say`
Envoie un message dans le chat AP.
```json
{ "cmd": "Say", "text": "Hello from Minecraft!" }
```
| Champ | Type | Description |
|-------|------|-------------|
| `cmd` | `str` | Toujours `"Say"`. |
| `text` | `str` | Texte du message (peut être une commande serveur comme `!hint`). |

---

### `GetDataPackage`
Demande le data package (noms de tous les items/locations).
```json
{ "cmd": "GetDataPackage", "games": ["Minecraft"] }
```
| Champ | Type | Description / valeurs possibles |
|-------|------|---------------------------------|
| `cmd` | `str` | Toujours `"GetDataPackage"`. |
| `games` | `list[str]` | Jeux dont on veut le data package. Omis ou vide = tous les jeux. |

Réponse : [`DataPackage`](#datapackage).

---

### `Bounce`
Envoie des données à d'autres clients (filtré par `games`, `slots` ou `tags`). Usage principal : **DeathLink**.
```json
{
    "cmd": "Bounce",
    "tags": ["DeathLink"],
    "data": {
        "time": 1234567890.0,
        "source": "PlayerName",
        "cause": "Fell into the void"
    }
}
```
| Champ | Type | Description / valeurs possibles |
|-------|------|---------------------------------|
| `cmd` | `str` | Toujours `"Bounce"`. |
| `games` | `list[str]` | (optionnel) cible par jeu. |
| `slots` | `list[int]` | (optionnel) cible par slot. |
| `tags` | `list[str]` | (optionnel) cible par tag ; `["DeathLink"]` pour la mort partagée. |
| `data` | `dict` | Charge utile libre. Pour DeathLink : `time` (`float`, timestamp Unix), `source` (`str`, nom du joueur mort), `cause` (`str`, optionnel). |

Reçu par les autres clients sous forme de [`Bounced`](#bounced).

---

### `Get`
Lit une ou plusieurs valeurs du DataStorage AP (persistance entre sessions).
```json
{ "cmd": "Get", "keys": ["euclesia_player1_unlocked_mobs"] }
```
| Champ | Type | Description |
|-------|------|-------------|
| `cmd` | `str` | Toujours `"Get"`. |
| `keys` | `list[str]` | Clés à lire. Les clés `_read_*` exposent des données serveur en lecture seule. |

Réponse : [`Retrieved`](#retrieved).

---

### `Set`
Écrit/modifie une valeur du DataStorage via des opérations atomiques.
```json
{
    "cmd": "Set",
    "key": "euclesia_player1_unlocked_mobs",
    "default": [],
    "want_reply": true,
    "operations": [
        {"operation": "add", "value": ["minecraft:zombie"]}
    ]
}
```
| Champ | Type | Description / valeurs possibles |
|-------|------|---------------------------------|
| `cmd` | `str` | Toujours `"Set"`. |
| `key` | `str` | Clé à modifier. |
| `default` | `any` | Valeur initiale si la clé n'existe pas. |
| `want_reply` | `bool` | Si `true`, le serveur renvoie un [`SetReply`](#setreply). |
| `operations` | `list[dict]` | Liste d'opérations `{"operation": <nom>, "value": <valeur>}` appliquées dans l'ordre. Voir [opérations DataStorage](#opérations-datastorage). |

---

### `SetNotify`
S'abonne aux changements de clés DataStorage (déclenche des [`SetReply`](#setreply)).
```json
{ "cmd": "SetNotify", "keys": ["euclesia_player1_unlocked_mobs"] }
```
| Champ | Type | Description |
|-------|------|-------------|
| `cmd` | `str` | Toujours `"SetNotify"`. |
| `keys` | `list[str]` | Clés à surveiller. |

---

## Serveur → Mod

### `RoomInfo`
Reçu à la connexion, **avant** `Connect`. Décrit la room.
```json
{
    "cmd": "RoomInfo",
    "version": {"major": 0, "minor": 6, "build": 0},
    "generator_version": {"major": 0, "minor": 6, "build": 0},
    "tags": [],
    "password": false,
    "permissions": {"release": 2, "collect": 2, "remaining": 0},
    "hint_cost": 10,
    "location_check_points": 1,
    "games": ["Minecraft"],
    "datapackage_checksums": {"Minecraft": "abc123"},
    "seed_name": "12345",
    "time": 1234567890.0
}
```
| Champ | Type | Description / valeurs possibles |
|-------|------|---------------------------------|
| `cmd` | `str` | Toujours `"RoomInfo"`. |
| `version` | [NetworkVersion](#networkversion) | Version du serveur. |
| `generator_version` | [NetworkVersion](#networkversion) | Version ayant généré le seed. |
| `tags` | `list[str]` | Tags du serveur. |
| `password` | `bool` | `true` si la room exige un mot de passe. |
| `permissions` | `dict[str,int]` | [Permission](#permission) par commande, clés `release` / `collect` / `remaining`. |
| `hint_cost` | `int` | Coût d'un hint en **pourcentage** du total de checks. |
| `location_check_points` | `int` | Points gagnés par check (alimente le budget de hints). |
| `games` | `list[str]` | Jeux présents dans le multiworld. |
| `datapackage_checksums` | `dict[str,str]` | Checksum du data package par jeu (cache). |
| `seed_name` | `str` | Identifiant du seed. |
| `time` | `float` | Timestamp Unix du serveur. |

---

### `Connected`
Reçu après un `Connect` réussi. Contient le `slot_data` (schéma complet : [`slot_data.md`](slot_data.md)).
```json
{
    "cmd": "Connected",
    "team": 0,
    "slot": 1,
    "players": [
        {"team": 0, "slot": 1, "alias": "PlayerName", "name": "PlayerName"}
    ],
    "missing_locations": [15470592, 15470593],
    "checked_locations": [15470590],
    "slot_data": {
        "boss_list": ["minecraft:ender_dragon", "minecraft:wither", "minecraft:warden", "minecraft:elder_guardian"],
        "death_link": false,
        "kill_sanity": true,
        "advancements_required": 60,
        "mob_spawn_lock": ["hostile"],
        "items": {"15466752": "Entity Unlock: Allay", "15467008": "Structure Unlock: Ancient City"},
        "locations": {"minecraft:story/mine_stone": 15470593, "minecraft:ender_dragon": 15470908},
        "tracked_mobs": {"minecraft:ender_dragon": 15470908, "minecraft:allay": 15471104},
        "mob_spawn_lock_mobs": {"minecraft:blaze": 15466806},
        "structure_locks": {"minecraft:ancient_city": 15467008}
    },
    "slot_info": {"1": {"name": "PlayerName", "game": "Minecraft", "type": 1, "group_members": []}},
    "hint_points": 0
}
```
| Champ | Type | Description / valeurs possibles |
|-------|------|---------------------------------|
| `cmd` | `str` | Toujours `"Connected"`. |
| `team` | `int` | Numéro d'équipe (généralement `0`). |
| `slot` | `int` | ID du slot du joueur connecté. |
| `players` | `list[dict]` | Joueurs : `team`, `slot`, `alias`, `name`. |
| `missing_locations` | `list[int]` | IDs de locations pas encore checkées. |
| `checked_locations` | `list[int]` | IDs de locations déjà checkées. |
| `slot_data` | `dict` | Données spécifiques au jeu (voir `slot_data.md`). `{}` si `slot_data:false` dans `Connect`. |
| `slot_info` | `dict[str,dict]` | Infos par slot : `name`, `game`, `type` ([SlotType](#slottype) `1` joueur / `2` groupe), `group_members`. |
| `hint_points` | `int` | Budget de points pour créer des hints. |

---

### `ConnectionRefused`
Reçu si la connexion échoue.
```json
{ "cmd": "ConnectionRefused", "errors": ["InvalidSlot"] }
```
| Champ | Type | Description |
|-------|------|-------------|
| `cmd` | `str` | Toujours `"ConnectionRefused"`. |
| `errors` | `list[str]` | Une ou plusieurs causes (voir ci-dessous). |

| Erreur | Cause |
|--------|-------|
| `InvalidSlot` | Slot/`name` inexistant. |
| `InvalidGame` | Le jeu du slot ne correspond pas à `game`. |
| `IncompatibleVersion` | Version AP du client trop ancienne. |
| `InvalidPassword` | Mot de passe incorrect. |
| `InvalidItemsHandling` | Valeur `items_handling` invalide. |

---

### `ReceivedItems`
Reçu quand le joueur obtient un ou plusieurs items.
```json
{
    "cmd": "ReceivedItems",
    "index": 0,
    "items": [
        {"item": 15466752, "location": 15470592, "player": 2, "flags": 1}
    ]
}
```
| Champ | Type | Description |
|-------|------|-------------|
| `cmd` | `str` | Toujours `"ReceivedItems"`. |
| `index` | `int` | Index de départ dans la liste cumulée des items reçus (`0` = resynchronisation complète). |
| `items` | `list[`[NetworkItem](#networkitem)`]` | Items reçus. |

> Euclesia : tous les `Entity Unlock` et la plupart des `Structure Unlock` sont progression (`flags = 1`).
> Le réseau n'expose pas `skip_balancing`, donc un item `progression_skip_balancing` apparaît aussi en `1`.
> Seules les structures `useful` (ex. Nether Fossil) arrivent en `2`. Le mod résout l'`item` via le
> mapping `items` du `slot_data` pour appliquer l'effet.

---

### `LocationInfo`
Réponse à un [`LocationScouts`](#locationscouts).
```json
{
    "cmd": "LocationInfo",
    "locations": [
        {"item": 15466496, "location": 15470592, "player": 1, "flags": 1}
    ]
}
```
| Champ | Type | Description |
|-------|------|-------------|
| `cmd` | `str` | Toujours `"LocationInfo"`. |
| `locations` | `list[`[NetworkItem](#networkitem)`]` | Contenu des locations scoutées (`item`, `location`, `player`, `flags`). |

---

### `RoomUpdate`
Mise à jour partielle de la room. Ne contient que les champs modifiés (sous-ensemble de `RoomInfo`/`Connected`).
```json
{ "cmd": "RoomUpdate", "checked_locations": [15470592], "players": [], "hint_points": 5 }
```
| Champ | Type | Description |
|-------|------|-------------|
| `cmd` | `str` | Toujours `"RoomUpdate"`. |
| `checked_locations` | `list[int]` | (optionnel) Nouvelles locations checkées. |
| `players` | `list[dict]` | (optionnel) Joueurs mis à jour. |
| `hint_points` | `int` | (optionnel) Nouveau budget de hints. |
| … | | Tout autre champ de `RoomInfo`/`Connected` peut apparaître s'il change. |

---

### `PrintJSON`
Message structuré à afficher dans le chat. `data` est une liste de [JSONMessagePart](#jsonmessagepart) à concaténer.
```json
{
    "cmd": "PrintJSON",
    "data": [
        {"type": "player_id", "text": "1"},
        {"type": "text", "text": " found "},
        {"type": "item_id", "text": "15466496", "flags": 1, "player": 1}
    ],
    "type": "ItemSend",
    "receiving": 1,
    "item": {"item": 15466496, "location": 15470592, "player": 1, "flags": 1}
}
```
| Champ | Type | Description / valeurs possibles |
|-------|------|---------------------------------|
| `cmd` | `str` | Toujours `"PrintJSON"`. |
| `data` | `list[`[JSONMessagePart](#jsonmessagepart)`]` | Fragments du message dans l'ordre d'affichage. |
| `type` | `str` | Déclencheur (voir table ci-dessous). Absent = message texte générique. |
| `receiving` | `int` | (type item) slot qui reçoit l'item. |
| `item` | [NetworkItem](#networkitem) | (type item) item concerné. |
| `found` | `bool` | (type `Hint`) `true` si la location du hint est déjà trouvée. |
| `countdown` | `int` | (type `Countdown`) valeur restante. |

| `type` | Déclencheur |
|--------|-------------|
| `ItemSend` | Un item a été envoyé d'un joueur à un autre. |
| `ItemCheat` | Item donné via commande admin. |
| `Hint` | Un hint a été créé/mis à jour. |
| `Join` / `Part` | Un joueur a rejoint / quitté. |
| `Chat` / `ServerChat` | Message d'un joueur / du serveur. |
| `Tutorial` | Message d'aide. |
| `TagsChanged` | Tags d'un joueur modifiés. |
| `CommandResult` | Résultat d'une commande. |
| `Goal` | Un joueur a atteint son objectif. |
| `Release` | Un joueur a relâché ses locations. |
| `Collect` | Un joueur a collecté ses items. |
| `Countdown` | Compte à rebours du serveur. |

---

### `DataPackage`
Réponse à [`GetDataPackage`](#getdatapackage). Mapping nom ↔ ID par jeu.
```json
{
    "cmd": "DataPackage",
    "data": {
        "games": {
            "Minecraft": {
                "item_name_to_id": {
                    "Progressive Villager Trust": 15466496,
                    "Progressive Material Handling": 15466499,
                    "Entity Unlock: Allay": 15466752,
                    "Structure Unlock: Ancient City": 15467008
                },
                "location_name_to_id": {
                    "Advancement: Minecraft": 15470592,
                    "Advancement: Stone Age": 15470593
                },
                "checksum": "abc123"
            }
        }
    }
}
```
| Champ | Type | Description |
|-------|------|-------------|
| `cmd` | `str` | Toujours `"DataPackage"`. |
| `data.games` | `dict[str,dict]` | Par jeu : `item_name_to_id` (`dict[str,int]`), `location_name_to_id` (`dict[str,int]`), `checksum` (`str`). |

---

### `Bounced`
Reçu quand un [`Bounce`](#bounce) correspond aux `games`/`slots`/`tags` du joueur. Usage : **DeathLink**.
```json
{
    "cmd": "Bounced",
    "tags": ["DeathLink"],
    "data": {"time": 1234567890.0, "source": "OtherPlayer", "cause": "Creeper explosion"}
}
```
| Champ | Type | Description |
|-------|------|-------------|
| `cmd` | `str` | Toujours `"Bounced"`. |
| `games` / `slots` / `tags` | `list` | Filtres ayant déclenché la réception (mêmes champs que `Bounce`). |
| `data` | `dict` | Charge utile. Pour DeathLink : `time`, `source`, `cause`. Ignorer son propre `source` pour éviter une boucle. |

---

### `InvalidPacket`
Reçu si un packet envoyé était malformé.
```json
{ "cmd": "InvalidPacket", "type": "cmd", "original_cmd": "LocationChecks", "text": "locations is required" }
```
| Champ | Type | Description / valeurs possibles |
|-------|------|---------------------------------|
| `cmd` | `str` | Toujours `"InvalidPacket"`. |
| `type` | `str` | `"cmd"` = commande inconnue ; `"arguments"` = arguments manquants/invalides. |
| `original_cmd` | `str \| null` | Commande fautive (si connue). |
| `text` | `str` | Message d'erreur lisible. |

---

### `Retrieved`
Réponse à un [`Get`](#get).
```json
{ "cmd": "Retrieved", "keys": {"euclesia_player1_unlocked_mobs": ["minecraft:zombie", "minecraft:creeper"]} }
```
| Champ | Type | Description |
|-------|------|-------------|
| `cmd` | `str` | Toujours `"Retrieved"`. |
| `keys` | `dict[str,any]` | Valeur courante de chaque clé demandée (`null` si absente). |

---

### `SetReply`
Reçu après un [`Set`](#set) avec `want_reply:true`, ou pour chaque clé surveillée via [`SetNotify`](#setnotify).
```json
{
    "cmd": "SetReply",
    "key": "euclesia_player1_unlocked_mobs",
    "value": ["minecraft:zombie", "minecraft:creeper"],
    "original_value": ["minecraft:zombie"]
}
```
| Champ | Type | Description |
|-------|------|-------------|
| `cmd` | `str` | Toujours `"SetReply"`. |
| `key` | `str` | Clé modifiée. |
| `value` | `any` | Nouvelle valeur (après opérations). |
| `original_value` | `any` | Valeur avant modification. |

---

## Types & énumérations communs

### NetworkItem
Représente un item dans le réseau (`ReceivedItems`, `LocationInfo`, `PrintJSON`).

| Champ | Type | Description / valeurs |
|-------|------|-----------------------|
| `item` | `int` | ID de l'item (clé du mapping `items`). |
| `location` | `int` | ID de la location d'origine (`-1`/valeurs spéciales pour starting inventory & co). |
| `player` | `int` | Slot propriétaire de la location. |
| `flags` | `int` | Bitfield de classification (voir ci-dessous). |

Flags (`flags`) — bitfield cumulable :

| Bit | Valeur | Signification |
|-----|--------|---------------|
| — | `0` | Rien de spécial (filler). |
| `0b001` | `1` | Progression (débloque de la logique). |
| `0b010` | `2` | Useful (particulièrement utile). |
| `0b100` | `4` | Trap. |

### items_handling
Flags (cumulables) du champ `items_handling` de [`Connect`](#connect)/[`ConnectUpdate`](#connectupdate) :

| Valeur | Signification |
|--------|---------------|
| `0b000` (`0`) | Aucun `ReceivedItems` n'est envoyé. |
| `0b001` (`1`) | Reçoit les items provenant des **autres** mondes. |
| `0b010` (`2`) | Reçoit les items de son **propre** monde. **Requiert** `0b001`. |
| `0b100` (`4`) | Reçoit son starting inventory. **Requiert** `0b001`. |
| `0b111` (`7`) | Tout (valeur recommandée). |

### ClientStatus

| Valeur | Statut |
|--------|--------|
| `0` | `CLIENT_UNKNOWN` |
| `5` | `CLIENT_CONNECTED` (mis automatiquement par le serveur) |
| `10` | `CLIENT_READY` |
| `20` | `CLIENT_PLAYING` |
| `30` | `CLIENT_GOAL` — victoire |

### Permission
Valeurs des entrées de `permissions` dans [`RoomInfo`](#roominfo) (`release`, `collect`, `remaining`) :

| Valeur | Signification |
|--------|---------------|
| `0b000` (`0`) | `disabled` — action interdite. |
| `0b001` (`1`) | `enabled` — autorisée manuellement à tout moment. |
| `0b010` (`2`) | `goal` — autorisée uniquement après avoir atteint l'objectif. |

### NetworkVersion
Objet version utilisé dans `Connect`/`RoomInfo`.

| Champ | Type | Description |
|-------|------|-------------|
| `major` / `minor` / `build` | `int` | Composantes de version. |
| `class` | `str` | `"Version"` (optionnel selon le client). |

### Tags
Étiquettes de capacité du client. Les plus courantes :

| Tag | Effet |
|-----|-------|
| `DeathLink` | Active la mort partagée (via `Bounce`/`Bounced`). |
| `Tracker` | Client en lecture seule (tracker). |
| `TextOnly` | Client texte sans gameplay. |
| `HintGame` | Client dédié aux hints. |

### JSONMessagePart
Fragment de message dans [`PrintJSON`](#printjson).

| Champ | Type | Description |
|-------|------|-------------|
| `type` | `str \| null` | Voir valeurs ci-dessous. Absent ⇒ `text`. |
| `text` | `str \| null` | Contenu textuel ou ID à résoudre. |
| `color` | `str \| null` | Couleur console (uniquement si `type = color`). |
| `flags` | `int \| null` | Flags de l'item (si `type = item_id`/`item_name`). |
| `player` | `int \| null` | Slot propriétaire (si `type` item/location). |
| `hint_status` | `int \| null` | [HintStatus](#hintstatus) (si `type = hint_status`). |

Valeurs de `type` : `text`, `player_id`, `player_name`, `item_id`, `item_name`, `location_id`,
`location_name`, `entrance_name`, `hint_status`, `color`.

Couleurs (`color`) : `bold`, `underline`, `black`, `red`, `green`, `yellow`, `blue`, `magenta`,
`cyan`, `white`, et les variantes fond `*_bg` (`black_bg`, `red_bg`, …, `white_bg`).

### HintStatus

| Valeur | Statut |
|--------|--------|
| `0` | `HINT_UNSPECIFIED` |
| `10` | `HINT_NO_PRIORITY` |
| `20` | `HINT_AVOID` |
| `30` | `HINT_PRIORITY` |
| `40` | `HINT_FOUND` |

### Opérations DataStorage
Valeurs possibles de `operation` dans [`Set`](#set) :

| Opération | Effet |
|-----------|-------|
| `replace` | Remplace la valeur. |
| `default` | Réinitialise à la valeur `default`. |
| `add` | Additionne (nombres) ou concatène (listes). |
| `mul` | Multiplie. |
| `pow` | Élève à la puissance. |
| `mod` | Modulo. |
| `floor` / `ceil` | Arrondi inférieur / supérieur. |
| `min` / `max` | Garde le minimum / maximum. |
| `and` / `or` / `xor` | Opérations binaires. |
| `left_shift` / `right_shift` | Décalage binaire. |
| `remove` | Retire la première occurrence d'une valeur d'une liste. |
| `pop` | Retire une clé d'un dict / un index d'une liste. |
| `update` | Merge un dict. |

---

## Flux résumé

```
Connexion
  Mod ◄─── RoomInfo                ───── Serveur AP   (avant Connect)
  Mod ──── Connect (slot, …)       ────► Serveur AP
  Mod ◄─── Connected (slot_data)   ───── Serveur AP   (ou ConnectionRefused)

En jeu
  Mod ──── LocationChecks ([id])   ────► Serveur AP   (advancement / mob kill)
  Mod ◄─── ReceivedItems ([item])  ───── Serveur AP   (item débloqué)
  Mod ◄─── PrintJSON / RoomUpdate  ───── Serveur AP
  Mod ──── StatusUpdate(30)        ────► Serveur AP   (victoire)

DeathLink
  Mod ──── Bounce  (DeathLink)     ────► Serveur AP
  Mod ◄─── Bounced (DeathLink)     ───── Serveur AP
```

Voir [`slot_data.md`](slot_data.md) pour le détail des données reçues à la connexion.
