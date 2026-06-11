from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def ravager(helper: RuleHelper) -> dict:
    return {
        E_RAVAGER: helper.entity(E_RAVAGER)
    }
