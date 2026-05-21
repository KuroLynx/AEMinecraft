from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper

def uneasy_alliance(helper: RuleHelper) -> dict:
    return {
        A_UNEASY_ALLIANCE: helper.entity(E_GHAST)
    }
