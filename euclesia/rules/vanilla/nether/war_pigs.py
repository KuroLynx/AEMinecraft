from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def war_pigs(helper: RuleHelper) -> dict:
    return {
        A_WAR_PIGS: helper.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}")
    }
