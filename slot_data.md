# Euclesia APWorld — Slot Data Reference

Documentation complète des données envoyées au mod Fabric via `fill_slot_data()` lors de la connexion au serveur Archipelago.

---

## `goal` — `int`

La condition de victoire choisie par le joueur.

| Valeur | Goal                                                |
|--------|-----------------------------------------------------|
| `0`    | Vanilla — tuer l'Ender Dragon                       |
| `1`    | All Bosses — tuer tous les boss de `boss_selection` |

```json
// Vanilla
"goal": 0

// All Bosses
"goal": 1
```

---

## `boss_selection` — `list[str]`

Les boss qui doivent être tués si `goal = 1` (all_bosses).  
Les valeurs correspondent aux clés de `MOBS_BOSS` dans `data.py`.

```json
// Tous les boss
"boss_selection": ["ender_dragon", "wither", "elder_guardian", "warden"]

// Seulement le Dragon et le Wither
"boss_selection": ["ender_dragon", "wither"]

// Seulement l'Ender Dragon
"boss_selection": ["ender_dragon"]
```

---

## `death_link` — `bool`

Si `true`, quand le joueur meurt, tous les joueurs du multiworld meurent, et vice-versa.

```json
// Activé
"death_link": true

// Désactivé
"death_link": false
```

---

## `villager_trust` — `bool`

Si `true`, les niveaux de commerce des villageois sont verrouillés jusqu'à réception des items `Progressive Villager Trust` (1 item par niveau, 5 niveaux au total).

```json
// Activé
"villager_trust": true

// Désactivé
"villager_trust": false
```

---

## `kill_sanity` — `bool`

Si `true`, tuer un mob pour la première fois envoie un check AP.  
Les boss envoient toujours un check, indépendamment de cette option.

```json
// Activé — tous les mobs envoient un check
"kill_sanity": true

// Désactivé — seuls les boss envoient un check
"kill_sanity": false
```

---

## `death_list` — `bool`

Si `true`, une liste de mobs spécifiques (`death_list_mobs`) est assignée au joueur.  
Tuer tous ces mobs est une condition de victoire en plus du goal principal.

```json
// Activé
"death_list": true

// Désactivé
"death_list": false
```

---

## `death_list_count` — `int`

Nombre de mobs dans la death list. Compris entre `1` et `87`.

```json
// 5 mobs à tuer
"death_list_count": 5

// 42 mobs à tuer (valeur par défaut)
"death_list_count": 42

// Maximum
"death_list_count": 87
```

---

## `advancements_required` — `int`

Nombre d'advancements à compléter pour gagner si `goal = advancementsanity`. Compris entre `0` et `125`.

```json
// Aucun advancement requis
"advancements_required": 0

// 60 advancements requis (valeur par défaut)
"advancements_required": 60

// Tous les advancements
"advancements_required": 125
```

---

## `mob_spawn_lock` — `list[str]`

Catégories de mobs bloqués au spawn jusqu'à réception de leur item `Entity Unlock: <nom>`.  
Liste vide = fonctionnalité désactivée.

| Valeur | Catégorie |
|--------|-----------|
| `"passive"` | Mobs passifs |
| `"neutral"` | Mobs neutres |
| `"hostile"` | Mobs hostiles |

```json
// Désactivé
"mob_spawn_lock": []

// Seulement les hostiles
"mob_spawn_lock": ["hostile"]

// Hostiles et neutres
"mob_spawn_lock": ["hostile", "neutral"]

// Tout le monde
"mob_spawn_lock": ["passive", "neutral", "hostile"]
```

---

## `items` — `dict[int, str]`

Mapping complet `item ID AP → nom de l'item`.  
À la réception d'un `ReceivedItems`, le mod lookup l'ID et applique l'effet en jeu correspondant au nom.

```json
"items": {
    15466496: "Progressive Tools",
    15466497: "Progressive Armor",
    15466498: "Progressive Sword",
    15466499: "Flint and Steel",
    15466500: "Ender Pearl",
    15466501: "Blaze Rod",
    15466502: "Bucket",
    15466503: "Shield",
    15466504: "Boat",
    15466506: "Progressive Villager Trust",
    15466516: "Bread",
    15466517: "Golden Apple",
    15466518: "Enchanted Book",
    15466519: "TNT",
    15466520: "Iron Ingot Bundle",
    15466521: "Experience Bottle",
    15466595: "Victory",
    15466997: "Entity Unlock: Zombie",
    15466998: "Entity Unlock: Creeper",
    15466999: "Dimension Unlock: Nether",
    15467000: "Dimension Unlock: The End"
}
```

