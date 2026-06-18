from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def warden(helper: RuleHelper) -> dict:
    return {
        E_WARDEN: helper.all_of(
            helper.entity(E_WARDEN),
            helper.can_kill(),
        )
    }
