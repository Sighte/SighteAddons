#!/usr/bin/env python
"""Generates the UI sprite sheet.

The sheet is checked in, but it is generated rather than drawn: a binary blob nobody can regenerate
is a dependency with no source. Run this and the PNG is reproduced byte for byte.

    python tools/gen_ui_sheet.py

Everything on the sheet is white with a varying alpha. Nothing carries a colour of its own, because
every region is blitted with a tint from the token palette -- that is what lets one 256x256 sheet
serve both themes and every state without a second asset, and it is what keeps the "no hue anywhere"
rule enforceable at the source rather than by review.

Two details are load-bearing:

  * The corners are authored at 2x their GUI size. The sheet is loaded with `blur: true`, so the GPU
    filters linearly: at GUI scale 4 a 2x master is magnified 2:1, and at scale 1 it is minified 2:1.
    Authoring at 1x would alias badly when magnified four times; authoring at 4x would alias when
    minified four times. 2x splits the error.
  * Every region is padded with a transparent gutter. Linear filtering samples beyond the edge of a
    blit's source rect, so two regions packed flush against each other bleed into one another along
    that seam -- and `clamp: true` only clamps at the texture border, not at a sub-rect edge.

Coordinates here are mirrored by `Sheet.kt`. Change one and the other is wrong.
"""

import math
import os
import struct
import zlib

SIZE = 256
GUTTER = 8

# Corner radii in GUI pixels, and where each radius's four orientations start on the sheet.
# Authored at 2x, so a radius of 12 occupies 24 texture pixels.
SCALE = 2
CORNERS = [
    (4, 0, 0),
    (8, 0, 16),
    (12, 0, 40),
    (16, 0, 72),
]

# The hairline arc that goes with each corner. Two texture pixels thick at 2x authoring, so it lands
# at one GUI pixel when blitted at its native GUI size.
#
# This is the one place the "one physical pixel" rule is knowingly not met: a sprite drawn in GUI
# units has its ring multiplied by the GUI scale, so at scale 4 this arc is 4 device pixels while the
# straight edges beside it are 1. Making it exact needs a ring baked per GUI scale, which is 80 tiny
# regions and a build step, for a difference that is invisible at radius 4 and nearly so at 12.
# Surface.roundedBorder owns the decision, so upgrading it later touches no call site.
RINGS = [
    (4, 96, 112),
    (8, 96, 128),
    (12, 96, 152),
    (16, 96, 184),
]
RING_THICKNESS = 2

RAMP_H = (0, 112, 64, 8)      # x, y, w, h -- alpha 0 -> 255 -> 0 across the width
SHADOW = (0, 128, 96, 96)     # x, y, w, h -- a blurred rounded rect, nine-sliced with a 24px border
SHADOW_INSET = 24
SHADOW_RADIUS = 12
SHADOW_SIGMA = 6.0

SUPERSAMPLE = 4


def blank():
    return [[0.0] * SIZE for _ in range(SIZE)]


def draw_corner(alpha, ox, oy, size, quadrant):
    """A quarter disc, antialiased by supersampling.

    `quadrant` is 0..3 for top-left, top-right, bottom-left, bottom-right. The disc centre sits at
    the corner of the tile diagonally opposite the rounded one, so the covered area is the part of
    the tile that stays inside the shape.
    """
    cx = size if quadrant in (0, 2) else 0.0
    cy = size if quadrant in (0, 1) else 0.0
    step = 1.0 / SUPERSAMPLE
    for py in range(size):
        for px in range(size):
            hits = 0
            for sy in range(SUPERSAMPLE):
                for sx in range(SUPERSAMPLE):
                    x = px + (sx + 0.5) * step
                    y = py + (sy + 0.5) * step
                    if math.hypot(x - cx, y - cy) <= size:
                        hits += 1
            if hits:
                alpha[oy + py][ox + px] = hits / float(SUPERSAMPLE * SUPERSAMPLE)


def draw_ring(alpha, ox, oy, size, quadrant, thickness):
    """The arc of [draw_corner]'s quarter disc: same centre, hollowed out from the inside."""
    cx = size if quadrant in (0, 2) else 0.0
    cy = size if quadrant in (0, 1) else 0.0
    inner = size - thickness
    step = 1.0 / SUPERSAMPLE
    for py in range(size):
        for px in range(size):
            hits = 0
            for sy in range(SUPERSAMPLE):
                for sx in range(SUPERSAMPLE):
                    x = px + (sx + 0.5) * step
                    y = py + (sy + 0.5) * step
                    d = math.hypot(x - cx, y - cy)
                    if inner <= d <= size:
                        hits += 1
            if hits:
                alpha[oy + py][ox + px] = hits / float(SUPERSAMPLE * SUPERSAMPLE)


