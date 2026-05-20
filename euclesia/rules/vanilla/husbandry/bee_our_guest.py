from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def bee_our_guest(helper: RuleHelper) -> dict:
    return {
        A_BEE_OUR_GUEST: helper.entity("Bee")
    }
