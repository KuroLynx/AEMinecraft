from ...constants import *
from ...helpers import RuleHelper


def heart_transplanter(helper: RuleHelper) -> dict:
    return {
        A_HEART_TRANSPLANTER: helper.entity(E_CREAKING)
    }
