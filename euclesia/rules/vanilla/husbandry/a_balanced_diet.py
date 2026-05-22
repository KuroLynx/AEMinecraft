from ...constants import *
from ...helpers import RuleHelper


def a_balanced_diet(helper: RuleHelper) -> dict:
    return {
        A_A_BALANCED_DIET: helper.all_of(
            # Always accessible foods (no condition needed):
            # Bread, Apple, Carrot, Potato, Baked Potato, Beetroot, Beetroot Soup,
            # Melon Slice, Pumpkin Pie, Cookie, Dried Kelp, Sweet Berries,
            # Glow Berries, Suspicious Stew, Poisonous Potato

            # Meats
            helper.entity(E_COW),  # Beef
            helper.entity(E_CHICKEN),  # Chicken
            helper.entity("Sheep"),  # Mutton
            helper.entity("Pig"),  # Porkchop
            helper.entity("Rabbit"),  # Rabbit

            # Fish
            helper.any_of(
                helper.knowledge(K_FISHING),
                helper.has_any_entities("Cod", "Salmon", "Pufferfish", "Tropical Fish"),
            ),

            # Mushroom Stew
            helper.entity("Mooshroom"),

            # Honey Bottle
            helper.entity("Bee"),

            # Golden Apple / Golden Carrot
            helper.can_get_gold(),

            # Enchanted golden apple
            helper.can_get_notch_apple(),

            # Spider Eye
            helper.has_any_entities("Spider", "Cave Spider", E_WITCH),

            # Rotten Flesh
            helper.has_any_entities(E_ZOMBIE, E_HUSK, E_DROWNED, E_ZOMBIE_VILLAGER),

            # Chorus Fruit
            helper.reached(f"{ADVANCEMENT_PREFIX}Free the End"),

            # Cake
            helper.can_get_cake(),
        )
    }
