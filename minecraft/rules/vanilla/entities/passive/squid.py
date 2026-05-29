from ....constants import *
from ....helpers import RuleHelper


def squid(helper: RuleHelper) -> dict:
    return {
        E_SQUID: helper.entity(E_SQUID)
    }
