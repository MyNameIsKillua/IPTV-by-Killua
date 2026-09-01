"""Draws the desktop client's icon: the brand's violet tile with a play mark cut out of it.

No image library is used, because none is available here and none is needed: a PNG is a handful of
chunks and zlib is in the standard library. Everything is drawn at 4x and averaged down, which is
what gives the rounded corners and the triangle their smooth edges at 16 pixels.
"""
import io
import struct
import zlib

# The brand, from BrandPalette in :shared.
VIOLET = (139, 92, 246)
VIOLET_BRIGHT = (167, 139, 250)
NIGHT = (9, 9, 14)

SUPERSAMPLE = 4


def rounded_tile(size):
    """RGBA pixels for one tile: a violet gradient with a dark play triangle."""
    big = size * SUPERSAMPLE
    radius = big * 0.22
    # The triangle, as fractions of the tile, nudged right so it reads as centred.
    left = big * 0.36
    right = big * 0.72
    top = big * 0.26
    bottom = big * 0.74

    coverage = [[0.0] * size for _ in range(size)]
    colour = [[(0, 0, 0)] * size for _ in range(size)]
    triangle = [[0.0] * size for _ in range(size)]

    for y in range(size):
        for x in range(size):
            inside = 0
            inTriangle = 0
            for sy in range(SUPERSAMPLE):
                for sx in range(SUPERSAMPLE):
                    px = x * SUPERSAMPLE + sx + 0.5
                    py = y * SUPERSAMPLE + sy + 0.5
                    if in_rounded_square(px, py, big, radius):
                        inside += 1
                        if in_triangle(px, py, left, right, top, bottom):
                            inTriangle += 1
            total = SUPERSAMPLE * SUPERSAMPLE
            coverage[y][x] = inside / total
            triangle[y][x] = inTriangle / total
            # A vertical gradient, brighter at the top, so the tile has a light source.
            t = y / max(size - 1, 1)
            colour[y][x] = tuple(
                round(VIOLET_BRIGHT[i] + (VIOLET[i] - VIOLET_BRIGHT[i]) * t) for i in range(3)
            )

    rows = []
    for y in range(size):
        row = bytearray()
        for x in range(size):
            a = coverage[y][x]
            base = colour[y][x]
            cut = triangle[y][x]
            rgb = tuple(round(base[i] * (1 - cut) + NIGHT[i] * cut) for i in range(3))
            row += bytes((rgb[0], rgb[1], rgb[2], round(a * 255)))
        rows.append(bytes(row))
    return rows


def in_rounded_square(x, y, size, radius):
    if radius <= 0:
        return True
    cx = min(max(x, radius), size - radius)
    cy = min(max(y, radius), size - radius)
    return (x - cx) ** 2 + (y - cy) ** 2 <= radius ** 2


def in_triangle(x, y, left, right, top, bottom):
    if x < left or x > right:
        return False
    height = bottom - top
    width = right - left
    # A triangle pointing right: its half-height shrinks linearly to the tip.
    half = (height / 2) * (1 - (x - left) / width)
    middle = (top + bottom) / 2
    return abs(y - middle) <= half


def png(rows, size):
    raw = b"".join(b"\x00" + row for row in rows)
    out = io.BytesIO()
    out.write(b"\x89PNG\r\n\x1a\n")

    def chunk(kind, payload):
        out.write(struct.pack(">I", len(payload)))
        out.write(kind + payload)
        out.write(struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF))

    chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
    chunk(b"IDAT", zlib.compress(raw, 9))
    chunk(b"IEND", b"")
    return out.getvalue()


def ico(images):
    """An ICO with PNG-encoded entries, which every Windows since Vista reads."""
    out = io.BytesIO()
    out.write(struct.pack("<HHH", 0, 1, len(images)))
    offset = 6 + 16 * len(images)
    for size, data in images:
        out.write(struct.pack(
            "<BBBBHHII",
            0 if size >= 256 else size,
            0 if size >= 256 else size,
            0, 0, 1, 32, len(data), offset,
        ))
        offset += len(data)
    for _, data in images:
        out.write(data)
    return out.getvalue()


sizes = [16, 32, 48, 64, 256]
rendered = {size: png(rounded_tile(size), size) for size in sizes}

# Run from the repository root: python desktop/tools/make-icon.py
io.open('desktop/src/main/resources/icon.png', 'wb').write(rendered[256])
io.open('desktop/icon.ico', 'wb').write(ico([(size, rendered[size]) for size in sizes]))
print("icon.png and icon.ico written")
