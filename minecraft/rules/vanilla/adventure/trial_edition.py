from ...constants import *
from ...helpers import RuleHelper


def trial_edition(helper: RuleHelper) -> dict:
    return {
        A_TRIAL_EDITION: helper.structure("Trial Chambers")
    }
