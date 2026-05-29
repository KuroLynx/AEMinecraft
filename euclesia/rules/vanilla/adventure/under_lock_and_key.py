from ...constants import *
from ...helpers import RuleHelper


def under_lock_and_key(helper: RuleHelper) -> dict:
    return {
        A_UNDER_LOCK_AND_KEY: helper.all_of(
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),
            helper.has_any_entities(
                E_BREEZE,  # always present
                E_ZOMBIE, E_HUSK, E_SLIME, E_BABY_ZOMBIE, E_SILVERFISH,  # melee pool
                E_SKELETON, E_STRAY, E_BOGGED,  # ranged pool
                E_SPIDER, E_CAVE_SPIDER,  # small melee pool
            ),
        )
    }