def draw_ramp_h(alpha, ox, oy, w, h):
    """A symmetric 0 -> 1 -> 0 sweep. Stretched to any width by one blit; see Shimmer."""
    for px in range(w):
        t = (px + 0.5) / w
        value = math.sin(t * math.pi) ** 2
        for py in range(h):
            alpha[oy + py][ox + px] = value


def rounded_rect_mask(w, h, inset, radius):
    mask = [[0.0] * w for _ in range(h)]
    left, top = inset, inset
    right, bottom = w - inset, h - inset
    step = 1.0 / SUPERSAMPLE
    for py in range(h):
        for px in range(w):
            hits = 0
            for sy in range(SUPERSAMPLE):
                for sx in range(SUPERSAMPLE):
                    x = px + (sx + 0.5) * step
                    y = py + (sy + 0.5) * step
                    if not (left <= x <= right and top <= y <= bottom):
                        continue
                    # Only the four corner boxes need the distance test.
                    ccx = min(max(x, left + radius), right - radius)
                    ccy = min(max(y, top + radius), bottom - radius)
                    if math.hypot(x - ccx, y - ccy) <= radius:
                        hits += 1
            mask[py][px] = hits / float(SUPERSAMPLE * SUPERSAMPLE)
    return mask


def gaussian_blur(mask, sigma):
    """Separable Gaussian. Small enough that a straightforward implementation is fine."""
    radius = int(math.ceil(sigma * 3))
    kernel = [math.exp(-(i * i) / (2.0 * sigma * sigma)) for i in range(-radius, radius + 1)]
    total = sum(kernel)
    kernel = [k / total for k in kernel]

    h = len(mask)
    w = len(mask[0])

    horizontal = [[0.0] * w for _ in range(h)]
    for y in range(h):
        row = mask[y]
        for x in range(w):
            acc = 0.0
            for i, k in enumerate(kernel):
                sx = min(max(x + i - radius, 0), w - 1)
                acc += row[sx] * k
            horizontal[y][x] = acc

    out = [[0.0] * w for _ in range(h)]
    for y in range(h):
        for x in range(w):
            acc = 0.0
            for i, k in enumerate(kernel):
                sy = min(max(y + i - radius, 0), h - 1)
                acc += horizontal[sy][x] * k
            out[y][x] = acc
    return out


def draw_shadow(alpha, ox, oy, w, h):
    mask = rounded_rect_mask(w, h, SHADOW_INSET, SHADOW_RADIUS)
    blurred = gaussian_blur(mask, SHADOW_SIGMA)
    for py in range(h):
        for px in range(w):
            alpha[oy + py][ox + px] = blurred[py][px]


def write_png(path, alpha):
    raw = bytearray()
    for y in range(SIZE):
        raw.append(0)  # filter type 0
        row = alpha[y]
        for x in range(SIZE):
            a = int(round(max(0.0, min(1.0, row[x])) * 255))
            # Straight (non-premultiplied) white. The tint supplies the colour.
            raw += bytes((255, 255, 255, a))

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")

    with open(path, "wb") as handle:
        handle.write(png)


def main():
    alpha = blank()

    for radius, ox, oy in CORNERS:
        size = radius * SCALE
        for quadrant in range(4):
            draw_corner(alpha, ox + quadrant * (size + GUTTER), oy, size, quadrant)

    for radius, ox, oy in RINGS:
        size = radius * SCALE
        for quadrant in range(4):
            draw_ring(alpha, ox + quadrant * (size + GUTTER), oy, size, quadrant, RING_THICKNESS)

    draw_ramp_h(alpha, *RAMP_H)
    draw_shadow(alpha, *SHADOW)

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out = os.path.join(root, "src", "main", "resources", "assets", "sighteaddons", "textures", "gui")
    if not os.path.isdir(out):
        os.makedirs(out)
    path = os.path.join(out, "ui_sheet.png")
    write_png(path, alpha)
    print("wrote %s (%d bytes)" % (path, os.path.getsize(path)))


if __name__ == "__main__":
    main()
