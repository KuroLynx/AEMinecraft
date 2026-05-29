from ...constants import *
from ...helpers import RuleHelper


def eye_spy(helper: RuleHelper) -> dict:
    return {
        A_EYE_SPY: helper.all_of(
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_INTO_FIRE}"),
            helper.entity(E_ENDERMAN),
            helper.structure("Stronghold"),
        ),
    }
