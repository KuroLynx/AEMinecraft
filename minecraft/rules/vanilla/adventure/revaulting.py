from ...constants import *
from ...helpers import RuleHelper


def revaulting(helper: RuleHelper) -> dict:
    return {
        A_REVAULTING: helper.all_of(
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_VOLUNTARY_EXILE}"),  # Ominous Bottle via Pillager Captain
            helper.has_any_entities(
                E_BREEZE,  # always present
                E_ZOMBIE, E_HUSK, E_SLIME, E_BABY_ZOMBIE, E_SILVERFISH,  # melee pool
                E_SKELETON, E_STRAY, E_BOGGED,  # ranged pool
                E_SPIDER, E_CAVE_SPIDER,  # small melee pool
            ),
        )
    }
