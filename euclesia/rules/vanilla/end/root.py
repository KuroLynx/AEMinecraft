from euclesia.rules.helpers import RuleHelper
from euclesia.rules.vanilla.end.free_the_end import free_the_end
from euclesia.rules.vanilla.end.great_view_from_up_here import great_view_from_up_here
from euclesia.rules.vanilla.end.remote_getaway import remote_getaway
from euclesia.rules.vanilla.end.sky_is_the_limit import sky_is_the_limit
from euclesia.rules.vanilla.end.the_city_at_the_end_of_the_game import the_city_at_the_end_of_the_game
from euclesia.rules.vanilla.end.the_end_again import the_end_again
from euclesia.rules.vanilla.end.the_next_generation import the_next_generation
from euclesia.rules.vanilla.end.you_need_a_mint import you_need_a_mint


def get_end_rules(helper: RuleHelper) -> dict:
    return (
            free_the_end(helper) |
            the_next_generation(helper) |
            remote_getaway(helper) |
            the_end_again(helper) |
            you_need_a_mint(helper) |
            the_city_at_the_end_of_the_game(helper) |
            sky_is_the_limit(helper) |
            great_view_from_up_here(helper)
    )
