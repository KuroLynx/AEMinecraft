from .. import *  # constants, RuleHelper, mob sets (re-export hub)

def whatever_floats_your_goat(helper: RuleHelper) -> dict:
    return {
        A_WHATEVER_FLOATS_YOUR_GOAT: helper.entity(E_GOAT)
    }
