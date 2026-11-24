// The app's synthesised pack sounds (sounds/*.wav, copied from the Android app) via Web Audio.
const NAMES = ["tear", "rip", "shimmer", "whoosh", "flip", "buildup", "reveal", "reveal_big", "twinkle"];

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
}
