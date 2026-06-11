from .. import *  # constants, RuleHelper, mob sets (re-export hub)

def feels_like_home(helper: RuleHelper) -> dict:
    return {
        A_FEELS_LIKE_HOME: helper.reached(f"{ADVANCEMENT_PREFIX}{A_THIS_BOAT_HAS_LEGS}")
    }
