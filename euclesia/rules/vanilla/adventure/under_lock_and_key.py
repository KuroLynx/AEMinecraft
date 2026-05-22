from ...constants import *
from ...helpers import RuleHelper


def under_lock_and_key(helper: RuleHelper) -> dict:
    return {
        A_UNDER_LOCK_AND_KEY: helper.all_of(
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),
            helper.has_any_entities(
                "Breeze",  # always present
                E_ZOMBIE, E_HUSK, "Slime", "Baby Zombie", "Silverfish",  # melee pool
                E_SKELETON, E_STRAY, E_BOGGED,  # ranged pool
                "Spider", "Cave Spider",  # small melee pool
            ),
        )
    }
