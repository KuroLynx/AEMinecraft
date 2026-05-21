from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def total_beelocation(helper: RuleHelper) -> dict:
    return {
        A_TOTAL_BEELOCATION: helper.all_of(
            helper.entity("Bee"),
            helper.knowledge(K_ENCHANT),
            helper.any_of(
                helper.knowledge(K_SHOVEL),
                helper.knowledge(K_PICKAXE),
                helper.knowledge("Axe Handling"),
                helper.knowledge("Hoe Handling"),
            )
        )
    }
