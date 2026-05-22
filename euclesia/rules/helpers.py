from .. import *


class RuleHelper:
    def __init__(self, world: World):
        self.world = world
        self.player = world.player
        # Correction : On utilise les méthodes d'instance de manière sécurisée
        self.structure_bound_mobs = {
            E_CAT           : lambda: self.any_village(),
            E_ALLAY         : lambda: self.any_of(self.structure(S_PILLAGER_OUTPOST), self.structure(S_MANSION)),
            E_ELDER_GUARDIAN: lambda: self.structure(S_OCEAN_MONUMENT),
            E_GUARDIAN      : lambda: self.structure(S_OCEAN_MONUMENT),
            E_BREEZE        : lambda: self.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),
        }

    # -----------------------------------------------------------------------
    # Global
    # -----------------------------------------------------------------------
    def has(self, item: str, count: int = 1):
        return lambda state: state.has(item, self.player, count)

    def has_all(self, *items: str):
        return lambda state: state.has_all(items, self.player)

    def has_any(self, *items: str):
        return lambda state: state.has_any(items, self.player)

    # Correction : Ajout de self pour respecter l'accès aux méthodes d'instance
    def any_of(self, *conditions):
        return lambda state: any(cond(state) for cond in conditions)

    # Correction : Ajout de self pour respecter l'accès aux méthodes d'instance
    def all_of(self, *conditions):
        return lambda state: all(cond(state) for cond in conditions)

    # -----------------------------------------------------------------------
    # Structures
    # -----------------------------------------------------------------------
    def structure(self, struct_name: str):
        if struct_name not in STRUCTURES:
            print(f"Warning: {struct_name} not found !")
        return lambda state: state.has(f"{STRUCT_UNLOCK_PREFIX}{struct_name}", self.player)

    def any_village(self):
        return self.has_any(*[f"{STRUCT_UNLOCK_PREFIX}Village ({biome})" for biome in ["Desert", "Plains", "Savanna", "Snowy", "Taiga"]])

    def any_portal(self, nether_allowed: bool = False):
        portals = [S_RUINED_PORTAL, S_RUINED_PORTAL_DESERT, S_RUINED_PORTAL_OCEAN, S_RUINED_PORTAL_MOUNTAIN, S_RUINED_PORTAL_JUNGLE, S_RUINED_PORTAL_SWAMP]
        if nether_allowed:
            return self.any_of(self.has_any(*[f"{STRUCT_UNLOCK_PREFIX}{p}" for p in portals]), self.structure(S_RUINED_PORTAL_NETHER))
        return self.has_any(*[f"{STRUCT_UNLOCK_PREFIX}{p}" for p in portals])

    def any_mineshaft(self):
        return self.has_any(f"{STRUCT_UNLOCK_PREFIX}{S_MINESHAFT}", f"{STRUCT_UNLOCK_PREFIX}{S_MINESHAFT_MESA}")

    # -----------------------------------------------------------------------
    # Locations
    # -----------------------------------------------------------------------
    def access_region(self, region_name: str):
        return lambda state: state.can_reach_region(region_name, self.player)

    def reached(self, location: str):
        return lambda state: state.can_reach_location(location, self.player)

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
        return self.all_of(
            self.entity(E_EVOKER),
            self.any_of(
                self.reached(f"{ADVANCEMENT_PREFIX}{A_VOLUNTARY_EXILE}"),
                self.structure(S_MANSION)
            )
        )

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
            self.structure(S_SHIPWRECK),  # Map chest
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
                self.structure(S_SHIPWRECK),  # Treasure chest
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
        structure_condition = self.structure_bound_mobs.get(entity_name)

        return lambda state: (
                state.can_reach_region(entity_data.region, self.player) and
                (structure_condition is None or structure_condition()(state)) and
                (not self.world.options.mob_spawn_lock_category.value or state.has(f"{ENTITY_UNLOCK_PREFIX}{entity_name}", self.player))
        )

    # -----------------------------------------------------------------------
    # Trades
    # -----------------------------------------------------------------------
    def can_trade(self, include_trader: bool = True, tier: int = 1):
        traders = [self.all_of(self.entity(E_VILLAGER), self.any_village())]
        if include_trader:
            traders.append(self.entity(E_WANDERING_TRADER))

        base = self.any_of(*traders)

        if self.world.options.villager_trust:
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
        return lambda state: state.has(ITEM_MATERIAL_HANDLING, self.player, tier)

    def knowledge(self, item: str):
        return lambda state: state.has(f"Knowledge: {item}", self.player)
