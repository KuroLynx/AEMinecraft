from ...constants import *
from ...helpers import RuleHelper
from .a_balanced_diet import a_balanced_diet
from .a_complete_catalogue import a_complete_catalogue
from .a_seedy_place import a_seedy_place
from .bee_our_guest import bee_our_guest
from .best_friends_forever import best_friends_forever
from .birthday_song import birthday_song
from .bukkit_bukkit import bukkit_bukkit
from .fishy_business import fishy_business
from .glow_and_behold import glow_and_behold
from .good_as_new import good_as_new
from .little_sniffs import little_sniffs
from .planting_the_past import planting_the_past
from .serious_dedication import serious_dedication
from .shear_brilliance import shear_brilliance
from .smells_interesting import smells_interesting
from .stay_hydrated import stay_hydrated
from .tactical_fishing import tactical_fishing
from .the_cutest_predator import the_cutest_predator
from .the_healing_power_of_friendship import the_healing_power_of_friendship
from .the_parrots_and_the_bats import the_parrots_and_the_bats
from .the_whole_pack import the_whole_pack
from .total_beelocation import total_beelocation
from .two_by_two import two_by_two
from .wax_off import wax_off
from .wax_on import wax_on
from .whatever_floats_your_goat import whatever_floats_your_goat
from .when_the_squad_hops_into_town import when_the_squad_hops_into_town
from .with_our_powers_combined import with_our_powers_combined
from .you_ve_got_a_friend_in_me import you_ve_got_a_friend_in_me


def get_husbandry_rules(helper: RuleHelper) -> dict:
    return (
            # The Husbandry root validates on eating any food. In the Overworld food is trivially
            # free; in the Nether you need a hunted or looted source — a hoglin (raw porkchop), a
            # zombified piglin (rotten flesh), or a Bastion chest (cooked porkchop / golden food).
            # can_get_food() enumerates every real source, each carrying its own region/lock gate.
            {A_HUSBANDRY: helper.can_get_food()} |
            a_balanced_diet(helper) |
            a_complete_catalogue(helper) |
            a_seedy_place(helper) |
            bee_our_guest(helper) |
            best_friends_forever(helper) |
            birthday_song(helper) |
            bukkit_bukkit(helper) |
            fishy_business(helper) |
            glow_and_behold(helper) |
            good_as_new(helper) |
            little_sniffs(helper) |
            planting_the_past(helper) |
            serious_dedication(helper) |
            shear_brilliance(helper) |
            smells_interesting(helper) |
            stay_hydrated(helper) |
            tactical_fishing(helper) |
            the_cutest_predator(helper) |
            the_healing_power_of_friendship(helper) |
            the_parrots_and_the_bats(helper) |
            the_whole_pack(helper) |
            total_beelocation(helper) |
            two_by_two(helper) |
            wax_off(helper) |
            wax_on(helper) |
            whatever_floats_your_goat(helper) |
            when_the_squad_hops_into_town(helper) |
            with_our_powers_combined(helper) |
            you_ve_got_a_friend_in_me(helper)
    )
