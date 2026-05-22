from ...constants import *
from ...helpers import RuleHelper


def sky_is_the_limit(helper: RuleHelper) -> dict:
    return {
        A_SKY_IS_THE_LIMIT: helper.all_of(
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_THE_CITY_AT_THE_END_OF_THE_GAME}"),
            helper.knowledge("Flying")
        )
    }
