"""
Makes the dark-mode backgrounds from the day photo: the same cabin, but the view
outside is a clear desert night (subtle stars over dark dunes, moonlit sand). Writes dashboard_night.webp and cabin_blur_night.webp.

    python3 tools/night_background.py [out_dir]

Run from the cockpit/ folder. Everything is drawn here; nothing is downloaded.
"""
import sys

import numpy as np
from PIL import Image
from scipy import ndimage

RES = "app/src/main/res/drawable-nodpi"
OUT = sys.argv[1] if len(sys.argv) > 1 else RES
rng = np.random.default_rng(11)

src = np.array(Image.open(f"{RES}/dashboard.webp").convert("RGBA")).astype(np.float32)
rgb, alpha = src[..., :3] / 255.0, src[..., 3]
H, W = alpha.shape
L = rgb.mean(-1)
sat = rgb.max(-1) - rgb.min(-1)
yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)


def smooth_noise(n, scale, seed):
    """1-D noise along x, smooth at [scale] pixels, in 0..1."""
    r = np.random.default_rng(seed).random(n // scale + 3)
    x = np.arange(n) / scale
    i = x.astype(int)
    f = x - i
    f = f * f * (3 - 2 * f)
    v = r[i] * (1 - f) + r[i + 1] * f
    return (v - v.min()) / (v.max() - v.min() + 1e-6)


# ---- Where the outside is: the pale sand, reached from the top edge ------------
pale = (L > 0.55) & (sat < 0.2) & (alpha > 250) & (yy < H * 0.45)
lab, _ = ndimage.label(pale)
top = np.setdiff1d(np.unique(lab[0]), [0])
outside = np.isin(lab, top)
outside = ndimage.binary_closing(outside, iterations=4)
outside = ndimage.binary_fill_holes(outside)
# Over the light fringe round the wheel, mirrors and dash, so no day halo stays.
outside = ndimage.binary_dilation(outside, iterations=2)
# Above the wheel's top it's all outside, corners included, and the sand down the
# photo's very edges beside the mirrors.
outside |= yy < 330
outside |= (L > 0.5) & (sat < 0.25) & (yy < 400) & ((xx < 10) | (xx > W - 11))
mask = ndimage.gaussian_filter(outside.astype(np.float32), 1.4)[..., None]

# ---- The night outside --------------------------------------------------------
HZ = 296.0  # the horizon in the photo
t = np.clip(yy / HZ, 0, 1)[..., None]
c_top = np.array([2, 4, 12]) / 255
c_mid = np.array([8, 14, 36]) / 255
c_hor = np.array([30, 34, 66]) / 255
sky = np.where(t < 0.6, c_top + (c_mid - c_top) * (t / 0.6), c_mid + (c_hor - c_mid) * ((t - 0.6) / 0.4))

# Distant dunes, a dark ridge just above the horizon.
ridge = HZ - 10 - 26 * smooth_noise(W, 140, 3) - 10 * smooth_noise(W, 37, 4)
land = (yy >= ridge[None, :]) & (yy < HZ + 1)
land_col = np.array([7, 9, 18]) / 255
night = np.where(land[..., None], land_col, sky)

# Stars, fewer towards the horizon; the brightest with a soft glint.
stars = np.zeros((H, W), np.float32)
glow = np.zeros((H, W), np.float32)
n = 1100
sx = rng.integers(0, W, n)
sy = (rng.random(n) ** 1.35 * (HZ - 40)).astype(int)
sb = rng.random(n) ** 3.2
for x, y, b in zip(sx, sy, sb):
    if y < ridge[x] - 6:
        stars[y, x] += 0.35 + 1.4 * b
        if b > 0.55:
            glow[y, x] += b
stars = ndimage.gaussian_filter(stars, 0.65) * 2.2
glints = np.zeros_like(glow)
for y, x in zip(*np.nonzero(glow)):
    b = glow[y, x]
    k = int(6 + 10 * b)
    for d in range(-k, k + 1):
        f = (1 - abs(d) / (k + 1)) ** 2.2 * 0.5 * b
        if 0 <= x + d < W:
            glints[y, x + d] += f
        if 0 <= y + d < H:
            glints[y + d, x] += f
glints = ndimage.gaussian_filter(glints, 0.6) + ndimage.gaussian_filter(glow, 3.0) * 6
fade = np.clip((ridge[None, :] - 8 - yy) / 70, 0, 1)
star_tint = np.array([0.85, 0.92, 1.0])
night = night + ((stars + glints) * fade)[..., None] * star_tint

# Moonlit sand below the horizon, keeping the photo's own texture.
below = np.clip((yy - HZ) / 6, 0, 1)[..., None]
sand = (L[..., None] ** 1.4) * (np.array([46, 56, 86]) / 255) * 1.35
night = night * (1 - below) + sand * below

# ---- The cabin itself: the same, a touch dimmer and cooler, as at night ------
cabin = rgb * np.array([0.84, 0.85, 0.9])
# The top of the dash and the wheel pick up a little of the night sky's blue.
edge = ndimage.gaussian_filter(outside.astype(np.float32), 6)[..., None] * (1 - mask)
cabin = cabin * (1 - edge * 0.25) + edge * 0.25 * np.array([0.12, 0.15, 0.26])

out = np.clip(cabin * (1 - mask) + night * mask, 0, 1)
night_img = np.dstack([out * 255, alpha]).astype(np.uint8)
Image.fromarray(night_img, "RGBA").save(f"{OUT}/dashboard_night.webp", quality=92, method=6)

# ---- The blurred cabin behind Overview, Apps and Settings ------------------
# Made the same way as the day one: the photo over black, small, softly blurred.
def blurred(rgba):
    a = rgba[..., 3:4] / 255.0
    flat = rgba[..., :3] * a
    small = np.array(Image.fromarray(flat.astype(np.uint8)).resize((720, 405), Image.LANCZOS)).astype(np.float32)
    return ndimage.gaussian_filter(small, (BLUR, BLUR, 0))

day_ref = np.array(Image.open(f"{RES}/cabin_blur.webp").convert("RGB")).astype(np.float32)
best = None
for BLUR in np.arange(4, 26, 1.0):
    err = ((blurred(src) - day_ref) ** 2).mean()
    if best is None or err < best[0]:
        best = (err, BLUR)
BLUR = best[1]
print(f"cabin blur radius {BLUR:.0f} (rms {best[0] ** 0.5:.1f})")
Image.fromarray(np.clip(blurred(night_img.astype(np.float32)), 0, 255).astype(np.uint8)).save(
    f"{OUT}/cabin_blur_night.webp", quality=92, method=6
)
print("wrote", OUT)
