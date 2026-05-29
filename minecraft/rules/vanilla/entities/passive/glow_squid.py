from ....constants import *
from ....helpers import RuleHelper


def glow_squid(helper: RuleHelper) -> dict:
    return {
        E_GLOW_SQUID: helper.entity(E_GLOW_SQUID)
    }
