from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper

def smells_interesting(helper: RuleHelper) -> dict:
    return {
        A_SMELLS_INTERESTING: helper.all_of(
            helper.has_brush(),
            helper.structure(S_OCEAN_RUIN_WARM)
        )
    }
