#!/usr/bin/env python3
"""
Render a full-bleed, photo-leaning beer-pour animation frame by frame, then (separately)
encode with ffmpeg into an all-intra MP4 that the app scrubs by seek position instead of
playing back. No network access is available in this environment to license real stock
footage, so this generates the closest procedural stand-in: real gradients/noise/bubbles
composited with numpy + PIL rather than flat vector shapes.

Usage:
    python3 gen_beer_video.py --preview      # renders frame 0, mid, last only, for a look
    python3 gen_beer_video.py --frames DIR   # renders the full sequence into DIR
"""
from __future__ import annotations

import argparse
import math
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

W, H = 720, 1280
N_FRAMES = 96
RNG_SEED = 7

AMBER_TOP = np.array([250, 196, 74], dtype=np.float32)
AMBER_MID = np.array([227, 149, 32], dtype=np.float32)
AMBER_BOTTOM = np.array([171, 96, 12], dtype=np.float32)
FOAM_TOP = np.array([255, 250, 235], dtype=np.float32)
FOAM_SHADOW = np.array([222, 196, 148], dtype=np.float32)
HEADSPACE_TOP = np.array([18, 11, 4], dtype=np.float32)
HEADSPACE_BOTTOM = np.array([34, 20, 6], dtype=np.float32)


def ease_out(t: float) -> float:
    return 1 - (1 - t) ** 2


def foam_frac(t: float) -> float:
    # Thick right after the "pour", settling to a thin ring, never quite zero.
    return 0.035 + 0.16 * math.exp(-3.2 * t)


def vertical_gradient(h: int, w: int, top: np.ndarray, bottom: np.ndarray) -> np.ndarray:
    ramp = np.linspace(0.0, 1.0, h, dtype=np.float32)[:, None, None]
    grad = top[None, None, :] * (1 - ramp) + bottom[None, None, :] * ramp
    return np.repeat(grad, w, axis=1)


def make_vignette(w: int, h: int) -> np.ndarray:
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    cx, cy = w / 2, h / 2
    d = np.sqrt(((xs - cx) / (w * 0.62)) ** 2 + ((ys - cy) / (h * 0.62)) ** 2)
    mask = np.clip(1.15 - 0.55 * d ** 2, 0.35, 1.0)
    return mask[:, :, None]


def make_static_overlay(rng: np.random.Generator) -> Image.Image:
    """Condensation droplets + two soft glass highlight streaks. Fixed camera, fixed glass —
    this stays identical every frame while the liquid behind it animates."""
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)

    # Two soft diagonal highlight bands, like light catching a curved glass wall.
    for cx, band_w, alpha in ((W * 0.22, W * 0.10, 40), (W * 0.78, W * 0.07, 30)):
        band = Image.new("L", (W, H), 0)
        bd = ImageDraw.Draw(band)
        bd.rectangle([cx - band_w / 2, 0, cx + band_w / 2, H], fill=alpha)
        band = band.filter(ImageFilter.GaussianBlur(radius=band_w * 0.35))
        white = Image.new("RGBA", (W, H), (255, 255, 255, 255))
        white.putalpha(band)
        layer = Image.alpha_composite(layer, white)
    d = ImageDraw.Draw(layer)

    # Condensation droplets, denser toward the bottom (colder glass near the liquid).
    n_drops = 130
    for _ in range(n_drops):
        yb = rng.random() ** 0.6
        y = yb * H
        x = rng.random() * W
        r = rng.uniform(1.5, 6.5) * (0.6 + yb * 0.8)
        alpha = int(rng.uniform(35, 90))
        d.ellipse([x - r, y - r, x + r, y + r], fill=(210, 225, 235, alpha))
        # tiny specular fleck
        d.ellipse([x - r * 0.3, y - r * 0.4, x - r * 0.05, y - r * 0.15],
                  fill=(255, 255, 255, min(255, alpha + 60)))

    # A few longer trickle streaks.
    for _ in range(10):
        x = rng.random() * W
        y0 = rng.uniform(H * 0.1, H * 0.6)
        length = rng.uniform(H * 0.08, H * 0.22)
        w = rng.uniform(1.0, 2.2)
        streak = Image.new("L", (W, H), 0)
        sd = ImageDraw.Draw(streak)
        sd.line([(x, y0), (x + rng.uniform(-8, 8), y0 + length)], fill=50, width=int(w))
        streak = streak.filter(ImageFilter.GaussianBlur(radius=1.4))
        white = Image.new("RGBA", (W, H), (225, 235, 240, 255))
        white.putalpha(streak)
        layer = Image.alpha_composite(layer, white)

    return layer


