# AST primitives must be imported directly: `from .. import *` cannot supply them because the
# package __init__ imports this module (via set_rules) before it defines Const/Has/and_/… .
from .ast import Const, Has, ReachRegion, ReachLocation, and_, or_, at_least
from .. import *


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
        # Options resolved once, up front, so rule nodes never carry option logic.
        self.villager_trust = bool(world.options.villager_trust.value)
        self.locked_categories = set(world.options.mob_spawn_lock_category.value)
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
        }
        # Player-constructed mobs: gated by their build materials (snow / copper / iron blocks) +
        # a carved pumpkin, on top of reaching their region (see entity). The material helpers are
        # called with their golem-drop branch disabled, since that branch references the very golem
        # being built → infinite recursion at rule-build time.
        self.constructed_mobs = {
            # Build-only (never spawn naturally): N blocks + a carved pumpkin.
            E_SNOW_GOLEM  : lambda: self.all_of(
                self.can_get_snowball(include_snow_golem=False),  # → snow blocks
                self.can_get_carved_pumpkin(),
            ),
            E_COPPER_GOLEM: lambda: self.all_of(
                self.can_get_copper(include_copper_golem=False),  # → copper block
                self.can_get_carved_pumpkin(),
            ),
            # Iron Golem also spawns naturally in villages, so either suffices.
            E_IRON_GOLEM  : lambda: self.any_of(
                self.any_village(),  # natural village spawn
                self.all_of(  # built: iron blocks + carved pumpkin
                    self.can_get_iron(include_iron_golem=False),
                    self.can_get_carved_pumpkin(),
                ),
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
    def structure(self, struct_name: str):
        if struct_name not in STRUCTURES:
            print(f"Warning: {struct_name} not found !")
            return Const(False)
        # Locked structures require their unlock item; unlocked ones are reachable as soon as their
        # dimension is reachable (Overworld is always reachable, Nether/End need their access).
        if struct_name in self.locked_structures:
            return self.has(f"{STRUCT_UNLOCK_PREFIX}{struct_name}")
        return self.access_region(STRUCTURES[struct_name].region)

    def any_village(self):
        return self.any_of(*[self.structure(f"Village ({biome})") for biome in ["Desert", "Plains", "Savanna", "Snowy", "Taiga"]])

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
        sources = [
            self.knowledge(K_SHOVEL),  # dig snow layers / snow blocks
            self.structure(S_ANCIENT_CITY),  # Ice Box chest
            self.any_village(),  # Snowy village house / chest
            self.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),  # chamber chest
            self.structure(S_IGLOO),  # snow blocks in igloo
        ]
        if include_snow_golem:
            sources.append(self.entity(E_SNOW_GOLEM))  # Snow Golem drop
        return self.any_of(*sources)

    def can_get_carved_pumpkin(self):
        # Carve a wild pumpkin with shears (pumpkins grow freely in the Overworld), or find one
        # already carved and placed in a structure.
        return self.any_of(
            self.knowledge(K_SHEAR),             # shears + naturally-grown pumpkin
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
            self.all_of(self.can_kill_with_fist(), self.has_any_entities(E_HUSK, E_ZOMBIE, E_ZOMBIE_VILLAGER)),  # mob drops (zombies punchable)
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
            self.can_trade_villager(2),  # Shepherd sells beds
        )

    def can_kill(self):
        """Kill a mob that requires a real weapon to fight safely — needs a melee weapon Knowledge
        (sword / axe / spear). Use for hostile/tanky mobs and bosses."""
        return self.any_of(
            self.knowledge(K_SWORD),
            self.knowledge(K_AXE),
            self.knowledge(K_SPEAR),
        )

    def can_kill_with_fist(self):
        """Low-level mobs (passive animals, weak mobs) can be punched to death — no weapon Knowledge
        required. Bare hands are always available, so this is unconditionally true; it exists to mark
        at the call site that the kill needs no weapon (vs can_kill())."""
        return Const(True)

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
            self.has_any_entities(E_GUARDIAN, E_DOLPHIN, E_POLAR_BEAR),  # mob drops
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
            self.all_of(self.can_kill_with_fist(), self.has_any_entities(E_ZOMBIE, E_HUSK, E_ZOMBIE_VILLAGER)),  # rare drop (zombies punchable)
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
            self.all_of(self.can_kill_with_fist(), self.has_any_entities(E_SPIDER, E_CAVE_SPIDER, E_WITCH)),  # drops (spiders are punchable)
            self.structure(S_DESERT_PYRAMID),  # chest
        )

    def can_get_slimeball(self):
        return self.any_of(
            self.all_of(self.can_kill_with_fist(), self.entity(E_SLIME)),  # Slime drop (punchable)
            self.can_trade_wandering_trader(),  # Wandering Trader sells slime balls
        )

    def can_get_seagrass(self):
        return self.any_of(
            self.knowledge(K_SHEAR),                            # shear seagrass
            self.all_of(self.can_kill_with_fist(), self.entity(E_TURTLE)),  # Turtle drop (passive)
        )

    def can_get_meat(self):
        # Wolves accept any meat, including rotten flesh. Passive animals in the pool are punchable,
        # so no weapon is required to obtain meat.
        return self.all_of(
            self.can_kill_with_fist(),
            self.has_any_entities(E_COW, E_PIG, E_SHEEP, E_CHICKEN, E_RABBIT, E_ZOMBIE),
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
        category_locked = (entity_data.category in self.locked_categories)
        unlock_node = self.has(f"{ENTITY_UNLOCK_PREFIX}{entity_name}") if category_locked else Const(True)

        return self.all_of(
            self.access_region(entity_data.region),
            structure_node,
            build_node,
            parent_node,
            unlock_node,
        )

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

    # -----------------------------------------------------------------------
    # AP Items
    # -----------------------------------------------------------------------
    def material(self, tier: int):
        return Has(self.player, ITEM_MATERIAL_HANDLING, tier)

    def knowledge(self, item: str):
        return Has(self.player, f"Knowledge: {item}")
