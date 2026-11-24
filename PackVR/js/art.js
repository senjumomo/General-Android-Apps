// 2D drawing for the AR scene: the pack wrapper (a port of the app's PackArt), the card-back fallback and
// rounded corners for the card back. (Text panels and buttons are in ui.js.)
import * as THREE from "three";

export const PACK_PX = { w: 640, h: 1110 };           // the wrapper is designed on this grid …
const PACK_SCALE = 1.25;                              // … and drawn this much bigger so it stays sharp up close
const clamp = (x, a, b) => Math.min(b, Math.max(a, x));

function rgbToHsv(r, g, b) {
  r /= 255; g /= 255; b /= 255;
  const max = Math.max(r, g, b), min = Math.min(r, g, b), d = max - min;
  let h = 0;
  if (d) h = max === r ? ((g - b) / d) % 6 : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
  return [((h * 60) + 360) % 360, max ? d / max : 0, max];
}
function hsvToRgb(h, s, v) {
  const c = v * s, x = c * (1 - Math.abs(((h / 60) % 2) - 1)), m = v - c;
  const [r, g, b] = h < 60 ? [c, x, 0] : h < 120 ? [x, c, 0] : h < 180 ? [0, c, x] : h < 240 ? [0, x, c] : h < 300 ? [x, 0, c] : [c, 0, x];
  return [(r + m) * 255, (g + m) * 255, (b + m) * 255];
}
const mix = (a, b, t) => a.map((v, i) => v * (1 - t) + b[i] * t);
const css = (c, a = 1) => `rgba(${c[0] | 0},${c[1] | 0},${c[2] | 0},${a})`;

function averageColor(img) {
  const c = document.createElement("canvas");
  c.width = c.height = 8;
  const x = c.getContext("2d");
  x.drawImage(img, 0, 0, 8, 8);
  const d = x.getImageData(0, 0, 8, 8).data;
  let r = 0, g = 0, b = 0;
  for (let i = 0; i < 64; i++) { r += d[i * 4]; g += d[i * 4 + 1]; b += d[i * 4 + 2]; }
  const [h, s, v] = rgbToHsv(r / 64, g / 64, b / 64);
  return hsvToRgb(h, Math.min(s * 1.4, 0.9), clamp(v, 0.35, 0.8));   // a bit more saturated, never muddy
}

function hashColor(name) {
  let h = 7;
  for (const ch of name) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  return hsvToRgb(h % 360, 0.55, 0.6);
}

function wrap(ctx, text, maxWidth) {
  const lines = [];
  let line = "";
  for (const word of text.split(/\s+/)) {
    const test = line ? line + " " + word : word;
    if (ctx.measureText(test).width > maxWidth && line) { lines.push(line); line = word; } else line = test;
  }
  if (line) lines.push(line);
  return lines;
}

function crimp(ctx, y0, y1, w, top) {
  const g = ctx.createLinearGradient(0, y0, w, y1);
  [[0, [170, 170, 180]], [0.33, [245, 245, 250]], [0.67, [150, 150, 160]], [1, [230, 230, 238]]]
    .forEach(([t, c]) => g.addColorStop(t, css(c)));
  ctx.fillStyle = g;
  ctx.fillRect(0, y0, w, y1 - y0);
  ctx.fillStyle = "rgba(0,0,0,0.24)";
  for (let x = 0; x < w; x += 9) ctx.fillRect(x, y0, 3, y1 - y0);          // ridges
  // Serrated outer edge, cut out of the wrapper.
  const edge = top ? y0 : y1, dir = top ? 1 : -1, tooth = 16;
  ctx.save();
  ctx.globalCompositeOperation = "destination-out";
  ctx.beginPath();
  ctx.moveTo(0, edge);
  for (let x = 0; x <= w; x += tooth) { ctx.lineTo(x + tooth / 2, edge + dir * 9); ctx.lineTo(x + tooth, edge); }
  ctx.lineTo(w, edge - dir * 2); ctx.lineTo(0, edge - dir * 2); ctx.closePath();
  ctx.fill();
  ctx.restore();
  ctx.fillStyle = "rgba(0,0,0,0.35)";                                         // inner seam
  ctx.fillRect(0, (top ? y1 : y0) - 2, w, 4);
}

