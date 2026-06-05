from ...constants import *
from ...helpers import RuleHelper


def it_spreads(helper: RuleHelper) -> dict:
    # Kill a mob next to a Sculk Catalyst, which generates only in the Deep Dark biome. Any non-boss
    # mob (one is enough) can be killed bare-handed; the Deep Dark is a specific biome to find.
    return {
        A_IT_SPREADS: helper.all_of(
            helper.can_kill_any_mob(),
            helper.needs_biome_finder(),
        )
    }
