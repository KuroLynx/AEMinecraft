from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def bring_home_the_beacon(helper: RuleHelper) -> dict:
    return {
        A_BRING_HOME_THE_BEACON: helper.reached(f"{ADVANCEMENT_PREFIX}Withering Heights")
    }
