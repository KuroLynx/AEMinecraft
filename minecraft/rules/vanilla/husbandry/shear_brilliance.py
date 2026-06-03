from ...constants import *
from ...helpers import RuleHelper

def shear_brilliance(helper: RuleHelper) -> dict:
    return {
        A_SHEAR_BRILLIANCE: helper.all_of(helper.entity(E_WOLF), helper.can_get_shear())
    }
