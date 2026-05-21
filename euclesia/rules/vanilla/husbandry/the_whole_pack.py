from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def the_whole_pack(helper: RuleHelper) -> dict:
    return {
        A_THE_WHOLE_PACK: helper.entity("Wolf")
    }
