from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def stone_age(helper: RuleHelper) -> dict:
    return {
        A_STONE_AGE: helper.all_of(helper.knowledge(K_PICKAXE), helper.material(MAT_STONE))
    }
