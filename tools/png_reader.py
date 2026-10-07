#!/usr/bin/env python3
"""Minimal PNG reader, so the harnesses can look at screenshots without pulling in Pillow.

Screenshots are the only way to check anything about what the player actually looks like, and the
device has no imaging libraries. This decodes the subset `screencap` produces: 8-bit, non-interlaced,
any of the standard colour types, all five filter types.

Shared deliberately. Two harnesses used to carry their own copy, and when one of them was replaced
the other broke on the import.
"""
import struct
import zlib


def read_png(path):
    """Returns (width, height, channels, pixels) with pixels as a flat bytearray."""
    data = open(path, "rb").read()
    pos = 8
    idat = b""
    width = height = colortype = None
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        ctype = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        if ctype == b"IHDR":
            width, height, _bitdepth, colortype = struct.unpack(">IIBB", chunk[:10])
        elif ctype == b"IDAT":
            idat += chunk
        pos += 12 + length

    raw = zlib.decompress(idat)
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[colortype]
    stride = width * channels
    out = bytearray(width * height * channels)
    prev = bytearray(stride)
    p = 0
    for y in range(height):
        filter_type = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        for i in range(stride):
            a = line[i - channels] if i >= channels else 0
            b = prev[i]
            c = prev[i - channels] if i >= channels else 0
            x = line[i]
            if filter_type == 1:
                x = (x + a) & 255
            elif filter_type == 2:
                x = (x + b) & 255
            elif filter_type == 3:
                x = (x + ((a + b) >> 1)) & 255
            elif filter_type == 4:
                pp = a + b - c
                pa, pb, pc = abs(pp - a), abs(pp - b), abs(pp - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                x = (x + pr) & 255
            line[i] = x
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return width, height, channels, out


class Png:
    """A screenshot with the lookups the harnesses keep needing."""

    def __init__(self, path):
        self.w, self.h, self.ch, self.px = read_png(path)

    def rgb(self, x, y):
        i = (y * self.w + x) * self.ch
        if self.ch >= 3:
            return self.px[i], self.px[i + 1], self.px[i + 2]
        return self.px[i], self.px[i], self.px[i]

    def luma(self, x, y):
        r, g, b = self.rgb(x, y)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
