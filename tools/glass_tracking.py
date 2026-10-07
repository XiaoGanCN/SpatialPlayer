#!/usr/bin/env python3
"""Does the glass sample the part of the picture that is genuinely behind it?

Takes three screenshots of the *same frozen frame*:

  raw.png    chrome hidden - the picture, unoccluded
  raw2.png   chrome hidden, one second later - proves the frame really is frozen
  glass.png  chrome shown - the glass over the same pixels

and reports the correlation between the two at identical screen coordinates. A glass that samples
its own backdrop scores ~0.95 with the peak at a zero-pixel offset; one that samples the wrong part
of the surface (or falls back to the gradient approximation) scores near zero and shows no peak.

Usage: glass_tracking.py RAW RAW2 GLASS
Exit status is the number of failed checks, so the shell harness can just run it.
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from png_reader import read_png  # noqa: E402

MIN_CORRELATION = 0.85
MAX_BOX_CORRELATION = 0.50  # over the pillarbox there is no picture to track
MAX_FROZEN_DIFF = 1.0  # mean |A-B|, out of 255, below which two frames are the same still


class Shot:
    def __init__(self, path):
        self.w, self.h, self.ch, self.px = read_png(path)

    def luma(self, x, y):
        i = (y * self.w + x) * self.ch
        if self.ch >= 3:
            return 0.2126 * self.px[i] + 0.7152 * self.px[i + 1] + 0.0722 * self.px[i + 2]
        return float(self.px[i])


def correlation(a, b, box, offset=(0, 0), step=4):
    """Pearson correlation of b against a, sampling a displaced by `offset`."""
    dx, dy = offset
    x0, y0, x1, y1 = box
    pa, pb = [], []
    for y in range(y0, y1, step):
        for x in range(x0, x1, step):
            pa.append(a.luma(x + dx, y + dy))
            pb.append(b.luma(x, y))
    if len(pa) < 16:
        return float("nan")
    ma = sum(pa) / len(pa)
    mb = sum(pb) / len(pb)
    cov = sum((u - ma) * (v - mb) for u, v in zip(pa, pb))
    va = math.sqrt(sum((u - ma) ** 2 for u in pa))
    vb = math.sqrt(sum((v - mb) ** 2 for v in pb))
    if va == 0 or vb == 0:
        return float("nan")
    return cov / (va * vb)


def mean_abs_difference(a, b, step=5):
    total = 0.0
    count = 0
    for y in range(0, a.h, step):
        for x in range(0, a.w, step):
            total += abs(a.luma(x, y) - b.luma(x, y))
            count += 1
    return total / max(count, 1)


def freeze_check(path_a, path_b):
    """`freeze A B` - only asks whether the two screenshots are the same still frame.

    Correlation is the wrong measure here: a near-black frame that differs by a hair still
    correlates at 0.999, and a genuinely frozen frame with one animated element (the seek bar) drops
    below it. Mean absolute difference says what it means.
    """
    a, b = Shot(path_a), Shot(path_b)
    diff = mean_abs_difference(a, b)
    print("frame frozen (mean|A-B|)            %.3f  (want < %.2f)" % (diff, MAX_FROZEN_DIFF))
    if diff < MAX_FROZEN_DIFF:
        return 0
    print("  FAIL the frame is not frozen - pause playback first")
    return 1


def main():
    if len(sys.argv) == 4 and sys.argv[1] == "freeze":
        return freeze_check(sys.argv[2], sys.argv[3])
    if len(sys.argv) < 4:
        print("usage: glass_tracking.py freeze A B")
        print("       glass_tracking.py RAW RAW2 GLASS")
        return 2
    raw, raw2, glass = (Shot(p) for p in sys.argv[1:4])
    failures = 0

    # The picture has to be a still frame, or "the glass tracks the picture" is meaningless.
    diff = mean_abs_difference(raw, raw2)
    print("frame frozen (raw vs raw2)          %.3f  (want < %.2f)" % (diff, MAX_FROZEN_DIFF))
    if not (diff < MAX_FROZEN_DIFF):
        print("  FAIL the frame is not frozen - pause playback first")
        failures += 1

    # The pane is the contiguous band of rows where the two frames disagree. It has to be a run, not
    # "everything from the first difference down": below the pane the chrome-hidden frame and the
    # chrome-shown frame are the same picture, and including those rows would drown the pane out.
    best = (0, 0)
    run_start = None
    for y in range(int(raw.h * 0.4), raw.h):
        diff = sum(
            abs(raw.luma(x, y) - glass.luma(x, y)) for x in range(0, raw.w, 17)
        ) / len(range(0, raw.w, 17))
        if diff > 3:
            if run_start is None:
                run_start = y
        elif run_start is not None:
            if y - run_start > best[1] - best[0]:
                best = (run_start, y - 1)
            run_start = None
    if run_start is not None and raw.h - run_start > best[1] - best[0]:
        best = (run_start, raw.h - 1)
    if best[1] - best[0] < 20:
        print("  FAIL no chrome found - reveal it before the third screenshot")
        return failures + 1
    y0, y1 = best
    print("chrome band detected from y=%d..%d" % (y0, y1))

    # Sample across the full width of the pane but away from both rims, which bend the picture by
    # design and would decorrelate it. Buttons and text sit on top of the glass; they are static, so
    # they dilute the correlation without moving its peak, but keeping off them sharpens the result.
    box = (int(raw.w * 0.18), y0 + 8, int(raw.w * 0.76), y1 - 8)

    r = correlation(raw, glass, box)
    print("pane over picture                   r=%.4f  (want > %.2f)" % (r, MIN_CORRELATION))
    if not (r > MIN_CORRELATION):
        print("  FAIL the glass is not tracking the picture behind it")
        failures += 1

    at_zero = r
    neighbours = [
        correlation(raw, glass, box, offset=(dx, dy))
        for dx, dy in ((-6, 0), (6, 0), (0, -6), (0, 6), (-12, 0), (12, 0))
    ]
    worst = max(v for v in neighbours if not math.isnan(v))
    print("best neighbouring offset            r=%.4f  (must be below the peak)" % worst)
    if not (at_zero > worst):
        print("  FAIL the mapping is offset - the peak is not at zero")
        failures += 1

    # Pillarbox columns: nothing behind the pane there, so no tracking.
    far = (4, y0 + 6, min(90, raw.w - 1), y1 - 6)
    box_r = correlation(raw, glass, far)
    print("pane over pillarbox                 r=%.4f  (want < %.2f)" % (box_r, MAX_BOX_CORRELATION))
    if not (math.isnan(box_r) or box_r < MAX_BOX_CORRELATION):
        print("  FAIL the glass is smearing the picture into the letterbox")
        failures += 1

    print("checks failed: %d" % failures)
    return failures


if __name__ == "__main__":
    sys.exit(main())