/** The pack wrapper: set artwork (if loaded) on a tinted foil body with crimped ends. Returns a canvas. */
export function drawPack(set, art) {
  const { w: W, h: H } = PACK_PX;
  const cv = document.createElement("canvas");
  cv.width = Math.round(W * PACK_SCALE); cv.height = Math.round(H * PACK_SCALE);
  const ctx = cv.getContext("2d");
  ctx.scale(PACK_SCALE, PACK_SCALE);
  const accent = art ? averageColor(art) : hashColor(set.name);
  const dark = mix(accent, [0, 0, 0], 0.72), midc = mix(accent, [0, 0, 0], 0.35), darkest = mix(accent, [0, 0, 0], 0.85);

  const body = ctx.createLinearGradient(0, 0, 0, H);
  body.addColorStop(0, css(midc)); body.addColorStop(0.5, css(dark)); body.addColorStop(1, css(darkest));
  ctx.fillStyle = body;
  ctx.fillRect(0, 0, W, H);

  if (art) {
    const size = W * 0.98, top = H * 0.14, left = (W - size) / 2;
    ctx.drawImage(art, left, top, size, size);
    let g = ctx.createLinearGradient(0, top + size * 0.65, 0, top + size);
    g.addColorStop(0, css(dark, 0)); g.addColorStop(1, css(dark, 1));
    ctx.fillStyle = g; ctx.fillRect(left, top + size * 0.65, size, size * 0.35 + 2);
    g = ctx.createLinearGradient(0, top, 0, top + size * 0.12);
    g.addColorStop(0, css(midc, 1)); g.addColorStop(1, css(midc, 0));
    ctx.fillStyle = g; ctx.fillRect(left, top - 1, size, size * 0.12 + 1);
  }

  // Set name, shrunk to fit two lines.
  const name = set.name.toUpperCase();
  let size = 74, lines;
  do {
    ctx.font = `bold ${size}px Georgia, "Times New Roman", serif`;
    lines = wrap(ctx, name, W * 0.86);
    size -= 4;
  } while (lines.length > 2 && size > 44);
  ctx.textAlign = "center"; ctx.fillStyle = "#fff";
  ctx.shadowColor = "rgba(0,0,0,0.85)"; ctx.shadowBlur = 14; ctx.shadowOffsetY = 4;
  const lh = (size + 4) * 1.1, y0 = H * 0.7 - (lines.length * lh) / 2 + lh * 0.8;
  lines.forEach((l, i) => ctx.fillText(l, W / 2, y0 + i * lh));
  ctx.shadowColor = "transparent";
  ctx.font = "bold 30px system-ui, sans-serif"; ctx.fillStyle = "rgba(255,255,255,0.9)";
  ctx.fillText(`${set.code}  ·  BOOSTER PACK`, W / 2, H * 0.81);
  ctx.font = "bold 24px system-ui, sans-serif"; ctx.fillStyle = "rgba(255,255,255,0.7)";
  ctx.fillText("9 CARDS PER PACK", W / 2, H * 0.845);

  crimp(ctx, 0, H * 0.085, W, true);
  crimp(ctx, H - H * 0.07, H, W, false);
  return cv;
}

/** A stand-in card back (used only if the official one can't be downloaded). */
export function drawBackFallback() {
  const cv = document.createElement("canvas");
  cv.width = 463; cv.height = 676;
  const ctx = cv.getContext("2d");
  const g = ctx.createRadialGradient(231, 338, 10, 231, 338, 380);
  g.addColorStop(0, "#050202"); g.addColorStop(0.5, "#5a2a0a"); g.addColorStop(1, "#b36a2a");
  ctx.fillStyle = "#8a4a1a"; ctx.fillRect(0, 0, 463, 676);
  ctx.fillStyle = g; ctx.fillRect(24, 24, 415, 628);
  return cv;
}

/** Rounded-corner mask for the card back (alphaMap). */
export function roundedMask() {
  const cv = document.createElement("canvas");
  cv.width = 128; cv.height = 186;
  const ctx = cv.getContext("2d");
  ctx.fillStyle = "#000"; ctx.fillRect(0, 0, 128, 186);
  ctx.fillStyle = "#fff";
  ctx.beginPath(); ctx.roundRect(0, 0, 128, 186, 7); ctx.fill();
  return new THREE.CanvasTexture(cv);
}
