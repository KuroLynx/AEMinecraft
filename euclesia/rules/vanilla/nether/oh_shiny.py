from ...constants import *
from ...helpers import RuleHelper

def oh_shiny(helper: RuleHelper) -> dict:
    return {
        A_OH_SHINY: helper.all_of(
            helper.entity(E_PIGLIN),
            helper.material(MAT_GOLD),
        ),
    }
