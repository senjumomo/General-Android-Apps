// The app's synthesised pack sounds (sounds/*.wav, copied from the Android app) via Web Audio, plus a few
// tiny blips for the interface that are generated here (oscillators only: no recorded audio).
const NAMES = ["tear", "rip", "shimmer", "whoosh", "flip", "buildup", "reveal", "reveal_big", "twinkle"];

// [frequency Hz, start offset s, length s]
const BLIPS = {
  click: [[900, 0, 0.07], [1350, 0.04, 0.09]],
  hover: [[1700, 0, 0.035]],
  back: [[700, 0, 0.07], [470, 0.05, 0.1]],
  warn: [[420, 0, 0.09], [420, 0.12, 0.09]],
  pick: [[660, 0, 0.08], [990, 0.06, 0.08], [1320, 0.12, 0.14]],
};

export class Sound {
  constructor() { this.ctx = null; this.buf = {}; this.muted = false; }

  /** Call from a click (browsers only start audio after a gesture). */
  async init() {
    if (this.ctx) { this.ctx.resume(); return; }
    this.ctx = new (window.AudioContext || window.webkitAudioContext)();
    this.ctx.resume();
    await Promise.all(NAMES.map(async (n) => {
      try {
        const res = await fetch(`sounds/pack_${n}.wav`);
        this.buf[n] = await this.ctx.decodeAudioData(await res.arrayBuffer());
      } catch (e) { /* a missing sound shouldn't stop the show */ }
    }));
  }

  play(name, volume = 1, rate = 1) {
    if (!this.ctx || this.muted || !this.buf[name]) return null;
    const src = this.ctx.createBufferSource();
    src.buffer = this.buf[name];
    src.playbackRate.value = rate;
    const gain = this.ctx.createGain();
    gain.gain.value = volume;
    src.connect(gain).connect(this.ctx.destination);
    src.start();
    return src;
  }

  /** A short interface blip: click, hover, back, warn, pick. */
  ui(kind, volume = 1) {
    if (!this.ctx || this.muted) return;
    const t0 = this.ctx.currentTime;
    for (const [freq, at, len] of BLIPS[kind] || BLIPS.click) {
      const osc = this.ctx.createOscillator(), gain = this.ctx.createGain();
      osc.type = "sine";
      osc.frequency.value = freq;
      gain.gain.setValueAtTime(0.0001, t0 + at);
      gain.gain.exponentialRampToValueAtTime(0.16 * volume, t0 + at + 0.006);
      gain.gain.exponentialRampToValueAtTime(0.0001, t0 + at + len);
      osc.connect(gain).connect(this.ctx.destination);
      osc.start(t0 + at);
      osc.stop(t0 + at + len + 0.02);
    }
  }
}
