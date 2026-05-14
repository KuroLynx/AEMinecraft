# Euclesia — Archipelago Protocol Reference

Documentation complète des commandes du protocole Archipelago pour le mod Fabric.

---

## Mod → Serveur

### `Connect`
Connexion initiale au serveur. Premier packet envoyé.
```json
{
    "cmd": "Connect",
    "game": "Minecraft",
    "name": "PlayerName",
    "password": "",
    "version": {"major": 0, "minor": 6, "build": 0},
    "items_handling": 7,
    "tags": [],
    "uuid": "random-uuid"
}
```
`items_handling` : `1` = items locaux, `2` = items distants, `4` = starting items, `7` = les trois.

---

### `ConnectUpdate`
Mettre à jour les tags ou `items_handling` après connexion.
```json
{
    "cmd": "ConnectUpdate",
    "tags": ["DeathLink"],
    "items_handling": 7
}
```

---

### `LocationChecks`
Envoyer un ou plusieurs checks complétés.
```json
{
    "cmd": "LocationChecks",
    "locations": [15470592, 15470593]
}
```

---

### `LocationScouts`
Demander ce qui se trouve dans certaines locations sans les checker.
```json
{
    "cmd": "LocationScouts",
    "locations": [15470592, 15470593],
    "create_as_hint": 0
}
```
| `create_as_hint` | Effet |
|------------------|-------|
| `0` | Pas de hint |
| `1` | Hint si pas encore checké |
| `2` | Hint forcé |

---

### `StatusUpdate`
Mettre à jour le statut du joueur.
```json
{
    "cmd": "StatusUpdate",
    "status": 30
}
```
| Valeur | Statut |
|--------|--------|
| `5` | `CLIENT_CONNECTED` |
| `10` | `CLIENT_READY` |
| `20` | `CLIENT_PLAYING` |
| `30` | `CLIENT_GOAL` — victoire |

---

### `Say`
Envoyer un message dans le chat AP.
```json
{
    "cmd": "Say",
    "text": "Hello from Minecraft!"
}
```

---

### `GetDataPackage`
Demander le data package (noms de tous les items/locations du multiworld).
```json
{
    "cmd": "GetDataPackage",
    "games": ["Minecraft"]
}
```

---

### `Bounce`
Envoyer un message à d'autres joueurs. Utilisé notamment pour **DeathLink**.
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

---

### `Get`
Lire une valeur depuis le DataStorage AP (persistance entre sessions).
```json
{
    "cmd": "Get",
    "keys": ["euclesia_player1_unlocked_mobs"]
}
```

---

### `Set`
Écrire une valeur dans le DataStorage AP.
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
| Opération | Effet |
|-----------|-------|
| `replace` | Remplace la valeur |
| `add` | Additionne (nombres) ou concatène (listes) |
| `mul` | Multiplie |
| `min` / `max` | Garde le min/max |
| `and` / `or` / `xor` | Opérations binaires |
| `left_shift` / `right_shift` | Décalage binaire |
| `update` | Merge un dict |

---

### `SetNotify`
S'abonner aux changements d'une clé DataStorage.
```json
{
    "cmd": "SetNotify",
    "keys": ["euclesia_player1_unlocked_mobs"]
}
```

---

## Serveur → Mod

### `RoomInfo`
Reçu à la connexion, avant `Connect`. Contient les infos de la room.
```json
{
    "cmd": "RoomInfo",
    "version": {"major": 0, "minor": 6, "build": 0},
    "games": ["Minecraft", "Zelda"],
    "tags": [],
    "password": false,
    "permissions": {},
    "hint_cost": 10,
    "location_check_points": 1,
    "datapackage_checksums": {}
}
```

---

