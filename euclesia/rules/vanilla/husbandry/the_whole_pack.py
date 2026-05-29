from ...constants import *
from ...helpers import RuleHelper


def the_whole_pack(helper: RuleHelper) -> dict:
    return {
        A_THE_WHOLE_PACK: helper.entity(E_WOLF)
    }
