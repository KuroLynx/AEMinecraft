import json
import re
from importlib.resources import files

# AST primitives must be imported directly: `from .. import *` cannot supply them because the
# package __init__ imports this module (via set_rules) before it defines Const/Has/and_/… .
from .ast import And, Const, Has, ReachRegion, ReachLocation, and_, or_, at_least
from ..content.registry import base_pack, overlay_packs  # registry only imports constants → cycle-safe
from .. import *

# Wood-family items (planks / logs / wood / stems / hyphae, stripped or not) have no knowledge or
# material gate — they are free once their dimension is reached. Collapsing them to a bare region
# node instead of recursing through every plank variant keeps acquisition trees small (a recipe that
# takes "#planks" otherwise fans out into a dozen wood subtrees).
_WOOD_RE = re.compile(r"^(stripped_)?[a-z]+_(planks|log|wood|stem|hyphae)$")
_NETHER_WOODS = ("crimson", "warped")

# Item -> required Progressive Material Handling tier (inverted from data.MATERIAL_HANDLING_ITEMS):
# the gate for mining a material, and the last-resort fallback for a material item with no recipe.
_MATERIAL_TIER_BY_ITEM: dict[str, int] = {
    item: tier for tier, items in MATERIAL_HANDLING_ITEMS.items() for item in items
}

# 'gameplay' loot tables (tools/build_acquisition.py) that are really a single mob's reliable,
# renewable output — a gift / interaction / growth table. Grounded in the 26.1.2 jar loot-table
# types: gift tables ARE reliable (an armadillo sheds scute, a chicken lays eggs, a sniffer digs
# seeds, a cat brings a morning gift), so they gate on reaching that mob rather than dropping the
# requirement.
_GAMEPLAY_MOB: dict[str, str] = {
    "armadillo": E_ARMADILLO, "armadillo_shed": E_ARMADILLO,
    "chicken_lay": E_CHICKEN, "turtle_grow": E_TURTLE, "panda_sneeze": E_PANDA,
    "sniffer_digging": E_SNIFFER, "cat_morning_gift": E_CAT,
}

# Mob heads/skulls drop ONLY when a CHARGED creeper kills that mob — verified against the 26.1.2 jar
# (data/minecraft/loot_table/charged_creeper/<mob>.json, dispatched from charged_creeper/root.json;
# none of these heads have a direct mob drop). So the gate is "charge a creeper" — a creeper plus an
# Overworld thunderstorm — AND reach the victim, not merely reaching the victim. (Note:
# wither_skeleton_skull ALSO drops directly from a Wither Skeleton, so its `drops` path keeps the
# simpler entity gate and absorption collapses the redundant charged-creeper AND.)
_CHARGED_CREEPER_VICTIM: dict[str, str] = {
    "creeper": E_CREEPER, "skeleton": E_SKELETON, "zombie": E_ZOMBIE,
    "wither_skeleton": E_WITHER_SKELETON, "piglin": E_PIGLIN,
}

# Villager 'Hero of the Village' profession gift tables: a villager of that profession throws these
# after the player wins a raid, so they gate on winning one (a pillager + a village).
_GAMEPLAY_VILLAGER_GIFTS = frozenset({
    "armorer_gift", "baby_gift", "butcher_gift", "cartographer_gift", "cleric_gift", "farmer_gift",
    "fisherman_gift", "fletcher_gift", "leatherworker_gift", "librarian_gift", "mason_gift",
    "shepherd_gift", "toolsmith_gift", "unemployed_gift", "weaponsmith_gift",
})

# Block-harvest 'gameplay' tables: pick/shear a block for its yield. (region, needs_shears) — the
# block's dimension, plus shears when the table is a shear interaction (honeycomb, pumpkin seeds).
_GAMEPLAY_HARVEST: dict[str, tuple] = {
    "beehive": (REGION_OVERWORLD, True), "pumpkin": (REGION_OVERWORLD, True),
    "cave_vine": (REGION_OVERWORLD, False), "sweet_berry_bush": (REGION_OVERWORLD, False),
}

# Lazily-loaded acquisition table (tools/build_acquisition.py) + reverse id lookups. Cached because
# they are read once per generation but queried thousands of times by the trigger compiler.
_MC_ROOT = __package__.rsplit(".", 1)[0]  # e.g. "worlds.minecraft_aem"
_ACQUISITION: dict | None = None
_ENTITY_BY_GID: dict | None = None


def _acquisition_table() -> dict:
    global _ACQUISITION
    if _ACQUISITION is None:
        table = _load_pack_acquisition(base_pack())
        # Overlay packs (BACAP) contribute ONLY their `advancements` reward source onto the base
        # table (item -> advancement game_ids that grant it). Other overlay sources are intentionally
        # not merged yet (see registry.overlay_packs / the items-merge TODO). The advancements source
        # is gated per seed by the bacap_rewards option in _acquire_from_sources, so merging it into
        # the once-cached, option-independent table is safe — an unused source for seeds with the
        # rewards (or the pack) off.
        for pack_dir in overlay_packs().values():
            for item, record in _load_pack_acquisition(pack_dir).items():
                advancements = record.get("advancements")
                if not advancements:
                    continue
                base_record = table.setdefault(item, {})
                base_record["advancements"] = sorted(
                    set(base_record.get("advancements", ())) | set(advancements))
        _ACQUISITION = table
    return _ACQUISITION


def _load_pack_acquisition(pack_dir_name: str) -> dict:
    """A pack's acquisition.json (``{}`` if it has none)."""
    path = files(_MC_ROOT).joinpath("packs", pack_dir_name, "acquisition.json")
    if not path.is_file():
        return {}
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def reward_events(world) -> dict[str, list[str]]:
    """Item base -> the active location names whose completion grants it as a BACAP advancement
    reward. Empty unless the pack AND its rewards are on (bacap_rewards), mirroring the mod disabling
    BACAP rewards on world load — with them off a reward is not a real way to obtain the item.

    Drives the reward *event* model (see REWARD_EVENT_PREFIX, create_regions, build_location_rules):
    each entry becomes an internal event location (rule = OR of reaching those advancements) holding a
    locked event item, and ``acquire`` sources the item through ``has(event)`` — a non-recursive leaf.
    AP's monotone event sweep then resolves rewards to a fixed point, instead of the recursive
    ``reached()`` source that forms ``acquire(X) -> reached(A) -> A's rule -> acquire(X)`` cycles.
    Only advancements that are an active check this seed contribute (an inactive tab / challenge_sanity
    drop is simply absent), so an event with no granting location is never created."""
    if not (bool(world.options.blazeandcave.value) and bool(world.options.bacap_rewards.value)):
        return {}
    location_by_gid = {
        data.game_id: name for name, data in world._get_active_locations().items() if data.game_id
    }
    events: dict[str, list[str]] = {}
    for base, record in _acquisition_table().items():
        names = sorted({location_by_gid[gid] for gid in record.get("advancements", ())
                        if gid in location_by_gid})
        if names:
            events[base] = names
    return events


def _entity_by_gid() -> dict:
    global _ENTITY_BY_GID
    if _ENTITY_BY_GID is None:
        _ENTITY_BY_GID = {data.game_id: name for name, data in MOBS_ALL.items()}
    return _ENTITY_BY_GID


def _wood_region(base: str) -> str | None:
    """Region a wood-family item (or a stick) is free in, or ``None`` if it is not wood."""
    if base == "stick" or _WOOD_RE.match(base):
        return REGION_NETHER if any(w in base for w in _NETHER_WOODS) else REGION_OVERWORLD
    return None


# Block id substrings that pin a mined block to a dimension (so e.g. nether wart is gated on the
# Nether, not just "having a pickaxe"). Everything else is treated as Overworld.
_NETHER_BLOCK_HINTS = ("nether", "crimson", "warped", "basalt", "blackstone", "soul_",
                       "magma", "glowstone", "ancient_debris", "nylium", "shroomlight", "gilded")
_END_BLOCK_HINTS = ("end_stone", "chorus", "purpur", "dragon_egg")
# Craftable blocks that ALSO generate naturally, so digging one up (its self-drop) is a genuine
# free source — unlike a placed-only crafted block (planks/wool/slime_block), whose self-mining is
# circular. Only blocks that BOTH self-mine and have a recipe need listing (others keep self-mining
# unconditionally). Missing one merely over-gates it to its recipe; wrongly adding a placed-only
# block would under-gate it, so keep this conservative.
_NATURAL_SELF_MINED = frozenset({
    "stone", "cobblestone", "granite", "diorite", "andesite", "tuff", "calcite", "deepslate",
    "cobbled_deepslate", "dripstone_block", "amethyst_block", "sandstone", "red_sandstone",
    "clay", "snow_block", "packed_ice", "blue_ice", "glowstone", "magma_block", "obsidian",
    "mossy_cobblestone", "mud", "packed_mud", "bone_block",
})
# needs_<tier>_tool tag -> the material tier the mining pickaxe (and the player) must have reached.
_NEEDS_TIER = {"stone": MAT_STONE, "iron": MAT_IRON, "diamond": MAT_DIAMOND}
_BLOCK_MINING: dict | None = None


