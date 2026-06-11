"""Chains the rule re-export hub one directory down so entity rule files (which sit one
level deeper than the other rule files) can use the same ``from .. import *``.
See ``rules/vanilla/__init__.py``.
"""
from .. import *  # noqa: F401,F403  (re-export hub)
