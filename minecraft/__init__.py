import logging

from BaseClasses import Item, Location, Region, Tutorial
from worlds.AutoWorld import WebWorld, World

from .data import *
from .filler import build_filler_export, build_trap_export
from .logic.ast import Const
from .logic.constants import *
from .logic.root import set_rules
from .logic_export import build_logic_export
from .options import MCOptions, StartDimension, StructureFinder
from .regions import MCRegion
from .trackers import build_trackers_export

# ---------------------------------------------------------------------------
# Classes Item et Location
# ---------------------------------------------------------------------------

class MCItem(Item):
    game = "Minecraft [AEM]"


class MCLocation(Location):
    game = "Minecraft [AEM]"


# Structures that gate a goal boss (and thus the critical path) when that boss is required: such a
# structure unlock is worth full progression (so progression balancing front-loads it); every other
# structure unlock stays progression_skip_balancing — still logic-bearing, but not balanced. The
# Nether Fortress gates the Wither (skulls) AND the Ender Dragon (blaze rods -> eyes of ender ->
# End); the Stronghold is the only End portal. This is logic knowledge, so it lives with the rules,
# not in structures.json (which is purely jar-derived).
_STRUCTURE_BOSS_GATES = {
    "stronghold": {"Ender Dragon"},
    "fortress":   {"Wither", "Ender Dragon"},
    "monument":   {"Elder Guardian"},
    "ancient_city": {"Warden"},
}


# Mobs that gate a goal boss (and thus the critical path) when that boss is required: such an Entity
# Unlock is worth full progression (so progression balancing front-loads it); every other Entity
# Unlock stays progression_skip_balancing — still a logic gate (it gates its own Kill location), but
# not balanced. Each boss mob gates itself; the key resource prerequisites are the Wither Skeleton
# (skulls -> Wither) and Blaze + Enderman (blaze rods + ender pearls -> eyes of ender -> the End ->
# Ender Dragon). Like _STRUCTURE_BOSS_GATES this is logic knowledge, so it lives with the rules, not
# in entities.json (which is purely game-derived). Keyed by mob display name (the MOBS_ALL key).
_MOB_BOSS_GATES = {
    "Ender Dragon":    {"Ender Dragon"},
    "Wither":          {"Wither"},
    "Elder Guardian":  {"Elder Guardian"},
    "Warden":          {"Warden"},
    "Wither Skeleton": {"Wither"},
    "Blaze":           {"Ender Dragon"},
    "Enderman":        {"Ender Dragon"},
}


# ---------------------------------------------------------------------------
# WebWorld
# ---------------------------------------------------------------------------

