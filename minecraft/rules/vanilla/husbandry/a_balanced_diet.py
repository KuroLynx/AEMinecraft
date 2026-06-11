from .. import *  # constants, RuleHelper, mob sets (re-export hub)


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
            helper.entity(E_SHEEP),  # Mutton
            helper.entity(E_PIG),  # Porkchop
            helper.entity(E_RABBIT),  # Rabbit

            # Fish
            helper.any_of(
                helper.knowledge(K_FISHING),
                helper.has_any_entities(E_COD, E_SALMON, E_PUFFERFISH, E_TROPICAL_FISH),
            ),

            # Mushroom Stew
            helper.entity(E_MOOSHROOM),

            # Honey Bottle
            helper.entity(E_BEE),

            # Golden Apple
            helper.can_get_golden_apple(),

            # Enchanted golden apple
            helper.can_get_notch_apple(),

            # Spider Eye
            helper.has_any_entities(E_SPIDER, E_CAVE_SPIDER, E_WITCH),

            # Rotten Flesh
            helper.has_any_entities(E_ZOMBIE, E_HUSK, E_DROWNED, E_ZOMBIE_VILLAGER),

            # Chorus Fruit
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_FREE_THE_END}"),

            # Cake
            helper.can_get_cake(),
        )
    }
