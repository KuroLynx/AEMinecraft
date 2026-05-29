from ...constants import *
from ...helpers import RuleHelper

def tactical_fishing(helper: RuleHelper) -> dict:
    return {
        A_TACTICAL_FISHING: helper.all_of(
            helper.can_craft_bucket(),
            helper.has_any_entities(E_COD, E_SALMON, E_PUFFERFISH, E_TROPICAL_FISH),
        ),
    }
