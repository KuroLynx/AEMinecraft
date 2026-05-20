from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def uneasy_alliance(helper: RuleHelper) -> dict:
    return {
        A_UNEASY_ALLIANCE: helper.entity(E_GHAST)
    }
