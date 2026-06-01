from ...constants import *
from ...helpers import RuleHelper

def uneasy_alliance(helper: RuleHelper) -> dict:
    return {
        A_UNEASY_ALLIANCE: helper.all_of(
            helper.entity(E_GHAST),
            helper.access_region(REGION_OVERWORLD),  # the rescued ghast must be killed in the Overworld
        )
    }
