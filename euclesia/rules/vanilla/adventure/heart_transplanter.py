from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def heart_transplanter(helper: RuleHelper) -> dict:
    return {
        A_HEART_TRANSPLANTER: helper.entity("Creaking")
    }
