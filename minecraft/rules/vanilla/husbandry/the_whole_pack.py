from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def the_whole_pack(helper: RuleHelper) -> dict:
    return {
        A_THE_WHOLE_PACK: helper.can_tame(E_WOLF)
    }
