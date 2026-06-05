from ...constants import *
from ...helpers import RuleHelper


def adventuring_time(helper: RuleHelper) -> dict:
    return {
        A_ADVENTURING_TIME: helper.all_of(
            helper.knowledge(K_FLYING),
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_THE_CITY_AT_THE_END_OF_THE_GAME}"),
            helper.needs_biome_finder(),  # visit every biome
        )
    }
