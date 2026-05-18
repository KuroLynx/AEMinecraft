from BaseClasses import Item, Location, Region, Tutorial
from worlds.AutoWorld import WebWorld, World
from worlds.stardew_valley.data.bundles_data.meme_bundles import trap_items

from .data import *
from .options import BossSelectionMode, MCOptions
from .regions import MCRegion
from .rules import set_rules


# ---------------------------------------------------------------------------
# Classes Item et Location
# ---------------------------------------------------------------------------

class MCItem(Item):
    game = "Minecraft"


class MCLocation(Location):
    game = "Minecraft"


# ---------------------------------------------------------------------------
# WebWorld
# ---------------------------------------------------------------------------

class MCWebWorld(WebWorld):
    theme = "dirt"
    tutorials = [
        Tutorial(
            tutorial_name = "Setup Guide",
            description = "Guide to set up the Euclesia Minecraft randomizer.",
            language = "English",
            file_name = "setup_en.md",
            link = "setup/en",
            authors = ["KuroLynx"],
        )
    ]


# ---------------------------------------------------------------------------
# World principal
# ---------------------------------------------------------------------------

class MCWorld(World):
    """Minecraft Randomizer by KuroLynx (Mod by EDGN)"""
    game = "Minecraft"
    options: MCOptions
    options_dataclass = MCOptions
    web = MCWebWorld()
    death_list: list[str] = []
    selected_bosses: list[str] = []

    item_name_to_id = {
        **{name: data.id for (name, data) in ITEMS.items()},
        **{f"{ENTITY_UNLOCK_PREFIX}{name}": BASE_ID_ENTITY_UNLOCK + mob.id for (name, mob) in MOBS_ALL.items()},
        **{f"{STRUCT_UNLOCK_PREFIX}{name}": BASE_ID_STRUCT_UNLOCK + structure.id for (name, structure) in
           STRUCTURES.items()}
    }

    location_name_to_id = {name: data.id for (name, data) in ALL_LOCATIONS.items()}

    # -----------------------------------------------------------------------
    # Génération
    # -----------------------------------------------------------------------

    def create_item(self, name: str) -> MCItem:

        if name in ITEMS:
            item_data: MCItemData = ITEMS[name]
            return MCItem(name, item_data.classification, item_data.id, self.player)

        if name.startswith(ENTITY_UNLOCK_PREFIX):
            mob_name = name.removeprefix(ENTITY_UNLOCK_PREFIX)
            mob_data = MOBS_ALL[mob_name]
            return MCItem(name, mob_data.unlock_classification, BASE_ID_ENTITY_UNLOCK + mob_data.id, self.player)

        if name.startswith(STRUCT_UNLOCK_PREFIX):
            struct_name = name.removeprefix(STRUCT_UNLOCK_PREFIX)
            struct_data = STRUCTURES[struct_name]
            return MCItem(name, struct_data.classification, BASE_ID_STRUCT_UNLOCK + struct_data.id, self.player)

        raise KeyError(f"Unknown item: {name}")

    def generate_early(self) -> None:
        """Génère les données aléatoires qui doivent être disponibles dès set_rules."""
        self.selected_bosses = self._get_selected_bosses()

        if self.options.death_list:
            count = min(self.options.death_list_count.value, len(MOBS_ALL))
            self.death_list: list[str] = self.random.sample(list(MOBS_ALL.keys()), count)

    def _get_active_locations(self) -> dict[str, MCLocationData]:
        """Retourne les locations actives selon les options du joueur."""
        locations: dict[str, MCLocationData] = {
            **LOCATIONS_ADVANCEMENT,
            **LOCATIONS_BOSS_KILLS,
        }

        if self.options.kill_sanity:
            locations.update(LOCATIONS_MOB_KILLS)

        return locations

    def create_regions(self) -> None:
        added_regions: dict[str, Region] = {}

        for region in MCRegion:
            added_regions[region] = Region(region, self.player, self.multiworld)

        for loc_name, loc_data in self._get_active_locations().items():
            region = added_regions[MCRegion(loc_data.region)]
            location = MCLocation(self.player, loc_name, loc_data.id, region)
            region.locations.append(location)

        added_regions[MCRegion.MENU].connect(added_regions[MCRegion.OVERWORLD])

        added_regions[MCRegion.OVERWORLD].connect(
            added_regions[MCRegion.NETHER],
            rule = lambda state: (
                    state.has("Dimension Unlock: Nether", self.player) and
                    state.can_reach(f"{ADVANCEMENT_PREFIX}Ice Bucket Challenge", "Location", self.player)
            ),
        )

        added_regions[MCRegion.OVERWORLD].connect(
            added_regions[MCRegion.THE_END],
            rule = lambda state: (
                    state.has("Dimension Unlock: The End", self.player) and
                    state.can_reach(f"{ADVANCEMENT_PREFIX}Into Fire", "Location", self.player)
            ),
        )

        # If needed to add new dimensions (like TP), do it here i guess

        self.multiworld.regions += list(added_regions.values())

    def create_items(self) -> None:
        pool: list[MCItem] = []

        for name, item_data in ITEMS.items():
            if not self.options.villager_trust and name == "Progressive Villager Trust":
                continue

            for _ in range(item_data.count):
                pool.append(self.create_item(name))

        if self.options.mob_spawn_lock_category:
            for mob_name, mob_data in MOBS_ALL.items():
                if mob_data.category in self.options.mob_spawn_lock_category.value:
                    pool.append(self.create_item(f"{ENTITY_UNLOCK_PREFIX}{mob_name}"))

        active_location_count = len(self._get_active_locations())

        filler_items = [item_name for item_name, item_data in ITEMS.items()
                        if item_data.classification == ItemClassification.filler
                        for _ in range(item_data.count)
                        ]

        shortage = active_location_count - len(pool)
        if shortage > 0:
            for _ in range(shortage):
                pool.append(self.create_item(self.random.choice(filler_items)))

        mc_trap_items = [item_name for item_name, item_data in ITEMS.items()
                         if item_data.classification == ItemClassification.trap
                         for _ in range(item_data.count)
                         ]

        trap_chance = self.options.trap_chance.value

        if mc_trap_items and trap_chance > 0:
            for (index, item) in enumerate(pool):
                if item.classification == ItemClassification.filler:
                    if self.random.randint(1, 100) <= trap_chance:
                        pool[index] = self.create_item(self.random.choice(mc_trap_items))

        self.multiworld.itempool += pool[:active_location_count]

    def set_rules(self) -> None:
        set_rules(self)
        self._set_goal_rule()

    # -----------------------------------------------------------------------
    # Goal
    # -----------------------------------------------------------------------

    def _get_selected_bosses(self) -> list[str]:
        available_bosses = [name for name in MOBS_BOSS.keys() if name in self.options.boss_list.value]
        count = min(self.options.boss_selection_mode.value, len(available_bosses))
        return self.random.sample(available_bosses, count)

    def _get_primary_condition(self):
        boss_locations = [f"{BOSS_KILL_PREFIX}{name}" for name in self.selected_bosses]
        return lambda state: all(state.can_reach(location, "Location", self.player) for location in boss_locations)

    def _set_goal_rule(self) -> None:
        player = self.player
        primary_condition = self._get_primary_condition()

        conditions = [primary_condition]

        required_advancement_count = self.options.advancements_required.value

        if required_advancement_count > 0:
            advancements_locations = list(LOCATIONS_ADVANCEMENT.keys())
            required = required_advancement_count

            def advancement_condition(state) -> bool:
                return sum(1 for location in advancements_locations if state.can_reach(location, "Location", self.player)) >= required

            conditions.append(advancement_condition)

        if self.options.death_list:
            death_list_locations = [
                f"{BOSS_KILL_PREFIX}{mob_name}" if MOBS_ALL[mob_name].category == MCEntityCategory.BOSS
                else f"{ENTITY_KILL_PREFIX}{mob_name}"
                for mob_name in self.death_list
            ]

            def death_list_condition(state) -> bool:
                return all(state.can_reach(location, "Location", self.player) for location in death_list_locations)

            conditions.append(death_list_condition)

        def completion_condition(state) -> bool:
            return all(cond(state) for cond in conditions)

        self.multiworld.completion_condition[player] = completion_condition


