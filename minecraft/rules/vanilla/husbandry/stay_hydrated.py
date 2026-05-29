from ...constants import *
from ...helpers import RuleHelper


def stay_hydrated(helper: RuleHelper) -> dict:
    return {
        A_STAY_HYDRATED: helper.all_of(
            helper.any_of(
                helper.can_barter(),
                helper.entity(E_GHAST)
            ),
            helper.entity(E_HAPPY_GHAST)
        )
    }
