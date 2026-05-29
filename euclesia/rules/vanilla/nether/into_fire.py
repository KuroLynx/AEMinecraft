from ...constants import *
from ...helpers import RuleHelper

def into_fire(helper: RuleHelper) -> dict:
    return {
        A_INTO_FIRE: helper.entity(E_BLAZE),
    }
