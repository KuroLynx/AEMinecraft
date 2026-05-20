from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def tactical_fishing(helper: RuleHelper) -> dict:
    return {
        A_TACTICAL_FISHING: helper.all_of(
            helper.can_craft_bucket(),
            helper.has_any_entities("Cod", "Salmon", "Pufferfish", "Tropical Fish"),
        ),
    }
