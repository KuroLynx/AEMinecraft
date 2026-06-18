from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def cover_me_in_debris(helper: RuleHelper) -> dict:
    return {
        A_COVER_ME_IN_DEBRIS: helper.all_of(
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_HIDDEN_IN_THE_DEPTHS}"),
            helper.knowledge(K_ARMOR),
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),
        )
    }
