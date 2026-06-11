from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def return_to_sender(helper: RuleHelper) -> dict:
    return {
        A_RETURN_TO_SENDER: helper.entity(E_GHAST)
    }
