from ...constants import *
from ...helpers import RuleHelper

# Is into end region
def enter_end_portal(helper: RuleHelper) -> dict:
    return {
        A_ENTER_END_PORTAL: helper.all_of()
    }
