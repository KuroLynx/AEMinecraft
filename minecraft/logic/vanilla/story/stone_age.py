from .. import *  # constants, RuleHelper, mob sets (re-export hub)

def stone_age(helper: RuleHelper) -> dict:
    return {
        A_STONE_AGE: helper.all_of(helper.knowledge(K_PICKAXE), helper.material(MAT_STONE))
    }