---

## `locations` — `dict[str, int]`

Mapping `game_id Minecraft → location ID AP`.  
Quand un événement se produit en jeu (advancement complété, mob tué), le mod cherche le `game_id` correspondant et envoie le `LocationChecks` avec l'ID AP associé.

```json
"locations": {
    // Advancements
    "minecraft:story/mine_stone":       15470592,
    "minecraft:story/enter_the_nether": 15470602,
    "minecraft:nether/find_fortress":   15470612,
    "minecraft:end/kill_dragon":        15470643,

    // Boss kills
    "minecraft:ender_dragon":           15470792,
    "minecraft:wither":                 15470793,
    "minecraft:elder_guardian":         15470794,
    "minecraft:warden":                 15470795,

    // Mob kills (si kill_sanity = true)
    "minecraft:zombie":                 15470901,
    "minecraft:creeper":                15470902
}
```

---

## `tracked_mobs` — `dict[str, int]`

Mapping `game_id du mob → location ID AP` pour uniquement les mobs actifs selon les options.  
Le mod n'écoute les kill events que pour les mobs présents dans cette map.

```json
// kill_sanity = false : seulement les boss
"tracked_mobs": {
    "minecraft:ender_dragon":   15470792,
    "minecraft:wither":         15470793,
    "minecraft:elder_guardian": 15470794,
    "minecraft:warden":         15470795
}

// kill_sanity = true : boss + tous les mobs
"tracked_mobs": {
    "minecraft:ender_dragon":   15470792,
    "minecraft:wither":         15470793,
    "minecraft:elder_guardian": 15470794,
    "minecraft:warden":         15470795,
    "minecraft:zombie":         15470901,
    "minecraft:creeper":        15470902,
    "minecraft:skeleton":       15470903
}
```

---

## `death_list_mobs` — `list[str]`

Liste des `game_id` des mobs assignés par la death list.  
Le mod affiche cette liste au joueur au démarrage et track leurs kills.  
Liste vide si `death_list = false`.

```json
// death_list = false
"death_list_mobs": []

// death_list = true, death_list_count = 5
"death_list_mobs": [
    "minecraft:creeper",
    "minecraft:blaze",
    "minecraft:enderman",
    "minecraft:skeleton",
    "minecraft:zombie"
]
```

---

## `mob_spawn_lock_mobs` — `dict[str, int]`

Mapping `game_id du mob → item ID AP de son unlock`.  
Le mod bloque le spawn de chaque mob listé jusqu'à ce que l'item ID correspondant ait été reçu via `ReceivedItems`.  
Dict vide si `mob_spawn_lock = []`.

```json
// mob_spawn_lock = []
"mob_spawn_lock_mobs": {}

// mob_spawn_lock = ["hostile"]
"mob_spawn_lock_mobs": {
    "minecraft:zombie":          15467086,
    "minecraft:creeper":         15467059,
    "minecraft:skeleton":        15467076,
    "minecraft:blaze":           15467055,
    "minecraft:wither_skeleton": 15467084
}

// mob_spawn_lock = ["hostile", "neutral"]
"mob_spawn_lock_mobs": {
    "minecraft:zombie":          15467086,
    "minecraft:creeper":         15467059,
    "minecraft:skeleton":        15467076,
    "minecraft:spider":          15467050,
    "minecraft:enderman":        15467040,
    "minecraft:piglin":          15467047
}

// mob_spawn_lock = ["passive", "neutral", "hostile"]
"mob_spawn_lock_mobs": {
    "minecraft:zombie":          15467086,
    "minecraft:creeper":         15467059,
    "minecraft:skeleton":        15467076,
    "minecraft:spider":          15467050,
    "minecraft:enderman":        15467040,
    "minecraft:piglin":          15467047,
    "minecraft:cow":             15467011,
    "minecraft:sheep":           15467024,
    "minecraft:chicken":         15467008
}
```

---

## Flux de communication

```
Connexion
  Mod ──── Connect (slot, password) ────► Serveur AP
  Mod ◄─── Connected (slot_data)   ───── Serveur AP

En jeu
  Mod ──── LocationChecks ([id])   ────► Serveur AP   (advancement / mob kill)
  Mod ◄─── ReceivedItems ([item])  ───── Serveur AP   (item débloqué)
```
