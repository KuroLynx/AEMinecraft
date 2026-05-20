from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def hot_tourist_destinations(helper: RuleHelper) -> dict:
    return {
        A_HOT_TOURIST_DESTINATIONS: helper.all_of()
    }
