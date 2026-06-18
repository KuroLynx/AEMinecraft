from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def monster_hunter(helper: RuleHelper) -> dict:
    return {
        A_MONSTER_HUNTER: helper.has_any_entities(*MOBS_HOSTILE.keys())
    }
