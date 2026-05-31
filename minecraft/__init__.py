from BaseClasses import Item, Location, Region, Tutorial
from worlds.AutoWorld import WebWorld, World

from .data import *
from .options import MCOptions
from .regions import MCRegion
from .rules.root import set_rules
from .rules.ast import Const, Has, ReachLocation, and_
from .rules.constants import *
from .logic_export import build_logic_export


# ---------------------------------------------------------------------------
# Classes Item et Location
# ---------------------------------------------------------------------------

class MCItem(Item):
    game = "Minecraft"


class MCLocation(Location):
    game = "Minecraft"


def _gate_classification(original: ItemClassification) -> ItemClassification:
    """Classification for an unlock item used as a logic gate.

    Only progression items are collected into AP's CollectionState, so any gate MUST be
    progression. Gates that were not originally progression are "insignificant progression":
    kept logic-bearing, but flagged skip_balancing so progression balancing leaves them alone.
    """
    if original == ItemClassification.progression:
        return ItemClassification.progression
    return ItemClassification.progression_skip_balancing


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

        # Every Entity Unlock that enters the pool gates at least its own Kill Entity location, so
        # it must stay progression-flavoured. AP's CollectionState only collects progression items,
        # so a useful/filler gate is invisible to the solver; _gate_classification keeps them
        # logic-bearing (progression, or skip_balancing when not originally progression).
        if name.startswith(ENTITY_UNLOCK_PREFIX):
            mob_name = name.removeprefix(ENTITY_UNLOCK_PREFIX)
            mob_data = MOBS_ALL[mob_name]
            classification = _gate_classification(mob_data.unlock_classification)
            return MCItem(name, classification, BASE_ID_ENTITY_UNLOCK + mob_data.id, self.player)

        # Structures are curated individually in structures.csv: gates are progression /
        # progression_skip_balancing, while a structure no rule references (e.g. Nether Fossil) may
        # be useful. Honour the CSV classification directly rather than forcing a gate upgrade.
        if name.startswith(STRUCT_UNLOCK_PREFIX):
            struct_name = name.removeprefix(STRUCT_UNLOCK_PREFIX)
            struct_data = STRUCTURES[struct_name]
            return MCItem(name, struct_data.classification, BASE_ID_STRUCT_UNLOCK + struct_data.id, self.player)

        raise KeyError(f"Unknown item: {name}")

    def generate_early(self) -> None:
        """Génère les données aléatoires qui doivent être disponibles dès set_rules."""
        self.selected_bosses = self._get_selected_bosses()

        # Clamp the advancement goal to the number of advancements that actually exist this seed
        # (challenge_sanity drops some). Done here so the goal rule and slot_data agree, and the
        # mod is never asked for more advancements than can be completed.
        active_advancement_count = sum(
            1 for loc_data in self._get_active_locations().values()
            if loc_data.category == MCLocationCategory.ADVANCEMENT
        )
        if self.options.advancements_required.value > active_advancement_count:
            self.options.advancements_required.value = active_advancement_count

    def _get_locked_structures(self) -> set[str]:
        """Structures locked behind a 'Structure Unlock' item, per the structure_unlock option.

        The option accepts dimension presets ("Overworld"/"Nether"/"The End"), "All", and/or
        individual structure names; this resolves them to a concrete set of structure names.
        """
        selected = self.options.structure_unlock.value
        if "All" in selected:
            return set(STRUCTURES.keys())

        locked: set[str] = set()
        for entry in selected:
            if entry in ("Overworld", "Nether", "The End"):
                locked |= {name for name, data in STRUCTURES.items() if data.region == entry}
            elif entry in STRUCTURES:
                locked.add(entry)
        return locked

    def _get_active_locations(self) -> dict[str, MCLocationData]:
        """Retourne les locations actives selon les options du joueur."""
        locations: dict[str, MCLocationData] = {
            **LOCATIONS_ADVANCEMENT,
            **LOCATIONS_BOSS_KILL
        }

        if self.options.kill_sanity:
            locations.update(LOCATIONS_MOB_KILL)

        if not self.options.challenge_sanity:
            locations = {
                name: loc_data for name, loc_data in locations.items() if not loc_data.challenge
            }

        return locations

    def create_regions(self) -> None:
        added_regions: dict[str, Region] = {}

        for region in MCRegion:
            added_regions[region] = Region(region, self.player, self.multiworld)

        for loc_name, loc_data in self._get_active_locations().items():
            region = added_regions[MCRegion(loc_data.region)]
            location = MCLocation(self.player, loc_name, loc_data.id, region)
            region.locations.append(location)

        # Entrance rules as AST nodes (callable for AP, serializable for the mod export).
        nether_rule = and_(
            Has(self.player, ITEM_DIMENSION_NETHER),
            ReachLocation(self.player, f"{ADVANCEMENT_PREFIX}{A_ICE_BUCKET_CHALLENGE}"),
            Has(self.player, f"Knowledge: {K_PYRO}"),
        )
        end_rule = and_(
            Has(self.player, ITEM_DIMENSION_END),
            ReachLocation(self.player, f"{ADVANCEMENT_PREFIX}{A_EYE_SPY}"),
        )

        added_regions[MCRegion.MENU].connect(added_regions[MCRegion.OVERWORLD])
        added_regions[MCRegion.OVERWORLD].connect(added_regions[MCRegion.NETHER], rule=nether_rule)
        added_regions[MCRegion.OVERWORLD].connect(added_regions[MCRegion.THE_END], rule=end_rule)

        # If needed to add new dimensions (like TP), do it here i guess

        # Captured for build_logic_export (region names as plain strings, not enum members).
        self.logic_region_rules = {
            MCRegion.MENU.value: [{"to": MCRegion.OVERWORLD.value, "rule": Const(True)}],
            MCRegion.OVERWORLD.value: [
                {"to": MCRegion.NETHER.value, "rule": nether_rule},
                {"to": MCRegion.THE_END.value, "rule": end_rule},
            ],
        }

        self.multiworld.regions += list(added_regions.values())

    def create_items(self) -> None:
        pool: list[MCItem] = []

        # Progression + useful only — fillers and traps are derived later
        for name, item_data in ITEMS.items():
            if item_data.classification in (ItemClassification.filler, ItemClassification.trap):
                continue
            if not self.options.villager_trust and name == ITEM_VILLAGER_TRUST:
                continue
            for _ in range(item_data.count):
                pool.append(self.create_item(name))

        if self.options.mob_spawn_lock_category:
            for mob_name, mob_data in MOBS_ALL.items():
                if mob_data.category in self.options.mob_spawn_lock_category.value:
                    pool.append(self.create_item(f"{ENTITY_UNLOCK_PREFIX}{mob_name}"))

        # Structure unlocks: only the structures locked by the structure_unlock option are added.
        # Unlocked structures are gated by their dimension instead (see RuleHelper.structure).
        for struct_name in self._get_locked_structures():
            pool.append(self.create_item(f"{STRUCT_UNLOCK_PREFIX}{struct_name}"))

        active_location_count = len(self._get_active_locations())

        if len(pool) > active_location_count:
            raise Exception(
                f"Euclesia: required item pool ({len(pool)}) exceeds active locations "
                f"({active_location_count}). Enable kill_sanity / challenge_sanity, or reduce "
                f"mob_spawn_lock_category."
            )

        filler_items = [item_name for item_name, item_data in ITEMS.items()
                        if item_data.classification == ItemClassification.filler
                        for _ in range(item_data.count)]

        while len(pool) < active_location_count:
            pool.append(self.create_item(self.random.choice(filler_items)))

        mc_trap_items = [item_name for item_name, item_data in ITEMS.items()
                         if item_data.classification == ItemClassification.trap
                         for _ in range(item_data.count)]

        trap_chance = self.options.trap_chance.value
        if mc_trap_items and trap_chance > 0:
            for (index, item) in enumerate(pool):
                if item.classification == ItemClassification.filler:
                    if self.random.randint(1, 100) <= trap_chance:
                        pool[index] = self.create_item(self.random.choice(mc_trap_items))

        self.multiworld.itempool += pool

    def set_rules(self) -> None:
        set_rules(self)
        self._set_goal_rule()

    # -----------------------------------------------------------------------
    # Goal
    # -----------------------------------------------------------------------

    def _get_selected_bosses(self) -> list[str]:
        """Bosses required by the goal: every boss in boss_list ("All" = every boss)."""
        selected = self.options.boss_list.value
        if "All" in selected:
            return list(MOBS_BOSS.keys())
        return [name for name in MOBS_BOSS.keys() if name in selected]

    def _get_primary_condition(self):
        boss_locations = [f"{BOSS_KILL_PREFIX}{name}" for name in self.selected_bosses]
        return lambda state: all(state.can_reach(location, "Location", self.player) for location in boss_locations)

    def _set_goal_rule(self) -> None:
        player = self.player
        primary_condition = self._get_primary_condition()

        conditions = [primary_condition]

        required_advancement_count = self.options.advancements_required.value

        if required_advancement_count > 0:
            # Only consider advancement locations that actually exist this seed (challenge_sanity
            # may drop some), and never require more than exist — otherwise the goal is impossible.
            advancements_locations = [
                name for name, loc_data in self._get_active_locations().items()
                if loc_data.category == MCLocationCategory.ADVANCEMENT
            ]
            required = min(required_advancement_count, len(advancements_locations))

            def advancement_condition(state) -> bool:
                return sum(1 for location in advancements_locations if state.can_reach(location, "Location", self.player)
                           ) >= required

            conditions.append(advancement_condition)

        def completion_condition(state) -> bool:
            return all(cond(state) for cond in conditions)

        self.multiworld.completion_condition[player] = completion_condition

    # -----------------------------------------------------------------------
    # Slot data (envoyé au mod Fabric)
    # -----------------------------------------------------------------------

    def fill_slot_data(self) -> dict:
        return {
            # --- Options ---
            "boss_list"            : [MOBS_BOSS[name].game_id for name in self.selected_bosses],
            "death_link"           : bool(self.options.death_link.value),
            "villager_trust"       : bool(self.options.villager_trust.value),
            "kill_sanity"          : bool(self.options.kill_sanity.value),
            "advancements_required": self.options.advancements_required.value,
            "mob_spawn_lock"       : list(self.options.mob_spawn_lock_category.value),

            # --- Mapping item ID → nom (le mod applique l'effet depuis le nom) ---
            # Inclut les items de base ET chaque Entity/Structure unlock, pour que le mod
            # puisse résoudre n'importe quel item reçu.
            "items"                : {
                item_id: name
                for name, item_id in self.item_name_to_id.items()
            },

            # --- Mapping game_id → location ID (le mod envoie le check depuis le game_id) ---
            # Uniquement les locations actives ce seed (challenge_sanity / kill_sanity filtrent
            # certaines locations) : le mod ne doit résoudre/envoyer que de vrais checks, et ce
            # mapping sert aussi de source de vérité "cette advancement est-elle un check".
            "locations"            : {
                location_data.game_id: location_data.id
                for location_data in self._get_active_locations().values()
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

            # --- Mob spawn lock : game_id des mobs à bloquer au spawn ---
            "mob_spawn_lock_mobs"  : {
                mob_data.game_id: BASE_ID_ENTITY_UNLOCK + mob_data.id
                for mob_name, mob_data in MOBS_ALL.items()
                if mob_data.category in self.options.mob_spawn_lock_category.value
            },

            # --- Structure lock : game_id de la structure → item ID de son unlock ---
            # Uniquement les structures verrouillées par l'option structure_unlock.
            "structure_locks"      : {
                STRUCTURES[name].game_id: BASE_ID_STRUCT_UNLOCK + STRUCTURES[name].id
                for name in self._get_locked_structures()
            },

            # --- Logic graph (region graph + per-location reachability rules) ---
            # The mod evaluates this against received items to colour advancements in/out of logic.
            "logic"                : build_logic_export(self),
        }
