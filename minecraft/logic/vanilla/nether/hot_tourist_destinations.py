from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def hot_tourist_destinations(helper: RuleHelper) -> dict:
    return {
        A_HOT_TOURIST_DESTINATIONS: helper.needs_biome_finder()  # visit every Nether biome
    }
