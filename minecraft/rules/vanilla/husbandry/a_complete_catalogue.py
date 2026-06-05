from ...constants import *
from ...helpers import RuleHelper


def a_complete_catalogue(helper: RuleHelper) -> dict:
    return {
        A_A_COMPLETE_CATALOGUE: helper.all_of(
            helper.can_tame(E_CAT),
            helper.needs_biome_finder(),  # all cat variants span multiple village biomes + swamp hut
        )
    }
