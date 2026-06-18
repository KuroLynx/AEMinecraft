from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def bring_home_the_beacon(helper: RuleHelper) -> dict:
    return {
        A_BRING_HOME_THE_BEACON: helper.all_of(
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_WITHERING_HEIGHTS}"),  # Nether Star (Wither kill)
            helper.access_region(REGION_OVERWORLD),  # a beacon needs glass (sand → Overworld only)
        )
    }