# -----------------------------------------------------------------------
# Slot data (envoyé au mod Fabric)
# -----------------------------------------------------------------------

def fill_slot_data(self) -> dict:
    return {
        # --- Options ---
        "boss_selection_mode"  : self.options.boss_selection_mode.value,
        "boss_list"            : [MOBS_BOSS[name].game_id for name in self.selected_bosses],
        "death_link"           : bool(self.options.death_link.value),
        "villager_trust"       : bool(self.options.villager_trust.value),
        "kill_sanity"          : bool(self.options.kill_sanity.value),
        "death_list"           : bool(self.options.death_list.value),
        "death_list_count"     : self.options.death_list_count.value,
        "advancements_required": self.options.advancements_required.value,
        "mob_spawn_lock"       : list(self.options.mob_spawn_lock_category.value),

        # --- Mapping item ID → nom (le mod applique l'effet depuis le nom) ---
        "items"                : {
            item_data.id: name
            for name, item_data in ITEMS.items()
        },

        # --- Mapping game_id → location ID (le mod envoie le check depuis le game_id) ---
        "locations"            : {
            location_data.game_id: location_data.id
            for location_data in ALL_LOCATIONS.values()
            if location_data.game_id  # exclut les locations sans game_id
        },

        # --- Mobs à tracker (uniquement ceux actifs selon les options) ---
        "tracked_mobs"         : {
            mob_data.game_id: loc_data.id
            for loc_name, loc_data in self._get_active_locations().items()
            if loc_data.category in (MCLocationCategory.MOB_KILL, MCLocationCategory.BOSS_KILL)
            for mob_name, mob_data in {**MOBS_ALL}.items()
            if f"{ENTITY_KILL_PREFIX}{mob_name}" == loc_name or f"{BOSS_KILL_PREFIX}{mob_name}" == loc_name
        },

        # --- Death list ---
        "death_list_mobs"      : [MOBS_ALL[mob_name].game_id for mob_name in self.death_list],

        # --- Mob spawn lock : game_id des mobs à bloquer au spawn ---
        "mob_spawn_lock_mobs"  : {
            mob_data.game_id: BASE_ID_ENTITY_UNLOCK + mob_data.id
            for mob_name, mob_data in MOBS_ALL.items()
            if mob_data.category in self.options.mob_spawn_lock_category.value
        },
    }
