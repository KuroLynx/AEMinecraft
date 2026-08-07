import logging

from BaseClasses import Item, Location, Region, Tutorial
from worlds.AutoWorld import WebWorld, World

from .data import *
from .filler import build_filler_export, build_trap_export
from .logic.ast import Const
from .logic.constants import *
from .logic.root import set_rules
from .logic_export import build_logic_export
from .options import ItemGateBehavior, MCOptions, StartDimension, StructureFinder
from .regions import MCRegion
from .trackers import build_trackers_export

# Slot-data schema version, advertised to the mod in fill_slot_data(). Bump ONLY on a change to
# fill_slot_data() that an existing mod can't read safely (a renamed/removed field, or a new field
# the mod must have). The mod knows the range of schema versions it understands and refuses to enter
# a world outside that range (see CompatibilityService, mod side, and docs/versioning.md). Additive,
# tolerated-if-absent fields do NOT require a bump.
SLOT_DATA_VERSION = 2

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
            classification = item_data.classification
            if name in KNOWLEDGE_ITEMS:
                classification = self._knowledge_classification(name)
            elif name == ITEM_STRUCTURE_FINDER:
                classification = self._finder_classification()
            return MCItem(name, classification, item_data.id, self.player)

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

    def _get_locked_mobs(self) -> set[str]:
        """Mobs locked behind an 'Entity Unlock' item, per the mob_spawn_lock option.

        The option accepts category presets ("passive"/"neutral"/"hostile"/"boss"), "All", and/or
        individual mob names; this resolves them to a concrete set of mob names (MOBS_ALL keys) — the
        mob-side mirror of _get_locked_structures.
        """
        selected = self.options.mob_spawn_lock.value
        if "All" in selected:
            return set(MOBS_ALL)

        categories = {"passive", "neutral", "hostile", "boss"}
        locked: set[str] = set()
        for entry in selected:
            if entry in categories:
                locked |= {name for name, data in MOBS_ALL.items() if data.category == entry}
            elif entry in MOBS_ALL:  # the option also lists individual mob names
                locked.add(entry)
        return locked

    def _active_knowledges(self) -> set[str]:
        """Knowledge gates switched on this seed, per the knowledge_gates option.

        The option accepts category presets ("tool"/"armor"/"misc"/"station"/"container"), "All", and/or
        individual knowledge names, any of them negated with a leading "-"; this resolves them to
        concrete BARE names (KNOWLEDGES keys) — the knowledge-side mirror of _get_locked_mobs. A gate
        that is off has no item in the pool, emits no lock in slot_data, and is dropped from the rules
        (see RuleHelper.knowledge).

        Negation is resolved in one pass at the end rather than in list order: a YAML list reads as a
        set, so "All" then "-Chest" and "-Chest" then "All" both mean everything but the chest.
        """
        # Memoized: the option can't change once generation starts, and the lock maps in
        # fill_slot_data ask per row (one call per tool/station, ~80 a seed).
        cached = getattr(self, "_active_knowledge_cache", None)
        if cached is not None:
            return cached

        selected = self.options.knowledge_gates.value
        active = self._resolve_knowledges({entry for entry in selected if not entry.startswith("-")})
        active -= self._resolve_knowledges({entry[1:] for entry in selected if entry.startswith("-")})
        self._active_knowledge_cache = active
        return active

    @staticmethod
    def _resolve_knowledges(entries: set[str]) -> set[str]:
        """Expand knowledge_gates entries ("All", a category preset, a name) to bare knowledge names."""
        names: set[str] = set()
        for entry in entries:
            if entry == "All":
                names |= set(KNOWLEDGES)
            elif entry in KNOWLEDGES_BY_CATEGORY:
                names |= set(KNOWLEDGES_BY_CATEGORY[entry])
            elif entry in KNOWLEDGES:  # the option also lists individual knowledge names
                names.add(entry)
        return names

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

        # Neither portal edge asks for Pyromaniac, and that is deliberate. The Knowledge gates two
        # ITEMS — flint_and_steel and fire_charge (see data.TOOL_LOCKS) — and nothing gates igniting
        # a frame from lava, which lights a portal just as well: lava beside any flammable block in
        # the Overworld, or beside netherrack in the Nether. Both edges already prove lava is in
        # reach (forming obsidian needs lava + water; the Nether is made of it), so the alternate is
        # always available and requiring the Knowledge only invented a lock the game does not have.
        if start_region == MCRegion.NETHER:
            # Spawn in the Nether: build the return portal — obtain obsidian and light it.
            # can_get_obsidian is region-gated, so from the Nether it resolves only to Nether sources
            # (Nether ruined portal / Bastion / Fortress / barter), exactly as the dimension allows.
            edges.append((
                MCRegion.NETHER, MCRegion.OVERWORLD,
                helper.all_of(
                    helper.has(ITEM_DIMENSION_OVERWORLD),
                    helper.can_get_obsidian(),
                ),
            ))
        else:
            # Overworld start (classic): build the Nether portal from the Overworld.
            edges.append((
                MCRegion.OVERWORLD, MCRegion.NETHER,
                helper.all_of(
                    helper.has(ITEM_DIMENSION_NETHER),
                    helper.reached(f"{ADVANCEMENT_PREFIX}{A_ICE_BUCKET_CHALLENGE}"),
                ),
            ))

        # The End is always entered from the Overworld — emitted last (see ordering note above).
        # Three things, and it is worth being precise about which is which. You need the unlock item;
        # you need to be in a stronghold, because that is where the portal is (stated as the
        # structure rather than via `Advancement: Eye Spy`, which is only the advancement that fires
        # when you walk in — same condition, one less location reference in an entrance rule); and
        # you need Eyes of Ender, which go in the PORTAL FRAME. The eyes were previously attached to
        # finding the stronghold, which is wrong in both directions: a stronghold can be dug into
        # once the Finder points at it, and no amount of standing in one opens the portal.
        edges.append((
            MCRegion.OVERWORLD, MCRegion.THE_END,
            helper.all_of(
                helper.has(ITEM_DIMENSION_END),
                helper.structure(S_STRONGHOLD),
                helper.acquire("minecraft:ender_eye"),
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

        # Knowledge items exist only for the gates this seed switched on (knowledge_gates); the rest are
        # not in the pool at all, and their rules were dropped to match (see RuleHelper.knowledge).
        active_knowledge_items = {KNOWLEDGES[name].item_name for name in self._active_knowledges()}

        # Progression + useful only — fillers and traps are derived later
        for name, item_data in ITEMS.items():
            if item_data.classification in (ItemClassification.filler, ItemClassification.trap):
                continue
            if not self.options.villager_trust and name == ITEM_VILLAGER_TRUST:
                continue
            if name in KNOWLEDGE_ITEMS and name not in active_knowledge_items:
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

        # Mob spawn unlocks: only the mobs locked by the mob_spawn_lock option are added.
        for mob_name in self._get_locked_mobs():
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
                f"mob_spawn_lock."
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

    # A Knowledge gating fewer than this share of the seed's checks is real logic, but not worth
    # front-loading. Deliberately a share rather than a count, so it means the same thing on a 104-check
    # vanilla seed and a 1000-check BACAP one.
    _KNOWLEDGE_BALANCE_SHARE = 0.05
    # …but the heaviest gates are always balanced, however small their share works out. A seed with
    # few gates switched on, or one diluted by a big pack, can leave every Knowledge under the share
    # and nothing front-loaded at all; this guarantees the ones carrying the most weight still are.
    _KNOWLEDGE_BALANCE_TOP = 7

    def _goal_knowledges(self) -> set[str]:
        """Knowledges standing anywhere between the player and a boss the goal requires.

        Volume is the wrong measure for these. A gate can block a single check and still be the thing
        holding up the whole run, because that check is on the critical path to the Ender Dragon — the
        same reason _STRUCTURE_BOSS_GATES and _MOB_BOSS_GATES promote a structure or mob unlock that
        gates a required boss. Balancing has to front-load those whatever their share works out at.

        Walks out from each required boss kill through the rules it depends on, following ``loc``
        nodes, and collects every Knowledge named on the way. Deliberately an OVER-approximation: a
        Knowledge appearing in one branch of an OR isn't strictly necessary (another branch may avoid
        it), but treating a maybe-critical gate as critical only front-loads it, while missing a real
        one strands the run. Cycle-guarded, since advancement rules reference each other freely.
        """
        cached = getattr(self, "_goal_knowledge_cache", None)
        if cached is not None:
            return cached
        rules = getattr(self, "_location_rules", {})
        found: set[str] = set()
        seen: set[str] = set()
        pending = [f"{BOSS_KILL_PREFIX}{boss}" for boss in self.selected_bosses]
        while pending:
            name = pending.pop()
            if name in seen or name not in rules:
                continue
            seen.add(name)
            node = rules[name]
            serialized = node.canonical_json()
            found.update(k for k in KNOWLEDGE_ITEMS if f'"{k}"' in serialized)
            pending.extend(self._referenced_locations(node.to_dict()))
        self._goal_knowledge_cache = found
        return found

    @staticmethod
    def _referenced_locations(node: dict) -> list[str]:
        """Every location name a serialized rule reaches through a ``loc`` leaf."""
        if node.get("k") == "loc":
            return [node["l"]]
        out: list[str] = []
        for child in node.get("c", ()):
            out.extend(MCWorld._referenced_locations(child))
        return out

    def _finder_classification(self) -> ItemClassification:
        """The Structure Finder is only filler-ish while nothing depends on it.

        Since RuleHelper.structure_located, the third copy is what strict logic counts on to find
        ANY structure — villages, fortresses, mansions, trial chambers, the lot. An item that
        gates that much has to be balanced, or the fill is free to leave the first three copies in
        the last sphere and the player spends most of the seed staring at a red tab. Only when the
        copies are in the pool: with `start` they are precollected and with `disabled` there is no
        item, so the tail classification is left alone. Derived per seed, like the mob/structure
        unlock rules, rather than pinned in items.csv."""
        if self.options.structure_finder == StructureFinder.option_in_pool:
            return ItemClassification.progression
        return ITEMS[ITEM_STRUCTURE_FINDER].classification

    def _knowledge_classification(self, item_name: str) -> ItemClassification:
        """Full progression for a Knowledge the seed leans on, progression_skip_balancing for the tail.

        Every Knowledge gates something, so all of them must stay progression-flavoured — AP's
        CollectionState only collects progression items, and a useful one would be invisible to the
        solver. What differs is whether progression balancing should fight to move it early. Pickaxe
        Handling gates half the game and deserves that; Chiseled Bookshelf gates a check or two and
        just displaces something that matters.

        Derived per seed from the compiled rules rather than curated in knowledges.csv, like
        _structure_classification and _mob_classification: which gates carry weight depends on the
        options (knowledge_gates, challenge_sanity, blazeandcave), so a hand-kept column would be wrong
        for most seeds. Counted on the STRICT rules, which are the ones fill actually plans against.
        """
        counts = getattr(self, "_knowledge_gate_counts", None)
        if counts is None:
            rules = getattr(self, "_location_rules", {})
            counts = self._knowledge_gate_counts = {}
            for rule in rules.values():
                serialized = rule.canonical_json()
                for knowledge in KNOWLEDGE_ITEMS:
                    if f'"{knowledge}"' in serialized:
                        counts[knowledge] = counts.get(knowledge, 0) + 1
            self._knowledge_gate_total = len(rules)
            # Heaviest first, name as tie-break so the ranking is reproducible for a given seed.
            self._knowledge_gate_rank = [
                name for name in sorted(KNOWLEDGE_ITEMS, key=lambda n: (-counts.get(n, 0), n))
            ]
        # On the critical path to a required boss: front-load it however little else it gates.
        if item_name in self._goal_knowledges():
            return ItemClassification.progression
        threshold = self._knowledge_gate_total * self._KNOWLEDGE_BALANCE_SHARE
        if counts.get(item_name, 0) >= threshold:
            return ItemClassification.progression
        # A gate under the share still counts when it is one of the heaviest this seed has — but never
        # on the strength of gating nothing at all, which would balance an item that blocks no check.
        rank = self._knowledge_gate_rank.index(item_name)
        if rank < self._KNOWLEDGE_BALANCE_TOP and counts.get(item_name, 0) > 0:
            return ItemClassification.progression
        return ItemClassification.progression_skip_balancing

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

    def _item_gate_routes(self) -> dict:
        """Every item_gate_behavior route as a plain bool (true = the route is gated).

        Emitting all five keys keeps the fallback logic on this side: the mod only has to read them. The
        'crafting' route predates 'station'/'container' (it used to cover every GUI take), so an explicit
        'crafting' value carries over to those two when they are omitted — a config written before the
        split still opens or gates every GUI the way it used to.
        """
        chosen = self.options.item_gate_behavior.value
        crafting = ItemGateBehavior.as_bool(chosen.get("crafting", True))
        return {
            route: ItemGateBehavior.as_bool(chosen.get(route, fallback))
            for route, fallback in (
                ("crafting", crafting),
                ("station", crafting),
                ("container", crafting),
                ("pickup", True),
                ("given", False),
            )
        }

    def fill_slot_data(self) -> dict:
        return {
            # Slot-data schema version; the mod refuses to enter a world it can't read (see
            # SLOT_DATA_VERSION and the mod's CompatibilityService).
            "slot_data_version"    : SLOT_DATA_VERSION,

            # --- Options ---
            "boss_list"            : [MOBS_BOSS[name].game_id for name in self.selected_bosses],
            "start_dimension"      : self.options.start_dimension.current_key,  # "overworld" | "nether"
            "death_link"           : bool(self.options.death_link.value),
            "villager_trust"       : bool(self.options.villager_trust.value),
            "kill_sanity"          : bool(self.options.kill_sanity.value),
            "advancements_required": self.options.advancements_required.value,
            "mob_spawn_lock"       : list(self.options.mob_spawn_lock.value),
            # Whether the Structure Finder exists at all this seed (disabled = no item in the pool /
            # on start). The mod uses this to skip its proactive structure scan when the finder is off.
            "structure_finder"     : self.options.structure_finder.value != self.options.structure_finder.option_disabled,
            # BACAP integration: whether the pack is in play, and whether to keep its item/XP rewards.
            # The mod runs blazeandcave's reward-disable functions on first world load accordingly.
            "blazeandcave"         : bool(self.options.blazeandcave.value),
            "bacap_rewards"        : bool(self.options.bacap_rewards.value),

            # Per-route handling of still-locked items (see ItemGateBehavior / the mod's
            # MaterialLockService + Give/Slot/ItemEntity mixins). Each route is emitted as a bool (true =
            # gated) so an omitted YAML key falls back to the historical default here, not in the mod.
            # The two workstation/storage routes inherit an explicit 'crafting' when they are absent, so a
            # config written before the GUI routes were split keeps meaning the same thing.
            "item_gate_behavior"   : self._item_gate_routes(),

            # Datapacks/mods this seed REQUIRES to be installed at a matching version. The mod verifies
            # each against the loaded datapacks (pack repository) / Fabric mods on world load and refuses
            # to enter the world if one is missing or the wrong version (see ContentVerification). Only
            # the overlays enabled this seed are listed (e.g. BACAP when blazeandcave is on).
            "required_content"     : [
                requirement
                for option, requirement in OVERLAY_REQUIREMENTS
                if getattr(self.options, option).value
            ],

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
                MOBS_ALL[mob_name].game_id: BASE_ID_ENTITY_UNLOCK + MOBS_ALL[mob_name].id
                for mob_name in self._get_locked_mobs()
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
            # Only the gates this seed switched on are emitted: a knowledge that is off has no item in
            # the pool, so leaving its lock in would block the item forever.
            "tool_locks"           : {
                # The craft/pickup half of a station/container gate: a gated block can't be made or
                # picked up either, the same way the enchanting table and brewing stand always worked.
                # No material tier of their own — the recipe's ingredients carry that. Listed FIRST so
                # the curated TOOL_LOCKS below win: the two blocks in both (enchanting table, brewing
                # stand) have a hand-set tier that a blanket 0 would throw away.
                **{
                    block: {"knowledge": f"{KNOWLEDGE_PREFIX}{knowledge}", "material": 0}
                    for block, knowledge in BLOCK_KNOWLEDGE.items()
                    if knowledge in self._active_knowledges()
                },
                **{
                    f"minecraft:{path}": {"knowledge": f"{KNOWLEDGE_PREFIX}{knowledge}", "material": tier}
                    for path, (knowledge, tier) in TOOL_LOCKS.items()
                    if knowledge in self._active_knowledges()
                },
            },

            # --- Station locks : block MC → Knowledge AP requise pour l'utiliser (ouvrir le GUI) ---
            # Le mod bloque le clic-droit sur la table d'enchantement / l'alambic tant que la
            # Knowledge n'est pas reçue (même ceux trouvés dans les structures).
            "station_knowledge_locks": {
                block: f"{KNOWLEDGE_PREFIX}{knowledge}"
                for block, knowledge in STATION_KNOWLEDGE_LOCKS.items()
                if knowledge in self._active_knowledges()
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
