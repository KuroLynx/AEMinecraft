from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def serious_dedication(helper: RuleHelper) -> dict:
    return {
        A_SERIOUS_DEDICATION: helper.all_of(
            helper.knowledge("Hoe Handling"),
            helper.reached(f"{ADVANCEMENT_PREFIX}Hidden in the Depths"),
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}")
        )
    }
