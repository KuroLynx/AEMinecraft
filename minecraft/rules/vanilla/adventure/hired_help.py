from ...constants import *
from ...helpers import RuleHelper


def hired_help(helper: RuleHelper) -> dict:
    return {
        A_HIRED_HELP: helper.all_of(
            helper.entity(E_IRON_GOLEM),
            helper.any_of(
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_ACQUIRE_HARDWARE}"),
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_REVAULTING}"),
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}")
            )
        )
    }
