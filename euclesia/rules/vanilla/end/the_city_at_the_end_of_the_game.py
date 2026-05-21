from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def the_city_at_the_end_of_the_game(helper: RuleHelper) -> dict:
    return {
        A_THE_CITY_AT_THE_END_OF_THE_GAME: helper.all_of(helper.reached(f"{ADVANCEMENT_PREFIX}Remote Getaway"), helper.structure("End City"))
    }
