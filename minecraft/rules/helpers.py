from .. import *
from .ast import Const, Has, ReachRegion, ReachLocation, and_, or_


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
        portals = [S_RUINED_PORTAL, S_RUINED_PORTAL_DESERT, S_RUINED_PORTAL_OCEAN, S_RUINED_PORTAL_MOUNTAIN, S_RUINED_PORTAL_JUNGLE, S_RUINED_PORTAL_SWAMP]
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
    def can_craft_bucket(self):
        return self.any_of(
            self.reached(f"{ADVANCEMENT_PREFIX}{A_ACQUIRE_HARDWARE}"),  # Craft it yourself
            self.structure(S_MANSION),
            self.structure(S_DUNGEON),
            self.any_village(),
        )

    def can_get_totem(self):
        return self.entity(E_EVOKER)

    def can_get_string(self):
        return self.any_of(
            self.has_any_entities(E_SPIDER, E_CAVE_SPIDER, E_CAT, E_STRIDER),  # mob drops
            self.knowledge(K_FISHING),  # fishing junk
            self.can_barter(),  # Piglin bartering
            self.structure(S_DESERT_PYRAMID),  # chest
            self.structure(S_JUNGLE_PYRAMID),  # string inside
            self.structure(S_PILLAGER_OUTPOST),  # chest
            self.structure(S_TRAIL_RUINS),  # chest
            self.any_mineshaft(),  # cobweb → string
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
            self.can_trade(False),  # Fletcher trade
            self.reached(f"{ADVANCEMENT_PREFIX}{A_HERO_OF_THE_VILLAGE}"),  # Fletcher gift
            self.can_barter(),  # Spectral Arrow
        )

    def can_get_disc(self):
        return self.any_of(
            self.all_of(self.has_any_entities(E_SKELETON, E_STRAY, E_BOGGED, E_PARCHED), self.entity(E_CREEPER)),  # skeleton variant kills Creeper
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
        )

    def can_get_spyglass(self):
        return self.all_of(
            self.material(MAT_COPPER),
            self.knowledge(K_PICKAXE),
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
        return self.any_of(
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
        )

    def can_get_snowball(self):
        return self.any_of(
            self.knowledge(K_SHOVEL),
            self.entity(E_SNOW_GOLEM),  # Snow Golem drop
            self.structure(S_ANCIENT_CITY),  # Ice Box chest
            self.any_village(),  # Snowy village house
            self.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),  # chamber chest
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
        return self.all_of(
            self.knowledge(K_BRUSH),
            self.material(MAT_COPPER),
            self.can_get_feather(),
        )

    def can_get_copper(self):
        return self.all_of(
            self.material(MAT_COPPER),  # always needed — unlock copper tier
            self.any_of(
                self.knowledge(K_PICKAXE),  # mine Copper Ore
                self.entity(E_DROWNED),  # Copper Ingot drop
                self.entity(E_COPPER_GOLEM),  # Copper Golem drop
            ),
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
            # Crafting the cake
            self.all_of(
                self.entity(E_COW),
                self.can_craft_bucket(),
                self.can_get_egg(),
            ),
            # Getting it
            self.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),
        )

    def can_get_bed(self):
        return self.any_of(
            self.entity(E_SHEEP),  # wool from sheep, plus planks → craft
            self.can_get_string(),  # 4 string → 1 wool
            self.any_village(),  # bed in house, shepherd chest
            self.structure(S_MANSION),
            self.structure(S_IGLOO),
            self.any_shipwreck(),  # supply chest
        )

    def can_kill(self):
        return self.any_of(
            self.knowledge(K_SWORD),
            self.knowledge(K_AXE),
            self.knowledge(K_SPEAR),
        )

    # -----------------------------------------------------------------------
    # Entities
    # -----------------------------------------------------------------------
    def has_all_entities(self, *entity_names: str):
        return self.all_of(*[self.entity(name) for name in entity_names])

    def has_any_entities(self, *entity_names: str):
        return self.any_of(*[self.entity(name) for name in entity_names])

    def entity(self, entity_name: str):
        if entity_name not in MOBS_ALL:
            print(f"Warning: {entity_name} not found !")

        entity_data = MOBS_ALL[entity_name]
        structure_thunk = self.structure_bound_mobs.get(entity_name)
        structure_node = structure_thunk() if structure_thunk is not None else Const(True)
        category_locked = (entity_data.category in self.locked_categories)
        unlock_node = self.has(f"{ENTITY_UNLOCK_PREFIX}{entity_name}") if category_locked else Const(True)

        return self.all_of(
            self.access_region(entity_data.region),
            structure_node,
            unlock_node,
        )

    # -----------------------------------------------------------------------
    # Trades
    # -----------------------------------------------------------------------
    def can_trade(self, include_trader: bool = True, tier: int = 1):
        traders = [self.all_of(self.entity(E_VILLAGER), self.any_village())]
        if include_trader:
            traders.append(self.entity(E_WANDERING_TRADER))

        base = self.any_of(*traders)

        if self.villager_trust:
            return self.all_of(base, self.has(ITEM_VILLAGER_TRUST, tier))
        return base

    def can_barter(self):
        return self.all_of(
            self.access_region(MCRegion.NETHER),
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
