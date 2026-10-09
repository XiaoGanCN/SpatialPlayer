#!/usr/bin/env python3
"""How much of the screen actually changed between two screenshots.

Playback can be "playing" with the position advancing and still show nothing: the audio decoder
runs, the clock runs, and the video surface stays black. The only honest test of "is a picture on
screen" is to look at two frames and count the pixels that differ.

```
python3 tools/frame_motion.py before.png after.png
```
"""
import sys

from png_reader import read_png


def main():
    if len(sys.argv) < 3:
        print("usage: frame_motion.py <a.png> <b.png>", file=sys.stderr)
        return 2

    a = read_png(sys.argv[1])
    b = read_png(sys.argv[2])
    if (a[0], a[1]) != (b[0], b[1]):
        print(f"size mismatch: {a[0]}x{a[1]} vs {b[0]}x{b[1]}", file=sys.stderr)
        return 2

    width, height, channels, pa = a
    _, _, _, pb = b
    total = width * height
    changed = 0
    luma = 0
    for i in range(0, total * channels, channels):
        r, g, bl = pa[i], pa[i + 1], pa[i + 2]
        luma += (r * 299 + g * 587 + bl * 114) // 1000
        r2, g2, b2 = pb[i], pb[i + 1], pb[i + 2]
        if abs(r - r2) + abs(g - g2) + abs(bl - b2) > 24:
            changed += 1

    print(
        f"{sys.argv[1]} -> {sys.argv[2]}: size={width}x{height} "
        f"changed={changed}/{total} ({100.0 * changed / total:.2f}%) "
        f"mean_luma_a={luma / total:.1f}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
