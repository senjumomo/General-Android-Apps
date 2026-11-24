// Crisp UI for the AR scene: glass panels, icon buttons and text, drawn into high-resolution canvas
// textures. Everything is laid out in MILLIMETRES (1 unit = 1 mm of the panel's real size), so the look
// doesn't depend on the texture resolution, and that resolution is high (about 5400 pixels per metre,
// roughly twice what the headset can show at arm's length) so text stays sharp.
//
// Icons are Material Design icons (Apache License 2.0, https://github.com/google/material-design-icons).
import * as THREE from "three";

export const FONT = 'system-ui, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif';
export const THEME = {
  gold: "#ffcd50", pink: "#ff78dc", blue: "#78c8ff", mint: "#7dffc4", red: "#ff6070",
  text: "#ffffff", dim: "#b9b9d6", ink: "#1a1226",
  rarity: ["#c8c8d2", "#dce1eb", "#78c8ff", "#ffcd50", "#ff78dc", "#aaf0ff"],   // by tier: Common … Premium
};

const PPM = 5400, MAX_SIDE = 2048;
let aniso = 8;
export const setAnisotropy = (n) => { aniso = Math.max(1, n); };

// --- Surfaces ---------------------------------------------------------------------------------------
/** A canvas (and its texture) for something [wm] × [hm] metres. Draw on `ctx` in millimetres. */
export function surface(wm, hm, ppm = PPM) {
  const k = Math.min(1, MAX_SIDE / (Math.max(wm, hm) * ppm));
  const w = Math.max(4, Math.round(wm * ppm * k)), h = Math.max(4, Math.round(hm * ppm * k));
  const cv = document.createElement("canvas");
  cv.width = w; cv.height = h;
  const ctx = cv.getContext("2d");
  const tex = new THREE.CanvasTexture(cv);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.anisotropy = aniso;
  const s = { cv, ctx, tex, w: wm * 1000, h: hm * 1000 };
  s.clear = () => {
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, cv.width, cv.height);
    ctx.setTransform(cv.width / s.w, 0, 0, cv.height / s.h, 0, 0);
  };
  s.clear();
  return s;
}

/** A flat panel. `draw(ctx, w, h, state)` paints it (mm); `mesh.userData.redraw(state)` repaints it. */
export function makePanel(wm, hm, draw, { ppm, order = 50 } = {}) {
  const s = surface(wm, hm, ppm);
  const mesh = new THREE.Mesh(new THREE.PlaneGeometry(wm, hm),
    new THREE.MeshBasicMaterial({ map: s.tex, transparent: true, depthWrite: false, toneMapped: false }));
  mesh.renderOrder = order;
  mesh.userData.surface = s;
  mesh.userData.redraw = (st) => { s.clear(); draw(s.ctx, s.w, s.h, st || {}); s.tex.needsUpdate = true; };
  mesh.userData.redraw();
  return mesh;
}

// --- Text -------------------------------------------------------------------------------------------
/** Sets the font to the biggest size ≤ [size] at which [text] fits [maxW] (never below [min]). */
export function fit(ctx, text, maxW, size, min = 6, weight = "700") {
  let s = size;
  ctx.font = `${weight} ${s}px ${FONT}`;
  while (ctx.measureText(text).width > maxW && s > min) { s -= 0.5; ctx.font = `${weight} ${s}px ${FONT}`; }
  return s;
}

export function wrap(ctx, text, maxW) {
  const lines = [];
  let line = "";
  for (const word of text.split(/\s+/)) {
    const test = line ? line + " " + word : word;
    if (ctx.measureText(test).width > maxW && line) { lines.push(line); line = word; } else line = test;
  }
  if (line) lines.push(line);
  return lines;
}

// --- Look: glass --------------------------------------------------------------------------------------
const rr = (ctx, x, y, w, h, r) => { ctx.beginPath(); ctx.roundRect(x, y, w, h, Math.max(0, Math.min(r, w / 2, h / 2))); };

