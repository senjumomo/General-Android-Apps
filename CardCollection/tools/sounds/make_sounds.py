"""
Synthesises the pack-opening sound effects (no recorded or borrowed audio): noise, filters and
sine partials only. Writes 22.05 kHz mono 16-bit WAVs to app/src/main/res/raw/.

    python3 tools/sounds/make_sounds.py
"""
import os, wave
import numpy as np

SR = 22050
OUT = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "res", "raw")
rng = np.random.default_rng(7)


def t(sec):
    return np.arange(int(SR * sec)) / SR


def biquad_bandpass(x, f0, q):
    """Band-pass with a centre frequency that may change per sample (array or scalar)."""
    f0 = np.broadcast_to(np.asarray(f0, dtype=float), x.shape)
    y = np.zeros_like(x)
    x1 = x2 = y1 = y2 = 0.0
    for i in range(len(x)):
        w = 2 * np.pi * f0[i] / SR
        alpha = np.sin(w) / (2 * q)
        b0, b2 = alpha, -alpha
        a0, a1, a2 = 1 + alpha, -2 * np.cos(w), 1 - alpha
        yi = (b0 * x[i] + b2 * x2 - a1 * y1 - a2 * y2) / a0
        x2, x1 = x1, x[i]
        y2, y1 = y1, yi
        y[i] = yi
    return y


def lowpass(x, a):
    y = np.zeros_like(x); acc = 0.0
    for i, v in enumerate(x):
        acc += a * (v - acc); y[i] = acc
    return y


def env(n, attack, decay_rate):
    tt = np.arange(n) / SR
    a = np.clip(tt / max(attack, 1e-4), 0, 1)
    return a * np.exp(-tt * decay_rate)


def bell(freq, sec, decay=4.0, partials=((1, 1.0), (2.76, 0.35), (5.4, 0.12), (8.9, 0.05))):
    tt = t(sec)
    s = sum(amp * np.sin(2 * np.pi * freq * r * tt) * np.exp(-tt * decay * (1 + r * 0.4)) for r, amp in partials)
    return s * np.clip(tt / 0.004, 0, 1)


def place(dst, src, at):
    i = int(at * SR)
    n = min(len(src), len(dst) - i)
    dst[i:i + n] += src[:n]


def write(name, x, peak=0.8):
    x = x / (np.max(np.abs(x)) + 1e-9) * peak
    fade = min(len(x), int(0.01 * SR))
    x[-fade:] *= np.linspace(1, 0, fade)
    rise = int(0.002 * SR)            # no pop at the start
    x[:rise] *= np.linspace(0, 1, rise)
    data = (x * 32767).astype(np.int16)
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(data.tobytes())
    print(name, f"{len(x) / SR:.2f}s")


# Tear tick: a few paper-fibre crackles.
n = int(0.09 * SR)
x = np.zeros(n)
for _ in range(7):
    i = rng.integers(0, n - 200)
    x[i:i + 200] += rng.normal(0, 1, 200) * np.exp(-np.arange(200) / 25)
x = biquad_bandpass(x, 3200, 0.9)
write("pack_tear", x, 0.55)

# Rip: a bright noise burst that darkens, with crackle.
n = int(0.5 * SR)
noise = rng.normal(0, 1, n)
sweep = np.linspace(5200, 900, n)
x = biquad_bandpass(noise, sweep, 0.7) * env(n, 0.006, 7)
crackle = np.zeros(n)
for _ in range(40):
    i = rng.integers(0, int(n * 0.6))
    crackle[i:i + 60] += rng.normal(0, 1, 60) * np.exp(-np.arange(60) / 10)
x += biquad_bandpass(crackle, 4000, 1.2) * 0.6
write("pack_rip", x)

# Shimmer: light pouring out: rising sparkly partials over a soft chord.
sec = 1.5
tt = t(sec)
x = np.zeros(len(tt))
for k in range(14):
    f = rng.uniform(1800, 5200)
    start = rng.uniform(0, 0.7)
    tone = np.sin(2 * np.pi * f * (1 + 0.15 * tt) * tt) * np.exp(-np.maximum(tt - start, 0) * 5)
    tone *= (tt >= start) * np.clip((tt - start) / 0.02, 0, 1)
    x += tone * rng.uniform(0.3, 1.0)
pad = sum(np.sin(2 * np.pi * f * tt) for f in (523.25, 659.25, 783.99, 1046.5)) * 0.25
x += pad * np.clip(tt / 0.25, 0, 1) * np.exp(-tt * 1.8)
write("pack_shimmer", x, 0.6)

# Whoosh: the card thrown aside.
n = int(0.32 * SR)
tt = np.arange(n) / SR
x = biquad_bandpass(rng.normal(0, 1, n), np.linspace(500, 2200, n), 1.1)
x *= np.sin(np.pi * tt / tt[-1]) ** 2
write("pack_whoosh", x, 0.55)

# Flip: a card snap: click plus a low thump.
n = int(0.1 * SR)
tt = np.arange(n) / SR
click = biquad_bandpass(rng.normal(0, 1, n), 2600, 1.5) * np.exp(-tt * 90)
thump = np.sin(2 * np.pi * 190 * tt) * np.exp(-tt * 45) * 0.7
write("pack_flip", click + thump, 0.6)

# Build-up: a rising, trembling riser before a big pull.
sec = 1.3
tt = t(sec)
f = 220 + 700 * (tt / sec) ** 2
phase = 2 * np.pi * np.cumsum(f) / SR
trem = 0.6 + 0.4 * np.sin(2 * np.pi * (4 + 14 * tt / sec) * tt)
riser = (np.sin(phase) + 0.5 * np.sin(2 * phase) + 0.25 * np.sin(3 * phase)) * trem
air = biquad_bandpass(rng.normal(0, 1, len(tt)), 1500 + 3000 * tt / sec, 0.8) * 0.5
x = (riser * 0.6 + air) * (tt / sec) ** 1.5
write("pack_buildup", x, 0.55)

# Big reveal: a bell arpeggio up an octave and a half, with sparkle.
x = np.zeros(int(2.0 * SR))
for i, fr in enumerate((1046.5, 1318.5, 1568.0, 2093.0, 2637.0)):
    place(x, bell(fr, 1.8, decay=2.6) * (0.8 if i < 4 else 0.6), i * 0.075)
for _ in range(10):
    place(x, bell(rng.uniform(3500, 6000), 0.4, decay=10) * 0.25, rng.uniform(0.3, 1.0))
write("pack_reveal_big", x, 0.75)

# Small reveal: a two-note ding for Super Rares.
x = np.zeros(int(1.0 * SR))
place(x, bell(1568.0, 0.9, decay=4), 0)
place(x, bell(2093.0, 0.9, decay=4), 0.09)
write("pack_reveal", x, 0.6)

# Twinkle: one tiny high bell as a Rare lands.
write("pack_twinkle", bell(3136.0, 0.35, decay=9), 0.4)