def _block_mining() -> dict:
    global _BLOCK_MINING
    if _BLOCK_MINING is None:
        path = files(_MC_ROOT).joinpath("packs", base_pack(), "block_mining.json")
        with path.open(encoding="utf-8") as handle:
            _BLOCK_MINING = json.load(handle)
    return _BLOCK_MINING


_BLOCK_STRUCTURES: dict | None = None


def _block_structures() -> dict:
    """Reverse of each structure's natural-generation palette (structures.json -> STRUCTURES): block
    id -> the structures it generates in. Lets acquire() treat 'mine this block where it spawns in a
    structure' as a source for a placed-only block (e.g. a comparator in an Ancient City) that
    recipes/loot tables miss."""
    global _BLOCK_STRUCTURES
    if _BLOCK_STRUCTURES is None:
        mapping: dict[str, list] = {}
        for struct_name, data in STRUCTURES.items():
            for block in data.blocks:
                mapping.setdefault(block, []).append(struct_name)
        _BLOCK_STRUCTURES = mapping
    return _BLOCK_STRUCTURES


def _block_region(block: str) -> str:
    if any(hint in block for hint in _END_BLOCK_HINTS):
        return REGION_END
    if any(hint in block for hint in _NETHER_BLOCK_HINTS):
        return REGION_NETHER
    return REGION_OVERWORLD