class MCWebWorld(WebWorld):
    theme = "dirt"
    tutorials = [
        Tutorial(
            tutorial_name = "Setup Guide",
            description = "Guide to set up the Minecraft [AEM] randomizer.",
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
    game = "Minecraft [AEM]"
    options: MCOptions
    options_dataclass = MCOptions
    web = MCWebWorld()
    selected_bosses: list[str] = []

    item_name_to_id = {
        **{name: data.id for (name, data) in ITEMS.items()},
        **{f"{ENTITY_UNLOCK_PREFIX}{name}": BASE_ID_ENTITY_UNLOCK + mob.id for (name, mob) in MOBS_ALL.items()},
        **{f"{STRUCT_UNLOCK_PREFIX}{structure.label}": BASE_ID_STRUCT_UNLOCK + structure.id
           for structure in STRUCTURES.values()}
    }

    location_name_to_id = {
        **{name: data.id for (name, data) in ALL_LOCATIONS.items()},
        # BACAP's blazeandcave locations are always in the (static) data package; created only when
        # the blazeandcave option is on. Its minecraft rewrites reuse the vanilla locations above.
        **{name: data.id for (name, data) in LOCATIONS_BACAP.items()},
    }

    # -----------------------------------------------------------------------
    # Génération
    # -----------------------------------------------------------------------

    def create_item(self, name: str) -> MCItem:

        if name in ITEMS:
            item_data: MCItemData = ITEMS[name]
            return MCItem(name, item_data.classification, item_data.id, self.player)

        # Every Entity Unlock that enters the pool gates at least its own Kill Entity location, so it
        # must stay progression-flavoured. AP's CollectionState only collects progression items, so a
        # useful/filler gate is invisible to the solver; _mob_classification keeps them logic-bearing
        # (full progression when goal-gating, else skip_balancing) — the per-seed structure-unlock rule.
        if name.startswith(ENTITY_UNLOCK_PREFIX):
            mob_name = name.removeprefix(ENTITY_UNLOCK_PREFIX)
            mob_data = MOBS_ALL[mob_name]
            classification = self._mob_classification(mob_name)
            return MCItem(name, classification, BASE_ID_ENTITY_UNLOCK + mob_data.id, self.player)

        # A Structure Unlock locks a structure, so it always gates that structure's locations — it
        # must stay progression-flavoured (CollectionState only collects progression items). Whether
        # it rises to full progression is a per-seed call, computed from the goal (see
        # _structure_classification), not stored in the data.
        if name.startswith(STRUCT_UNLOCK_PREFIX):
            struct_gid = STRUCTURE_BY_LABEL[name.removeprefix(STRUCT_UNLOCK_PREFIX)]
            struct_data = STRUCTURES[struct_gid]
            classification = self._structure_classification(struct_gid)
            return MCItem(name, classification, BASE_ID_STRUCT_UNLOCK + struct_data.id, self.player)

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
            logging.warning(
                "Minecraft [AEM] (%s): advancements_required (%d) exceeds the %d advancement checks this "
                "seed has; clamping to %d. Enable challenge_sanity / blazeandcave for more.",
                self.multiworld.get_player_name(self.player),
                self.options.advancements_required.value,
                active_advancement_count, active_advancement_count,
            )
            self.options.advancements_required.value = active_advancement_count

    def _get_active_structures(self) -> set[str]:
        """Structures that exist this seed: every vanilla (base) structure, plus an overlay pack's
        structures only when that pack's option is enabled — mirroring how blazeandcave gates BACAP.
        STRUCTURE_PACK_OPTION holds an overlay structure's gating option; base structures are absent
        from it and so are always active."""
        return {
            name for name in STRUCTURES
            if name not in STRUCTURE_PACK_OPTION
            or getattr(self.options, STRUCTURE_PACK_OPTION[name])
        }

    def _get_locked_structures(self) -> set[str]:
        """Structures locked behind a 'Structure Unlock' item, per the structure_unlock option.

        The option accepts dimension presets ("Overworld"/"Nether"/"The End"), "All", and/or
        individual structure display names; this resolves them to a concrete set of structure
        game_ids. Only structures active this seed (see _get_active_structures) can be locked — a
        disabled overlay pack's structures don't exist, so they can't be a Structure Unlock.
        """
        active = self._get_active_structures()
        selected = self.options.structure_unlock.value
        if "All" in selected:
            return set(active)

        locked: set[str] = set()
        for entry in selected:
            if entry in ("Overworld", "Nether", "The End"):
                locked |= {gid for gid in active if STRUCTURES[gid].region == entry}
            else:
                gid = STRUCTURE_BY_LABEL.get(entry)  # the option lists display labels
                if gid in active:
                    locked.add(gid)
        return locked

    def _get_active_locations(self) -> dict[str, MCLocationData]:
        """Retourne les locations actives selon les options du joueur."""
        locations: dict[str, MCLocationData] = {
            **LOCATIONS_ADVANCEMENT,
            **LOCATIONS_BOSS_KILL
        }

        if self.options.kill_sanity:
            locations.update(LOCATIONS_MOB_KILL)

        # BACAP adds its new advancements; the vanilla advancement locations stay (BACAP rewrites
        # them, so set_rules compiles their logic from BACAP's criteria instead — see set_rules).
        if self.options.blazeandcave:
            locations.update(LOCATIONS_BACAP)

        if not self.options.challenge_sanity:
            # A reused vanilla location's challenge-ness follows the active manifest: BACAP's frame
            # when blazeandcave is on (it can promote/demote a vanilla advancement), else the
            # vanilla flag baked into the location.
            rewrites = BACAP_REWRITE_CHALLENGE if self.options.blazeandcave else {}
            locations = {
                name: loc_data for name, loc_data in locations.items()
                if not rewrites.get(loc_data.game_id, loc_data.challenge)
            }

        return locations

    def _start_region(self) -> MCRegion:
        """The dimension the player spawns in — the free origin of the region graph."""
        if self.options.start_dimension.value == StartDimension.option_nether:
            return MCRegion.NETHER
        return MCRegion.OVERWORLD

    def _start_dimension_item(self) -> str:
        """The 'Dimension Unlock' item for the start dimension. It is NOT added to the pool
        (you start there for free); the other two dimensions keep theirs."""
        if self.options.start_dimension.value == StartDimension.option_nether:
            return ITEM_DIMENSION_NETHER
        return ITEM_DIMENSION_OVERWORLD

    def create_regions(self) -> None:
        from .logic.acquisition import RuleHelper  # local import: avoids a top-level import cycle
        from .logic.root import build_location_rules, derive_location_regions

        added_regions: dict[str, Region] = {}

        for region in MCRegion:
            added_regions[region] = Region(region, self.player, self.multiworld)

        # Each location is placed in the region its reachability rule actually requires (a Nether-/
        # End-native check goes in that dimension; a multi-dimension or item-only check goes in the
        # always-reachable origin), instead of the legacy uniform-Overworld floor that over-gated
        # non-Overworld checks and broke a Nether start. The rules are cached for set_rules so the two
        # stay in lockstep. (Falls back to the data-declared region for any location without a rule.)
        self._location_rules = build_location_rules(self)
        placement = derive_location_regions(self._location_rules)
        for loc_name, loc_data in self._get_active_locations().items():
            region = added_regions[MCRegion(placement.get(loc_name, loc_data.region))]
            location = MCLocation(self.player, loc_name, loc_data.id, region)
            region.locations.append(location)

        # Inter-dimension portal gates as AST nodes (callable for AP, serializable for the mod).
        # The Overworld is always the travel hub and the End is always entered from it; only the
        # Overworld↔Nether link's direction and gate depend on the chosen start dimension. The
        # start dimension is free (Menu → start) and needs no unlock item; the others need theirs.
        helper = RuleHelper(self)
        start_region = self._start_region()

        # (from_region, to_region, rule). The Overworld↔Nether link is emitted FIRST because the
        # End gate (Eye Spy → blaze powder) depends on the Nether, and AP's region sweep is
        # order-sensitive for entrance rules that reference locations: the Nether must be wired
        # before the End edge or AP evaluates the End gate too early and never reaches the End.
        edges = []

        if start_region == MCRegion.NETHER:
            # Spawn in the Nether: build the return portal — obtain obsidian and light it (Pyromaniac).
            # can_get_obsidian is region-gated, so from the Nether it resolves only to Nether sources
            # (Nether ruined portal / Bastion / Fortress / barter), exactly as the dimension allows.
            edges.append((
                MCRegion.NETHER, MCRegion.OVERWORLD,
                helper.all_of(
                    helper.has(ITEM_DIMENSION_OVERWORLD),
                    helper.can_get_obsidian(),
                    helper.knowledge(K_PYRO),
                ),
            ))
        else:
            # Overworld start (classic): build the Nether portal from the Overworld.
            edges.append((
                MCRegion.OVERWORLD, MCRegion.NETHER,
                helper.all_of(
                    helper.has(ITEM_DIMENSION_NETHER),
                    helper.reached(f"{ADVANCEMENT_PREFIX}{A_ICE_BUCKET_CHALLENGE}"),
                    helper.knowledge(K_PYRO),
                ),
            ))

        # The End is always entered from the Overworld — emitted last (see ordering note above).
        edges.append((
            MCRegion.OVERWORLD, MCRegion.THE_END,
            helper.all_of(
                helper.has(ITEM_DIMENSION_END),
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_EYE_SPY}"),
            ),
        ))

        # Menu → start dimension is always free; the rest are the gated portal edges.
        added_regions[MCRegion.MENU].connect(added_regions[start_region])
        self.logic_region_rules = {
            MCRegion.MENU.value: [{"to": start_region.value, "rule": Const(True)}],
        }
        for from_region, to_region, rule in edges:
            added_regions[from_region].connect(added_regions[to_region], rule=rule)
            self.logic_region_rules.setdefault(from_region.value, []).append(
                {"to": to_region.value, "rule": rule}
            )

        # BACAP reward events: an internal, always-origin location per rewarded item, holding a
        # locked (non-networked) event item. Its rule (set in set_rules from build_location_rules) is
        # the OR of reaching a granting advancement, so the event item is collected exactly when one of
        # those advancements is reachable — letting acquire() source the item via a non-recursive
        # has() leaf instead of a cycle-forming reached(). Empty unless bacap_rewards is on. The event
        # carries no networked id, so it sits outside the create_items pool/location balance.
        menu_region = added_regions[MCRegion.MENU]
        for base in helper.reward_events:
            event_name = f"{REWARD_EVENT_PREFIX}{base}"
            event_location = MCLocation(self.player, event_name, None, menu_region)
            event_location.place_locked_item(
                MCItem(event_name, ItemClassification.progression, None, self.player))
            menu_region.locations.append(event_location)

        self.multiworld.regions += list(added_regions.values())

    def create_items(self) -> None:
        pool: list[MCItem] = []

        # The start dimension is reached for free (Menu → start), so its unlock item is never added.
        start_dimension_item = self._start_dimension_item()

        # Items handled out-of-band below by their own option (disabled / start / in pool).
        finder_modes = {
            ITEM_STRUCTURE_FINDER: self.options.structure_finder,
            ITEM_BIOME_FINDER: self.options.biome_finder,
        }

        # Progression + useful only — fillers and traps are derived later
        for name, item_data in ITEMS.items():
            if item_data.classification in (ItemClassification.filler, ItemClassification.trap):
                continue
            if not self.options.villager_trust and name == ITEM_VILLAGER_TRUST:
                continue
            if name == start_dimension_item:
                continue
            if name in finder_modes:
                continue
            for _ in range(item_data.count):
                pool.append(self.create_item(name))

        # Finders: in_pool shuffles the copies into the pool, start grants them up front, disabled
        # drops them entirely. (Counts come from items.csv — 5 for the Structure Finder, 1 Biome Finder.)
        for finder_name, mode in finder_modes.items():
            count = ITEMS[finder_name].count
            if mode == StructureFinder.option_in_pool:
                for _ in range(count):
                    pool.append(self.create_item(finder_name))
            elif mode == StructureFinder.option_start:
                for _ in range(count):
                    self.multiworld.push_precollected(self.create_item(finder_name))

        if self.options.mob_spawn_lock_category:
            for mob_name, mob_data in MOBS_ALL.items():
                if mob_data.category in self.options.mob_spawn_lock_category.value:
                    pool.append(self.create_item(f"{ENTITY_UNLOCK_PREFIX}{mob_name}"))

        # Structure unlocks: only the structures locked by the structure_unlock option are added.
        # Unlocked structures are gated by their dimension instead (see RuleHelper.structure).
        for struct_gid in self._get_locked_structures():
            pool.append(self.create_item(f"{STRUCT_UNLOCK_PREFIX}{STRUCTURES[struct_gid].label}"))

        active_location_count = len(self._get_active_locations())

        if len(pool) > active_location_count:
            raise Exception(
                f"Minecraft [AEM]: required item pool ({len(pool)}) exceeds active locations "
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

    def _structure_classification(self, struct_name: str) -> ItemClassification:
        """A Structure Unlock's classification, computed for this seed. It is at least
        progression_skip_balancing (it gates its structure's locations and must be collectable), and
        full progression only when it gates a boss the goal requires (see _STRUCTURE_BOSS_GATES)."""
        if _STRUCTURE_BOSS_GATES.get(struct_name, frozenset()) & set(self.selected_bosses):
            return ItemClassification.progression
        return ItemClassification.progression_skip_balancing

    def _mob_classification(self, mob_name: str) -> ItemClassification:
        """An Entity Unlock's classification, computed for this seed. It is at least
        progression_skip_balancing (it gates at least its own Kill location and must be collectable),
        and full progression only when it gates a boss the goal requires (see _MOB_BOSS_GATES) —
        the same per-seed, goal-driven rule the structure unlocks use."""
        if _MOB_BOSS_GATES.get(mob_name, frozenset()) & set(self.selected_bosses):
            return ItemClassification.progression
        return ItemClassification.progression_skip_balancing

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
            "start_dimension"      : self.options.start_dimension.current_key,  # "overworld" | "nether"
            "death_link"           : bool(self.options.death_link.value),
            "villager_trust"       : bool(self.options.villager_trust.value),
            "kill_sanity"          : bool(self.options.kill_sanity.value),
            "advancements_required": self.options.advancements_required.value,
            "mob_spawn_lock"       : list(self.options.mob_spawn_lock_category.value),
            # Whether the Structure Finder exists at all this seed (disabled = no item in the pool /
            # on start). The mod uses this to skip its proactive structure scan when the finder is off.
            "structure_finder"     : self.options.structure_finder.value != self.options.structure_finder.option_disabled,
            # BACAP integration: whether the pack is in play, and whether to keep its item/XP rewards.
            # The mod runs blazeandcave's reward-disable functions on first world load accordingly.
            "blazeandcave"         : bool(self.options.blazeandcave.value),
            "bacap_rewards"        : bool(self.options.bacap_rewards.value),

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

            # --- Material handling : item MC → nombre de "Progressive Material Handling" requis ---
            # Le mod bloque le ramassage de ces items tant que ce nombre de copies n'est pas reçu.
            "material_handling_locks": {
                f"minecraft:{path}": count
                for count, paths in MATERIAL_HANDLING_ITEMS.items()
                for path in paths
            },

            # --- Tool/armor locks : item MC → {knowledge AP requise, palier de matériau requis} ---
            # Le mod bloque le ramassage/craft tant que le joueur n'a pas reçu la Knowledge ET assez
            # de "Progressive Material Handling" (ex. épée diamant = Sword Handling + 5).
            "tool_locks"           : {
                f"minecraft:{path}": {"knowledge": f"Knowledge: {knowledge}", "material": tier}
                for path, (knowledge, tier) in TOOL_LOCKS.items()
            },

            # --- Station locks : block MC → Knowledge AP requise pour l'utiliser (ouvrir le GUI) ---
            # Le mod bloque le clic-droit sur la table d'enchantement / l'alambic tant que la
            # Knowledge n'est pas reçue (même ceux trouvés dans les structures).
            "station_knowledge_locks": {
                f"minecraft:{block}": f"Knowledge: {knowledge}"
                for block, knowledge in STATION_KNOWLEDGE_LOCKS.items()
            },

            # --- Dimension gating : dimension MC → item AP requis pour y entrer (portail) ---
            # La dimension de départ est gratuite (son item n'existe pas dans le pool) donc exclue ;
            # les autres exigent leur "Dimension Unlock". Le mod bloque le passage du portail sinon.
            "dimension_locks"      : {
                dim_id: item
                for dim_id, item in {
                    "minecraft:overworld" : ITEM_DIMENSION_OVERWORLD,
                    "minecraft:the_nether": ITEM_DIMENSION_NETHER,
                    "minecraft:the_end"   : ITEM_DIMENSION_END,
                }.items()
                if item != self._start_dimension_item()
            },

            # --- Filler & trap effects (item id -> what the mod grants / triggers on receipt) ---
            "filler_items"         : build_filler_export(self.item_name_to_id),
            "trap_items"           : build_trap_export(self.item_name_to_id),

            # --- Logic graph (region graph + per-location reachability rules) ---
            # The mod evaluates this against received items to colour advancements in/out of logic.
            "logic"                : build_logic_export(self),

            # --- Tracker tab (active mob/boss kills + mob/structure unlocks) ---
            # Active-only; maps each tracker advancement id to its AP location/item (see trackers.py).
            "trackers"             : build_trackers_export(self),
        }
