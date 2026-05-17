from enum import IntEnum

from worlds.generic.Rules import set_rule

from . import MOBS_BREEDABLE, MOBS_TAMEABLE
from .data import MOBS_ALL, MOBS_HOSTILE


class MaterialTier(IntEnum):
    WOOD = 0
    STONE = 1
    COPPER = 2
    IRON = 3
    GOLD = 4
    DIAMOND = 5
    NETHERITE = 6


def set_rules(world) -> None:
    player = world.player

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

    def entity(entity_name: str):
        if entity_name not in MOBS_ALL:
            print(f"Warning: {entity_name} not found !")
        return lambda state: (
            not world.options.mob_spawn_lock_category.value or
            state.has(f"Entity Unlock: {entity_name}", player)
        )

    def reached(location: str):
        return lambda state: state.can_reach(location, "Location", player)

    def material(tier: int):
        """Progressive Material Handling tier shorthand.
        1=Stone, 2=Copper, 3=Iron, 4=Gold, 5=Diamond, 6=Netherite
        """
        return lambda state: state.has("Progressive Material Handling", player, tier)

    def knowledge(item: str):
        return lambda state: state.has(f"Knowledge: {item}", player)

    def advancement(name: str, condition):
        rule(f"Advancement: {name}", condition)

    def can_trade():
        base = any_of(
            entity("Villager"),
            entity("Wandering Trader"),
        )
        if world.options.villager_trust:
            return all_of(base, has("Progressive Villager Trust"))
        return base

    # -----------------------------------------------------------------------
    # Story
    # -----------------------------------------------------------------------

    advancement("Stone Age", all_of(
        knowledge("Pickaxe Handling"),
        material(MaterialTier.STONE),
    ))

    advancement("Getting an Upgrade", reached("Advancement: Stone Age"))

    advancement("Acquire Hardware", all_of(
        knowledge("Pickaxe Handling"),
        material(MaterialTier.IRON),
    ))

    advancement("Suit Up", all_of(
        knowledge("Armor Handling"),
        material(MaterialTier.IRON),
    ))

    advancement("Hot Stuff", material(MaterialTier.IRON))

    advancement("Isn't It Iron Pick", all_of(
        knowledge("Pickaxe Handling"),
        material(MaterialTier.IRON),
    ))

    advancement("Not Today Thank You", all_of(
        knowledge("Shield Handling"),
        material(MaterialTier.IRON),
    ))

    advancement("Diamonds!", all_of(
        knowledge("Pickaxe Handling"),
        material(MaterialTier.DIAMOND),
    ))

    advancement("Ice Bucket Challenge", any_of(
        material(MaterialTier.DIAMOND),  # obsidienne trouvée naturellement (coffres, ruines portail)
    ))

    advancement("We Need to Go Deeper", all_of(
        reached("Advancement: Ice Bucket Challenge"),
        has("Dimension Unlock: Nether"),
    ))

    advancement("Cover Me with Diamonds", all_of(
        knowledge("Armor Handling"),
        material(MaterialTier.DIAMOND),
    ))

    advancement("Enchanter", all_of(
        knowledge("Enchanting"),
        reached("Advancement: Ice Bucket Challenge"),  # obsidienne nécessaire pour l'enchanting table
    ))

    advancement("Zombie Doctor", all_of(
        reached("Advancement: Local Brewery"),
        entity("Zombie Villager"),
    ))

    advancement("Eye Spy", all_of(
        reached("Advancement: Into Fire"),
        entity("Enderman"),  # ender pearl
    ))

    advancement("The End?", all_of(
        reached("Advancement: Eye Spy"),
        has("Dimension Unlock: The End"),
    ))

    # -----------------------------------------------------------------------
    # Nether
    # -----------------------------------------------------------------------

    advancement("Return to Sender", entity("Ghast"))

    advancement("Those Were the Days", all_of())

    advancement("Hidden in the Depths", all_of(
        knowledge("Pickaxe Handling"),
        material(MaterialTier.NETHERITE),
    ))

    advancement("A Terrible Fortress", all_of())

    advancement("Oh Shiny", entity("Piglin"))

    advancement("This Boat Has Legs", all_of(
        entity("Strider"),
        knowledge("Fishing"),  # pour crafter la Warped Fungus on a Stick
    ))

    advancement("Uneasy Alliance", all_of(
        entity("Ghast"),
        reached("Advancement: Return to Sender"),
    ))

    advancement("War Pigs", reached("Advancement: Those Were the Days"))

    advancement("Cover Me in Debris", all_of(
        reached("Advancement: Hidden in the Depths"),
        knowledge("Armor Handling"),
    ))

    advancement("Spooky Scary Skeleton", all_of(
        entity("Wither Skeleton"),
    ))

    advancement("Into Fire", entity("Blaze"))

    advancement("Not Quite Nine Lives", reached("Advancement: Those Were the Days"))

    advancement("Feels Like Home", reached("Advancement: This Boat Has Legs"))

    advancement("Hot Tourist Destinations", all_of())

    advancement("Withering Heights", all_of(
        reached("Advancement: Spooky Scary Skeleton"),
        entity("Wither"),
    ))

    advancement("Local Brewery", all_of(
        reached("Advancement: Into Fire"),
        knowledge("Brewing"),
    ))

    advancement("Bring Home the Beacon", reached("Advancement: Withering Heights"))

    advancement("A Furious Cocktail", all_of(
        knowledge("Brewing"),
        reached("Advancement: Local Brewery"),

        # Fire Resistance — Magma Cream ou Witch (drop direct)
        any_of(
            entity("Magma Cube"),
            entity("Witch"),
        ),

        # Regeneration — Ghast Tear ou Beacon
        any_of(
            entity("Ghast"),
            reached("Advancement: Bring Home the Beacon"),
        ),

        # Jump Boost — Rabbit's Foot ou Beacon
        any_of(
            entity("Rabbit"),
            reached("Advancement: Bring Home the Beacon"),
        ),

        # Resistance — Turtle Master ou Beacon
        any_of(
            entity("Turtle"),
            reached("Advancement: Bring Home the Beacon"),
        ),

        # Slow Falling — Phantom Membrane (seule source)
        entity("Phantom"),

        # Speed — Sugar (Witch drop) ou Beacon
        any_of(
            entity("Witch"),
            reached("Advancement: Bring Home the Beacon"),
        ),

        # Water Breathing — Pufferfish ou Turtle Shell ou Witch
        any_of(
            knowledge("Fishing"),
            entity("Turtle"),
            entity("Witch"),
        ),

        # Poison — Spider Eye (craft ou Witch drop)
        any_of(
            entity("Spider"),
            entity("Cave Spider"),
            entity("Witch"),
        ),

        # Oozing — Slime Block
        entity("Slime"),

        # Weaving — Cobweb (Spider drop)
        any_of(
            entity("Spider"),
            entity("Cave Spider"),
        ),

        # Wind Charged — Breeze Rod
        entity("Breeze"),
    ))

    advancement("Beaconator", reached("Advancement: Bring Home the Beacon"))

    advancement("How Did We Get Here?", all_of(
        reached("Advancement: A Furious Cocktail"),
        reached("Advancement: Beaconator"),  # Haste via Beacon niveau 2
        entity("Drowned"),        # Conduit Power — Nautilus Shell
        entity("Guardian"),       # Conduit Power — Heart of the Sea via monument
        entity("Warden"),         # Darkness
        entity("Shulker"),        # Levitation
        entity("Elder Guardian"), # Mining Fatigue
        entity("Dolphin"),        # Dolphin's Grace
        any_of(
            knowledge("Fishing"),
            entity("Pufferfish"),
        ),                        # Hunger/Nausea via Pufferfish
        reached("Advancement: Hero of the Village"),  # Hero of the Village effect
        entity("Pillager"),       # Bad Omen via Raid Captain
        entity("Wither"),         # Wither effect via Wither Rose
        entity("Evoker"),         # Absorption via Totem of Undying
    ))

    advancement("Subspace Bubble", all_of())

    # -----------------------------------------------------------------------
    # The End
    # -----------------------------------------------------------------------

    advancement("Free the End", entity("Ender Dragon"))

    advancement("The Next Generation", reached("Advancement: Free the End"))

    advancement("Remote Getaway", reached("Advancement: Free the End"))

    advancement("The End... Again...", all_of(
        reached("Advancement: Free the End"),
        entity("Ghast"),  # Ghast Tear pour crafter les End Crystals
    ))

    advancement("You Need a Mint", reached("Advancement: Free the End"))

    advancement("The City at the End of the Game", reached("Advancement: Remote Getaway"))

    advancement("Sky's the Limit", all_of(
        reached("Advancement: The City at the End of the Game"),
        knowledge("Flying"),  # pour équiper et utiliser les Elytra
    ))

    advancement("Great View From Up Here", all_of(
        reached("Advancement: The City at the End of the Game"),
        entity("Shulker"),
    ))

    # -----------------------------------------------------------------------
    # Adventure
    # -----------------------------------------------------------------------

    advancement("Heart Transplanter", entity("Creaking"))

    advancement("Voluntary Exile", entity("Pillager"))

    advancement("Country Lode Take Me Home", material(MaterialTier.IRON))

    advancement("Is It a Bird?", entity("Parrot"))

    advancement("Monster Hunter", any_of(
        *[entity(name) for name in MOBS_HOSTILE.keys()],
    ))

    advancement("The Power of Books", material(MaterialTier.IRON))

    advancement("What a Deal!", can_trade())

    advancement("Crafting a New Look", all_of(
        knowledge("Armor Handling"),
        material(MaterialTier.IRON),
    ))

    advancement("Sticky Situation", entity("Bee"))

    advancement("Ol' Betsy", knowledge("Sharpshooter"))

    advancement("Surge Protector", all_of(
        material(MaterialTier.COPPER),
        entity("Villager"),  # villageois non enflammé requis
    ))

    advancement("Caves & Cliffs", material(MaterialTier.IRON))

    advancement("Respecting the Remnants", knowledge("Brush Handling"))

    advancement("Sneak 100", any_of(
        entity("Warden"),
        # Sculk Sensor suffit — pas besoin du Warden
    ))

    advancement("Sweet Dreams", all_of())

    advancement("Hero of the Village", all_of(
        reached("Advancement: Voluntary Exile"),
        entity("Villager"),
        any_of(
            entity("Pillager"),
            entity("Vindicator"),
            entity("Evoker"),
            entity("Ravager"),
            entity("Witch"),
        ),
    ))

    advancement("Is It a Balloon?", entity("Ghast"))

    advancement("A Throwaway Joke", knowledge("Trident Handling"))

    advancement("It Spreads", any_of(
        *[entity(name) for name in MOBS_ALL.keys()],
    ))

    advancement("Take Aim", any_of(
        knowledge("Sharpshooter"),
        knowledge("Trident Handling"),
    ))

    advancement("Monsters Hunted", all_of(
        *[entity(name) for name in MOBS_HOSTILE.keys() if name != "Warden"],
        entity("Ender Dragon"),
        entity("Wither"),
    ))

    advancement("Postmortal", entity("Evoker"))

    advancement("Mob Kabob", knowledge("Spear Handling"))

    advancement("Hired Help", all_of(
        material(MaterialTier.IRON),
        entity("Iron Golem"),
    ))

    advancement("Star Trader", can_trade())

    advancement("Smithing with Style", all_of(
        knowledge("Armor Handling"),
        reached("Advancement: The City at the End of the Game"),  # Spire
        reached("Advancement: Those Were the Days"),               # Snout
        reached("Advancement: A Terrible Fortress"),               # Rib
        entity("Evoker"),                                          # Vex template
        knowledge("Brush Handling"),                               # Wayfinder (Trail Ruins)
        entity("Elder Guardian"),                                  # Tide (Ocean Monument)
        # Ward + Silence → Ancient City (structure pure, pas de mob)
    ))

    advancement("Two Birds One Arrow", all_of(
        knowledge("Sharpshooter"),
        entity("Phantom"),
    ))

    advancement("Who's the Pillager Now?", all_of(
        knowledge("Sharpshooter"),
        entity("Pillager"),
    ))

    advancement("Arbalistic", all_of(
        knowledge("Enchanting"),
        reached("Advancement: Ol' Betsy"),
        any_of(
            *[entity(name) for name in MOBS_ALL.keys()],
        ),
    ))

    advancement("Careful Restoration", knowledge("Brush Handling"))

    advancement("Adventuring Time", all_of())

    advancement("Sound of Music", all_of(
        material(MaterialTier.DIAMOND),
        any_of(
            knowledge("Brush Handling"),  # disque via Trail Ruins
            all_of(
                entity("Skeleton"),
                entity("Creeper"),  # Skeleton tue Creeper → Music Disc
            ),
        ),
    ))

    advancement("Light as a Rabbit", all_of())

    advancement("Is It a Plane?", entity("Ender Dragon"))

    advancement("Very Very Frightening", all_of(
        knowledge("Trident Handling"),
        knowledge("Enchanting"),  # enchantement Channeling requis
        entity("Villager"),
    ))

    advancement("Sniper Duel", all_of(
        entity("Skeleton"),
        knowledge("Sharpshooter"),
    ))

    advancement("Bullseye", knowledge("Sharpshooter"))

    advancement("Isn't It Scute?", all_of(
        entity("Armadillo"),
        knowledge("Brush Handling"),
    ))

    advancement("Minecraft: Trial(s) Edition", all_of())

    advancement("Crafters Crafting Crafters", all_of())

    advancement("Lighten Up", all_of(
        knowledge("Axe Handling"),
        material(MaterialTier.COPPER),
    ))

    advancement("Who Needs Rockets?", entity("Breeze"))

    advancement("Under Lock and Key", entity("Breeze"))

    advancement("Revaulting", all_of(
        entity("Breeze"),
        entity("Pillager"),
    ))

    advancement("Blowback", all_of(
        knowledge("Shield Handling"),
        entity("Breeze"),
    ))

    advancement("Over-Overkill", knowledge("Mace Handling"))

    # -----------------------------------------------------------------------
    # Husbandry
    # -----------------------------------------------------------------------

    advancement("Stay Hydrated!", any_of(
        reached("Advancement: We Need to Go Deeper"),
        entity("Piglin"),  # possible source de Dried Ghast via trade
    ))

    advancement("Bee Our Guest", entity("Bee"))

    advancement("The Parrots and the Bats", any_of(
        entity("Trader Llama"),
        *[entity(name) for name in MOBS_BREEDABLE.keys()],
    ))

    advancement("You've Got a Friend in Me", entity("Allay"))

    advancement("Whatever Floats Your Goat!", entity("Goat"))

    advancement("Best Friends Forever", any_of(
        *[entity(name) for name in MOBS_TAMEABLE.keys()],
    ))

    advancement("Glow and Behold!", entity("Glow Squid"))

    advancement("Fishy Business", knowledge("Fishing"))

    advancement("Total Beelocation", all_of(
        entity("Bee"),
        knowledge("Enchanting"),
    ))

    advancement("Bukkit Bukkit", all_of(
        entity("Tadpole"),
        entity("Frog"),  # les Tadpoles viennent des Frogs
        material(MaterialTier.IRON),  # water bucket = iron bucket
    ))

    advancement("Uh Oh", all_of())

    advancement("Smells Interesting", knowledge("Brush Handling"))

    advancement("A Seedy Place", knowledge("Hoe Handling"))

    advancement("Wax On", entity("Bee"))

    advancement("Two by Two", all_of(
        *[entity(name) for name in MOBS_BREEDABLE.keys()],
    ))

    advancement("Birthday Song", entity("Allay"))

    advancement("A Complete Catalogue", all_of(
        entity("Cat"),
    ))

    advancement("Tactical Fishing", all_of(
        material(MaterialTier.IRON),
        any_of(
            entity("Cod"),
            entity("Salmon"),
            entity("Pufferfish"),
            entity("Tropical Fish"),
        ),
    ))

    advancement("When the Squad Hops into Town", entity("Frog"))

    advancement("Little Sniffs", entity("Sniffer"))

    advancement("A Balanced Diet", all_of(
        # Viandes — mobs à tuer
        entity("Cow"),
        entity("Chicken"),
        entity("Sheep"),
        entity("Pig"),
        entity("Rabbit"),
        # Poissons — pêche ou mobs
        entity("Salmon"),
        entity("Cod"),
        entity("Pufferfish"),
        knowledge("Fishing"),
        # Soup / Beverages
        entity("Mooshroom"),  # Mushroom Stew
        entity("Bee"),        # Honey Bottle
        # Golden Apple/Carrot — nécessite de l'or
        material(MaterialTier.GOLD),
        # Chorus Fruit — uniquement dans le End
        reached("Advancement: The End?"),
    ))

    advancement("Serious Dedication", all_of(
        knowledge("Hoe Handling"),
        material(MaterialTier.NETHERITE),
    ))

    advancement("Wax Off", all_of(
        reached("Advancement: Wax On"),
        knowledge("Axe Handling"),
    ))

    advancement("The Cutest Predator", all_of(
        entity("Axolotl"),
        material(MaterialTier.IRON),
    ))

    advancement("With Our Powers Combined!", all_of(
        entity("Frog"),
        entity("Magma Cube"),
    ))

    advancement("Planting the Past", entity("Sniffer"))

    advancement("The Healing Power of Friendship!", all_of(
        entity("Axolotl"),
        any_of(
            entity("Drowned"),
            entity("Guardian"),
            entity("Elder Guardian"),
        ),
    ))

    advancement("Good as New", all_of(
        entity("Wolf"),
        entity("Armadillo"),
        knowledge("Brush Handling"),  # pour obtenir les Scutes de l'Armadillo
    ))

    advancement("The Whole Pack", entity("Wolf"))

    advancement("Shear Brilliance", all_of(
        entity("Wolf"),
        knowledge("Shear Handling"),
    ))
