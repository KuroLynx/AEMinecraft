from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def eye_spy(helper: RuleHelper) -> dict:
    return {
        A_EYE_SPY: helper.all_of(
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_INTO_FIRE}"),
            helper.entity(E_ENDERMAN),
            helper.structure("Stronghold"),
        ),
    }
