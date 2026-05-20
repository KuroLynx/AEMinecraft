from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def good_as_new(helper: RuleHelper) -> dict:
    return {
        A_GOOD_AS_NEW: helper.all_of(
            helper.entity("Wolf"),
            helper.entity("Armadillo"),
            helper.has_brush(),
        ),
    }
