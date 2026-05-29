# Euclesia APWorld — Slot Data Reference

Documentation des données envoyées au mod Fabric via `MCWorld.fill_slot_data()` (dans `euclesia/__init__.py`),
transmises dans le packet `Connected` lors de la connexion au serveur Archipelago.

> Ce document est généré à partir du code actuel. En cas de doute, `fill_slot_data()` fait foi.

---

## Schéma des IDs

Tous les IDs AP dérivent de bases fixes définies dans `euclesia/data.py` :

| Base | Valeur | Hex | Contenu |
|------|--------|-----|---------|
| `BASE_ID_ITEMS`           | `15466496` | `0xEC0000` | Items de base (`items.csv`) |
| `BASE_ID_ENTITY_UNLOCK`   | `15466752` | `0xEC0100` | `Entity Unlock: <mob>` (`BASE + mob.id`) |
| `BASE_ID_STRUCT_UNLOCK`   | `15467008` | `0xEC0200` | `Structure Unlock: <structure>` (`BASE + structure.id`) |
| `BASE_ID_LOC_ADVANCEMENT` | `15470592` | `0xEC1000` | Locations d'advancements |
| `BASE_ID_LOC_BOSS_KILL`   | `15470848` | `0xEC1100` | Locations de kill de boss (`BASE + mob.id`) |
| `BASE_ID_LOC_MOB_KILL`    | `15471104` | `0xEC1200` | Locations de kill de mob (`BASE + mob.id`) |

Les `game_id` de mobs et de structures sont toujours préfixés `minecraft:` (ex. `minecraft:zombie`,
`minecraft:ancient_city`). Les `game_id` d'advancements ont la forme `minecraft:<tab>/<id>`
(ex. `minecraft:story/mine_stone`).

---

## Condition de victoire

Il n'y a **pas** de champ `goal`. La victoire est la **conjonction** des conditions actives
(le mod envoie `StatusUpdate(30)` quand toutes sont remplies) :

1. **Boss** — tuer tous les boss listés dans `boss_list` (toujours actif).
2. **Advancements** — compléter au moins `advancements_required` advancements (si `> 0`).
3. **Death list** — tuer tous les mobs de `death_list_mobs` (si `death_list = true`).

---

## Options

### `boss_selection_mode` — `int`
Nombre de boss requis demandé par l'option (`1`–`4`). Informatif : `boss_list` contient déjà
la liste effective à tuer.

### `boss_list` — `list[str]`
`game_id` des boss à tuer pour valider la condition principale.

```json
"boss_list": ["minecraft:ender_dragon", "minecraft:warden", "minecraft:wither", "minecraft:elder_guardian"]
```

### `death_link` — `bool`
Si `true`, la mort d'un joueur tue tous les joueurs DeathLink (et inversement).

### `villager_trust` — `bool`
Si `true`, les niveaux de commerce des villageois sont verrouillés jusqu'à réception des items
`Progressive Villager Trust` (5 niveaux).

### `kill_sanity` — `bool`
Si `true`, tuer un mob pour la première fois envoie un check. Les boss envoient toujours un check.

### `death_list` — `bool`
Si `true`, une liste de mobs (`death_list_mobs`) devient une condition de victoire supplémentaire.

### `death_list_count` — `int`
Nombre de mobs dans la death list (`1`–`88`, défaut `42`). Clampé au nombre réel de mobs si nécessaire.

### `advancements_required` — `int`
Nombre d'advancements à compléter (`0`–`125`, défaut `0`). Clampé au nombre d'advancements réellement
présents dans le seed (ex. `121` si `challenge_sanity = false`).

### `mob_spawn_lock` — `list[str]`
Catégories de mobs bloqués au spawn jusqu'à réception de leur `Entity Unlock`. Valeurs possibles :
`"passive"`, `"neutral"`, `"hostile"`. Liste vide = désactivé.

```json
"mob_spawn_lock": ["hostile", "neutral"]
```

---

## Mappings

