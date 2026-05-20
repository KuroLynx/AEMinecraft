from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def great_view_from_up_here(helper: RuleHelper) -> dict:
    return {
        A_GREAT_VIEW_FROM_UP_HERE: helper.all_of(
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_THE_CITY_AT_THE_END_OF_THE_GAME}"),
            helper.entity(E_SHULKER)
        )
    }
