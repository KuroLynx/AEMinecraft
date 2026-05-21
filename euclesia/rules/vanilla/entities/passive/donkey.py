from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def donkey(helper: RuleHelper) -> dict:
    return {
        E_DONKEY: helper.entity(E_DONKEY)
    }
