from ...constants import *
from ...helpers import RuleHelper


def sticky_situation(helper: RuleHelper) -> dict:
    return {
        A_STICKY_SITUATION: helper.any_of(
            helper.entity(E_BEE),
            helper.structure(S_TRIAL_CHAMBERS),
        )
    }
