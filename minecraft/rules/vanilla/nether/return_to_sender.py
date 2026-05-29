from ...constants import *
from ...helpers import RuleHelper


def return_to_sender(helper: RuleHelper) -> dict:
    return {
        A_RETURN_TO_SENDER: helper.entity(E_GHAST)
    }
