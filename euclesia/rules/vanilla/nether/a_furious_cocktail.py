from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def a_furious_cocktail(helper: RuleHelper) -> dict:
    return {
        A_A_FURIOUS_COCKTAIL: helper.all_of(
            helper.reached(f"{ADVANCEMENT_PREFIX}Local Brewery"),  # Brewing Stand + Blaze Rod

            # Infestation — Stone always accessible, no condition needed

            # Invisibility + Night Vision — Golden Carrot or Suspicious Stew
            helper.any_of(
                helper.material(MAT_GOLD),  # craft Golden Carrot
                helper.entity(E_WITCH),  # Witch drop
                helper.can_barter(),  # Piglin barter
                helper.any_village(),  # village chests
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # Bastion chests
                helper.can_trade(False, 4),  # expert farmer villager trade
                helper.structure(S_SHIPWRECK),  # Suspicious Stew
                # Suspicious Stew with Poppy — always accessible
            ),

            # Jump Boost — Rabbit's Foot or Beacon
            helper.any_of(
                helper.entity("Rabbit"),  # Rabbit's Foot
                helper.reached(f"{ADVANCEMENT_PREFIX}Bring Home the Beacon"),  # Beacon
            ),

            # Oozing — Slime Block, Panda sneeze or Ominous Bottle
            helper.any_of(
                helper.entity("Slime"),  # Slime Block
                helper.entity("Panda"),  # Slimeball from sneeze
                helper.all_of(helper.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"), helper.entity("Pillager")),  # Ominous Bottle
            ),

            # Poison — Spider Eye, Witch, Pufferfish, Bee, Poisonous Potato or Suspicious Stew
            helper.any_of(
                helper.entity("Spider"),  # Spider Eye
                helper.entity("Cave Spider"),  # Spider Eye
                helper.entity(E_WITCH),  # Witch drop
                helper.entity("Pufferfish"),  # Pufferfish
                helper.knowledge(K_FISHING),  # Fish a Pufferfish
                helper.entity("Bee"),  # Bee sting
                # Poisonous Potato — always accessible
                # Suspicious Stew with Lily of the Valley — always accessible
            ),

            # Regeneration — Ghast Tear, Axolotl, Beacon, Totem, Golden Apple or Enchanted Golden Apple
            helper.any_of(
                helper.entity(E_GHAST),  # Ghast Tear
                helper.reached(f"{ADVANCEMENT_PREFIX}The Healing Power of Friendship!"),  # Kill mob near Axolotl
                helper.reached(f"{ADVANCEMENT_PREFIX}Bring Home the Beacon"),  # Beacon
                helper.can_get_totem(),  # Totem of Undying
                helper.material(MAT_GOLD),  # Golden Apple craft
                helper.any_mineshaft(),  # Golden/Enchanted Golden Apple
                helper.structure(S_ANCIENT_CITY),  # Enchanted Golden Apple
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # Golden/Enchanted Golden Apple in Bastion
                helper.structure(S_DESERT_PYRAMID),  # Golden/Enchanted Golden Apple
                helper.any_portal(True),  # Golden/Enchanted Golden Apple
                helper.structure(S_DUNGEON),  # Golden/Enchanted Golden Apple
                helper.structure(S_MANSION),  # Golden/Enchanted Golden Apple
                helper.structure(S_IGLOO),  # Golden Apple
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_EYE_SPY}"),  # Golden Apple in Stronghold
                helper.structure(S_OCEAN_RUIN_COLD),  # Golden Apple in Underwater Ruin
                helper.structure(S_OCEAN_RUIN_WARM),  # Golden Apple in Underwater Ruin
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_REVAULTING}"),  # Golden Apple in Ominous Rare Vault
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_UNDER_LOCK_AND_KEY}"),  # Golden Apple in Unique Vault
            ),

            # Resistance — Turtle Shell, Beacon, Totem or Enchanted Golden Apple
            helper.any_of(
                helper.entity("Turtle"),  # Turtle Shell
                helper.reached(f"{ADVANCEMENT_PREFIX}Bring Home the Beacon"),  # Beacon
                helper.can_get_totem(),  # Totem of Undying
                helper.any_mineshaft(),  # Enchanted Golden Apple
                helper.structure(S_ANCIENT_CITY),  # Enchanted Golden Apple
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # Enchanted Golden Apple in Bastion
                helper.structure(S_DESERT_PYRAMID),  # Enchanted Golden Apple
                helper.any_portal(True),  # Enchanted Golden Apple
                helper.structure(S_DUNGEON),  # Enchanted Golden Apple
                helper.structure(S_MANSION),  # Enchanted Golden Apple
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_REVAULTING}"),  # Enchanted Golden Apple in Ominous Vault
            ),

            # Fire Resistance — Magma Cream, Witch, Totem, Enchanted Golden Apple or Barter
            helper.any_of(
                helper.entity("Magma Cube"),  # Magma Cream drop
                helper.entity(E_WITCH),  # Witch drop
                helper.can_get_totem(),  # Totem of Undying
                helper.can_barter(),  # Piglin barter
                helper.any_mineshaft(),  # Enchanted Golden Apple
                helper.structure(S_ANCIENT_CITY),  # Enchanted Golden Apple
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # Enchanted Golden Apple in Bastion
                helper.structure(S_DESERT_PYRAMID),  # Enchanted Golden Apple
                helper.any_portal(True),  # Enchanted Golden Apple
                helper.structure(S_DUNGEON),  # Enchanted Golden Apple
                helper.structure(S_MANSION),  # Enchanted Golden Apple
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_REVAULTING}"),  # Enchanted Golden Apple in Ominous Vault
            ),

            # Slow Falling — Phantom Membrane or Cat morning gift
            helper.any_of(
                helper.entity("Phantom"),  # Phantom Membrane
                helper.entity(E_CAT),  # Cat morning gift
            ),

            # Slowness — Potion of Slowness, Turtle Master or Stray attack
            helper.any_of(
                helper.has_any_entities("Spider", "Cave Spider", E_WITCH),  # Fermented Spider Eye → Potion of Slowness
                helper.entity("Turtle"),  # Potion of Turtle Master
                helper.entity(E_STRAY),  # Stray attack
            ),

            # Speed — Sugar cane always accessible, no condition needed

            # Strength — Blaze Powder always available via Local Brewery prerequisite

            # Water Breathing — Pufferfish, Turtle Shell or Witch drop
            helper.any_of(
                helper.entity("Pufferfish"),  # Pufferfish mob
                helper.knowledge(K_FISHING),  # Fish a Pufferfish
                helper.entity("Turtle"),  # Turtle Shell
                helper.entity(E_WITCH),  # Witch drop
            ),

            # Weakness — Fermented Spider Eye, Witch drop, Igloo or Suspicious Stew
            helper.any_of(
                helper.has_any_entities("Spider", "Cave Spider", E_WITCH),  # Fermented Spider Eye or Witch drop
                helper.structure(S_IGLOO),  # pre-brewed Weakness potion
                # Suspicious Stew with Brown Mushroom — always accessible
            ),

            # Weaving — Cobweb (Spider drop or Mineshaft) or Ominous Bottle
            helper.any_of(
                helper.has_any_entities("Spider", "Cave Spider"),  # String → Cobweb craft
                helper.any_mineshaft(),  # Cobweb naturally in Mineshaft
                helper.all_of(helper.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"), helper.entity("Pillager")),  # Ominous Bottle
            ),

            # Wind Charged — Breeze Rod or Ominous Bottle
            helper.any_of(
                helper.entity("Breeze"),  # Breeze Rod
                helper.all_of(helper.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"), helper.entity("Pillager")),  # Ominous Bottle
            ),
        ),
    }