### `items` — `dict[int, str]`
Mapping **complet** `item ID AP → nom`. Inclut les items de base **et** chaque `Entity Unlock`
et `Structure Unlock` (dérivé de `item_name_to_id`), pour que le mod puisse résoudre n'importe
quel item reçu via `ReceivedItems`.

```json
"items": {
    "15466496": "Progressive Villager Trust",
    "15466497": "Dimension Unlock: Nether",
    "15466498": "Dimension Unlock: The End",
    "15466499": "Progressive Material Handling",
    "15466752": "Entity Unlock: Allay",
    "15466753": "Entity Unlock: Armadillo",
    "15467008": "Structure Unlock: Ancient City",
    "15467009": "Structure Unlock: Bastion Remnant"
}
```

### `locations` — `dict[str, int]`
Mapping `game_id → location ID AP` pour **toutes** les locations existantes (advancements, kills de
boss, kills de mob). Quand l'événement correspondant se produit en jeu, le mod envoie un
`LocationChecks` avec l'ID associé.

```json
"locations": {
    "minecraft:story/root":       15470592,
    "minecraft:story/mine_stone": 15470593,
    "minecraft:elder_guardian":   15470907,
    "minecraft:ender_dragon":     15470908,
    "minecraft:allay":            15471104
}
```

### `tracked_mobs` — `dict[str, int]`
Sous-ensemble de `locations` : uniquement les `game_id` de mobs/boss dont le kill est une location
**active** selon les options. Le mod n'écoute les kill events que pour ces mobs.

```json
// kill_sanity = false : seulement les boss
"tracked_mobs": {
    "minecraft:elder_guardian": 15470907,
    "minecraft:ender_dragon":   15470908,
    "minecraft:warden":         15470928,
    "minecraft:wither":         15470930
}

// kill_sanity = true : boss + tous les mobs
"tracked_mobs": {
    "minecraft:elder_guardian": 15470907,
    "minecraft:ender_dragon":   15470908,
    "minecraft:allay":          15471104
}
```

### `death_list_mobs` — `list[str]`
`game_id` des mobs assignés par la death list. Liste vide si `death_list = false`.

```json
"death_list_mobs": ["minecraft:creeper", "minecraft:blaze", "minecraft:enderman"]
```

### `mob_spawn_lock_mobs` — `dict[str, int]`
Mapping `game_id du mob → item ID de son unlock`. Le mod bloque le spawn de chaque mob listé
jusqu'à réception de l'item correspondant. Dict vide si `mob_spawn_lock = []`.

```json
// mob_spawn_lock = ["hostile"]
"mob_spawn_lock_mobs": {
    "minecraft:blaze":   15466806,
    "minecraft:bogged":  15466807,
    "minecraft:breeze":  15466808,
    "minecraft:creaking":15466809
}
```

### `structure_locks` — `dict[str, int]`
Mapping `game_id de la structure → item ID de son unlock`, pour **toutes** les structures (toujours
verrouillées jusqu'à réception de leur `Structure Unlock`). Mirroir de `mob_spawn_lock_mobs` côté
structures.

```json
"structure_locks": {
    "minecraft:ancient_city":    15467008,
    "minecraft:bastion_remnant": 15467009,
    "minecraft:buried_treasure": 15467010,
    "minecraft:desert_pyramid":  15467011
}
```

> Note : une structure peut être de classification `useful` (ex. Nether Fossil) — elle reste dans
> `structure_locks` (donc verrouillée), mais son unlock n'est requis par aucune règle de logique.

---

## Flux de communication

```
Connexion
  Mod ──── Connect (slot, password) ────► Serveur AP
  Mod ◄─── Connected (slot_data)   ───── Serveur AP

En jeu
  Mod ──── LocationChecks ([id])   ────► Serveur AP   (advancement / mob kill, via locations & tracked_mobs)
  Mod ◄─── ReceivedItems ([item])  ───── Serveur AP   (item débloqué, résolu via items)
  Mod ──── StatusUpdate(30)        ────► Serveur AP   (victoire : conditions remplies)
```

Voir [`ap_protocol.md`](ap_protocol.md) pour le détail des packets.
