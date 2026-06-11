"""Re-export hub for the rule modules.

Every rule file needs the same small API: the ``constants`` names, ``RuleHelper``, and a
few mob sets from ``data``. Importing them once here lets each rule file say a single
``from .. import *`` instead of repeating deep ``from ...constants`` / ``from ....data``
chains.

This lives in ``rules/vanilla`` rather than ``rules/__init__`` on purpose: ``data.py``
imports ``rules.constants`` during package initialisation, so ``rules/__init__`` must stay
side-effect-free to avoid an import cycle. ``rules.vanilla`` is only imported later, via
``rules.root``, by which point ``data`` is fully loaded.
"""
from ..constants import *
from ..helpers import RuleHelper
from ...data import (
    MCEntityCategory,
    MOBS_ALL,
    MOBS_BOSS,
    MOBS_BREEDABLE,
    MOBS_HOSTILE,
    MOBS_TAMEABLE,
)
