from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def heart_transplanter(helper: RuleHelper) -> dict:
    return {
        A_HEART_TRANSPLANTER: helper.entity("Creaking")
    }
