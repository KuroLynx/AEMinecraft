from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def sticky_situation(helper: RuleHelper) -> dict:
    return {
        A_STICKY_SITUATION: helper.any_of(
            helper.entity(E_BEE),
            helper.structure(S_TRIAL_CHAMBERS),
        )
    }
