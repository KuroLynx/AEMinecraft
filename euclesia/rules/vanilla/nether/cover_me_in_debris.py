from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def cover_me_in_debris(helper: RuleHelper) -> dict:
    return {
        A_COVER_ME_IN_DEBRIS: helper.all_of(
            helper.reached(f"{ADVANCEMENT_PREFIX}Hidden in the Depths"),
            helper.knowledge(K_ARMOR),
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),
        )
    }
