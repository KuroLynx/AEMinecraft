from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def elder_guardian(helper: RuleHelper) -> dict:
    return {
        E_ELDER_GUARDIAN: helper.all_of(
            helper.entity(E_ELDER_GUARDIAN),
            helper.can_kill(),
            helper.can_breath_underwater(),  # survive the fight underwater
        )
    }
