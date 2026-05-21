from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper

def stay_hydrated(helper: RuleHelper) -> dict:
    return {
        A_STAY_HYDRATED: helper.any_of(
            helper.can_barter(),
            helper.entity(E_GHAST)
        )
    }