/** A dark frosted-glass plate with a soft shadow, a lit edge and a faint sheen. */
export function glass(ctx, x, y, w, h, { r = 7, alpha = 0.84, tint = null, edge = 1 } = {}) {
  ctx.save();
  ctx.shadowColor = "rgba(0,0,0,0.5)"; ctx.shadowBlur = 5; ctx.shadowOffsetY = 1.4;
  const g = ctx.createLinearGradient(0, y, 0, y + h);
  g.addColorStop(0, `rgba(46,42,76,${alpha})`); g.addColorStop(1, `rgba(16,14,30,${alpha})`);
  ctx.fillStyle = g;
  rr(ctx, x + 3, y + 3, w - 6, h - 6, r); ctx.fill();
  ctx.restore();
  if (tint) {                                                      // a coloured wash from the left edge
    const t = ctx.createLinearGradient(x, 0, x + w * 0.7, 0);
    t.addColorStop(0, hexA(tint, 0.30)); t.addColorStop(1, hexA(tint, 0));
    ctx.fillStyle = t; rr(ctx, x + 3, y + 3, w - 6, h - 6, r); ctx.fill();
  }
  const s = ctx.createLinearGradient(0, y, 0, y + h * 0.5);       // sheen
  s.addColorStop(0, "rgba(255,255,255,0.10)"); s.addColorStop(1, "rgba(255,255,255,0)");
  ctx.fillStyle = s; rr(ctx, x + 3, y + 3, w - 6, h * 0.5, r); ctx.fill();
  const b = ctx.createLinearGradient(x, y, x + w, y + h);
  b.addColorStop(0, `rgba(255,255,255,${0.6 * edge})`); b.addColorStop(0.5, `rgba(255,255,255,${0.14 * edge})`); b.addColorStop(1, `rgba(255,255,255,${0.4 * edge})`);
  ctx.lineWidth = 0.9; ctx.strokeStyle = tint ? hexA(tint, 0.85) : b;
  rr(ctx, x + 3, y + 3, w - 6, h - 6, r); ctx.stroke();
}

