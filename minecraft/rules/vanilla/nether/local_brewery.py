from ...constants import *
from ...helpers import RuleHelper


def local_brewery(helper: RuleHelper) -> dict:
    return {
        A_LOCAL_BREWERY: helper.all_of(
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_INTO_FIRE}"),  # blaze powder for the brewing stand
            helper.knowledge(K_BREWING),
            helper.access_region(REGION_OVERWORLD),  # brewing starts from a water bottle; no water in the Nether
        )
    }
