from ...constants import *
from ...helpers import RuleHelper


def serious_dedication(helper: RuleHelper) -> dict:
    return {
        A_SERIOUS_DEDICATION: helper.all_of(
            helper.knowledge(K_HOE),
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_HIDDEN_IN_THE_DEPTHS}"),
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}")
        )
    }
