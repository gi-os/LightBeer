#!/usr/bin/env python3
"""
Regenerate the LightBeer launcher icon.

Every sibling Light Phone III icon is white line art on black. This one is the one
exception: a small pint glass rendered in the same amber/foam palette as the app itself,
because the app's whole joke is being the one full-colour tool on an otherwise monochrome
phone. The background layer stays pure black so it disappears on the OLED panel like the
others; only the foreground breaks the rule.

Geometry is defined once against a 108x108 adaptive-icon canvas, safe zone 18..90, and
rendered as raster PNGs (a gradient pour isn't worth expressing as vector paths). Run:

    python3 scripts/generate_icon.py

Needs Pillow. Rewrites app/src/main/res/{drawable-nodpi,mipmap-anydpi-v26,mipmap-*}.
"""

from __future__ import annotations

import os

from PIL import Image, ImageDraw

RES = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")

CANVAS = 108
SAFE = (18, 90)
SUPERSAMPLE = 8
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

AMBER_LIGHT = (246, 185, 59, 255)
AMBER_DARK = (185, 114, 12, 255)
FOAM = (255, 247, 227, 255)
GLASS_LINE = (207, 207, 207, 255)
BG = (0, 0, 0, 255)

# Glass silhouette within the 18..90 safe zone.
GLASS_TOP = 32
GLASS_BOTTOM = 84
LEFT_TOP, RIGHT_TOP = 40, 68
LEFT_BOTTOM, RIGHT_BOTTOM = 43, 65
FOAM_BOTTOM = 40


def write_background() -> None:
    xml = f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Pure black. On the Light Phone III's OLED these pixels are simply off. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{CANVAS}dp"
    android:height="{CANVAS}dp"
    android:viewportWidth="{CANVAS}"
    android:viewportHeight="{CANVAS}">
    <path
        android:fillColor="#000000"
        android:pathData="M0,0h{CANVAS}v{CANVAS}h-{CANVAS}z" />
</vector>
"""
    d = os.path.join(RES, "drawable")
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "ic_launcher_background.xml"), "w") as f:
        f.write(xml)


def glass_polygon(s: int) -> list[tuple[float, float]]:
    return [
        (LEFT_TOP * s, GLASS_TOP * s),
        (RIGHT_TOP * s, GLASS_TOP * s),
        (RIGHT_BOTTOM * s, GLASS_BOTTOM * s),
        (LEFT_BOTTOM * s, GLASS_BOTTOM * s),
    ]


def render_foreground(px: int) -> Image.Image:
    s = SUPERSAMPLE
    img = Image.new("RGBA", (CANVAS * s, CANVAS * s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    poly = glass_polygon(s)
    d.polygon(poly, fill=(28, 18, 6, 255))  # empty-glass tint, matches the in-app canvas

    # Amber liquid, gradient by row, clipped to the glass trapezoid via a mask.
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).polygon(poly, fill=255)
    liquid = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ld = ImageDraw.Draw(liquid)
    top_y = int((GLASS_TOP + FOAM_BOTTOM - GLASS_TOP) * 0) + int((GLASS_TOP + 10) * s)
    for y in range(int(GLASS_TOP * s) + int(10 * s), int(GLASS_BOTTOM * s)):
        t = (y - (int(GLASS_TOP * s) + int(10 * s))) / max(1, int(GLASS_BOTTOM * s) - (int(GLASS_TOP * s) + int(10 * s)))
        r = int(AMBER_LIGHT[0] + (AMBER_DARK[0] - AMBER_LIGHT[0]) * t)
        g = int(AMBER_LIGHT[1] + (AMBER_DARK[1] - AMBER_LIGHT[1]) * t)
        b = int(AMBER_LIGHT[2] + (AMBER_DARK[2] - AMBER_LIGHT[2]) * t)
        ld.line([(0, y), (CANVAS * s, y)], fill=(r, g, b, 255))
    liquid.putalpha(mask)
    img.alpha_composite(liquid)

    # Foam cap.
    foam_mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(foam_mask).polygon(poly, fill=255)
    foam_band = Image.new("L", img.size, 0)
    ImageDraw.Draw(foam_band).rectangle(
        [0, GLASS_TOP * s, CANVAS * s, FOAM_BOTTOM * s], fill=255,
    )
    foam_mask = Image.composite(foam_mask, Image.new("L", img.size, 0), foam_band)
    foam_layer = Image.new("RGBA", img.size, FOAM)
    foam_layer.putalpha(foam_mask)
    img.alpha_composite(foam_layer)

    # Glass outline + a single highlight streak, matching the in-app render language.
    outline_w = max(1, int(2.2 * s))
    d.line(poly + [poly[0]], fill=GLASS_LINE, width=outline_w, joint="curve")
    hi_w = max(1, int(2.6 * s))
    d.line(
        [(LEFT_TOP + 4) * s, (GLASS_TOP + 3) * s, (LEFT_BOTTOM + 5) * s, (GLASS_BOTTOM - 4) * s],
        fill=(255, 255, 255, 200), width=hi_w,
    )

    lo, hi = SAFE
    out = img.crop((lo * s, lo * s, hi * s, hi * s)).resize((px, px), Image.LANCZOS)
    return out


def render_monochrome(px: int) -> Image.Image:
    """Plain white silhouette for launchers that theme icons — no colour promises here."""
    s = SUPERSAMPLE
    img = Image.new("RGBA", (CANVAS * s, CANVAS * s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    poly = glass_polygon(s)
    outline_w = max(1, int(3.2 * s))
    d.line(poly + [poly[0]], fill=(255, 255, 255, 255), width=outline_w, joint="curve")
    lo, hi = SAFE
    return img.crop((lo * s, lo * s, hi * s, hi * s)).resize((px, px), Image.LANCZOS)


def write_foreground_assets() -> None:
    d = os.path.join(RES, "drawable-nodpi")
    os.makedirs(d, exist_ok=True)
    render_foreground(432).save(os.path.join(d, "ic_launcher_foreground.png"))
    render_monochrome(432).save(os.path.join(d, "ic_launcher_monochrome.png"))

    adaptive = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
"""
    amdpi = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(amdpi, exist_ok=True)
    for name in ("ic_launcher", "ic_launcher_round"):
        with open(os.path.join(amdpi, f"{name}.xml"), "w") as f:
            f.write(adaptive)


def write_legacy_rasters() -> None:
    for dpi, px in DENSITIES.items():
        folder = os.path.join(RES, f"mipmap-{dpi}")
        os.makedirs(folder, exist_ok=True)
        flat = Image.new("RGBA", (px, px), BG)
        flat.alpha_composite(render_foreground(px))
        flat.save(os.path.join(folder, "ic_launcher.png"))

        mask = Image.new("L", (px * 4, px * 4), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, px * 4 - 1, px * 4 - 1], fill=255)
        mask = mask.resize((px, px), Image.LANCZOS)
        round_img = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        round_img.paste(flat, (0, 0), mask)
        round_img.save(os.path.join(folder, "ic_launcher_round.png"))


if __name__ == "__main__":
    write_background()
    write_foreground_assets()
    write_legacy_rasters()
    print("icon regenerated in", os.path.normpath(RES))
