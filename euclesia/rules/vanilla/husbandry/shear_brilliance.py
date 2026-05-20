from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def shear_brilliance(helper: RuleHelper) -> dict:
    return {
        A_SHEAR_BRILLIANCE: helper.all_of(helper.entity("Wolf"), helper.knowledge("Shear Handling"))
    }
