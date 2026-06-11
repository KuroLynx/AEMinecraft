from .. import *  # constants, RuleHelper, mob sets (re-export hub)

def oh_shiny(helper: RuleHelper) -> dict:
    return {
        A_OH_SHINY: helper.all_of(
            helper.entity(E_PIGLIN),
            helper.material(MAT_GOLD),
        ),
    }
