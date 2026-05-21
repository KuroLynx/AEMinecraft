from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def revaulting(helper: RuleHelper) -> dict:
    return {
        A_REVAULTING: helper.all_of(
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),
            helper.reached(f"{ADVANCEMENT_PREFIX}Voluntary Exile"),  # Ominous Bottle via Pillager Captain
            helper.has_any_entities(
                "Breeze",  # always present
                E_ZOMBIE, E_HUSK, "Slime", "Baby Zombie", "Silverfish",  # melee pool
                E_SKELETON, E_STRAY, E_BOGGED,  # ranged pool
                "Spider", "Cave Spider",  # small melee pool
            ),
        )
    }
