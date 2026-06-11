from .. import *  # constants, RuleHelper, mob sets (re-export hub)

def shear_brilliance(helper: RuleHelper) -> dict:
    return {
        A_SHEAR_BRILLIANCE: helper.all_of(helper.entity(E_WOLF), helper.can_get_shear())
    }
