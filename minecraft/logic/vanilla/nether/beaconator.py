from .. import *  # constants, RuleHelper, mob sets (re-export hub)

def beaconator(helper: RuleHelper) -> dict:
    return {
        A_BEACONATOR: helper.reached(f"{ADVANCEMENT_PREFIX}{A_BRING_HOME_THE_BEACON}")
    }
