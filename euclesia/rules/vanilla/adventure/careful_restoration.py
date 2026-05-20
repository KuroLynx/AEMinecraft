from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def careful_restoration(helper: RuleHelper) -> dict:
    return {
        A_CAREFUL_RESTORATION: helper.reached(f"{ADVANCEMENT_PREFIX}{A_RESPECTING_THE_REMNANTS}")
    }
