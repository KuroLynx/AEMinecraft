from ...helpers import RuleHelper
from .a_furious_cocktail import a_furious_cocktail
from .a_terrible_fortress import a_terrible_fortress
from .beaconator import beaconator
from .bring_home_the_beacon import bring_home_the_beacon
from .cover_me_in_debris import cover_me_in_debris
from .feels_like_home import feels_like_home
from .hidden_in_the_depths import hidden_in_the_depths
from .hot_tourist_destinations import hot_tourist_destinations
from .how_did_we_get_here import how_did_we_get_here
from .into_fire import into_fire
from .local_brewery import local_brewery
from .not_quite_nine_lives import not_quite_nine_lives
from .oh_shiny import oh_shiny
from .return_to_sender import return_to_sender
from .spooky_scary_skeleton import spooky_scary_skeleton
from .subspace_bubble import subspace_bubble
from .this_boat_has_legs import this_boat_has_legs
from .those_were_the_days import those_were_the_days
from .uneasy_alliance import uneasy_alliance
from .war_pigs import war_pigs
from .we_need_to_go_deeper import we_need_to_go_deeper
from .who_is_cutting_onions import who_is_cutting_onions
from .withering_heights import withering_heights


def get_nether_rules(helper: RuleHelper) -> dict:
    return (
            we_need_to_go_deeper(helper) |
            return_to_sender(helper) |
            those_were_the_days(helper) |
            hidden_in_the_depths(helper) |
            a_terrible_fortress(helper) |
            oh_shiny(helper) |
            this_boat_has_legs(helper) |
            uneasy_alliance(helper) |
            war_pigs(helper) |
            cover_me_in_debris(helper) |
            spooky_scary_skeleton(helper) |
            into_fire(helper) |
            who_is_cutting_onions(helper) |
            not_quite_nine_lives(helper) |
            feels_like_home(helper) |
            withering_heights(helper) |
            local_brewery(helper) |
            bring_home_the_beacon(helper) |
            beaconator(helper) |
            a_furious_cocktail(helper) |
            how_did_we_get_here(helper) |
            subspace_bubble(helper) |
            hot_tourist_destinations(helper)
    )
