from ....helpers import RuleHelper
from .blaze import blaze
from .bogged import bogged
from .breeze import breeze
from .creaking import creaking
from .creeper import creeper
from .endermite import endermite
from .evoker import evoker
from .ghast import ghast
from .guardian import guardian
from .hoglin import hoglin
from .husk import husk
from .magma_cube import magma_cube
from .parched import parched
from .phantom import phantom
from .piglin_brute import piglin_brute
from .pillager import pillager
from .ravager import ravager
from .shulker import shulker
from .silverfish import silverfish
from .skeleton import skeleton
from .slime import slime
from .stray import stray
from .vex import vex
from .vindicator import vindicator
from .witch import witch
from .wither_skeleton import wither_skeleton
from .zoglin import zoglin
from .zombie import zombie
from .zombie_villager import zombie_villager


def get_hostile_rules(helper: RuleHelper) -> dict:
    return (
        blaze(helper) |
        bogged(helper) |
        breeze(helper) |
        creaking(helper) |
        creeper(helper) |
        endermite(helper) |
        evoker(helper) |
        ghast(helper) |
        guardian(helper) |
        hoglin(helper) |
        husk(helper) |
        magma_cube(helper) |
        parched(helper) |
        phantom(helper) |
        piglin_brute(helper) |
        pillager(helper) |
        ravager(helper) |
        shulker(helper) |
        silverfish(helper) |
        skeleton(helper) |
        slime(helper) |
        stray(helper) |
        vex(helper) |
        vindicator(helper) |
        witch(helper) |
        wither_skeleton(helper) |
        zoglin(helper) |
        zombie(helper) |
        zombie_villager(helper)
    )