def render_frame(t: float, rng: np.random.Generator, overlay: Image.Image,
                  vignette: np.ndarray, bubble_sites: list[dict]) -> Image.Image:
    # Linear in t on purpose: the app seeks positionMs = t * durationMs directly from its
    # own fill level, so this mapping has to stay 1:1 or the scrub and the video disagree.
    fill = 1.0 - t
    liquid_top_y = H * (1.0 - fill)
    ff = foam_frac(t)
    foam_h = H * ff
    foam_bottom_y = liquid_top_y + foam_h

    canvas = np.empty((H, W, 3), dtype=np.float32)

    # Headspace above the pour line: dark out-of-focus glass interior, not flat black.
    head_h = max(1, int(round(liquid_top_y)))
    if head_h > 0:
        canvas[:head_h] = vertical_gradient(head_h, W, HEADSPACE_TOP, HEADSPACE_BOTTOM)

    # Liquid: vertical amber gradient plus a brighter core where light passes through,
    # narrower as the column drains so it still reads once the glass is nearly empty.
    liq_top_i = int(round(foam_bottom_y))
    liq_h = H - liq_top_i
    if liq_h > 0:
        liquid = vertical_gradient(liq_h, W, AMBER_TOP, AMBER_BOTTOM)
        # subtle mid-tone band so the gradient doesn't read as a flat two-stop ramp
        mid_ramp = np.linspace(0, 1, liq_h, dtype=np.float32)
        mid_weight = np.exp(-((mid_ramp - 0.4) ** 2) / 0.08)[:, None, None]
        liquid += (AMBER_MID[None, None, :] - AMBER_TOP[None, None, :]) * 0.25 * mid_weight
        xs = np.linspace(-1, 1, W, dtype=np.float32)
        core = np.exp(-(xs ** 2) / 0.18)[None, :, None]
        top_bias = np.linspace(1.0, 0.15, liq_h, dtype=np.float32)[:, None, None]
        liquid += 26.0 * core * top_bias
        canvas[liq_top_i:] = liquid

    img = Image.fromarray(np.clip(canvas, 0, 255).astype(np.uint8), mode="RGB").convert("RGBA")
    draw = ImageDraw.Draw(img)

    # Bubbles: fixed nucleation columns, rising continuously, compressed into whatever
    # liquid column remains this frame.
    if liq_h > 4:
        for site in bubble_sites:
            travel = (t * site["speed"] * 6.0 + site["phase"]) % 1.0
            by = liq_top_i + liq_h * (1.0 - travel)
            if by <= liq_top_i + 2:
                continue
            r = site["r"] * (0.7 + 0.3 * math.sin(travel * math.pi))
            bx = site["x"] + math.sin(travel * 9.0 + site["phase"]) * 3.5
            draw.ellipse([bx - r, by - r, bx + r, by + r],
                         fill=(255, 224, 150, 70))
            draw.ellipse([bx - r * 0.35, by - r * 0.45, bx + r * 0.05, by - r * 0.05],
                         fill=(255, 255, 255, 130))

    # Foam band: cellular noise, irregular lower edge, soft contact shadow into the beer.
    if foam_h > 1:
        foam_top_i = max(0, int(round(liquid_top_y)))
        band_h = max(1, int(round(foam_bottom_y)) - foam_top_i)
        noise = rng.random((max(1, band_h // 3 + 2), max(1, W // 3 + 2))).astype(np.float32)
        noise_img = Image.fromarray((noise * 255).astype(np.uint8), mode="L").resize(
            (W, band_h), Image.BICUBIC,
        )
        noise_img = noise_img.filter(ImageFilter.GaussianBlur(radius=2.2))
        cell = np.asarray(noise_img).astype(np.float32) / 255.0
        cell = np.clip((cell - 0.42) * 3.2, 0.0, 1.0)  # posterize into cell/gap pattern
        foam_rgb = FOAM_TOP[None, None, :] * (1 - cell[:, :, None] * 0.35) \
            + FOAM_SHADOW[None, None, :] * (cell[:, :, None] * 0.35)
        # uneven lower edge: per-column jitter on the alpha falloff
        col_jitter = rng.uniform(-3, 3, size=W).astype(np.float32)
        rows = np.arange(band_h)[:, None]
        edge = band_h - 4 + col_jitter[None, :]
        alpha = np.clip((edge - rows) / 3.0 + cell * 40, 0.0, 1.0) * 255.0
        foam_rgba = np.dstack([foam_rgb, alpha]).astype(np.uint8)
        foam_img = Image.fromarray(foam_rgba, mode="RGBA")
        img.alpha_composite(foam_img, (0, foam_top_i))
        # contact shadow where foam meets beer
        shadow = Image.new("L", (W, 10), 0)
        ImageDraw.Draw(shadow).rectangle([0, 0, W, 10], fill=60)
        shadow = shadow.filter(ImageFilter.GaussianBlur(radius=4))
        shadow_rgba = Image.new("RGBA", (W, 10), (60, 30, 5, 255))
        shadow_rgba.putalpha(shadow)
        img.alpha_composite(shadow_rgba, (0, max(0, int(round(foam_bottom_y)) - 5)))

    img = Image.alpha_composite(img, overlay)
    arr = np.asarray(img.convert("RGB")).astype(np.float32)
    arr *= vignette
    grain = rng.normal(0, 3.2, size=(H, W, 1)).astype(np.float32)
    arr += grain
    arr = np.clip(arr, 0, 255).astype(np.uint8)
    out = Image.fromarray(arr, mode="RGB")
    out = out.filter(ImageFilter.GaussianBlur(radius=0.4))

    jx = int(round(math.sin(t * 11.0 + 1.7) * 1.6))
    jy = int(round(math.cos(t * 9.0 + 0.4) * 1.6))
    if jx or jy:
        out = Image.fromarray(np.roll(np.asarray(out), (jy, jx), axis=(0, 1)))

    return out


def build_bubble_sites(rng: np.random.Generator) -> list[dict]:
    sites = []
    for _ in range(26):
        sites.append({
            "x": rng.uniform(W * 0.12, W * 0.88),
            "r": rng.uniform(2.0, 6.0),
            "speed": rng.uniform(0.7, 1.6),
            "phase": rng.uniform(0, 1),
        })
    return sites


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", action="store_true")
    ap.add_argument("--frames", type=str, default=None)
    args = ap.parse_args()

    rng = np.random.default_rng(RNG_SEED)
    overlay = make_static_overlay(rng)
    vignette = make_vignette(W, H)
    bubble_sites = build_bubble_sites(rng)

    if args.preview:
        os.makedirs("/tmp/beer_preview", exist_ok=True)
        for label, t in (("frame_full", 0.0), ("frame_mid", 0.5), ("frame_empty", 0.98)):
            frame_rng = np.random.default_rng(RNG_SEED + int(t * 1000))
            im = render_frame(t, frame_rng, overlay, vignette, bubble_sites)
            im.save(f"/tmp/beer_preview/{label}.png")
        print("preview frames written to /tmp/beer_preview")
        return

    if args.frames:
        os.makedirs(args.frames, exist_ok=True)
        for i in range(N_FRAMES):
            t = i / (N_FRAMES - 1)
            frame_rng = np.random.default_rng(RNG_SEED + i)
            im = render_frame(t, frame_rng, overlay, vignette, bubble_sites)
            im.save(os.path.join(args.frames, f"frame_{i:04d}.png"))
        print(f"{N_FRAMES} frames written to {args.frames}")
        return

    ap.error("pass --preview or --frames DIR")


if __name__ == "__main__":
    main()
