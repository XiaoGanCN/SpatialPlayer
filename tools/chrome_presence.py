#!/usr/bin/env python3
"""How much structure is in the player's top bar - a proxy for "is the chrome visible".

Extracted into its own file so the shell harness never has to embed Python: the nested quoting
needed for that is exactly the kind of thing that breaks silently.

## Why the top bar, and why structure rather than brightness

This used to average the bottom eighth of the screen, which was where the control capsule lived. It
no longer lives there: the capsule floats over the picture, just inside its bottom edge, so its
position moves with the aspect ratio of whatever is playing (for a 16:9 clip on this device it sits
around 54-60% of the height). Any fixed probe is therefore aimed at the wrong place.

The *top* bar is anchored to the window and always contains the title, the status pills and the two
round buttons when the chrome is up. Brightness is a poor discriminator there - measured on device,
9.1 with the chrome up against 1.9 without, which is far too close - but the spread is not: the
standard deviation was 28.5 with the chrome up against 2.7 without, because text and pills are hard
edges on a flat letterbox. That is what this returns.
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from png_reader import read_png  # noqa: E402


def main():
    if len(sys.argv) < 2:
        print("0")
        return
    try:
        width, height, channels, px = read_png(sys.argv[1])
    except Exception:
        print("0")
        return

    # The title and status pills. Left of centre only, so the round buttons on the right (which are
    # always drawn when the chrome is up, whatever the title says) cannot carry the measurement on
    # their own.
    y0, y1 = int(height * 0.03), int(height * 0.11)
    x0, x1 = int(width * 0.18), int(width * 0.62)

    samples = []
    for y in range(y0, y1, 3):
        for x in range(x0, x1, 3):
            i = (y * width + x) * channels
            samples.append((px[i] + px[i + 1] + px[i + 2]) / 3 if channels >= 3 else px[i])

    if len(samples) < 2:
        print("0")
        return
    mean = sum(samples) / len(samples)
    variance = sum((value - mean) ** 2 for value in samples) / len(samples)
    print(f"{math.sqrt(variance):.1f}")


if __name__ == "__main__":
    main()
