from pathlib import Path
import numpy as np

N = 17
OUT = Path(r"X:\gongzuoqu\app\src\main\assets\LUTBeta_SLog3_Warm_17.cube")

def xy_to_XYZ(x, y):
    return np.array([x / y, 1.0, (1 - x - y) / y])

def rgb_to_xyz(prim, white):
    P = np.array([xy_to_XYZ(*p) for p in prim]).T
    W = xy_to_XYZ(*white)
    S = np.linalg.solve(P, W)
    return P * S

cine = [(0.766, 0.275), (0.225, 0.800), (0.089, -0.087)]
rec709 = [(0.64, 0.33), (0.30, 0.60), (0.15, 0.06)]
M = np.linalg.inv(rgb_to_xyz(rec709, (0.3127, 0.3290))) @ rgb_to_xyz(cine, (0.3127, 0.3290))

def slog3_to_linear(y):
    y = np.asarray(y, dtype=np.float64)
    return np.clip(np.where(
        y >= 171.2102946929 / 1023.0,
        10 ** ((y * 1023.0 - 420.0) / 261.5) * 0.19 - 0.01,
        (y * 1023.0 - 95.0) * 0.01125 / (171.2102946929 - 95.0),
    ), 0.0, 1.0)

def rec709_gamma(x):
    x = np.clip(x, 0.0, 1.0)
    return np.where(x < 0.018, 4.5 * x, 1.099 * x ** 0.45 - 0.099)

def grade(rgb):
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
    shadow = np.clip(1.0 - lum * 2.0, 0.0, 1.0)
    high = np.clip((lum - 0.45) * 1.8, 0.0, 1.0)
    r = np.clip(r + 0.020 * high - 0.006 * shadow, 0.0, 1.0)
    g = np.clip(g + 0.006 * high + 0.004 * shadow, 0.0, 1.0)
    b = np.clip(b - 0.016 * high + 0.016 * shadow, 0.0, 1.0)

    def curve(x):
        s = x * x * (3.0 - 2.0 * x)
        m = x * 0.70 + s * 0.30
        return np.clip(0.008 + m * 0.982, 0.0, 1.0)

    r, g, b = curve(r), curve(g), curve(b)
    lum2 = 0.2126 * r + 0.7152 * g + 0.0722 * b
    sat = 1.04
    return np.stack([
        np.clip(lum2 + (r - lum2) * sat, 0.0, 1.0),
        np.clip(lum2 + (g - lum2) * sat, 0.0, 1.0),
        np.clip(lum2 + (b - lum2) * sat, 0.0, 1.0),
    ], axis=-1)

lines = [
    'TITLE "LUT beta S-Log3 Warm Film"',
    f"LUT_3D_SIZE {N}",
    "DOMAIN_MIN 0.0 0.0 0.0",
    "DOMAIN_MAX 1.0 1.0 1.0",
    "",
]
for bi in range(N):
    b = bi / (N - 1)
    for gi in range(N):
        g = gi / (N - 1)
        for ri in range(N):
            r = ri / (N - 1)
            lin = slog3_to_linear(np.array([r, g, b]))
            rec = np.clip(M @ lin, 0.0, 1.0)
            enc = rec709_gamma(rec)
            out = grade(enc[None, None, :])[0, 0]
            lines.append(f"{out[0]:.6f} {out[1]:.6f} {out[2]:.6f}")

OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
print("wrote", OUT, OUT.stat().st_size)
