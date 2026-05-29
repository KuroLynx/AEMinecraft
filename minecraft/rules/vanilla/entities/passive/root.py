from ....helpers import RuleHelper
from .allay import allay
from .armadillo import armadillo
from .axolotl import axolotl
from .bat import bat
from .camel import camel
from .camel_husk import camel_husk
from .cat import cat
from .chicken import chicken
from .cod import cod
from .copper_golem import copper_golem
from .cow import cow
from .donkey import donkey
from .frog import frog
from .glow_squid import glow_squid
from .happy_ghast import happy_ghast
from .horse import horse
from .mooshroom import mooshroom
from .mule import mule
from .ocelot import ocelot
from .parrot import parrot
from .pig import pig
from .rabbit import rabbit
from .salmon import salmon
from .sheep import sheep
from .skeleton_horse import skeleton_horse
from .sniffer import sniffer
from .snow_golem import snow_golem
from .squid import squid
from .strider import strider
from .tadpole import tadpole
from .tropical_fish import tropical_fish
from .turtle import turtle
from .villager import villager
from .wandering_trader import wandering_trader
from .zombie_horse import zombie_horse


def get_passive_rules(helper: RuleHelper) -> dict:
    return (
        allay(helper) |
        armadillo(helper) |
        axolotl(helper) |
        bat(helper) |
        camel(helper) |
        camel_husk(helper) |
        cat(helper) |
        chicken(helper) |
        cod(helper) |
        copper_golem(helper) |
        cow(helper) |
        donkey(helper) |
        frog(helper) |
        glow_squid(helper) |
        happy_ghast(helper) |
        horse(helper) |
        mooshroom(helper) |
        mule(helper) |
        ocelot(helper) |
        parrot(helper) |
        pig(helper) |
        rabbit(helper) |
        salmon(helper) |
        sheep(helper) |
        skeleton_horse(helper) |
        sniffer(helper) |
        snow_golem(helper) |
        squid(helper) |
        strider(helper) |
        tadpole(helper) |
        tropical_fish(helper) |
        turtle(helper) |
        villager(helper) |
        wandering_trader(helper) |
        zombie_horse(helper)
    )
