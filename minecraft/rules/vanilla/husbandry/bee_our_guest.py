from ...constants import *
from ...helpers import RuleHelper


def bee_our_guest(helper: RuleHelper) -> dict:
    return {
        A_BEE_OUR_GUEST: helper.entity(E_BEE)
    }
