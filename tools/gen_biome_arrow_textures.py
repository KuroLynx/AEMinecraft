"""Generates the Biome Finder guide arrow's 32 direction frames.

Like the vanilla compass (``compass_00`` … ``compass_31``), the arrow is not rotated at runtime: the mod
picks the frame for the target's angle relative to where the player looks, and draws it on a quad that
always faces the player's view. Frame ``k`` points ``k * 11.25`` degrees clockwise from straight ahead
(seen from above), so ``arrow_00`` points away from the player and ``arrow_16`` back at them.

Writes ``archipelago-euclesia-minecraft/src/main/resources/assets/aem/textures/misc/biome_finder_arrow/
arrow_NN.png``. Resource packs can replace any frame at the same path. Needs Pillow.
"""
from __future__ import annotations

import os

from PIL import Image, ImageDraw, ImageFilter

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(REPO_ROOT, "archipelago-euclesia-minecraft", "src", "main", "resources",
                       "assets", "aem", "textures", "misc", "biome_finder_arrow")
FRAMES = 32
SIZE = 64
SUPERSAMPLE = 4
# Chevron pointing up (frame 0), in 64px coordinates; centred so rotation keeps it inside the square.
CHEVRON = [(32, 10), (57, 35), (47, 45), (32, 30), (17, 45), (7, 35)]


def base_frame() -> Image.Image:
    """The up-pointing arrow at SUPERSAMPLE scale: a white chevron with a soft dark rim."""
    n = SIZE * SUPERSAMPLE
    points = [(x * SUPERSAMPLE, y * SUPERSAMPLE) for x, y in CHEVRON]
    face = Image.new("L", (n, n), 0)
    ImageDraw.Draw(face).polygon(points, fill=255)
    rim = face.filter(ImageFilter.MaxFilter(2 * SUPERSAMPLE + 1))
    image = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    image.paste((20, 20, 20, 150), mask=rim)
    image.paste((255, 255, 255, 255), mask=face)
    return image


def main() -> None:
    os.makedirs(OUT_DIR, exist_ok=True)
    base = base_frame()
    for frame in range(FRAMES):
        # PIL rotates counter-clockwise; frames go clockwise.
        rotated = base.rotate(-frame * 360.0 / FRAMES, resample=Image.BICUBIC)
        rotated.resize((SIZE, SIZE), Image.LANCZOS).save(os.path.join(OUT_DIR, f"arrow_{frame:02d}.png"))
    print(f"wrote {FRAMES} frames to {OUT_DIR}")


if __name__ == "__main__":
    main()
