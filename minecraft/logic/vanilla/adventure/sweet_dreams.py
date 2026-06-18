from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def sweet_dreams(helper: RuleHelper) -> dict:
    return {
        A_SWEET_DREAMS: helper.can_get_bed()
    }