### `Connected`
Reçu après un `Connect` réussi. Contient le `slot_data`.
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
    "slot_data": {},
    "slot_info": {},
    "hint_points": 0
}
```

---

### `ConnectionRefused`
Reçu si la connexion échoue.
```json
{
    "cmd": "ConnectionRefused",
    "errors": ["InvalidSlot"]
}
```
| Erreur | Cause |
|--------|-------|
| `InvalidSlot` | Slot inexistant |
| `InvalidGame` | Mauvais nom de jeu |
| `IncompatibleVersion` | Version AP incompatible |
| `InvalidPassword` | Mauvais mot de passe |
| `InvalidItemsHandling` | `items_handling` invalide |

---

### `ReceivedItems`
Reçu quand le joueur reçoit un ou plusieurs items.
```json
{
    "cmd": "ReceivedItems",
    "index": 0,
    "items": [
        {
            "item": 15466496,
            "location": 15470592,
            "player": 2,
            "flags": 1
        }
    ]
}
```
| `flags` | Classification |
|---------|----------------|
| `0` | Normal |
| `1` | Progression |
| `2` | Useful |
| `4` | Trap |

---

### `LocationInfo`
Réponse à un `LocationScouts`.
```json
{
    "cmd": "LocationInfo",
    "locations": [
        {
            "item": 15466496,
            "location": 15470592,
            "player": 1,
            "flags": 1
        }
    ]
}
```

---

### `RoomUpdate`
Mise à jour de la room (nouveaux checks, nouveaux joueurs...).
```json
{
    "cmd": "RoomUpdate",
    "checked_locations": [15470592],
    "players": []
}
```

---

### `PrintJSON`
Message à afficher dans le chat AP.
```json
{
    "cmd": "PrintJSON",
    "data": [
        {"type": "player_id", "text": "1"},
        {"type": "text", "text": " found "},
        {"type": "item_id", "text": "15466496", "flags": 1, "player": 1}
    ],
    "type": "ItemSend"
}
```
| `type` | Déclencheur |
|--------|-------------|
| `ItemSend` | Un item a été envoyé |
| `ItemCheat` | Item donné via commande |
| `Hint` | Un hint a été créé |
| `Join` | Un joueur a rejoint |
| `Part` | Un joueur a quitté |
| `Chat` | Message chat |
| `ServerChat` | Message système |
| `Tutorial` | Message tutoriel |
| `TagsChanged` | Tags mis à jour |
| `CommandResult` | Résultat d'une commande |
| `Goal` | Un joueur a atteint son objectif |
| `Release` | Un joueur a releasé ses locations |
| `Collect` | Un joueur a collecté ses items |
| `Countdown` | Compte à rebours |

---

### `DataPackage`
Réponse à `GetDataPackage`. Contient tous les noms d'items et locations.
```json
{
    "cmd": "DataPackage",
    "data": {
        "games": {
            "Minecraft": {
                "item_name_to_id": {
                    "Progressive Tools": 15466496
                },
                "location_name_to_id": {
                    "Advancement: Story: Stone Age": 15470592
                },
                "checksum": "abc123"
            }
        }
    }
}
```

---

### `Bounced`
Reçu quand un `Bounce` correspond aux tags du joueur. Utilisé pour **DeathLink**.
```json
{
    "cmd": "Bounced",
    "tags": ["DeathLink"],
    "data": {
        "time": 1234567890.0,
        "source": "OtherPlayer",
        "cause": "Creeper explosion"
    }
}
```

---

### `InvalidPacket`
Reçu si un packet envoyé était malformé.
```json
{
    "cmd": "InvalidPacket",
    "type": "cmd",
    "original_cmd": "LocationChecks",
    "text": "locations is required"
}
```
| `type` | Cause |
|--------|-------|
| `cmd` | Commande inconnue |
| `arguments` | Arguments manquants ou invalides |

---

### `Retrieved`
Réponse à un `Get`.
```json
{
    "cmd": "Retrieved",
    "keys": {
        "euclesia_player1_unlocked_mobs": ["minecraft:zombie", "minecraft:creeper"]
    }
}
```

---

### `SetReply`
Réponse à un `Set` si `want_reply = true`.
```json
{
    "cmd": "SetReply",
    "key": "euclesia_player1_unlocked_mobs",
    "value": ["minecraft:zombie", "minecraft:creeper"],
    "original_value": ["minecraft:zombie"]
}
```