class RuleHelper:
    """Builds logic rules as serializable AST nodes (see ``ast.py``).

    Every method returns a ``Rule`` node that is both callable against an AP
    ``CollectionState`` (so it can be handed to ``set_rule``) and serializable for
    export to the mod. All option-dependent branching is resolved here, at build
    time, so the resulting tree contains only the primitive node kinds.
    """

    def __init__(self, world: World):
        self.world = world
        self.player = world.player
        # Structures locked behind a 'Structure Unlock' item (structure_unlock option). Others are
        # gated by their dimension being reachable instead (see self.structure).
        self.locked_structures = world._get_locked_structures()
        # Structures that actually exist this seed (vanilla + overlay packs whose option is on). An
        # inactive overlay structure can't be a source or a reachable target.
        self.active_structures = world._get_active_structures()
        # Options resolved once, up front, so rule nodes never carry option logic.
        self.villager_trust = bool(world.options.villager_trust.value)
        # Mobs locked behind an 'Entity Unlock' item (mob_spawn_lock option), resolved to concrete
        # mob names (categories/All/individual names all collapse to this set).
        self.locked_mobs = world._get_locked_mobs()
        # Biome Finder enabled (start or in_pool); disabled == 0. Biome-specific advancements require
        # it when on, since that's how you locate the biome.
        self.biome_finder_enabled = bool(world.options.biome_finder.value)
        # BACAP advancement rewards, modeled as event items: base item -> active location names that
        # grant it (empty unless bacap_rewards is on). acquire() sources a rewarded item via
        # has(REWARD_EVENT_PREFIX + base); the event location carrying the reached() OR is created in
        # create_regions / build_location_rules. See reward_events for the cycle rationale.
        self.reward_events = reward_events(world)
        # Memo for acquire(): the acquisition table + options are fixed for this helper, so
        # acquire(base, stack) is pure. Datapack-scale compilation calls it millions of times for the
        # same (base, stack) pairs (planks/sticks/ingots recur in every recipe); caching collapses
        # that. Returned nodes are shared read-only across rules, which is safe (eval + to_dict only).
        self._acquire_cache: dict[tuple[str, frozenset], object] = {}
        # Thunks (deferred so cross-referencing mobs don't recurse at construction).
        self.structure_bound_mobs = {
            # Overworld — structure-locked
            E_CAT            : lambda: self.any_of(self.any_village(), self.structure(S_SWAMP_HUT)),
            E_ALLAY          : lambda: self.any_of(self.structure(S_PILLAGER_OUTPOST), self.structure(S_MANSION)),
            E_SILVERFISH     : lambda: self.structure(S_STRONGHOLD),
            E_WARDEN         : lambda: self.structure(S_ANCIENT_CITY),
            E_ENDERMITE      : lambda: self.entity(E_ENDERMAN),  # spawns from Ender Pearl throws

            # Ocean Monument
            E_ELDER_GUARDIAN : lambda: self.structure(S_OCEAN_MONUMENT),
            E_GUARDIAN       : lambda: self.structure(S_OCEAN_MONUMENT),

            # Mansion + raid mobs (Mansion direct, or raid via Pillager Captain + Village)
            E_EVOKER         : lambda: self.any_of(
                self.structure(S_MANSION),
                self.all_of(self.entity(E_PILLAGER), self.any_village()),
            ),
            E_VINDICATOR     : lambda: self.any_of(
                self.structure(S_MANSION),
                self.all_of(self.entity(E_PILLAGER), self.any_village()),
            ),
            E_VEX            : lambda: self.any_of(
                self.structure(S_MANSION),
                self.all_of(self.entity(E_PILLAGER), self.any_village()),
            ),
            E_RAVAGER        : lambda: self.all_of(self.entity(E_PILLAGER), self.any_village()),

            # Nether — structure-locked (delegates to canonical advancement)
            E_BLAZE          : lambda: self.reached(f"{ADVANCEMENT_PREFIX}{A_A_TERRIBLE_FORTRESS}"),
            E_WITHER_SKELETON: lambda: self.reached(f"{ADVANCEMENT_PREFIX}{A_A_TERRIBLE_FORTRESS}"),
            E_PIGLIN_BRUTE   : lambda: self.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),

            # End — delegates to City at the End advancement (which encodes Dragon kill + End City)
            E_SHULKER        : lambda: self.reached(f"{ADVANCEMENT_PREFIX}{A_THE_CITY_AT_THE_END_OF_THE_GAME}"),

            # Trial Chambers
            E_BREEZE         : lambda: self.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),
        }

        # Extra food/item gate required to *tame* a mob, on top of reaching it (see can_tame).
        # Mobs absent from the map are itemless (mount-tamed) and need only the entity itself.
        self.taming_food = {
            E_WOLF           : lambda: self.can_get_bone(),  # bones
            E_CAT            : lambda: self.can_get_raw_fish(),  # raw cod / salmon
            E_NAUTILUS       : lambda: self.entity(E_PUFFERFISH),
            E_ZOMBIE_NAUTILUS: lambda: self.entity(E_PUFFERFISH),
        }
        # Extra food/item gate required to *breed* a mob, on top of reaching it (see can_breed).
        # Mobs absent from the map breed with a food co-located with them (seeds, flowers, nether
        # fungi, jungle bamboo, …) and so need only the entity itself.
        self.breeding_food = {
            E_ALLAY    : lambda: self.can_duplicate_allay(),  # amethyst + jukebox/disc
            E_ARMADILLO: lambda: self.can_get_spider_eye(),  # spider eye
            E_AXOLOTL  : lambda: self.all_of(self.can_craft_bucket(),
                                             self.entity(E_TROPICAL_FISH)
                                             ),  # bucket of tropical fish
            E_CAT      : lambda: self.can_get_raw_fish(),  # raw cod / salmon
            E_OCELOT   : lambda: self.can_get_raw_fish(),  # raw cod / salmon
            E_COW      : lambda: self.can_get_wheat(),  # wheat
            E_MOOSHROOM: lambda: self.can_get_wheat(),  # wheat
            E_SHEEP    : lambda: self.can_get_wheat(),  # wheat
            E_GOAT     : lambda: self.can_get_wheat(),  # wheat
            E_LLAMA    : lambda: self.can_get_wheat(),  # hay bale = 9 wheat
            E_HORSE    : lambda: self.can_get_golden_food(),  # golden carrot / apple
            E_DONKEY   : lambda: self.can_get_golden_food(),  # golden carrot / apple
            E_PIG      : lambda: self.can_get_pig_food(),  # carrot / potato / beetroot
            E_FROG     : lambda: self.can_get_slimeball(),  # slimeball
            E_TURTLE   : lambda: self.can_get_seagrass(),  # seagrass (shears)
            E_WOLF     : lambda: self.can_get_meat(),  # any meat
            E_NAUTILUS : lambda: self.can_get_all_fish(),
        }
        # Mobs that never spawn naturally and only come from another mob: a breeding cross, a
        # transformation, or a companion spawn. Gated by reaching (and, for the bred ones, being
        # able to breed) their parent(s), on top of reaching their own region. No entry references
        # itself, so there is no rule-build recursion.
        self.parent_bound_mobs = {
            E_MULE           : lambda: self.all_of(self.can_breed(E_HORSE), self.can_breed(E_DONKEY)),  # Horse × Donkey
            E_TADPOLE        : lambda: self.can_breed(E_FROG),                # Frog spawn
            E_TRADER_LLAMA   : lambda: self.entity(E_WANDERING_TRADER),       # spawns leashed to a Wandering Trader
            E_ZOMBIE_NAUTILUS: lambda: self.entity(E_DROWNED),                # drowned-converted variant
            E_ZOGLIN         : lambda: self.entity(E_HOGLIN),                 # a Hoglin that left the Nether
        }
        # Player-constructed mobs: gated by their build materials (snow / copper / iron blocks) +
        # a carved pumpkin, on top of reaching their region (see entity). The material helpers are
        # called with their golem-drop branch disabled, since that branch references the very golem
        # being built → infinite recursion at rule-build time.
        # Pure build recipes (N blocks + a carved pumpkin) for golems you summon by placing blocks.
        # summon() uses these directly for minecraft:summoned_entity (e.g. "Hired Help"): a *naturally
        # spawned* village Iron Golem does NOT count toward summoning, so the recipe must exclude it.
        self.summon_recipes = {
            E_SNOW_GOLEM  : lambda: self.all_of(
                self.can_get_snowball(include_snow_golem=False),  # → snow blocks
                self.can_get_carved_pumpkin(),
            ),
            E_COPPER_GOLEM: lambda: self.all_of(
                self.can_get_copper(include_copper_golem=False),  # → copper block
                self.can_get_carved_pumpkin(),
            ),
            E_IRON_GOLEM  : lambda: self.all_of(  # iron blocks + carved pumpkin
                self.can_get_iron(include_iron_golem=False),
                self.can_get_carved_pumpkin(),
            ),
        }
        # How entity() *reaches* each constructed mob: the build recipe, plus any natural spawn. Snow
        # and Copper Golems never spawn naturally; an Iron Golem also spawns in villages, so for the
        # encounter/kill gate either path suffices.
        self.constructed_mobs = {
            E_SNOW_GOLEM  : self.summon_recipes[E_SNOW_GOLEM],
            E_COPPER_GOLEM: self.summon_recipes[E_COPPER_GOLEM],
            E_IRON_GOLEM  : lambda: self.any_of(
                self.any_village(),                   # natural village spawn
                self.summon_recipes[E_IRON_GOLEM](),  # or built
            ),
        }
        # Mobs whose only natural spawn is a specific, searchable biome — gated on the Biome Finder
        # (when enabled), since that's how you locate the biome. The Dried Ghast (→ Happy Ghast) can
        # also come from Piglin bartering, so there the finder is only needed without that path.
        self.biome_bound_mobs = {
            E_AXOLOTL    : lambda: self.needs_biome_finder(),   # Lush Caves
            E_GOAT       : lambda: self.needs_biome_finder(),   # mountain biomes
            E_FROG       : lambda: self.needs_biome_finder(),   # temperate / warm / cold variants
            E_HAPPY_GHAST: lambda: self.any_of(                 # Dried Ghast: Soul Sand Valley or bartering
                self.can_barter(),
                self.needs_biome_finder(),
            ),
        }

    # -----------------------------------------------------------------------
    # Global
    # -----------------------------------------------------------------------
    def has(self, item: str, count: int = 1):
        return Has(self.player, item, count)

    def has_all(self, *items: str):
        return and_(*[Has(self.player, item) for item in items])

    def has_any(self, *items: str):
        return or_(*[Has(self.player, item) for item in items])

    def any_of(self, *conditions):
        return or_(*conditions)

    def all_of(self, *conditions):
        return and_(*conditions)

    # -----------------------------------------------------------------------
    # Structures
    # -----------------------------------------------------------------------
    def structure(self, struct_gid: str):
        if struct_gid not in STRUCTURES:
            print(f"Warning: {struct_gid} not found !")
            return Const(False)
        if struct_gid not in self.active_structures:
            return Const(False)  # an overlay structure whose pack is off this seed never generates
        # A structure is reachable only once its dimension is reachable (Overworld is always
        # reachable, Nether/End need their access). Locked structures additionally require their
        # unlock item — but the dimension gate still applies, so e.g. the Nether ruined portal is
        # not reachable from the Overworld just because its unlock item was received.
        region = self.access_region(STRUCTURES[struct_gid].region)
        if struct_gid in self.locked_structures:
            return self.all_of(self.has(f"{STRUCT_UNLOCK_PREFIX}{STRUCTURES[struct_gid].label}"), region)
        return region

    def any_village(self):
        return self.any_of(*[self.structure(gid) for gid in
                             (S_VILLAGE_DESERT, S_VILLAGE_PLAINS, S_VILLAGE_SAVANNA, S_VILLAGE_SNOWY, S_VILLAGE_TAIGA)])

    def any_portal(self, nether_allowed: bool = False):
        portals = [
            S_RUINED_PORTAL, S_RUINED_PORTAL_DESERT, S_RUINED_PORTAL_OCEAN, S_RUINED_PORTAL_MOUNTAIN, S_RUINED_PORTAL_JUNGLE,
            S_RUINED_PORTAL_SWAMP
        ]
        if nether_allowed:
            portals = portals + [S_RUINED_PORTAL_NETHER]
        return self.any_of(*[self.structure(p) for p in portals])

    def any_mineshaft(self):
        return self.any_of(self.structure(S_MINESHAFT), self.structure(S_MINESHAFT_MESA))

    def any_shipwreck(self):
        return self.any_of(self.structure(S_SHIPWRECK), self.structure(S_SHIPWRECK_BEACHED))

    # -----------------------------------------------------------------------
    # Locations
    # -----------------------------------------------------------------------
    def access_region(self, region_name: str):
        return ReachRegion(self.player, region_name)

    def reached(self, location: str):
        return ReachLocation(self.player, location)

    # -----------------------------------------------------------------------
    # Items
    # -----------------------------------------------------------------------
    def can_get_obsidian(self):
        # Obtain obsidian (e.g. to build a portal). Every source is region-gated, so this resolves
        # correctly per dimension: from the Nether only the Nether ruined portal / Bastion / Nether
        # Fortress / Piglin barter count — the Overworld paths (mining diamonds for the pickaxe,
        # village chests) are unreachable until the Overworld itself is.
        return self.any_of(
            self.reached(f"{ADVANCEMENT_PREFIX}{A_DIAMONDS}"),    # mine it (diamond pickaxe)
            self.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # Bastion Remnant chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_A_TERRIBLE_FORTRESS}"),  # Nether Fortress
            self.any_portal(True),                                        # any ruined portal (incl. Nether)
            self.can_barter(),                                            # Piglin bartering
            self.any_village(),                                           # village chest
        )

    def can_craft_bucket(self):
        return self.any_of(
            self.reached(f"{ADVANCEMENT_PREFIX}{A_ACQUIRE_HARDWARE}"),  # Craft it yourself
            self.structure(S_MANSION),
            self.structure(S_DUNGEON),
            self.any_village(),
            self.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),  # Trial Chambers barrel
        )

    def can_get_totem(self):
        return self.entity(E_EVOKER)

    def can_get_string(self):
        return self.any_of(
            self.has_any_entities(E_SPIDER, E_CAVE_SPIDER, E_CAT, E_STRIDER),  # mob drops
            self.knowledge(K_FISHING),  # fishing junk
            self.can_barter(),  # Piglin bartering
            self.structure(S_DESERT_PYRAMID),  # chest
            self.structure(S_JUNGLE_PYRAMID),  # tripwire trap → string
            self.structure(S_PILLAGER_OUTPOST),  # chest
            self.structure(S_TRAIL_RUINS),  # chest
            self.all_of(
                self.knowledge(K_SWORD),
                self.any_mineshaft(),  # cobweb → string
            ),
            self.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # Bastion chests
            self.structure(S_DUNGEON),  # chest
            self.structure(S_MANSION),  # chest
        )

    def can_get_arrow(self):
        return self.any_of(
            self.can_get_feather(),
            self.has_any_entities(E_SKELETON, E_STRAY, E_BOGGED, E_PARCHED),  # Arrow drop
            self.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # Bastion Remnant chest
            self.structure(S_PILLAGER_OUTPOST),  # Pillager Outpost chest
            self.structure(S_JUNGLE_PYRAMID),  # Temple Dispenser
            self.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),  # Entrance/Supply/Common chest | Also tipped arrow
            self.any_village(),  # Fletcher chest
            self.can_trade_villager(),  # Fletcher trade
            self.reached(f"{ADVANCEMENT_PREFIX}{A_HERO_OF_THE_VILLAGE}"),  # Fletcher gift
            self.can_barter(),  # Spectral Arrow
        )

    def can_get_disc(self):
        return self.any_of(
            self.all_of(self.has_any_entities(E_SKELETON, E_STRAY, E_BOGGED, E_PARCHED), self.entity(E_CREEPER)),
            # skeleton variant kills Creeper
            self.entity(E_GHAST),  # Tears disc — deflect fireball
            self.all_of(self.has_brush(), self.structure(S_TRAIL_RUINS)),  # Relic disc — archaeology
            self.structure(S_DUNGEON),  # 13, cat, otherside
            self.structure(S_ANCIENT_CITY),  # 13, cat, otherside
            self.structure(S_MANSION),  # 13, cat
            self.reached(f"{ADVANCEMENT_PREFIX}{A_EYE_SPY}"),  # otherside — Stronghold
            self.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # Pigstep — Bastion
            self.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),  # Creator (Music Box) — decorated pots
            self.reached(f"{ADVANCEMENT_PREFIX}{A_UNDER_LOCK_AND_KEY}"),  # Precipice/Creator — Vault
            self.reached(f"{ADVANCEMENT_PREFIX}{A_REVAULTING}"),  # Creator — Ominous Vault
            # NOTE: a Chicken Jockey (Baby Zombie riding a Chicken) can drop music_disc_lava_chicken.
            # Not in logic — revisit if mob-lock variants (e.g. Chicken Jockey) are introduced.
        )

    def can_get_spyglass(self):
        # Spyglass = 2 amethyst shards + 1 copper ingot.
        return self.all_of(
            self.can_get_copper(),
            self.any_of(
                self.knowledge(K_PICKAXE),       # mine an amethyst geode
                self.structure(S_ANCIENT_CITY),  # chest
                self.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),  # Trial Chambers
            ),
        )

    def can_get_trident(self):
        return self.all_of(
            self.knowledge(K_TRIDENT),
            self.any_of(
                self.entity(E_DROWNED),
                self.reached(f"{ADVANCEMENT_PREFIX}{A_UNDER_LOCK_AND_KEY}"),
            )
        )

    def can_get_redstone(self):
        # Redstone has no Nether/End source at all (ore, chests, mobs and trades are all Overworld).
        return self.all_of(
            self.access_region(REGION_OVERWORLD),
            self.any_of(
                self.all_of(
                    self.knowledge(K_PICKAXE),
                    self.material(MAT_IRON),
                ),  # mine Redstone Ore
                self.any_mineshaft(),  # chest
                self.structure(S_DUNGEON),  # chest
                self.reached(f"{ADVANCEMENT_PREFIX}{A_EYE_SPY}"),  # Stronghold chest
                self.any_village(),  # temple chest
                self.structure(S_MANSION),  # chest
                self.entity(E_WITCH),  # Witch drop
                self.reached(f"{ADVANCEMENT_PREFIX}{A_HERO_OF_THE_VILLAGE}"),  # Cleric gift
                self.can_trade_villager(1),  # Cleric novice trade (cleric/1/emerald_redstone)
            ),
        )

    def can_get_snowball(self, include_snow_golem: bool = True):
        # ``include_snow_golem`` must be False when building the Snow Golem gate itself, otherwise
        # entity(Snow Golem) → this helper → entity(Snow Golem) recurses at rule-build time.
        # Sources verified against the 26.1.2 jar (acquisition indexer + loot tables): snow layers /
        # snow blocks drop snowballs only when mined with a SHOVEL (block tool gate → K_SHOVEL); the
        # only snowball *chest* tables are the Ancient City ice box, the snowy village house, and the
        # Trial Chambers. Igloos have no snowball table — their snow is just snow-block mining, so it
        # is already covered by K_SHOVEL and must not appear as a shovel-free source.
        sources = [
            self.knowledge(K_SHOVEL),  # dig snow layers / snow blocks (incl. igloo / snowy-village blocks)
            self.structure(S_ANCIENT_CITY),  # Ice Box chest
            self.any_village(),  # Snowy village house chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),  # chamber chest
        ]
        if include_snow_golem:
            sources.append(self.entity(E_SNOW_GOLEM))  # Snow Golem drop
        return self.any_of(*sources)

    def can_get_carved_pumpkin(self):
        # Carve a wild pumpkin with shears (pumpkins grow freely in the Overworld), or find one
        # already carved and placed in a structure.
        return self.any_of(
            self.can_get_shear(),                # shears + naturally-grown pumpkin
            self.structure(S_PILLAGER_OUTPOST),  # placed in structure
            self.structure(S_MANSION),           # placed in structure
        )

    def can_get_egg(self):
        return self.any_of(
            self.entity(E_CHICKEN),  # Chicken lay
            self.any_village(),  # Fletcher chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),  # chamber chest
        )

    def can_get_feather(self):
        return self.any_of(
            self.entity(E_CHICKEN),  # Chicken drop
            self.entity(E_PARROT),  # Parrot drop
            self.entity(E_CAT),  # Cat morning gift
            self.any_village(),  # Fletcher/Plains House chest
            self.any_shipwreck(),  # Map chest
        )

    def can_get_gold(self):
        return self.all_of(
            self.material(MAT_GOLD),  # always needed — unlock gold tier
            self.any_of(
                self.knowledge(K_PICKAXE),  # mine Gold Ore
                self.entity(E_ZOMBIFIED_PIGLIN),  # Gold Nugget drop
                self.can_barter(),  # Piglin bartering
                self.any_mineshaft(),  # Gold Ingot in chest
                self.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # Bastion chests
                self.structure(S_DESERT_PYRAMID),  # Gold Ingot in chest
                self.structure(S_JUNGLE_PYRAMID),  # Gold Ingot in chest
                self.structure(S_BURIED_TREASURE),  # Gold Ingot in chest
                self.reached(f"{ADVANCEMENT_PREFIX}{A_A_TERRIBLE_FORTRESS}"),  # Nether Fortress bridge
                self.any_portal(True),  # Ruined Portal
                self.any_shipwreck(),  # Treasure chest
                self.structure(S_DUNGEON),  # Gold Ingot in chest
                self.reached(f"{ADVANCEMENT_PREFIX}{A_EYE_SPY}"),  # Stronghold chest
                self.any_village(),  # Temple/Toolsmith/Weaponsmith
                self.structure(S_MANSION),  # Gold Ingot in chest
                self.reached(f"{ADVANCEMENT_PREFIX}{A_THE_CITY_AT_THE_END_OF_THE_GAME}"),  # End City
                self.structure(S_OCEAN_RUIN_COLD),  # Gold Nugget
                self.structure(S_OCEAN_RUIN_WARM),  # Gold Nugget
                self.structure(S_TRAIL_RUINS),  # Gold Nugget
                self.structure(S_IGLOO),  # Gold Nugget
            ),
        )

    def has_brush(self):
        # Brush = copper ingot + feather + stick.
        return self.all_of(
            self.knowledge(K_BRUSH),
            self.can_get_copper(),
            self.can_get_feather(),
        )

    def can_get_copper(self, include_copper_golem: bool = True):
        # ``include_copper_golem`` must be False when building the Copper Golem gate itself,
        # otherwise entity(Copper Golem) → this helper → entity(Copper Golem) recurses.
        sources = [
            self.knowledge(K_PICKAXE),  # mine Copper Ore
            self.entity(E_DROWNED),  # Copper Ingot drop
        ]
        if include_copper_golem:
            sources.append(self.entity(E_COPPER_GOLEM))  # Copper Golem drop
        return self.all_of(
            self.material(MAT_COPPER),  # always needed — unlock copper tier
            self.access_region(REGION_OVERWORLD),  # no copper of any kind in the Nether/End
            self.any_of(*sources),
        )

    def can_get_iron(self, include_iron_golem: bool = True):
        # ``include_iron_golem`` must be False when building the Iron Golem gate itself, otherwise
        # entity(Iron Golem) → this helper → entity(Iron Golem) recurses at rule-build time.
        sources = [
            self.all_of(self.knowledge(K_PICKAXE), self.access_region(REGION_OVERWORLD)),  # mine Iron Ore (Overworld only)
            self.has_any_entities(E_HUSK, E_ZOMBIE, E_ZOMBIE_VILLAGER),  # mob drops
            self.any_mineshaft(),  # chest
            self.structure(S_DESERT_PYRAMID),  # chest
            self.structure(S_JUNGLE_PYRAMID),  # chest
            self.structure(S_PILLAGER_OUTPOST),  # chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # Bastion chests
            self.structure(S_BURIED_TREASURE),  # chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_A_TERRIBLE_FORTRESS}"),  # Nether Fortress chest
            self.any_shipwreck(),  # Treasure chest
            self.structure(S_DUNGEON),  # chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_EYE_SPY}"),  # Stronghold chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),  # Trial Chambers chest
            self.any_village(),  # Toolsmith/Weaponsmith/Armorer chest
            self.structure(S_MANSION),  # chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_THE_CITY_AT_THE_END_OF_THE_GAME}"),  # End City chest
        ]
        if include_iron_golem:
            sources.append(self.entity(E_IRON_GOLEM))  # Iron Golem drop
        return self.all_of(
            self.material(MAT_IRON),  # always needed — unlock iron tier
            self.any_of(*sources),
        )

    def can_get_shear(self):
        # Shears = 2 iron ingots (verified against the 26.1.2 jar). They are also sold by a Shepherd
        # (shepherd/1 trade) and found in the snowy-village shepherd house chest, but both of those
        # require reaching a village — which is itself an iron source (see can_get_iron) — so iron
        # access already subsumes them, no extra branch needed. The mod additionally gates shears
        # behind Knowledge: Shear Handling + the iron material tier (data.py TOOL_LOCKS), enforced on
        # craft *and* pickup, so the Knowledge is always required regardless of how shears are got.
        # Iron is taken with the Iron Golem drop disabled: building that golem already needs iron, so
        # it is never a unique iron source here, and leaving it on would recurse through the carved-
        # pumpkin gate (entity(Iron Golem) → carved pumpkin → shears → iron → entity(Iron Golem)).
        return self.all_of(
            self.knowledge(K_SHEAR),
            self.can_get_iron(include_iron_golem=False),
        )

    def can_get_honeycomb(self):
        # Honeycomb (verified against the 26.1.2 jar) comes from exactly two sources: shearing a
        # full beehive/bee-nest (needs shears + bees) or the Trial Chambers corridor/entrance chests.
        return self.any_of(
            self.all_of(
                self.can_get_shear(),
                self.entity(E_BEE),
            ),
            self.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),
        )

    def can_get_notch_apple(self):
        return self.any_of(
            self.any_mineshaft(),  # Mineshaft chest
            self.structure(S_ANCIENT_CITY),  # Ancient City chest
            self.structure(S_DESERT_PYRAMID),  # Desert Pyramid chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # Bastion Treasure chest
            self.any_portal(True),  # Ruined Portal chest
            self.structure(S_DUNGEON),  # Dungeon chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_REVAULTING}"),  # Ominous Unique Vault
            self.structure(S_MANSION),  # Mansion chest
        )

    def can_get_cake(self):
        return self.any_of(
            # Crafting the cake: 3 milk buckets + 2 sugar + 1 egg + 3 wheat (sugar cane is trivial).
            self.all_of(
                self.entity(E_COW),
                self.can_craft_bucket(),
                self.can_get_egg(),
                self.can_get_wheat(),
            ),
            # Getting it
            self.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),
            self.can_trade_villager(4),  # Farmer expert trade (farmer/4/emerald_cake)
        )

    def can_get_bed(self):
        return self.any_of(
            self.entity(E_SHEEP),  # wool from sheep, plus planks → craft
            self.can_get_string(),  # 4 string → 1 wool
            self.any_village(),  # bed in house, shepherd chest
            self.structure(S_MANSION),
            self.structure(S_IGLOO),
            self.any_shipwreck(),  # supply chest
            self.can_trade_villager(2),  # Shepherd sells wool @lvl2 → craft bed (beds direct @lvl3)
        )

    def can_kill(self):
        """Kill a mob that requires a real weapon to fight safely — needs a melee weapon Knowledge
        (sword / axe / spear). Use for hostile/tanky mobs and bosses."""
        return self.any_of(
            self.knowledge(K_SWORD),
            self.knowledge(K_AXE),
            self.knowledge(K_SPEAR),
        )

    def can_breath_underwater(self):
        # Every way to breathe underwater long enough to fight down there (e.g. the Elder Guardian).
        return self.any_of(
            self.all_of(self.knowledge(K_BREWING), self.entity(E_PUFFERFISH)),         # Water Breathing potion
            self.all_of(self.entity(E_NAUTILUS), self.structure(S_BURIED_TREASURE)),   # Conduit: nautilus shells + heart of the sea
            self.entity(E_TURTLE),                                                     # Turtle Shell helmet (scute)
        )

    def can_kill_any_mob(self):
        # At least one non-boss mob is reachable (every non-boss mob is beatable bare-handed). Used
        # where the action is just "kill something", e.g. spreading sculk.
        return self.any_of(*[
            self.entity(name) for name, data in MOBS_ALL.items()
            if data.category != MCEntityCategory.BOSS
        ])

    def needs_biome_finder(self):
        # Advancements that require finding a specific biome depend on the Biome Finder when it is
        # enabled; with it disabled no such item exists, so the requirement vanishes.
        return self.has(ITEM_BIOME_FINDER) if self.biome_finder_enabled else Const(True)

    # -----------------------------------------------------------------------
    # Breeding / taming foods
    # -----------------------------------------------------------------------
    def can_get_bone(self):
        return self.any_of(
            self.has_any_entities(E_SKELETON, E_STRAY, E_BOGGED, E_PARCHED),  # mob drops
            self.knowledge(K_FISHING),       # fishing junk
            self.structure(S_DESERT_PYRAMID),  # chest
            self.structure(S_JUNGLE_PYRAMID),  # chest
            self.structure(S_DUNGEON),         # chest
            self.structure(S_MANSION),         # chest
            self.structure(S_ANCIENT_CITY),    # chest
        )

    def can_get_raw_fish(self):
        # Raw cod / salmon (cat & ocelot food).
        return self.any_of(
            self.has_any_entities(E_COD, E_SALMON),                 # punch/kill the fish
            self.knowledge(K_FISHING),                              # rod
            self.has_any_entities(E_GUARDIAN, E_ELDER_GUARDIAN, E_DOLPHIN, E_POLAR_BEAR),  # mob drops
            self.any_village(),                                     # village chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_HERO_OF_THE_VILLAGE}"),  # Fisherman gift
        )

    def can_get_all_fish(self):
        # Any fish item, including the puffer/tropical variants used to breed nautili.
        return self.any_of(
            self.can_get_raw_fish(),
            self.entity(E_PUFFERFISH),
            self.entity(E_TROPICAL_FISH),
        )

    def can_get_wheat(self):
        # Till + harvest needs a hoe; otherwise wheat is found ready-made in many chests.
        return self.any_of(
            self.knowledge(K_HOE),
            self.any_village(),               # farms / chests
            self.any_shipwreck(),             # supply chest
            self.structure(S_PILLAGER_OUTPOST),
            self.structure(S_DUNGEON),
            self.structure(S_IGLOO),
            self.structure(S_TRAIL_RUINS),
            self.structure(S_MANSION),
            self.structure(S_OCEAN_RUIN_COLD),
            self.structure(S_OCEAN_RUIN_WARM),
        )

    def can_get_carrot(self):
        return self.any_of(
            self.any_village(),                 # village farms / chests
            self.structure(S_PILLAGER_OUTPOST),  # chest
            self.any_shipwreck(),               # chest
            self.has_any_entities(E_ZOMBIE, E_HUSK, E_ZOMBIE_VILLAGER),  # rare drop
        )

    def can_get_golden_apple(self):
        # Craft (gold + apple; apples drop freely from oak/dark-oak leaves) or find ready-made.
        return self.any_of(
            self.can_get_gold(),                                            # craft it
            self.any_mineshaft(),                                           # chest
            self.structure(S_DESERT_PYRAMID),                               # chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),   # Bastion chest
            self.structure(S_IGLOO),                                        # chest
            self.any_portal(True),                                          # Ruined Portal chest
            self.structure(S_DUNGEON),                                      # chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_EYE_SPY}"),               # Stronghold chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),         # Trial Chambers chest
            self.structure(S_OCEAN_RUIN_COLD),                             # Underwater Ruin chest
            self.structure(S_OCEAN_RUIN_WARM),
            self.structure(S_MANSION),                                      # chest
            self.structure(S_ANCIENT_CITY),                                # placed in city center
        )

    def can_get_golden_carrot(self):
        # Craft (gold nuggets + carrot) or find ready-made.
        return self.any_of(
            self.all_of(self.can_get_gold(), self.can_get_carrot()),       # craft it
            self.structure(S_ANCIENT_CITY),                                # ice box chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),   # Bastion chest
            self.any_portal(True),                                          # Ruined Portal chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),         # Trial Chambers chest
            self.can_trade_villager(5),                                     # Farmer master trade (farmer/5/emerald_golden_carrot)
        )

    def can_get_golden_food(self):
        # Either golden apple or golden carrot (e.g. horse/donkey breeding accepts both).
        return self.any_of(self.can_get_golden_apple(), self.can_get_golden_carrot())

    def can_get_pig_food(self):
        # Carrot / potato / beetroot share the same sources (village farms, chests, zombie drops).
        return self.can_get_carrot()

    def can_get_spider_eye(self):
        return self.any_of(
            self.has_any_entities(E_SPIDER, E_CAVE_SPIDER, E_WITCH),  # drops
            self.structure(S_DESERT_PYRAMID),  # chest
        )

    def can_get_slimeball(self):
        return self.any_of(
            self.entity(E_SLIME),  # Slime drop
            self.can_trade_wandering_trader(),  # Wandering Trader sells slime balls
        )

    def can_get_seagrass(self):
        return self.any_of(
            self.can_get_shear(),                               # shear seagrass
            self.entity(E_TURTLE),  # Turtle drop
        )

    def can_get_meat(self):
        # Wolves accept any meat, including rotten flesh. Any non-boss mob is killable without a
        # weapon, so this reduces to reaching one of these meat/flesh sources.
        return self.has_any_entities(E_COW, E_PIG, E_SHEEP, E_CHICKEN, E_RABBIT, E_ZOMBIE)

    def can_get_food(self):
        # "Obtain any edible item" (e.g. the Husbandry root, which validates on eating anything).
        # The Overworld has trivial food everywhere; the Nether grows none, so a Nether-start player
        # needs a hunted or looted source. Every path below is a real, in-jar food source — adding
        # them only widens reachability, never a fake path that could soft-lock generation.
        return self.any_of(
            self.access_region(REGION_OVERWORLD),                          # apples, crops, animals
            self.can_get_meat(),                                           # cow/pig/sheep/chicken/rabbit/zombie
            self.entity(E_HOGLIN),                                         # raw porkchop (crimson forest / bastion stable)
            self.entity(E_ZOMBIFIED_PIGLIN),                              # rotten flesh (edible)
            self.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # Bastion chests: cooked porkchop + golden apple/carrot
            self.can_get_golden_food(),                                   # golden apple/carrot (ruined portal, structure loot, …)
        )

    def can_duplicate_allay(self):
        # Allay duplicate: hand it an Amethyst Shard while it dances to a jukebox.
        return self.all_of(
            self.any_of(
                self.knowledge(K_PICKAXE),       # mine an amethyst geode
                self.structure(S_ANCIENT_CITY),  # chest
                self.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),  # Trial Chambers
            ),
            self.material(MAT_DIAMOND),  # jukebox needs a diamond
            self.can_get_disc(),         # disc to play
        )

    # -----------------------------------------------------------------------
    # Entities
    # -----------------------------------------------------------------------
    def has_all_entities(self, *entity_names: str):
        return self.all_of(*[self.entity(name) for name in entity_names])

    def has_any_entities(self, *entity_names: str):
        return self.any_of(*[self.entity(name) for name in entity_names])

    def has_n_entities(self, n: int, *entity_names: str):
        """Reach at least ``n`` distinct mobs from the pool. Uses an AtLeast node so the rule
        stays O(len(pool)) instead of an OR over C(len(pool), n) combinations (which blew the
        serialized export up to ~500MB for Arbalistic's 35-mob pool)."""
        return at_least(n, [self.entity(name) for name in entity_names])

    def entity(self, entity_name: str):
        if entity_name not in MOBS_ALL:
            print(f"Warning: {entity_name} not found !")

        entity_data = MOBS_ALL[entity_name]
        structure_thunk = self.structure_bound_mobs.get(entity_name)
        structure_node = structure_thunk() if structure_thunk is not None else Const(True)
        build_thunk = self.constructed_mobs.get(entity_name)
        build_node = build_thunk() if build_thunk is not None else Const(True)
        parent_thunk = self.parent_bound_mobs.get(entity_name)
        parent_node = parent_thunk() if parent_thunk is not None else Const(True)
        biome_thunk = self.biome_bound_mobs.get(entity_name)
        biome_node = biome_thunk() if biome_thunk is not None else Const(True)
        mob_locked = (entity_name in self.locked_mobs)
        unlock_node = self.has(f"{ENTITY_UNLOCK_PREFIX}{entity_name}") if mob_locked else Const(True)

        return self.all_of(
            self.access_region(entity_data.region),
            structure_node,
            build_node,
            parent_node,
            biome_node,
            unlock_node,
        )

    def summon(self, entity_name: str):
        """Gate for *summoning/building* an entity (minecraft:summoned_entity), e.g. constructing an
        Iron Golem for "Hired Help". Unlike entity(), a naturally spawned mob does not count, so the
        natural-spawn paths are excluded and the build recipe is required. Region access and the
        spawn-lock unlock still apply (a locked mob cannot be built either). Entities with no known
        recipe fall back to plain reachability."""
        recipe = self.summon_recipes.get(entity_name)
        if recipe is None:
            return self.entity(entity_name)
        entity_data = MOBS_ALL[entity_name]
        mob_locked = (entity_name in self.locked_mobs)
        unlock_node = self.has(f"{ENTITY_UNLOCK_PREFIX}{entity_name}") if mob_locked else Const(True)
        return self.all_of(
            self.access_region(entity_data.region),
            recipe(),
            unlock_node,
        )

    def can_defeat(self, entity_name: str):
        """Gate for *killing* a mob — its drops and any kill goal. Ordinary mobs reduce to plain
        reachability (beatable bare-handed via the boat trap); the four bosses require the gear,
        knowledge and environment their fight demands. Single source of truth, shared by the
        entity-kill locations (engine.collect_entity_rules) and boss drops resolved through acquire()
        (e.g. a nether star from the Wither)."""
        if entity_name == E_ENDER_DRAGON:
            return self.all_of(
                self.entity(E_ENDER_DRAGON),
                self.knowledge(K_BOW),  # shoot out the end crystals
                self.any_of(self.can_kill(), self.can_get_bed()),  # melee or bed-bombing
            )
        if entity_name == E_WITHER:
            return self.all_of(
                self.reached(f"{ADVANCEMENT_PREFIX}{A_SPOOKY_SCARY_SKELETON}"),  # wither skulls
                self.entity(E_WITHER),
                self.can_kill(),
                self.knowledge(K_ARMOR),  # survive the blast / wither effect
                self.material(MAT_IRON),  # at least iron-tier gear
            )
        if entity_name == E_WARDEN:
            return self.all_of(self.entity(E_WARDEN), self.can_kill())
        if entity_name == E_ELDER_GUARDIAN:
            return self.all_of(
                self.entity(E_ELDER_GUARDIAN),
                self.can_kill(),
                self.can_breath_underwater(),  # survive the fight underwater
            )
        return self.entity(entity_name)

    def can_tame(self, entity_name: str):
        """Reach the mob *and* hold its taming item (bones, fish, …). Mobs with no taming
        item (mount-tamed: horses, llamas, …) reduce to plain reachability."""
        food_thunk = self.taming_food.get(entity_name)
        food_node = food_thunk() if food_thunk is not None else Const(True)
        return self.all_of(self.entity(entity_name), food_node)

    def can_breed(self, entity_name: str):
        """Reach the mob *and* hold its breeding food. Mobs whose food is co-located with them
        (seeds, flowers, nether fungi, …) reduce to plain reachability."""
        food_thunk = self.breeding_food.get(entity_name)
        food_node = food_thunk() if food_thunk is not None else Const(True)
        return self.all_of(self.entity(entity_name), food_node)

    # -----------------------------------------------------------------------
    # Trades
    # -----------------------------------------------------------------------
    def can_trade_villager(self, tier: int = 1):
        """Trade with a profession villager in a village at trade level ``tier``
        (novice = 1 … master = 5). When the villager_trust option is on, the level
        is gated behind that many Progressive Villager Trust items."""
        base = self.all_of(self.entity(E_VILLAGER), self.any_village())
        if self.villager_trust:
            return self.all_of(base, self.has(ITEM_VILLAGER_TRUST, tier))
        return base

    def can_trade_wandering_trader(self):
        """Trade with a Wandering Trader. It has no trade levels, so it is never
        gated by villager_trust — only by reaching the mob."""
        return self.entity(E_WANDERING_TRADER)

    def can_trade(self):
        """The player can perform *some* trade — a novice (min-level) villager or a
        Wandering Trader. Use for rules that only need "a trade happened" with no
        specific profession/level (e.g. What a Deal!, Star Trader)."""
        return self.any_of(self.can_trade_villager(), self.can_trade_wandering_trader())

    def can_barter(self):
        return self.all_of(
            self.access_region(REGION_NETHER),
            self.entity(E_PIGLIN),
            self.material(MAT_GOLD),
        )

    def can_get_beacon_base(self):
        """A block valid for a beacon pyramid base — any of iron / gold / emerald / diamond /
        netherite. Required by the construct_beacon trigger whenever the beacon needs a pyramid
        (level >= 1); the cheapest reachable one satisfies it."""
        sources = [
            self.acquire("minecraft:iron_block"),
            self.acquire("minecraft:gold_block"),
            self.acquire("minecraft:emerald_block"),
            self.acquire("minecraft:diamond_block"),
            self.acquire("minecraft:netherite_block"),
        ]
        return self.any_of(*[node for node in sources if node is not None])

    # -----------------------------------------------------------------------
    # AP Items
    # -----------------------------------------------------------------------
    def material(self, tier: int):
        node = Has(self.player, ITEM_MATERIAL_HANDLING, tier)
        # A material tier carries the dimension its ore lives in, so a requirement gates on reaching
        # that dimension — not just the item count (single source of truth: every caller, curated or
        # compiled, inherits the floor). Copper/iron/diamond are Overworld-only ores; netherite is the
        # Nether (ancient debris). Stone and gold exist in BOTH the Overworld and the Nether
        # (cobblestone/blackstone, overworld/nether gold ore), so they gate on either. Wood tier is
        # ``has(..., 0)`` (trivially true), so it needs no floor.
        if tier >= MAT_NETHERITE:
            return self.all_of(node, self.access_region(REGION_NETHER))
        if tier in (MAT_COPPER, MAT_IRON, MAT_DIAMOND):
            return self.all_of(node, self.access_region(REGION_OVERWORLD))
        if tier in (MAT_STONE, MAT_GOLD):
            return self.all_of(node, self.any_of(self.access_region(REGION_OVERWORLD),
                                                 self.access_region(REGION_NETHER)))
        return node

    def knowledge(self, item: str):
        return Has(self.player, f"Knowledge: {item}")

    # -----------------------------------------------------------------------
    # Item acquisition (used by the trigger compiler to resolve item criteria)
    #
    # ``acquire`` reads the acquisition table (tools/build_acquisition.py) and turns an item's data
    # sources into AST: recipes recurse into their ingredients, mob drops into ``entity``, mining
    # into a pickaxe + material tier, trades into ``can_trade_villager``, chest loot into
    # ``structure``. A recursion stack breaks the ingot⟷block recipe cycles; items with no usable
    # source fall back to the tool/material gates (``_acquire_fallback``), else ``None``.
    # -----------------------------------------------------------------------
    _MAX_DEPTH = 4
    _SIZE_CAP = 1500  # serialized bytes; larger trees collapse to their region floor (see _coarsen)

    def acquire(self, item_id: str, _stack: frozenset = frozenset()):
        base = item_id.split(":", 1)[-1] if ":" in item_id else item_id
        if base.startswith("#"):
            return None  # a raw tag (recipe tags are pre-expanded; a bare tag can't be resolved)
        if base in _stack:
            return None  # recipe cycle — this path can't justify itself
        if len(_stack) >= self._MAX_DEPTH:
            # Too deep to keep expanding. A base material bottoms out at an ore/region regardless, so
            # its tier floor is a sound TERMINAL here — this is not the lossy top-level bypass (a
            # shallow acquire still uses the real sources, e.g. diamond via bastion loot); it only
            # stops the runaway recursion of a deep crafting chain (waxed_copper_lantern → … →
            # copper_ingot). A non-material this deep gives up.
            tier = _MATERIAL_TIER_BY_ITEM.get(base)
            return self.material(tier) if tier is not None else None
        # Memoize on (base, stack): the result is pure for this helper, so the same item is computed
        # once and shared. Datapack compilation calls acquire ~9M times for far fewer distinct keys.
        key = (base, _stack)
        cache = self._acquire_cache
        if key in cache:
            return cache[key]
        cache[key] = result = self._acquire_compute(base, _stack)
        return result

    def _acquire_compute(self, base: str, _stack: frozenset):
        # Wood is free once its dimension is reached; collapse it instead of fanning out variants.
        wood_region = _wood_region(base)
        if wood_region is not None:
            return self.access_region(wood_region)

        # Dragon's breath has no recipe or loot table — you bottle it from the Ender Dragon's breath
        # mid-fight — so it is modeled here: the dragon must be reachable (and unlocked, when the
        # boss-lock option gates it) plus a glass bottle.
        if base == "dragon_breath":
            return self.all_of(self.entity(E_ENDER_DRAGON), self.acquire("minecraft:glass_bottle"))

        # A froglight is made when a frog eats a small magma cube — the frog spawns only in the
        # Overworld and the magma cube only in the Nether, so it genuinely needs BOTH dimensions.
        # (The table mis-models it as a plain magma-cube drop, which would drop the frog/Overworld.)
        if base in ("ochre_froglight", "pearlescent_froglight", "verdant_froglight"):
            return self.all_of(self.entity(E_FROG), self.entity(E_MAGMA_CUBE))

        # An elytra exists only in an End City ship — placed in an item frame, not a loot table the
        # indexer reads — so it has no acquisition record and would fall back to its bare material
        # tier (MAT_WOOD, trivially true), dropping the End requirement entirely. Gate it explicitly
        # on the End City (region The End + any structure lock) plus Knowledge: Flying.
        if base == "elytra":
            return self.all_of(self.knowledge(K_FLYING), self.structure(S_END_CITY))

        # A dragon head sits on the End City ships' spires (no recipe or loot table, like the elytra),
        # so it has no acquisition record — gate it on reaching an End City.
        if base == "dragon_head":
            return self.structure(S_END_CITY)

        # Tools / armor / gated craftables (bow, fishing rod, shears, …) need their Knowledge to be
        # USED however they were obtained — but they are still obtained via their real sources, each
        # carrying its own region. So gate on the Knowledge AND the obtainability (recipe ingredients,
        # structure loot, a trade, a drop, …), not a lossy material-tier proxy that drops the
        # ingredients' regions entirely (e.g. a fishing rod's string → spider/cobweb → Overworld). The
        # sources are coarsened first so a big region-only tree collapses to its region floor instead
        # of bloating every tool rule.
        if base in TOOL_LOCKS:
            knowledge_name, tier = TOOL_LOCKS[base]
            sources = self._acquire_from_sources(base, _stack)
            obtain = self._coarsen(sources) if sources is not None else self.material(tier)
            # A tool granted as a reward still needs its Knowledge to be used, so the reward joins
            # `obtain` (inside the Knowledge gate), not the whole node.
            return self.all_of(self.knowledge(knowledge_name), self._with_reward(base, obtain))

        sources = self._acquire_from_sources(base, _stack)
        result = self._coarsen(sources if sources is not None else self._acquire_fallback(base))
        # Universal Material Handling pickup lock: obtaining a tier-gated raw material by ANY in-world
        # route — mining, chest loot, mob drop, villager trade, crafting — is blocked until enough
        # Progressive Material Handling is received (MaterialLockService, enforced on floor pickup by
        # ItemEntityMixin AND on container/crafting takes by SlotMixin). Only the mining path carried
        # this gate, so _unique_or absorption could drop the gated branch and leave the material
        # reachable via a bare chest/region path (e.g. diamond looted from a structure). Gate the whole
        # obtain-it node on the tier count — no region floor, since each source carries its own region.
        tier = _MATERIAL_TIER_BY_ITEM.get(base)
        if tier is not None and result is not None:
            result = self.all_of(self.has(ITEM_MATERIAL_HANDLING, tier), result)
        # The BACAP reward grants the item via a /give, which adds straight to the inventory and so
        # bypasses both pickup mixins — a real, lock-free way to get it — so it joins OUTSIDE the
        # material gate.
        return self._with_reward(base, result)

    def _with_reward(self, base: str, node):
        """OR a BACAP advancement reward (its event item) into an item's obtainability, when
        bacap_rewards is on. ADDITIVE only — it never replaces the item's real sources or fallback,
        so e.g. powder_snow_bucket keeps its bucket path (and the advancement that grants it stays
        reachable instead of deadlocking on its own circular reward). Cycle-free: the event is a
        has() leaf resolved by AP's event sweep, not a recursive reached(). reward_events is empty
        unless the option is on, so this is a no-op otherwise."""
        if base not in self.reward_events:
            return node
        reward = self.has(f"{REWARD_EVENT_PREFIX}{base}")
        return reward if node is None else self.any_of(node, reward)

    def _acquire_from_sources(self, base: str, _stack: frozenset):
        """OR over every modeled way to obtain ``base`` (recipe, drop, mining, silk-mining, trade,
        structure loot, gameplay), each carrying its region/tier gate; ``None`` when the item has no
        acquisition record or no usable source. Shared by ordinary items and tool/armor gates."""
        record = _acquisition_table().get(base)
        if record is None:
            return None
        inner = _stack | {base}
        options = []
        for recipe in record.get("recipes", ()):
            node = self._recipe_node(recipe, inner)
            if node is not None:
                options.append(node)
        for mob_file in record.get("drops", ()):
            name = _entity_by_gid().get(f"minecraft:{mob_file}")
            if name in MOBS_ALL:
                # A drop needs the mob *defeated*, not merely reached: harmless for ordinary mobs
                # (can_defeat == reachability) but correct for boss drops like the Wither's nether
                # star, which must gate on the whole boss fight rather than just entering its arena.
                options.append(self.can_defeat(name))
        mining_blocks = record.get("mining", ())
        # Data-driven: when the item has a tier-gated ORE source, a same-item block carrying no tier
        # info is a circular placed form (e.g. ``redstone_wire`` beside ``redstone_ore`` [iron]) whose
        # bare region path would undercut the ore's tier gate — the tiered ore is the real source.
        has_tiered_ore = any(_block_mining().get(b, {}).get("needs") for b in mining_blocks)
        for block in mining_blocks:
            if has_tiered_ore and _block_mining().get(block) is None:
                continue
            # A block that drops *itself* is only a real "mine it" source when it generates
            # naturally. A placed-only block — a crafted one (slime_block, wool, planks, …), a mob
            # trophy (a skull/head) or a frog-made froglight — must be obtained then placed first,
            # so its self-mining is circular; worse, it yields a bare region path that _unique_or
            # absorption uses to delete the item's real entity/structure gates (e.g. slime_ball via
            # slime_block, or wither_skeleton_skull, dropping their mob gate).
            placed_only = (bool(record.get("recipes"))
                           or base.endswith(("_head", "_skull", "_froglight")))
            if block == base and placed_only and base not in _NATURAL_SELF_MINED:
                # The self-mine is circular (placed-only), but the block may still generate naturally
                # inside a structure's template (structures.json palette) — reaching that structure
                # and mining it there is a genuine source recipes/loot don't capture (e.g. a
                # comparator in an Ancient City, an Overworld path its quartz recipe otherwise hides
                # behind the Nether). Redundant ones (a block whose recipe is already reachable in the
                # structure's dimension) collapse in _unique_or / _coarsen.
                for struct_name in _block_structures().get(base, ()):
                    if struct_name in self.active_structures:
                        options.append(self.structure(struct_name))
                continue
            options.append(self._mining_node(block, base))
        for block in record.get("silk_mining", ()):
            # The block yields itself only to a Silk-Touch tool (bee_nest, ice, coral, …): same
            # region/tier as a normal mine PLUS the capability to silk-touch (enchant). Always behind
            # the silk gate, so it is never a free path even when the block is placed-only.
            options.append(self._mining_node(block, base, silk=True))
        if record.get("trades"):
            options.append(self.can_trade_villager())
        for structure_name in record.get("structures", ()):
            if structure_name in STRUCTURES:
                options.append(self.structure(structure_name))
        for table in record.get("gameplay", ()):
            node = self._gameplay_node(table, inner)
            if node is not None:
                options.append(node)
        options = [o for o in options if o is not None]  # mining/silk paths may yield None on a cycle
        return self._unique_or(options) if options else None

    def _coarsen(self, node):
        """Bound the serialized tree: a recipe-combinatorial item (dyes, beds, stews) past
        ``_SIZE_CAP`` whose only leaves are region reachability collapses to the OR of its regions.
        A tree that carries a real gate — a ``Has`` (structure/entity unlock, knowledge, material)
        or a reached-location — is left intact even when large, so a structure/mob lock or
        progression gate is never silently dropped (else a locked source would look reachable)."""
        if node is None or len(node.canonical_json()) <= self._SIZE_CAP:
            return node
        gated, regions = node.gate_summary()
        if gated or not regions:
            return node
        return or_(*[self.access_region(region) for region in sorted(regions)])

    @staticmethod
    def _unique_or(nodes):
        """OR of ``nodes``, deduplicated and absorption-simplified. Besides dropping identical
        sources, this applies ``A ∨ (A ∧ B) = A``: a source that is an AND containing another,
        simpler source as one of its conjuncts is redundant and removed. That collapses the common
        explosion where an item is obtainable trivially (e.g. ``region(Overworld)`` from a cow) and
        also via a far heavier path that still needs that same trivial step (a bred-mob drop)."""
        seen = set()
        unique = []
        for node in nodes:
            key = node.key()
            if key not in seen:
                seen.add(key)
                unique.append(node)

        keep = []
        for node in unique:
            if isinstance(node, And):
                conjunct_keys = {child.key() for child in node.children}
                if conjunct_keys & (seen - {node.key()}):  # a simpler sibling is one of this AND's conjuncts
                    continue
            keep.append(node)
        return or_(*keep)

    def _recipe_node(self, recipe: dict, stack: frozenset):
        """A recipe is satisfied when every distinct ingredient is obtainable (AND)."""
        parts = []
        for ingredient in recipe.get("ingredients", ()):
            node = self._ingredient_node(ingredient, stack)
            if node is None:
                return None  # an unobtainable ingredient disqualifies the whole recipe
            parts.append(node)
        return and_(*parts) if parts else None

    def _ingredient_node(self, ingredient: dict, stack: frozenset):
        if "any_of" in ingredient:
            options = [self._ingredient_node(sub, stack) for sub in ingredient["any_of"]]
            options = [node for node in options if node is not None]
            return or_(*options) if options else None
        if "item" in ingredient:
            return self.acquire(ingredient["item"], stack)
        return None

    def _mining_node(self, block: str, item: str, silk: bool = False):
        """Break ``block`` (to obtain ``item``): be in the block's dimension, and — only for
        pickaxe-mineable blocks (soul sand, glowstone, crops drop bare-handed) — hold a pickaxe of
        the required material tier. ``silk`` adds the Silk-Touch capability when the block yields
        itself only to a Silk-Touch tool."""
        parts = [self.access_region(_block_region(block))]
        info = _block_mining().get(block)
        if info is not None:
            parts.append(self.knowledge(K_PICKAXE))
            tier = _MATERIAL_TIER_BY_ITEM.get(item) or _NEEDS_TIER.get(info.get("needs"))
            if tier is not None:
                parts.append(self.material(tier))
        if silk:
            parts.append(self.can_silk_touch())
        return self.all_of(*parts)

    def can_silk_touch(self):
        """The capability to wield a Silk-Touch tool. Two routes, mirroring the enchant gate in
        ``triggers.py``: enchant one yourself (``acquire(enchanting_table)`` gates Knowledge:
        Enchanting + its tier), OR apply a Silk-Touch enchanted book with an anvil (a librarian's
        book is a no-Knowledge trade path)."""
        routes = [self.acquire("minecraft:enchanting_table")]
        book = self.acquire("minecraft:enchanted_book")
        if book is not None:
            routes.append(self.all_of(book, self.acquire("minecraft:anvil")))
        return self.any_of(*routes)

    def _gameplay_node(self, table: str, stack: frozenset = frozenset()):
        """The gate for a 'gameplay' loot source — a real, repeatable acquisition path, grounded in
        the 26.1.2 jar loot-table types. ``stack`` is the acquisition recursion stack, threaded into
        the items a source implies (a fishing rod for fishing, gold for bartering, shears for harvest)
        so a circular source — fishing up the very fishing rod being resolved — breaks instead of
        recursing forever:
          * fishing tables → a fishing rod;
          * piglin_bartering → the Nether, a piglin and gold;
          * a mob's gift / interaction / growth table → reach that mob (gift tables ARE reliable
            renewable sources — see ``_GAMEPLAY_MOB``);
          * a charged-creeper head/skull table → charge a creeper (a creeper + an Overworld
            thunderstorm) AND reach the victim (see ``_CHARGED_CREEPER_VICTIM``);
          * a Hero-of-the-Village villager profession gift → win a raid (a pillager + a village);
          * a block-harvest table → the block's dimension, plus shears for a shear interaction;
          * trial-chamber spawner equipment / chest loot → the Trial Chambers structure."""
        if table in ("fishing", "fish", "junk", "treasure"):
            return self.acquire("minecraft:fishing_rod", stack)  # None on a cycle → caller drops it
        if table == "piglin_bartering":
            gold = self.acquire("minecraft:gold_ingot", stack)
            if gold is None:
                return None  # circular (bartering FOR gold) — not a usable source here
            return self.all_of(self.access_region(REGION_NETHER), self.entity(E_PIGLIN), gold)
        victim = _CHARGED_CREEPER_VICTIM.get(table)
        if victim is not None:
            # Head only drops from a CHARGED creeper's kill: charge a creeper (creeper + Overworld
            # thunderstorm) AND reach the victim. dict.fromkeys dedups when the victim is a creeper.
            mobs = list(dict.fromkeys([E_CREEPER, victim]))
            return self.all_of(*[self.entity(name) for name in mobs],
                               self.access_region(REGION_OVERWORLD))
        mob = _GAMEPLAY_MOB.get(table)
        if mob is not None:
            return self.entity(mob)
        if table in _GAMEPLAY_VILLAGER_GIFTS:
            return self.all_of(self.entity(E_PILLAGER), self.any_village())  # Hero of the Village
        harvest = _GAMEPLAY_HARVEST.get(table)
        if harvest is not None:
            region, needs_shears = harvest
            if needs_shears:
                shears = self.acquire("minecraft:shears", stack)
                if shears is None:
                    return None  # can't shear-harvest without shears (circular here)
                return self.all_of(self.access_region(region), shears)
            return self.access_region(region)
        if table in ("corridor", "trial_chamber_melee", "trial_chamber_ranged"):
            return self.structure(S_TRIAL_CHAMBERS)
        return None

    def _acquire_fallback(self, base: str):
        """Items absent from the acquisition table (or with no usable source): the tool/armor
        knowledge+material gate, the diamond/netherite mining gates, then the bare material tier."""
        if base in ("diamond", "diamond_block"):
            return self.all_of(self.knowledge(K_PICKAXE), self.material(MAT_DIAMOND),
                               self.access_region(REGION_OVERWORLD))
        if base in ("netherite_ingot", "netherite_block", "netherite_scrap", "ancient_debris"):
            return self.all_of(self.knowledge(K_PICKAXE), self.material(MAT_NETHERITE),
                               self.access_region(REGION_NETHER))
        if base in TOOL_LOCKS:
            knowledge_name, tier = TOOL_LOCKS[base]
            return self.all_of(self.knowledge(knowledge_name), self.material(tier))
        tier = _MATERIAL_TIER_BY_ITEM.get(base)
        if tier is not None:
            return self.material(tier)
        if base.endswith("_bucket"):
            return self.acquire("minecraft:bucket")  # a filled bucket needs a bucket
        return None
