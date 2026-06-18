from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def trial_edition(helper: RuleHelper) -> dict:
    return {
        A_TRIAL_EDITION: helper.structure("Trial Chambers")
    }
