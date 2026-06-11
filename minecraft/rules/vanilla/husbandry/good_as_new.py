from .. import *  # constants, RuleHelper, mob sets (re-export hub)

def good_as_new(helper: RuleHelper) -> dict:
    return {
        A_GOOD_AS_NEW: helper.all_of(
            helper.entity(E_WOLF),
            helper.entity(E_ARMADILLO),
            helper.has_brush(),
        ),
    }
