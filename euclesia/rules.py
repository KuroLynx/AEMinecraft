from enum import IntEnum

from worlds.generic.Rules import set_rule

from . import ADVANCEMENT_PREFIX, ENTITY_UNLOCK_PREFIX, MOBS_BOSS, MOBS_BREEDABLE, MOBS_TAMEABLE, STRUCT_UNLOCK_PREFIX, STRUCTURES
from .data import MOBS_ALL, MOBS_HOSTILE


class ProgressiveMaterialTier(IntEnum):
    WOOD = 0
    STONE = 1
    COPPER = 2
    IRON = 3
    GOLD = 4
    DIAMOND = 5
    NETHERITE = 6


def set_rules(world) -> None:
    player = world.player

    STRUCTURE_BOUND_MOBS = {
        "Cat"           : lambda: any_village(),
        "Allay"         : lambda: any_of(structure("Pillager Outpost"), structure("Mansion")),
        "Elder Guardian": lambda: structure("Ocean Monument"),
        "Guardian"      : lambda: structure("Ocean Monument"),
        "Breeze"        : lambda: reached(f"{ADVANCEMENT_PREFIX}Minecraft: Trial(s) Edition"),
    }

    def rule(location_name: str, condition):
        set_rule(world.multiworld.get_location(location_name, player), condition)

    def has(item: str, count: int = 1):
        return lambda state: state.has(item, player, count)

    def has_all(*items: str):
        return lambda state: state.has_all(*items, player)

    def has_any(*items: str):
        return lambda state: state.has_any(*items, player)

    def any_of(*conditions):
        return lambda state: any(cond(state) for cond in conditions)

    def all_of(*conditions):
        return lambda state: all(cond(state) for cond in conditions)

    # --- NOUVEAUX HELPERS POUR ÉVITER LES PIÈGES DE BOUCLES LAMBDAS ---
    def has_all_entities(*entity_names: str):
        return all_of(*[entity(name) for name in entity_names])

    def has_any_entities(*entity_names: str):
        return any_of(*[entity(name) for name in entity_names])

    def structure(struct_name: str):
        if struct_name not in STRUCTURES:
            print(f"Warning: {struct_name} not found !")
        return lambda state: state.has(f"{STRUCT_UNLOCK_PREFIX}{struct_name}", player)

    def any_village():
        return has_any(*[f"{STRUCT_UNLOCK_PREFIX}Village ({biome})" for biome in ["Desert", "Plains", "Savanna", "Snowy", "Taiga"]])

    def any_portal(nether_allowed: bool = False):
        portals = ["Ruined Portal", "Ruined Portal (Desert)", "Ruined Portal (Ocean)", "Ruined Portal (Mountain)", "Ruined Portal (Jungle)", "Ruined Portal (Swamp)"]
        if nether_allowed:
            return any_of(has_any(*[f"{STRUCT_UNLOCK_PREFIX}{p}" for p in portals]), structure("Ruined Portal (Nether)"))
        return has_any(*[f"{STRUCT_UNLOCK_PREFIX}{p}" for p in portals])

    def any_mineshaft():
        return has_any(f"{STRUCT_UNLOCK_PREFIX}Mineshaft", f"{STRUCT_UNLOCK_PREFIX}Mineshaft (Mesa)")

    def entity(entity_name: str):
        if entity_name not in MOBS_ALL:
            print(f"Warning: {entity_name} not found !")

        entity_data = MOBS_ALL[entity_name]

        structure_condition = STRUCTURE_BOUND_MOBS.get(entity_name)

        return lambda state: (
                state.can_reach_region(entity_data.region, player) and
                (structure_condition is None or structure_condition()(state)) and
                (not world.options.mob_spawn_lock_category.value or state.has(f"{ENTITY_UNLOCK_PREFIX}{entity_name}", player))
        )

    def access_region(region_name: str):
        return lambda state: state.can_reach_region(region_name, player)

    def reached(location: str):
        return lambda state: state.can_reach_location(location, player)

    def material(tier: int):
        return lambda state: state.has("Progressive Material Handling", player, tier)

    def knowledge(item: str):
        return lambda state: state.has(f"Knowledge: {item}", player)

    def advancement(name: str, condition):
        rule(f"{ADVANCEMENT_PREFIX}{name}", condition)

    def can_trade(include_trader: bool = True, tier: int = 1):
        traders = [all_of(entity("Villager"), any_village())]
        if include_trader:
            traders.append(entity("Wandering Trader"))

        base = any_of(*traders)

        if world.options.villager_trust:
            return all_of(base, has("Progressive Villager Trust", tier))
        return base

    def can_craft_bucket():
        return any_of(
            reached(f"{ADVANCEMENT_PREFIX}Acquire Hardware"),  # Craft it yourself
            structure("Mansion"),
            structure("Dungeon"),
            any_village(),
        )

    def can_get_totem():
        return all_of(
            entity("Evoker"),
            any_of(
                reached(f"{ADVANCEMENT_PREFIX}Voluntary Exile"),
                structure("Mansion")
            )
        )

    def can_get_string():
        return any_of(
            has_any_entities("Spider", "Cave Spider", "Cat", "Strider"),  # mob drops
            knowledge("Fishing"),  # fishing junk
            can_barter(),  # Piglin bartering
            structure("Desert Pyramid"),  # chest
            structure("Jungle Pyramid"),  # string inside
            structure("Pillager Outpost"),  # chest
            structure("Trail Ruins"),  # chest
            any_mineshaft(),  # cobweb → string
            reached(f"{ADVANCEMENT_PREFIX}Those Were the Days"),  # Bastion chests
            structure("Dungeon"),  # chest
            structure("Mansion"),  # chest
        )

    def can_get_arrow():
        return any_of(
            can_get_feather(),
            has_any_entities("Skeleton", "Stray", "Bogged", "Parched"),  # Arrow drop
            reached(f"{ADVANCEMENT_PREFIX}Those Were the Days"),  # Bastion Remnant chest
            structure("Pillager Outpost"),  # Pillager Outpost chest
            structure("Jungle Pyramid"),  # Temple Dispenser
            reached(f"{ADVANCEMENT_PREFIX}Minecraft: Trial(s) Edition"),  # Entrance/Supply/Common chest | Also tipped arrow
            any_village(),  # Fletcher chest
            can_trade(False),  # Fletcher trade
            reached(f"{ADVANCEMENT_PREFIX}Hero of the Village"),  # Fletcher gift
            can_barter(),  # Spectral Arrow
        )

    def can_get_disc():
        return any_of(
            all_of(has_any_entities("Skeleton", "Stray", "Bogged", "Parched"), entity("Creeper")),  # skeleton variant kills Creeper
            entity("Ghast"),  # Tears disc — deflect fireball
            all_of(has_brush(), structure("Trail Ruins")),  # Relic disc — archaeology
            structure("Dungeon"),  # 13, cat, otherside
            structure("Ancient City"),  # 13, cat, otherside
            structure("Mansion"),  # 13, cat
            reached(f"{ADVANCEMENT_PREFIX}Eye Spy"),  # otherside — Stronghold
            reached(f"{ADVANCEMENT_PREFIX}Those Were the Days"),  # Pigstep — Bastion
            reached(f"{ADVANCEMENT_PREFIX}Minecraft: Trial(s) Edition"),  # Creator (Music Box) — decorated pots
            reached(f"{ADVANCEMENT_PREFIX}Under Lock and Key"),  # Precipice/Creator — Vault
            reached(f"{ADVANCEMENT_PREFIX}Revaulting"),  # Creator — Ominous Vault
        )

    def can_barter():
        return all_of(
            access_region("Nether"),
            entity("Piglin"),
            material(ProgressiveMaterialTier.GOLD),
        )

    def can_get_spyglass():
        return all_of(
            material(ProgressiveMaterialTier.COPPER),
            knowledge("Pickaxe Handling"),
        )

    def can_get_trident():
        return all_of(
            knowledge("Trident Handling"),
            any_of(
                entity("Drowned"),
                reached(f"{ADVANCEMENT_PREFIX}Under Lock and Key"),
            )
        )

    def can_get_redstone():
        return any_of(
            all_of(
                knowledge("Pickaxe Handling"),
                material(ProgressiveMaterialTier.IRON),
            ),  # mine Redstone Ore
            any_mineshaft(),  # chest
            structure("Dungeon"),  # chest
            reached(f"{ADVANCEMENT_PREFIX}Eye Spy"),  # Stronghold chest
            any_village(),  # temple chest
            structure("Mansion"),  # chest
            entity("Witch"),  # Witch drop
            reached(f"{ADVANCEMENT_PREFIX}Hero of the Village"),  # Cleric gift
        )

    def can_get_snowball():
        return any_of(
            knowledge("Shovel Handling"),
            entity("Snow Golem"),  # Snow Golem drop
            structure("Ancient City"),  # Ice Box chest
            any_village(),  # Snowy village house
            reached(f"{ADVANCEMENT_PREFIX}Minecraft: Trial(s) Edition"),  # chamber chest
        )

    def can_get_egg():
        return any_of(
            entity("Chicken"),  # Chicken lay
            any_village(),  # Fletcher chest
            reached(f"{ADVANCEMENT_PREFIX}Minecraft: Trial(s) Edition"),  # chamber chest
        )

    def can_get_feather():
        return any_of(
            entity("Chicken"),  # Chicken drop
            entity("Parrot"),  # Parrot drop
            entity("Cat"),  # Cat morning gift
            any_village(),  # Fletcher/Plains House chest
            structure("Shipwreck"),  # Map chest
        )

    def can_get_gold():
        return all_of(
            material(ProgressiveMaterialTier.GOLD),  # always needed — unlock gold tier
            any_of(
                knowledge("Pickaxe Handling"),  # mine Gold Ore
                entity("Zombified Piglin"),  # Gold Nugget drop
                can_barter(),  # Piglin bartering
                any_mineshaft(),  # Gold Ingot in chest
                reached(f"{ADVANCEMENT_PREFIX}Those Were the Days"),  # Bastion chests
                structure("Desert Pyramid"),  # Gold Ingot in chest
                structure("Jungle Pyramid"),  # Gold Ingot in chest
                structure("Buried Treasure"),  # Gold Ingot in chest
                reached(f"{ADVANCEMENT_PREFIX}A Terrible Fortress"),  # Nether Fortress bridge
                any_portal(True),  # Ruined Portal
                structure("Shipwreck"),  # Treasure chest
                structure("Dungeon"),  # Gold Ingot in chest
                reached(f"{ADVANCEMENT_PREFIX}Eye Spy"),  # Stronghold chest
                any_village(),  # Temple/Toolsmith/Weaponsmith
                structure("Mansion"),  # Gold Ingot in chest
                reached(f"{ADVANCEMENT_PREFIX}The City at the End of the Game"),  # End City
                structure("Ocean Ruin (Cold)"),  # Gold Nugget
                structure("Ocean Ruin (Warm)"),  # Gold Nugget
                structure("Trail Ruins"),  # Gold Nugget
                structure("Igloo"),  # Gold Nugget
            ),
        )

    def has_brush():
        return all_of(
            knowledge("Brush Handling"),
            material(ProgressiveMaterialTier.COPPER),
            can_get_feather(),
        )

    def can_get_copper():
        return all_of(
            material(ProgressiveMaterialTier.COPPER),  # always needed — unlock copper tier
            any_of(
                knowledge("Pickaxe Handling"),  # mine Copper Ore
                entity("Drowned"),  # Copper Ingot drop
                entity("Copper Golem"),  # Copper Golem drop
            ),
        )

    def can_get_notch_apple():
        return any_of(
            any_mineshaft(),  # Mineshaft chest
            structure("Ancient City"),  # Ancient City chest
            structure("Desert Pyramid"),  # Desert Pyramid chest
            reached(f"{ADVANCEMENT_PREFIX}Those Were the Days"),  # Bastion Treasure chest
            any_portal(True),  # Ruined Portal chest
            structure("Dungeon"),  # Dungeon chest
            reached(f"{ADVANCEMENT_PREFIX}Revaulting"),  # Ominous Unique Vault
            structure("Mansion"),  # Mansion chest
        )

    def can_get_cake():
        return any_of(
            # Crafting the cake
            all_of(
                entity("Cow"),
                can_craft_bucket(),
                can_get_egg(),
            ),
            # Getting it
            reached(f"{ADVANCEMENT_PREFIX}Minecraft: Trial(s) Edition"),
        )

    # -----------------------------------------------------------------------
    # Story
    # -----------------------------------------------------------------------

    advancement("Stone Age", all_of(
        knowledge("Pickaxe Handling"),
        material(ProgressiveMaterialTier.STONE),
    ),
                )

    advancement("Getting an Upgrade", any_of(
        reached(f"{ADVANCEMENT_PREFIX}Stone Age"),
        reached(f"{ADVANCEMENT_PREFIX}Minecraft: Trial(s) Edition"),
        reached(f"{ADVANCEMENT_PREFIX}Hero of the Village"),
        can_trade(False),  # A toolsmith
    ),
                )

    advancement("Acquire Hardware", all_of(
        material(ProgressiveMaterialTier.IRON),  # You always need to have unlocked iron handling
        any_of(
            reached(f"{ADVANCEMENT_PREFIX}Stone Age"),  # You can mine iron ore
            any_village(),  # Find it in any village chests
            any_mineshaft(),  # Find it in any mineshaft chests
            structure("Buried Treasure"),
            structure("Shipwreck"),
            structure("Desert Pyramid"),
            structure("Jungle Pyramid"),
            structure("Mansion"),
            reached(f"{ADVANCEMENT_PREFIX}Minecraft: Trial(s) Edition"),

            reached(f"{ADVANCEMENT_PREFIX}Eye Spy"),  # In Stronghold
            reached(f"{ADVANCEMENT_PREFIX}A Terrible Fortress"),  # In Nether Fortress
            reached(f"{ADVANCEMENT_PREFIX}Those Were the Days"),  # In Bastion Remnant
            reached(f"{ADVANCEMENT_PREFIX}The City at the End of the Game"),  # In End City

            can_barter(),  # Piglin trade gives nuggets

            any_portal(True),
            has_any_entities("Husk", "Iron Golem", "Zombie", "Zombie Villager"),
        ),
    ),
                )

    advancement("Suit Up", all_of(
        knowledge("Armor Handling"),  # Always needing armor handling
        any_of(
            reached(f"{ADVANCEMENT_PREFIX}Acquire Hardware"),  # Craft it yourself
            can_trade(False),  # Tradeable at novice level from armorer / in any_village
            reached(f"{ADVANCEMENT_PREFIX}The City at the End of the Game"),  # In End City
            reached(f"{ADVANCEMENT_PREFIX}Eye Spy"),  # In Stronghold
            reached(f"{ADVANCEMENT_PREFIX}Under Lock and Key"),  # In Trial Chambers Vault
            structure("Ancient City"),
            can_barter(),  # Iron Boots
        ),
    ),
                )

    advancement("Hot Stuff", any_of(
        can_craft_bucket()
    ),
                )

    advancement("Isn't It Iron Pick", all_of(
        knowledge("Pickaxe Handling"),
        any_of(
            reached(f"{ADVANCEMENT_PREFIX}Acquire Hardware"),  # Craft it yourself
            any_mineshaft(),  # In Mineshaft
            reached(f"{ADVANCEMENT_PREFIX}The City at the End of the Game"),  # In End City
            reached(f"{ADVANCEMENT_PREFIX}Eye Spy"),  # In Stronghold
            any_village(),
            can_trade(False, 3),  # A toolsmith
        ),
    ),
                )

    advancement("Not Today Thank You", all_of(
        knowledge("Shield Handling"),
        any_of(
            reached(f"{ADVANCEMENT_PREFIX}Acquire Hardware"),  # Craft it yourself
            reached(f"{ADVANCEMENT_PREFIX}Under Lock and Key"),  # In Trial Chambers Vault
            can_trade(False, 3),
        ),
    ),
                )

    advancement("Diamonds!", all_of(
        material(ProgressiveMaterialTier.DIAMOND),
        any_of(
            knowledge("Pickaxe Handling"),  # Mine it yourself
            any_mineshaft(),  # In Mineshaft
            reached(f"{ADVANCEMENT_PREFIX}Those Were the Days"),  # In Bastion Remnant
            structure("Desert Pyramid"),  # In Desert Pyramid (Chest)
            structure("Jungle Pyramid"),
            structure("Buried Treasure"),
            structure("Shipwreck"),
            reached(f"{ADVANCEMENT_PREFIX}A Terrible Fortress"),  # In Nether Fortress
            reached(f"{ADVANCEMENT_PREFIX}The City at the End of the Game"),  # In End City
            reached(f"{ADVANCEMENT_PREFIX}Eye Spy"),  # In Stronghold

            reached(f"{ADVANCEMENT_PREFIX}Minecraft: Trial(s) Edition"),  # In Trial Chambers (Chest & Pots)
            reached(f"{ADVANCEMENT_PREFIX}Under Lock and Key"),  # In Trial Chambers (Vault)
            reached(f"{ADVANCEMENT_PREFIX}Revaulting"),  # In Trial Chambers (Ominous Vault)

            any_village(),  # In Village

            all_of(  # In Desert Pyramid (Archeology)
                structure("Desert Pyramid"),
                has_brush(),
            ),
        ),
    ),
                )

    advancement("Ice Bucket Challenge", any_of(
        reached(f"{ADVANCEMENT_PREFIX}Diamonds!"),  # Mine it with your diamonds
        reached(f"{ADVANCEMENT_PREFIX}Those Were the Days"),  # In Bastion Remnants
        reached(f"{ADVANCEMENT_PREFIX}A Terrible Fortress"),  # In Nether Fortress
        any_portal(True),  # In any ruined portal
        can_barter(),  # Piglin trade
        any_village(),  # In village chests
    ),
                )

    advancement("Cover Me with Diamonds", all_of(
        knowledge("Armor Handling"),  # Must always have that
        any_of(
            reached(f"{ADVANCEMENT_PREFIX}Diamonds!"),  # Craft it yourself
            can_trade(False, 4),  # Villager trade toolsmith
            reached(f"{ADVANCEMENT_PREFIX}Those Were the Days"),  # In Bastion Remnant
            reached(f"{ADVANCEMENT_PREFIX}The City at the End of the Game"),  # In End City
            structure("Mansion"),
            reached(f"{ADVANCEMENT_PREFIX}Under Lock and Key"),  # In Trial Chambers (Vault)
            reached(f"{ADVANCEMENT_PREFIX}Revaulting"),  # In Trial Chambers (Ominous Vault)
            structure("Ancient City"),
        ),
    ),
                )

    advancement("Enchanter", all_of(
        knowledge("Enchanting"),
        reached(f"{ADVANCEMENT_PREFIX}Ice Bucket Challenge"),
        reached(f"{ADVANCEMENT_PREFIX}Diamonds!"),  # In case we don't get obsidian by mining
        # Book: (Make assumption that we have a grindstone for enchanted book)
        any_of(
            entity("Cow"),
            can_trade(False),  # bookshelf from librarian
            any_mineshaft(),
            structure("Ancient City"),
            reached(f"{ADVANCEMENT_PREFIX}Those Were the Days"),  # In Bastion Remnant
            structure("Desert Pyramid"),
            structure("Jungle Pyramid"),
            structure("Pillager Outpost"),
            structure("Shipwreck"),
            structure("Dungeon"),
            reached(f"{ADVANCEMENT_PREFIX}Eye Spy"),  # In Stronghold
            structure("Ocean Ruin (Cold)"),
            structure("Ocean Ruin (Warm)"),
            structure("Mansion"),
            reached(f"{ADVANCEMENT_PREFIX}Under Lock and Key"),  # In Trial Chambers (Vault)
            reached(f"{ADVANCEMENT_PREFIX}Revaulting"),  # In Trial Chambers (Ominous Vault)
            can_barter(),
            knowledge("Fishing"),
            reached(f"{ADVANCEMENT_PREFIX}Hero of the Village"),
        ),
    ),
                )

    advancement("Zombie Doctor", all_of(
        entity("Zombie Villager"),
        # Golden Apple
        any_of(
            can_get_gold(),
            any_mineshaft(),
            reached(f"{ADVANCEMENT_PREFIX}Those Were the Days"),  # In Bastion Remnant
            structure("Desert Pyramid"),
            structure("Igloo"),
            any_portal(True),
            structure("Dungeon"),
            reached(f"{ADVANCEMENT_PREFIX}Eye Spy"),  # In Stronghold
            structure("Ocean Ruin (Cold)"),
            structure("Ocean Ruin (Warm)"),
            structure("Mansion"),
        ),
        # Weakness potion
        any_of(
            # Brewing
            all_of(
                reached(f"{ADVANCEMENT_PREFIX}Local Brewery"),
                has_any_entities("Cave Spider", "Spider"),
            ),
            # Finding
            all_of(knowledge("Brewing"), structure("Igloo")),
            reached(f"{ADVANCEMENT_PREFIX}Minecraft: Trial(s) Edition"),
            entity("Witch"),
        ),
    ),
                )

    advancement("Eye Spy", all_of(
        reached(f"{ADVANCEMENT_PREFIX}Into Fire"),
        entity("Enderman"),
        structure("Stronghold"),
    ),
                )

    # -----------------------------------------------------------------------
    # Nether
    # -----------------------------------------------------------------------

    advancement("We Need to Go Deeper", all_of())

    advancement("Return to Sender", entity("Ghast"))

    advancement("Those Were the Days", structure("Bastion Remnant"))

    advancement("Hidden in the Depths", all_of(
        material(ProgressiveMaterialTier.NETHERITE),  # Always needed
        any_of(
            knowledge("Pickaxe Handling"),  # Obtain it
            reached(f"{ADVANCEMENT_PREFIX}Those Were the Days"),
        ),
    ),
                )

    advancement("A Terrible Fortress", structure("Nether Fortress"))

    advancement("Oh Shiny", all_of(
        entity("Piglin"),
        material(ProgressiveMaterialTier.GOLD),
    ),
                )

    advancement("This Boat Has Legs", all_of(entity("Strider"), knowledge("Fishing")))

    advancement("Uneasy Alliance", entity("Ghast"))

    advancement("War Pigs", reached(f"{ADVANCEMENT_PREFIX}Those Were the Days"))

    advancement("Cover Me in Debris", all_of(
        reached(f"{ADVANCEMENT_PREFIX}Hidden in the Depths"),
        knowledge("Armor Handling"),
        reached(f"{ADVANCEMENT_PREFIX}Those Were the Days"),
    )
                )

    advancement("Spooky Scary Skeleton", all_of(
        entity("Wither Skeleton"),
        reached(f"{ADVANCEMENT_PREFIX}A Terrible Fortress"),
    ),
                )

    advancement("Into Fire", all_of(
        reached(f"{ADVANCEMENT_PREFIX}A Terrible Fortress"),
        entity("Blaze"),
    ),
                )

    advancement("Who is Cutting Onions?", any_of(
        can_barter(),
        reached(f"{ADVANCEMENT_PREFIX}Those Were the Days"),
    ),
                )

    advancement("Not Quite Nine Lives", reached(f"{ADVANCEMENT_PREFIX}Who is Cutting Onions?"))

    advancement("Feels Like Home", reached(f"{ADVANCEMENT_PREFIX}This Boat Has Legs"))

    advancement("Withering Heights", all_of(reached(f"{ADVANCEMENT_PREFIX}Spooky Scary Skeleton"), entity("Wither")))

    advancement("Local Brewery", all_of(reached(f"{ADVANCEMENT_PREFIX}Into Fire"), knowledge("Brewing")))

    advancement("Bring Home the Beacon", reached(f"{ADVANCEMENT_PREFIX}Withering Heights"))

    advancement("Beaconator", reached(f"{ADVANCEMENT_PREFIX}Bring Home the Beacon"))

    advancement("A Furious Cocktail", all_of(
        reached(f"{ADVANCEMENT_PREFIX}Local Brewery"),  # Brewing Stand + Blaze Rod

        # Infestation — Stone always accessible, no condition needed

        # Invisibility + Night Vision — Golden Carrot or Suspicious Stew
        any_of(
            material(ProgressiveMaterialTier.GOLD),  # craft Golden Carrot
            entity("Witch"),  # Witch drop
            can_barter(),  # Piglin barter
            any_village(),  # village chests
            reached(f"{ADVANCEMENT_PREFIX}Those Were the Days"),  # Bastion chests
            can_trade(False, 4),  # expert farmer villager trade
            structure("Shipwreck"),  # Suspicious Stew
            # Suspicious Stew with Poppy — always accessible
        ),

        # Jump Boost — Rabbit's Foot or Beacon
        any_of(
            entity("Rabbit"),  # Rabbit's Foot
            reached(f"{ADVANCEMENT_PREFIX}Bring Home the Beacon"),  # Beacon
        ),

        # Oozing — Slime Block, Panda sneeze or Ominous Bottle
        any_of(
            entity("Slime"),  # Slime Block
            entity("Panda"),  # Slimeball from sneeze
            all_of(reached(f"{ADVANCEMENT_PREFIX}Minecraft: Trial(s) Edition"), entity("Pillager")),  # Ominous Bottle
        ),

        # Poison — Spider Eye, Witch, Pufferfish, Bee, Poisonous Potato or Suspicious Stew
        any_of(
            entity("Spider"),  # Spider Eye
            entity("Cave Spider"),  # Spider Eye
            entity("Witch"),  # Witch drop
            entity("Pufferfish"),  # Pufferfish
            knowledge("Fishing"),  # Fish a Pufferfish
            entity("Bee"),  # Bee sting
            # Poisonous Potato — always accessible
            # Suspicious Stew with Lily of the Valley — always accessible
        ),

        # Regeneration — Ghast Tear, Axolotl, Beacon, Totem, Golden Apple or Enchanted Golden Apple
        any_of(
            entity("Ghast"),  # Ghast Tear
            reached(f"{ADVANCEMENT_PREFIX}The Healing Power of Friendship!"),  # Kill mob near Axolotl
            reached(f"{ADVANCEMENT_PREFIX}Bring Home the Beacon"),  # Beacon
            can_get_totem(),  # Totem of Undying
            material(ProgressiveMaterialTier.GOLD),  # Golden Apple craft
            any_mineshaft(),  # Golden/Enchanted Golden Apple
            structure("Ancient City"),  # Enchanted Golden Apple
            reached(f"{ADVANCEMENT_PREFIX}Those Were the Days"),  # Golden/Enchanted Golden Apple in Bastion
            structure("Desert Pyramid"),  # Golden/Enchanted Golden Apple
            any_portal(True),  # Golden/Enchanted Golden Apple
            structure("Dungeon"),  # Golden/Enchanted Golden Apple
            structure("Mansion"),  # Golden/Enchanted Golden Apple
            structure("Igloo"),  # Golden Apple
            reached(f"{ADVANCEMENT_PREFIX}Eye Spy"),  # Golden Apple in Stronghold
            structure("Ocean Ruin (Cold)"),  # Golden Apple in Underwater Ruin
            structure("Ocean Ruin (Warm)"),  # Golden Apple in Underwater Ruin
            reached(f"{ADVANCEMENT_PREFIX}Revaulting"),  # Golden Apple in Ominous Rare Vault
            reached(f"{ADVANCEMENT_PREFIX}Under Lock and Key"),  # Golden Apple in Unique Vault
        ),

        # Resistance — Turtle Shell, Beacon, Totem or Enchanted Golden Apple
        any_of(
            entity("Turtle"),  # Turtle Shell
            reached(f"{ADVANCEMENT_PREFIX}Bring Home the Beacon"),  # Beacon
            can_get_totem(),  # Totem of Undying
            any_mineshaft(),  # Enchanted Golden Apple
            structure("Ancient City"),  # Enchanted Golden Apple
            reached(f"{ADVANCEMENT_PREFIX}Those Were the Days"),  # Enchanted Golden Apple in Bastion
            structure("Desert Pyramid"),  # Enchanted Golden Apple
            any_portal(True),  # Enchanted Golden Apple
            structure("Dungeon"),  # Enchanted Golden Apple
            structure("Mansion"),  # Enchanted Golden Apple
            reached(f"{ADVANCEMENT_PREFIX}Revaulting"),  # Enchanted Golden Apple in Ominous Vault
        ),

        # Fire Resistance — Magma Cream, Witch, Totem, Enchanted Golden Apple or Barter
        any_of(
            entity("Magma Cube"),  # Magma Cream drop
            entity("Witch"),  # Witch drop
            can_get_totem(),  # Totem of Undying
            can_barter(),  # Piglin barter
            any_mineshaft(),  # Enchanted Golden Apple
            structure("Ancient City"),  # Enchanted Golden Apple
            reached(f"{ADVANCEMENT_PREFIX}Those Were the Days"),  # Enchanted Golden Apple in Bastion
            structure("Desert Pyramid"),  # Enchanted Golden Apple
            any_portal(True),  # Enchanted Golden Apple
            structure("Dungeon"),  # Enchanted Golden Apple
            structure("Mansion"),  # Enchanted Golden Apple
            reached(f"{ADVANCEMENT_PREFIX}Revaulting"),  # Enchanted Golden Apple in Ominous Vault
        ),

        # Slow Falling — Phantom Membrane or Cat morning gift
        any_of(
            entity("Phantom"),  # Phantom Membrane
            entity("Cat"),  # Cat morning gift
        ),

        # Slowness — Potion of Slowness, Turtle Master or Stray attack
        any_of(
            has_any_entities("Spider", "Cave Spider", "Witch"),  # Fermented Spider Eye → Potion of Slowness
            entity("Turtle"),  # Potion of Turtle Master
            entity("Stray"),  # Stray attack
        ),

        # Speed — Sugar cane always accessible, no condition needed

        # Strength — Blaze Powder always available via Local Brewery prerequisite

        # Water Breathing — Pufferfish, Turtle Shell or Witch drop
        any_of(
            entity("Pufferfish"),  # Pufferfish mob
            knowledge("Fishing"),  # Fish a Pufferfish
            entity("Turtle"),  # Turtle Shell
            entity("Witch"),  # Witch drop
        ),

        # Weakness — Fermented Spider Eye, Witch drop, Igloo or Suspicious Stew
        any_of(
            has_any_entities("Spider", "Cave Spider", "Witch"),  # Fermented Spider Eye or Witch drop
            structure("Igloo"),  # pre-brewed Weakness potion
            # Suspicious Stew with Brown Mushroom — always accessible
        ),

        # Weaving — Cobweb (Spider drop or Mineshaft) or Ominous Bottle
        any_of(
            has_any_entities("Spider", "Cave Spider"),  # String → Cobweb craft
            any_mineshaft(),  # Cobweb naturally in Mineshaft
            all_of(reached(f"{ADVANCEMENT_PREFIX}Minecraft: Trial(s) Edition"), entity("Pillager")),  # Ominous Bottle
        ),

        # Wind Charged — Breeze Rod or Ominous Bottle
        any_of(
            entity("Breeze"),  # Breeze Rod
            all_of(reached(f"{ADVANCEMENT_PREFIX}Minecraft: Trial(s) Edition"), entity("Pillager")),  # Ominous Bottle
        ),
    ),
                )

    advancement("How Did We Get Here?", all_of(
        reached(f"{ADVANCEMENT_PREFIX}A Furious Cocktail"),  # Covers: Fire Resistance, Infestation, Invisibility,
        # Jump Boost, Night Vision, Oozing, Poison, Regeneration,
        # Resistance, Slow Falling, Slowness, Speed, Strength,
        # Water Breathing, Weakness, Weaving, Wind Charged

        # Absorption — Golden Apple, Enchanted Golden Apple or Totem of Undying
        # Already specified by A Furious Cocktail indirectly

        # Bad Omen — Pillager Raid Captain
        entity("Pillager"),

        # Blindness — Suspicious Stew with Azure Bluet (always accessible)
        # Hunger — Pufferfish or Rotten Flesh (always accessible)
        # Nausea — Pufferfish (always accessible)

        # Breath of the Nautilus + Conduit Power — Nautilus Shell + Heart of the Sea
        all_of(
            entity("Drowned"),  # Nautilus Shell
            structure("Buried Treasure"),  # Heart of the Sea
        ),

        # Darkness — Warden proximity
        entity("Warden"),

        # Dolphin's Grace — swim near a Dolphin
        entity("Dolphin"),

        # Glowing — Spectral Arrow (Glowstone always accessible in Nether)
        all_of(
            knowledge("Sharpshooter"),  # need bow/crossbow to shoot
            any_of(
                # Glowstone + Arrow — always accessible in Nether
                reached(f"{ADVANCEMENT_PREFIX}Those Were the Days"),  # Bastion Remnant chests
                can_barter(),  # Piglin bartering
            ),
        ),

        # Haste — Beacon level 2
        reached(f"{ADVANCEMENT_PREFIX}Beaconator"),

        # Hero of the Village — complete a Raid
        reached(f"{ADVANCEMENT_PREFIX}Hero of the Village"),

        # Infested — Stone always accessible, no condition needed

        # Levitation — Shulker attack
        entity("Shulker"),

        # Mining Fatigue — Elder Guardian
        entity("Elder Guardian"),

        # Raid Omen — Ominous Bottle near a village
        any_village(),  # village needed for Raid Omen

        # Trial Omen — already covered by A Furious Cocktail (Trial Chambers + Pillager)

        # Wither — Wither Rose or Wither Skeleton arrow
        any_of(
            entity("Wither"),  # Wither Rose
            entity("Wither Skeleton"),  # Wither Skeleton arrow
        ),
    ),
                )

    advancement("Subspace Bubble", all_of())

    advancement("Hot Tourist Destinations", all_of())

    # -----------------------------------------------------------------------
    # The End
    # -----------------------------------------------------------------------

    advancement("The End?", all_of())
    advancement("Free the End", entity("Ender Dragon"))
    advancement("The Next Generation", reached(f"{ADVANCEMENT_PREFIX}Free the End"))
    advancement("Remote Getaway", reached(f"{ADVANCEMENT_PREFIX}Free the End"))
    advancement("The End... Again...", all_of(reached(f"{ADVANCEMENT_PREFIX}Free the End"), entity("Ghast")))
    advancement("You Need a Mint", entity("Ender Dragon"))
    advancement("The City at the End of the Game", all_of(reached(f"{ADVANCEMENT_PREFIX}Remote Getaway"), structure("End City")))
    advancement("Sky's the Limit", all_of(reached(f"{ADVANCEMENT_PREFIX}The City at the End of the Game"), knowledge("Flying")))
    advancement("Great View From Up Here", all_of(reached(f"{ADVANCEMENT_PREFIX}The City at the End of the Game"), entity("Shulker")))

    # -----------------------------------------------------------------------
    # Adventure
    # -----------------------------------------------------------------------

    advancement("Heart Transplanter", entity("Creaking"))
    advancement("Voluntary Exile", entity("Pillager"))
    advancement("Country Lode Take Me Home", all_of(
        # Lodestone — craft or found in structures
        any_of(
            reached(f"{ADVANCEMENT_PREFIX}Acquire Hardware"),  # craft Lodestone (Iron + Chiseled Stone Brick)
            reached(f"{ADVANCEMENT_PREFIX}Those Were the Days"),  # Bastion Remnant
            any_portal(True),  # Ruined Portal
        ),
        # Compass — craft or found in structures
        any_of(
            reached(f"{ADVANCEMENT_PREFIX}Acquire Hardware"),  # craft Compass (Iron + Redstone)
            structure("Ancient City"),  # found in chest
            structure("Shipwreck"),  # found in chest
            reached(f"{ADVANCEMENT_PREFIX}Eye Spy"),  # Stronghold
            reached(f"{ADVANCEMENT_PREFIX}Minecraft: Trial(s) Edition"),  # found in chest
            can_trade(False, 4),  # expert cartographer trade
        ),
    ),
                )

    advancement("Is It a Bird?", all_of(
        entity("Parrot"),
        can_get_spyglass()
    )
                )

    advancement("Monster Hunter", has_any_entities(*MOBS_HOSTILE.keys()))

    advancement("The Power of Books", any_of(
        all_of(
            material(ProgressiveMaterialTier.IRON),  # mine Redstone
            knowledge("Pickaxe Handling"),
            access_region("Nether"),  # Nether Quartz
        ),
        structure("Ancient City"),  # generate here
    )
                )

    advancement("What a Deal!", can_trade())

    advancement("Crafting a New Look", all_of(
        knowledge("Armor Handling"),
        material(ProgressiveMaterialTier.IRON),
        # Trim locations
        any_of(
            entity("Elder Guardian"),  # Tide — Kill on Elder Guardian
            reached(f"{ADVANCEMENT_PREFIX}Those Were the Days"),  # Snout/Netherite Upgrade — Bastion
            structure("Pillager Outpost"),  # Sentry
            structure("Mansion"),  # Vex
            structure("Jungle Pyramid"),  # Wild
            structure("Shipwreck"),  # Coast
            structure("Desert Pyramid"),  # Dune
            structure("Ancient City"),  # Ward + Silence
            reached(f"{ADVANCEMENT_PREFIX}A Terrible Fortress"),  # Rib — Nether Fortress
            reached(f"{ADVANCEMENT_PREFIX}Eye Spy"),  # Eye — Stronghold
            reached(f"{ADVANCEMENT_PREFIX}The City at the End of the Game"),  # Spire — End City
            all_of(has_brush(), structure("Trail Ruins"))  # Wayfinder/Raiser/Shaper/Host
        )
    )
                )

    advancement("Smithing with Style", all_of(
        knowledge("Armor Handling"),
        material(ProgressiveMaterialTier.IRON),
        reached(f"{ADVANCEMENT_PREFIX}The City at the End of the Game"),  # Spire — End City
        reached(f"{ADVANCEMENT_PREFIX}Those Were the Days"),  # Snout/Netherite Upgrade — Bastion
        reached(f"{ADVANCEMENT_PREFIX}A Terrible Fortress"),  # Rib — Nether Fortress
        structure("Ancient City"),  # Ward + Silence
        structure("Mansion"),  # Vex
        entity("Elder Guardian"),  # Tide — Kill on Elder Guardian
        all_of(has_brush(), structure("Trail Ruins")),  # Wayfinder
    )
                )

    advancement("Sticky Situation", any_of(
        entity("Bee"),
        reached(f"{ADVANCEMENT_PREFIX}Under Lock and Key")
    )
                )

    advancement("Ol' Betsy", all_of(
        knowledge("Sharpshooter"),
        # Crossbow
        any_of(
            all_of(reached(f"{ADVANCEMENT_PREFIX}Acquire Hardware"), can_get_string()),  # craft Crossbow (Iron + Tripwire + String)
            reached(f"{ADVANCEMENT_PREFIX}Those Were the Days"),  # Bastion Remnant chest
            structure("Pillager Outpost"),  # Pillager Outpost chest
            reached(f"{ADVANCEMENT_PREFIX}Under Lock and Key"),  # Trial Chambers Vault
            reached(f"{ADVANCEMENT_PREFIX}Revaulting"),  # Trial Chambers Ominous Vault
        ),
        # Arrow
        can_get_arrow(),
    )
                )

    advancement("Surge Protector", all_of(material(ProgressiveMaterialTier.COPPER), can_trade(False, 0)))

    advancement("Caves & Cliffs", any_of(
        can_craft_bucket(),
        can_get_totem(),
        all_of(
            reached(f"{ADVANCEMENT_PREFIX}Local Brewery"),
            entity("Phantom")
        )
    )
                )

    advancement("Respecting the Remnants", all_of(
        has_brush(),
        any_of(
            structure("Trail Ruins"),  # Burn, Danger, Friend, Heart, Heartbreak, Howl, Sheaf
            structure("Ocean Ruin (Warm)"),  # Angler, Shelter, Snort
            structure("Ocean Ruin (Cold)"),  # Blade, Explorer, Mourner, Plenty
            structure("Desert Pyramid"),  # Archer, Miner, Prize, Skull
            structure("Desert Well"),  # Arms Up, Brewer
        ),
    )
                )

    advancement("Sneak 100", all_of())

    advancement("Sweet Dreams", any_of(
        can_trade(False, 2),  # Shepherd
        can_get_string(),
        entity("Sheep"),
        structure("Igloo"),
        structure("Mansion")
    )
                )

    advancement("Hero of the Village", all_of(
        can_trade(False, 0),
        reached(f"{ADVANCEMENT_PREFIX}Voluntary Exile"),
    )
                )

    advancement("Is It a Balloon?", all_of(
        entity("Ghast"),
        can_get_spyglass()
    )
                )

    advancement("A Throwaway Joke", can_get_trident())

    advancement("It Spreads", has_any_entities(*MOBS_ALL.keys()))

    advancement("Take Aim", all_of(
        knowledge("Sharpshooter"),
        can_get_arrow()
    )
                )

    advancement("Monsters Hunted", all_of(
        has_all_entities(*[n for n in MOBS_HOSTILE.keys() if n != "Warden"]),
        entity("Ender Dragon"),
        entity("Wither"),
    ),
                )

    advancement("Postmortal", can_get_totem())

    advancement("Mob Kabob", all_of(
        knowledge("Spear Handling"),
        has_any_entities(*[name for name in MOBS_ALL.keys() if name not in MOBS_BOSS]),
    )
                )

    advancement("Hired Help", all_of(
        entity("Iron Golem"),
        knowledge("Shear Handling"),
        any_of(
            reached(f"{ADVANCEMENT_PREFIX}Acquire Hardware"),
            reached(f"{ADVANCEMENT_PREFIX}Revaulting"),
            reached(f"{ADVANCEMENT_PREFIX}Those Were the Days"),
            reached(f"{ADVANCEMENT_PREFIX}Minecraft: Trial(s) Edition")
        )
    )
                )

    advancement("Star Trader", can_trade())

    advancement("Two Birds One Arrow", all_of(
        reached(f"{ADVANCEMENT_PREFIX}Ol' Betsy"),
        knowledge("Enchanting"),
        entity("Phantom"),
    )
                )

    advancement("Who's the Pillager Now?", all_of(
        reached(f"{ADVANCEMENT_PREFIX}Ol' Betsy"),
        entity("Pillager")
    )
                )

    advancement("Arbalistic", all_of(
        reached(f"{ADVANCEMENT_PREFIX}Ol' Betsy"),
        knowledge("Enchanting"),
        has_all_entities(*[name for name in MOBS_ALL.keys() if name not in MOBS_BOSS]),
    ),
                )

    advancement("Careful Restoration", reached(f"{ADVANCEMENT_PREFIX}Respecting the Remnants"))

    advancement("Adventuring Time", all_of())

    advancement("Sound of Music", all_of(
        reached(f"{ADVANCEMENT_PREFIX}Diamonds!"),
        can_get_disc(),
    )
                )

    advancement("Light as a Rabbit", all_of(
        knowledge("Armor Handling"),
        any_of(
            # Leather sources → craft boots
            has_any_entities("Cow", "Donkey", "Horse", "Llama", "Mooshroom", "Mule", "Trader Llama", "Hoglin"),
            entity("Rabbit"),  # 4 Rabbit Hide → Leather
            knowledge("Fishing"),  # fishing junk
            can_barter(),  # Piglin bartering
            can_trade(False, 2),  # leatherworker trade
            reached(f"{ADVANCEMENT_PREFIX}Hero of the Village"),  # leatherworker gift
            structure("Ancient City"),  # Leather in chest
            reached(f"{ADVANCEMENT_PREFIX}Those Were the Days"),  # Leather in Bastion
            structure("Desert Pyramid"),  # Leather in chest
            structure("Jungle Pyramid"),  # Leather in chest
            structure("Dungeon"),  # Leather in chest
            reached(f"{ADVANCEMENT_PREFIX}Eye Spy"),  # Leather in Stronghold
            any_village(),  # Leather in tannery
            # Leather Boots directly
            structure("Shipwreck"),  # Leather Boots in supply chest
        )
    )
                )

    advancement("Is It a Plane?", all_of(
        can_get_spyglass(),
        entity("Ender Dragon"),
    )
                )

    advancement("Very Very Frightening", all_of(
        can_get_trident(),
        can_trade(False, 0),
        knowledge("Enchanting"),
    )
                )

    advancement("Sniper Duel", all_of(
        knowledge("Sharpshooter"),
        can_get_arrow(),
        entity("Skeleton"),
    )
                )

    advancement("Bullseye", all_of(
        can_get_redstone(),  # Target Block needs Redstone
        any_of(
            all_of(knowledge("Sharpshooter"), can_get_arrow()),  # bow/crossbow + arrow
            can_get_snowball(),
            can_get_egg(),
            entity("Breeze"),  # Wind Charge
        ),
    )
                )

    advancement("Isn't It Scute?", all_of(entity("Armadillo"), has_brush()))

    advancement("Minecraft: Trial(s) Edition", structure("Trial Chambers"))

    advancement("Crafters Crafting Crafters", all_of(
        reached(f"{ADVANCEMENT_PREFIX}Acquire Hardware"),
        can_get_redstone(),
    )
                )

    advancement("Lighten Up", all_of(
        knowledge("Axe Handling"),
        any_of(
            all_of(
                can_get_copper(),  # craft Copper Bulb
                can_get_redstone(),  # Redstone
                reached(f"{ADVANCEMENT_PREFIX}Into Fire"),  # Blaze Rod
            ),
            reached(f"{ADVANCEMENT_PREFIX}Minecraft: Trial(s) Edition"),  # found in Trial Chambers
        ),
    )
                )

    advancement("Who Needs Rockets?", any_of(
        entity("Breeze"),  # Breeze Rod → craft Wind Charge
        reached(f"{ADVANCEMENT_PREFIX}Under Lock and Key"),  # Wind Charge in Common Vault
        reached(f"{ADVANCEMENT_PREFIX}Revaulting"),  # Wind Charge in Ominous Common Vault
    )
                )

    advancement("Under Lock and Key", all_of(
        reached(f"{ADVANCEMENT_PREFIX}Minecraft: Trial(s) Edition"),
        has_any_entities(
            "Breeze",  # always present
            "Zombie", "Husk", "Slime", "Baby Zombie", "Silverfish",  # melee pool
            "Skeleton", "Stray", "Bogged",  # ranged pool
            "Spider", "Cave Spider",  # small melee pool
        ),
    )
                )

    advancement("Revaulting", all_of(
        reached(f"{ADVANCEMENT_PREFIX}Minecraft: Trial(s) Edition"),
        reached(f"{ADVANCEMENT_PREFIX}Voluntary Exile"),  # Ominous Bottle via Pillager Captain
        has_any_entities(
            "Breeze",  # always present
            "Zombie", "Husk", "Slime", "Baby Zombie", "Silverfish",  # melee pool
            "Skeleton", "Stray", "Bogged",  # ranged pool
            "Spider", "Cave Spider",  # small melee pool
        ),
    )
                )

    advancement("Blowback", entity("Breeze"))

    advancement("Over-Overkill", all_of(
        knowledge("Mace Handling"),
        knowledge("Enchanting"),
        entity("Breeze"),
        reached(f"{ADVANCEMENT_PREFIX}Revaulting"),
        has_any_entities(*[name for name in MOBS_ALL.keys()])
    )
                )

    # -----------------------------------------------------------------------
    # Husbandry
    # -----------------------------------------------------------------------

    advancement("Stay Hydrated!", any_of(
        can_barter(),
        entity("Ghast")
    )
                )

    advancement("Bee Our Guest", entity("Bee"))

    advancement("The Parrots and the Bats", any_of(
        entity("Trader Llama"),
        has_any_entities(*MOBS_BREEDABLE.keys()),
    ),
                )

    advancement("You've Got a Friend in Me", entity("Allay"))

    advancement("Whatever Floats Your Goat!", entity("Goat"))

    advancement("Glow and Behold!", entity("Glow Squid"))

    advancement("Best Friends Forever", has_any_entities(*MOBS_TAMEABLE.keys()))

    advancement("Fishy Business", knowledge("Fishing"))

    advancement("Total Beelocation", all_of(
        entity("Bee"),
        knowledge("Enchanting"),
        any_of(
            knowledge("Shovel Handling"),
            knowledge("Pickaxe Handling"),
            knowledge("Axe Handling"),
            knowledge("Hoe Handling"),
        )
    )
                )

    advancement("Bukkit Bukkit", all_of(
        entity("Tadpole"),
        entity("Frog"),
        can_craft_bucket(),
    ),
                )

    advancement("Smells Interesting", all_of(
        has_brush(),
        structure("Ocean Ruin (Warm)")
    )
                )

    advancement("A Seedy Place", any_of(
        knowledge("Hoe Handling"),
        any_village(),
    )
                )

    advancement("Wax On", all_of(
        knowledge("Shear Handling"),
        entity("Bee"),
        can_get_copper(),
    )
                )

    advancement("Two by Two", has_all_entities(*MOBS_BREEDABLE.keys()))

    advancement("Birthday Song", all_of(
        entity("Allay"),
        can_get_cake(),
    )
                )

    advancement("A Complete Catalogue", entity("Cat"))

    advancement("Tactical Fishing", all_of(
        can_craft_bucket(),
        has_any_entities("Cod", "Salmon", "Pufferfish", "Tropical Fish"),
    ),
                )

    advancement("When the Squad Hops into Town", entity("Frog"))

    advancement("Little Sniffs", reached(f"{ADVANCEMENT_PREFIX}Smells Interesting"))

    advancement("A Balanced Diet", all_of(
        # Always accessible foods (no condition needed):
        # Bread, Apple, Carrot, Potato, Baked Potato, Beetroot, Beetroot Soup,
        # Melon Slice, Pumpkin Pie, Cookie, Dried Kelp, Sweet Berries,
        # Glow Berries, Suspicious Stew, Poisonous Potato

        # Meats
        entity("Cow"),  # Beef
        entity("Chicken"),  # Chicken
        entity("Sheep"),  # Mutton
        entity("Pig"),  # Porkchop
        entity("Rabbit"),  # Rabbit

        # Fish
        any_of(
            knowledge("Fishing"),
            has_any_entities("Cod", "Salmon", "Pufferfish", "Tropical Fish"),
        ),

        # Mushroom Stew
        entity("Mooshroom"),

        # Honey Bottle
        entity("Bee"),

        # Golden Apple / Golden Carrot
        can_get_gold(),

        # Enchanted golden apple
        can_get_notch_apple(),

        # Spider Eye
        has_any_entities("Spider", "Cave Spider", "Witch"),

        # Rotten Flesh
        has_any_entities("Zombie", "Husk", "Drowned", "Zombie Villager"),

        # Chorus Fruit
        reached(f"{ADVANCEMENT_PREFIX}Free the End"),

        # Cake
        can_get_cake(),
    )
                )

    advancement("Serious Dedication", all_of(
        knowledge("Hoe Handling"),
        reached(f"{ADVANCEMENT_PREFIX}Hidden in the Depths"),
        reached(f"{ADVANCEMENT_PREFIX}Those Were the Days")
    )
                )

    advancement("Wax Off", all_of(
        reached(f"{ADVANCEMENT_PREFIX}Wax On"),
        knowledge("Axe Handling"),
    ),
                )

    advancement("The Cutest Predator", all_of(
        entity("Axolotl"),
        can_craft_bucket(),
    ),
                )

    advancement("With Our Powers Combined!", all_of(
        entity("Frog"),
        entity("Magma Cube"),
    ),
                )

    advancement("Planting the Past", reached(f"{ADVANCEMENT_PREFIX}Smells Interesting"))

    advancement("The Healing Power of Friendship!", all_of(
        entity("Axolotl"),
        has_any_entities("Drowned", "Guardian", "Elder Guardian"),
    ),
                )

    advancement("Good as New", all_of(
        entity("Wolf"),
        entity("Armadillo"),
        has_brush(),
    ),
                )

    advancement("The Whole Pack", entity("Wolf"))

    advancement("Shear Brilliance", all_of(entity("Wolf"), knowledge("Shear Handling")))
