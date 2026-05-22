from ....constants import *
from ....helpers import RuleHelper


def sniffer(helper: RuleHelper) -> dict:
    return {
        E_SNIFFER: helper.entity(E_SNIFFER)
    }
