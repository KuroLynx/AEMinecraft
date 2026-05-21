from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper

def beaconator(helper: RuleHelper) -> dict:
    return {
        A_BEACONATOR: helper.reached(f"{ADVANCEMENT_PREFIX}{A_BRING_HOME_THE_BEACON}")
    }
