from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def postmortal(helper: RuleHelper) -> dict:
    return {
        A_POSTMORTAL: helper.can_get_totem()
    }