export function hexA(hex, a) {
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${n >> 16},${(n >> 8) & 255},${n & 255},${a})`;
}

// --- Icons --------------------------------------------------------------------------------------------
const ICON_D = {
  shelf: "M7 19h10V4H7v15zm-5-2h4V6H2v11zM18 6v11h4V6h-4z",
  undo: "M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z",
  skip: "M4 18l8.5-6L4 6v12zm9-12v12l8.5-6L13 6z",
  sets: "M3 3v8h8V3H3zm6 6H5V5h4v4zm-6 4v8h8v-8H3zm6 6H5v-4h4v4zm4-16v8h8V3h-8zm6 6h-4V5h4v4zm-6 4v8h8v-8h-8zm6 6h-4v-4h4v4z",
  recenter: "M12 8c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm8.94 3c-.46-4.17-3.77-7.48-7.94-7.94V1h-2v2.06C6.83 3.52 3.52 6.83 3.06 11H1v2h2.06c.46 4.17 3.77 7.48 7.94 7.94V23h2v-2.06c4.17-.46 7.48-3.77 7.94-7.94H23v-2h-2.06zM12 19c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7z",
  sound: "M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z",
  mute: "M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z",
  exit: "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z",
  left: "M15.41 7.41L14 6l-6 6 6 6 1.41-1.41L10.83 12z",
  right: "M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z",
  again: "M12 6v3l4-4-4-4v3c-4.42 0-8 3.58-8 8 0 1.57.46 3.03 1.24 4.26L6.7 14.8c-.45-.83-.7-1.79-.7-2.8 0-3.31 2.69-6 6-6zm6.76 1.74L17.3 9.2c.44.84.7 1.79.7 2.8 0 3.31-2.69 6-6 6v-3l-4 4 4 4v-3c4.42 0 8-3.58 8-8 0-1.57-.46-3.03-1.24-4.26z",
  back: "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z",
  check: "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z",
};
const iconPaths = new Map();
/** Draws icon [name] centred at (cx, cy), [size] mm square. */
export function icon(ctx, name, cx, cy, size, color = THEME.text) {
  if (!iconPaths.has(name)) iconPaths.set(name, new Path2D(ICON_D[name]));
  ctx.save();
  ctx.translate(cx - size / 2, cy - size / 2);
  ctx.scale(size / 24, size / 24);
  ctx.fillStyle = color;
  ctx.fill(iconPaths.get(name));
  ctx.restore();
}
export const ICON_NAMES = Object.keys(ICON_D);

// --- Shared glow texture ------------------------------------------------------------------------------
let glowTex = null;
export function glowTexture() {
  if (glowTex) return glowTex;
  const c = document.createElement("canvas"); c.width = c.height = 128;
  const x = c.getContext("2d"), g = x.createRadialGradient(64, 64, 0, 64, 64, 64);
  g.addColorStop(0, "rgba(255,255,255,1)"); g.addColorStop(0.35, "rgba(255,255,255,0.45)"); g.addColorStop(1, "rgba(255,255,255,0)");
  x.fillStyle = g; x.fillRect(0, 0, 128, 128);
  glowTex = new THREE.CanvasTexture(c);
  glowTex.colorSpace = THREE.SRGBColorSpace;
  glowTex.userData.shared = true;
  return glowTex;
}

// --- Buttons ------------------------------------------------------------------------------------------
/**
 * A pinchable button. `draw(ctx, w, h, state)` paints its face, `set(patch)` merges into the state and
 * repaints, `show(bool)` pops it in or out, `press()` triggers it. `update(dt, now)` animates hover/press.
 * Its face is the thing pointers hit (userData.kind = "button").
 */
export function makeButton({ w, h, draw, onPress, glow = THEME.blue, state = {} }) {
  const group = new THREE.Group();
  const face = makePanel(w, h, draw, { order: 52 });
  const halo = new THREE.Mesh(new THREE.PlaneGeometry(w * 1.8, h * 1.6),
    new THREE.MeshBasicMaterial({ map: glowTexture(), color: glow, transparent: true, opacity: 0, depthWrite: false, blending: THREE.AdditiveBlending, toneMapped: false }));
  halo.renderOrder = 51; halo.position.z = -0.002;
  group.add(halo, face);
  const b = { group, face, halo, w, h, enabled: true, shown: true, showK: 0, hoverK: 0, pressK: 0, hoverAt: -9, st: { ...state }, onPress };
  face.userData.kind = "button";
  face.userData.owner = b;
  face.userData.redraw(b.st);                                      // paint with the starting state (makePanel painted it blank)
  b.set =(patch) => { Object.assign(b.st, patch); face.userData.redraw(b.st); };
  b.show = (on) => { b.shown = on; };
  b.setEnabled = (on) => { if (b.enabled !== on) { b.enabled = on; b.set({ disabled: !on }); } };
  b.interactive = () => b.shown && b.enabled && b.showK > 0.5;
  b.press = () => { if (!b.interactive()) return false; b.pressK = 1; b.onPress?.(b); return true; };
  b.update = (dt, now) => {
    const hov = b.interactive() && now - b.hoverAt < 0.12;
    b.hoverK += ((hov ? 1 : 0) - b.hoverK) * (1 - Math.exp(-dt * 16));
    b.pressK = Math.max(0, b.pressK - dt * 5);
    b.showK += ((b.shown ? 1 : 0) - b.showK) * (1 - Math.exp(-dt * 12));
    group.visible = b.showK > 0.02;
    if (!group.visible) return;
    const s = (0.45 + 0.55 * b.showK) * (1 + 0.1 * b.hoverK - 0.08 * b.pressK);
    face.scale.setScalar(s);
    face.material.opacity = Math.min(1, b.showK * 1.3);
    face.position.z = 0.005 * b.hoverK - 0.005 * b.pressK;
    halo.scale.setScalar(s);
    halo.material.opacity = (0.5 * b.hoverK + 0.6 * b.pressK) * b.showK;
  };
  b.dispose = () => { face.geometry.dispose(); face.material.map.dispose(); face.material.dispose(); halo.geometry.dispose(); halo.material.dispose(); };
  return b;
}

// Face painters ---------------------------------------------------------------------------------------
/** A round icon button with a caption underneath (the menu bar). state: icon, label, tone, disabled. */
export function drawRound(ctx, w, h, st) {
  const d = Math.min(w - 8, 58), cx = w / 2, cy = d / 2 + 4;
  ctx.save();
  ctx.globalAlpha = st.disabled ? 0.38 : 1;
  const tone = st.tone || "glass";
  ctx.shadowColor = "rgba(0,0,0,0.5)"; ctx.shadowBlur = 5; ctx.shadowOffsetY = 1.4;
  let fill;
  if (tone === "primary") { fill = ctx.createLinearGradient(cx - d / 2, cy - d / 2, cx + d / 2, cy + d / 2); fill.addColorStop(0, THEME.gold); fill.addColorStop(1, THEME.pink); }
  else if (tone === "danger") fill = THEME.red;
  else { fill = ctx.createLinearGradient(0, cy - d / 2, 0, cy + d / 2); fill.addColorStop(0, "rgba(58,54,92,0.92)"); fill.addColorStop(1, "rgba(22,20,40,0.94)"); }
  ctx.fillStyle = fill;
  ctx.beginPath(); ctx.arc(cx, cy, d / 2, 0, Math.PI * 2); ctx.fill();
  ctx.restore();
  ctx.save();
  ctx.globalAlpha = st.disabled ? 0.38 : 1;
  ctx.lineWidth = 1; ctx.strokeStyle = tone === "glass" ? "rgba(255,255,255,0.42)" : "rgba(255,255,255,0.7)";
  ctx.beginPath(); ctx.arc(cx, cy, d / 2 - 0.5, 0, Math.PI * 2); ctx.stroke();
  if (st.icon) icon(ctx, st.icon, cx, cy, d * 0.52, tone === "primary" ? THEME.ink : THEME.text);
  if (st.label) {
    ctx.textAlign = "center"; ctx.textBaseline = "middle";
    const lw = w - 4;
    fit(ctx, st.label, lw, 11, 7);
    const ty = cy + d / 2 + 11, tw = Math.min(lw, ctx.measureText(st.label).width + 10);
    ctx.fillStyle = "rgba(10,8,20,0.55)";                              // a dark plate so it reads over a bright room
    rr(ctx, cx - tw / 2, ty - 7.5, tw, 15, 7.5); ctx.fill();
    ctx.fillStyle = THEME.text;
    ctx.fillText(st.label, cx, ty + 0.4);
  }
  ctx.restore();
}

/** A wide pill button: optional icon, one line of text. state: text, icon, tone ("primary" | "glass"), disabled. */
export function drawPill(ctx, w, h, st) {
  ctx.save();
  ctx.globalAlpha = st.disabled ? 0.4 : 1;
  const tone = st.tone || "glass", r = h / 2 - 3;
  if (tone === "primary") {
    ctx.shadowColor = "rgba(0,0,0,0.5)"; ctx.shadowBlur = 5; ctx.shadowOffsetY = 1.4;
    const g = ctx.createLinearGradient(0, 0, w, h);
    g.addColorStop(0, THEME.gold); g.addColorStop(1, THEME.pink);
    ctx.fillStyle = g; rr(ctx, 3, 3, w - 6, h - 6, r); ctx.fill();
    ctx.shadowColor = "transparent";
    const s = ctx.createLinearGradient(0, 0, 0, h * 0.5);
    s.addColorStop(0, "rgba(255,255,255,0.35)"); s.addColorStop(1, "rgba(255,255,255,0)");
    ctx.fillStyle = s; rr(ctx, 3, 3, w - 6, (h - 6) / 2, r); ctx.fill();
  } else glass(ctx, 0, 0, w, h, { r, alpha: 0.88, edge: 1.3 });
  ctx.textAlign = "center"; ctx.textBaseline = "middle";
  const color = tone === "primary" ? THEME.ink : THEME.text;
  const size = h * 0.4, iconSize = h * 0.5;
  const fs = fit(ctx, st.text || "", w - 24 - (st.icon ? iconSize + 4 : 0), size, 7);
  const tw = ctx.measureText(st.text || "").width, total = tw + (st.icon ? iconSize + 4 : 0);
  let x = w / 2 - total / 2;
  if (st.icon) { icon(ctx, st.icon, x + iconSize / 2, h / 2, iconSize, color); x += iconSize + 4; }
  ctx.fillStyle = color; ctx.textAlign = "left";
  ctx.fillText(st.text || "", x, h / 2 + fs * 0.04);
  ctx.restore();
}

/** A small chip (a year): text only. state: text, active, disabled. */
export function drawChip(ctx, w, h, st) {
  ctx.save();
  ctx.globalAlpha = st.disabled ? 0.4 : 1;
  const r = h / 2 - 2.5;
  if (st.active) {
    const g = ctx.createLinearGradient(0, 0, w, h);
    g.addColorStop(0, THEME.gold); g.addColorStop(1, THEME.pink);
    ctx.fillStyle = g; rr(ctx, 3, 3, w - 6, h - 6, r); ctx.fill();
  } else glass(ctx, 0, 0, w, h, { r, alpha: 0.86, edge: 1.1 });
  ctx.textAlign = "center"; ctx.textBaseline = "middle";
  const fs = fit(ctx, st.text || "", w - 10, h * 0.46, 7);
  ctx.fillStyle = st.active ? THEME.ink : THEME.text;
  ctx.fillText(st.text || "", w / 2, h / 2 + fs * 0.04);
  ctx.restore();
}

// Panel painters --------------------------------------------------------------------------------------
/** The hint strip: a main line and an optional smaller line under it. state: main, sub. */
export function drawHint(ctx, w, h, st) {
  if (!st.main) return;
  glass(ctx, 0, 0, w, h, { r: h / 2 - 3, alpha: 0.74 });
  ctx.textAlign = "center"; ctx.textBaseline = "middle";
  fit(ctx, st.main, w - 30, st.sub ? 15 : 17, 8);
  ctx.fillStyle = THEME.text;
  ctx.fillText(st.main, w / 2, st.sub ? h * 0.38 : h / 2);
  if (st.sub) { fit(ctx, st.sub, w - 30, 10.5, 7, "600"); ctx.fillStyle = THEME.dim; ctx.fillText(st.sub, w / 2, h * 0.73); }
}

/** The card readout under the held card. state: name, rarity, price, tier. */
export function drawInfo(ctx, w, h, st) {
  if (!st.name) return;
  const col = THEME.rarity[st.tier ?? 0];
  glass(ctx, 0, 0, w, h, { r: 9, alpha: 0.86, tint: st.tier >= 2 ? col : null });
  ctx.textAlign = "center"; ctx.textBaseline = "middle";
  ctx.fillStyle = THEME.text;
  let size = fit(ctx, st.name, w - 30, 19, 12);
  const wide = ctx.measureText(st.name).width > w - 30;
  if (wide) {                                                      // a long name: two lines
    const lines = wrap(ctx, st.name, w - 30).slice(0, 2);
    size = Math.min(size, 13.5); ctx.font = `700 ${size}px ${FONT}`;
    lines.forEach((l, i) => ctx.fillText(l, w / 2, 17 + i * (size + 1.5)));
  } else ctx.fillText(st.name, w / 2, 22.5);
  const y = h - 16.5;
  ctx.font = `700 13px ${FONT}`;
  const rw = ctx.measureText(st.rarity).width, pw = ctx.measureText(st.price).width;
  const gap = 12, total = rw + pw + gap + 20;
  let x = w / 2 - total / 2;
  ctx.fillStyle = hexA(col, 0.2); rr(ctx, x, y - 9, rw + 14, 18, 9); ctx.fill();
  ctx.lineWidth = 0.7; ctx.strokeStyle = hexA(col, 0.9); rr(ctx, x, y - 9, rw + 14, 18, 9); ctx.stroke();
  ctx.fillStyle = col; ctx.fillText(st.rarity, x + 7 + rw / 2, y + 0.4);
  x += rw + 14 + gap;
  ctx.fillStyle = THEME.gold; ctx.textAlign = "left"; ctx.fillText(st.price, x, y + 0.4);
}

/** The name tag under a pack on the shelf. state: name, sub, active. */
export function drawTag(ctx, w, h, st) {
  if (!st.name) return;
  glass(ctx, 0, 0, w, h, { r: 8, alpha: 0.78 });
  ctx.textAlign = "center"; ctx.textBaseline = "middle";
  fit(ctx, st.name, w - 22, 15.5, 8);
  ctx.fillStyle = THEME.text; ctx.fillText(st.name, w / 2, h * 0.40);
  if (st.sub) { fit(ctx, st.sub, w - 22, 10, 6.5, "600"); ctx.fillStyle = THEME.dim; ctx.fillText(st.sub, w / 2, h * 0.76); }
}

/** A big word that glows in a colour (the rarity banner). state: text, color. */
export function drawBanner(ctx, w, h, st) {
  if (!st.text) return;
  ctx.textAlign = "center"; ctx.textBaseline = "middle";
  ctx.letterSpacing = "2.5px";
  fit(ctx, st.text, w - 30, 25, 10, "800");
  ctx.shadowColor = st.color; ctx.shadowBlur = 14;
  ctx.fillStyle = st.color; ctx.fillText(st.text, w / 2, h / 2);
  ctx.shadowBlur = 4; ctx.fillStyle = "#fff"; ctx.globalAlpha = 0.9; ctx.fillText(st.text, w / 2, h / 2);
  ctx.letterSpacing = "0px";
}

/** Progress dots (which card of the pack you're on). state: n, at, seen (count). */
export function drawPips(ctx, w, h, st) {
  const n = st.n || 0;
  if (!n) return;
  const gap = 11, x0 = w / 2 - ((n - 1) * gap) / 2;
  for (let i = 0; i < n; i++) {
    const x = x0 + i * gap, cur = i === st.at;
    ctx.beginPath(); ctx.arc(x, h / 2, cur ? 3.2 : 2.3, 0, Math.PI * 2);
    if (i < st.at) { ctx.fillStyle = "rgba(255,255,255,0.85)"; ctx.fill(); }
    else if (cur) { ctx.fillStyle = THEME.gold; ctx.shadowColor = THEME.gold; ctx.shadowBlur = 5; ctx.fill(); ctx.shadowBlur = 0; }
    else { ctx.lineWidth = 0.9; ctx.strokeStyle = "rgba(255,255,255,0.5)"; ctx.stroke(); }
  }
}

/** A heading plate (e.g. "Jump to a year"). state: text, sub. */
export function drawTitle(ctx, w, h, st) {
  if (!st.text) return;
  glass(ctx, 0, 0, w, h, { r: h / 2 - 3, alpha: 0.8 });
  ctx.textAlign = "center"; ctx.textBaseline = "middle";
  fit(ctx, st.text, w - 30, st.sub ? 17 : 19, 9, "800");
  ctx.fillStyle = THEME.text; ctx.fillText(st.text, w / 2, st.sub ? h * 0.36 : h / 2);
  if (st.sub) { fit(ctx, st.sub, w - 30, 10.5, 7, "600"); ctx.fillStyle = THEME.dim; ctx.fillText(st.sub, w / 2, h * 0.72); }
}

/** The summary plate beside the nine cards. state: set, total, best { name, rarity, tier }, counts [{ label, tier, n }]. */
export function drawSummary(ctx, w, h, st) {
  if (!st.set) return;
  glass(ctx, 0, 0, w, h, { r: 11, alpha: 0.86 });
  ctx.textAlign = "center"; ctx.textBaseline = "middle";
  ctx.font = `700 9.5px ${FONT}`; ctx.letterSpacing = "2.2px"; ctx.fillStyle = THEME.dim;
  ctx.fillText("YOUR PULL", w / 2, 17);
  ctx.letterSpacing = "0px";
  fit(ctx, st.set, w - 30, 16, 9);
  ctx.fillStyle = THEME.text; ctx.fillText(st.set, w / 2, 35);
  const g = ctx.createLinearGradient(w * 0.2, 52, w * 0.8, 92);
  g.addColorStop(0, THEME.gold); g.addColorStop(1, THEME.pink);
  fit(ctx, st.total, w - 40, 40, 16, "800");
  ctx.fillStyle = g; ctx.fillText(st.total, w / 2, 70);
  ctx.font = `600 9.5px ${FONT}`; ctx.fillStyle = THEME.dim;
  ctx.fillText("estimated value", w / 2, 93);
  ctx.fillStyle = "rgba(255,255,255,0.14)"; ctx.fillRect(22, 106, w - 44, 0.8);
  if (st.best) {                                                    // the best card
    const col = THEME.rarity[st.best.tier];
    ctx.font = `600 9px ${FONT}`; ctx.fillStyle = THEME.dim; ctx.fillText("BEST PULL", w / 2, 119);
    fit(ctx, st.best.name, w - 30, 13, 8);
    ctx.fillStyle = THEME.text; ctx.fillText(st.best.name, w / 2, 134);
    ctx.font = `700 11px ${FONT}`; ctx.fillStyle = col; ctx.fillText(st.best.rarity, w / 2, 150);
  }
  // How many of each rarity, as little chips.
  ctx.font = `700 10px ${FONT}`;
  let x = 0, y = 172;
  const chips = (st.counts || []).map((c) => ({ ...c, text: `${c.n}× ${c.label}`, tw: ctx.measureText(`${c.n}× ${c.label}`).width + 12 }));
  const rows = [];
  for (const c of chips) {
    if (!rows.length || rows[rows.length - 1].w + c.tw + 5 > w - 30) rows.push({ w: 0, items: [] });
    const r = rows[rows.length - 1]; r.items.push(c); r.w += c.tw + 5;
  }
  for (const r of rows) {
    x = w / 2 - (r.w - 5) / 2;
    for (const c of r.items) {
      const col = THEME.rarity[c.tier];
      ctx.fillStyle = hexA(col, 0.16); rr(ctx, x, y - 8, c.tw, 16, 8); ctx.fill();
      ctx.lineWidth = 0.6; ctx.strokeStyle = hexA(col, 0.8); rr(ctx, x, y - 8, c.tw, 16, 8); ctx.stroke();
      ctx.fillStyle = col; ctx.textAlign = "center"; ctx.fillText(c.text, x + c.tw / 2, y + 0.4);
      x += c.tw + 5;
    }
    y += 20;
  }
}

/** Frees the GPU memory of everything under [obj] (except textures marked shared). */
export function disposeTree(obj) {
  obj.traverse((n) => {
    if (!n.geometry?.userData?.shared) n.geometry?.dispose?.();
    const m = n.material;
    if (!m) return;
    for (const mm of Array.isArray(m) ? m : [m]) {
      if (mm.map && !mm.map.userData?.shared) mm.map.dispose();
      mm.dispose?.();
    }
  });
}
