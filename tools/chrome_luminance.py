#!/usr/bin/env python3
"""Mean luminance of the player's control-bar strip, used as a proxy for "is the chrome visible".

Extracted into its own file so the shell harness never has to embed Python: the nested quoting
needed for that is exactly the kind of thing that breaks silently.
"""
import struct
import sys
import zlib


def read_png(path):
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


def main():
    if len(sys.argv) < 2:
        print("0")
        return
    try:
        width, height, channels, px = read_png(sys.argv[1])
    except Exception:
        print("0")
        return

    # The floating capsule lives in the bottom eighth of the screen.
    total = 0.0
    count = 0
    for y in range(int(height * 0.86), int(height * 0.95)):
        for x in range(200, min(width - 1, 900), 11):
            i = (y * width + x) * channels
            total += (px[i] + px[i + 1] + px[i + 2]) / 3 if channels >= 3 else px[i]
            count += 1
    print(f"{total / max(count, 1):.1f}")


if __name__ == "__main__":
    main()
