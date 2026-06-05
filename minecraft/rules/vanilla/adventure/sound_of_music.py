from ...constants import *
from ...helpers import RuleHelper


def sound_of_music(helper: RuleHelper) -> dict:
    return {
        A_SOUND_OF_MUSIC: helper.all_of(
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_DIAMONDS}"),
            helper.can_get_disc(),
            helper.needs_biome_finder(),  # must be done in a Meadow
        )
    }
