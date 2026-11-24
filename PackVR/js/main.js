// Card Binder AR: open booster packs in front of you (Meta Quest passthrough). Pack opening only.
import * as THREE from "three";
import * as P from "./packs.js";
import * as UI from "./ui.js";
import { makeHoloMaterial, holoTexture, updateTilt } from "./shaders.js";
import { drawPack, drawBackFallback, roundedMask } from "./art.js";
import { createFx, makeSoftGlow, makeTearGuide, makeSpinner, starTexture } from "./fx.js";
import { Sound } from "./sound.js";

const $ = (id) => document.getElementById(id);
const params = new URLSearchParams(location.search);
const CARD_W = 0.095, CARD_H = CARD_W * 86 / 59;       // a real card is 5.9 × 8.6 cm: a bit bigger so it reads
const PACK_W = 0.2, PACK_H = PACK_W * 1110 / 640;
const HOLD = new THREE.Vector3(0, 0.03, 0.1);          // where the current card hovers (world-local)
const SHELF_SCALE = 0.85, PICKED_SCALE = 1.2;
const STAGE_TILT = -0.2;                               // the stage leans back a little, so it faces your eyes
const clamp01 = (x) => Math.min(1, Math.max(0, x));
const easeOut = (k) => 1 - Math.pow(1 - k, 3);
const easeInOut = (k) => (k < 0.5 ? 4 * k * k * k : 1 - Math.pow(-2 * k + 2, 3) / 2);
const easeOutBack = (k) => 1 + 2.70158 * Math.pow(k - 1, 3) + 1.70158 * Math.pow(k - 1, 2);
const TIER = P.TIER;
const rarityColor = (tier) => UI.THEME.rarity[tier];

const prefs = {
  get(k, d) { try { const v = localStorage.getItem("packvr." + k); return v == null ? d : v; } catch (e) { return d; } },
  set(k, v) { try { localStorage.setItem("packvr." + k, v); } catch (e) { /* private window */ } },
};
// Graphics: "sharp" renders the headset view at 1.25× and shades the edges less; "smooth" is lighter.
const QUALITY = { sharp: { scale: 1.25, foveation: 0.4 }, smooth: { scale: 1.0, foveation: 1.0 } };
const CONFIRM_S = Number(params.get("confirm")) || 3.5;   // how long "Sure?" waits for the second press (?confirm=30 for the slow emulator)
const FX_PINNED = params.has("fx") ? Math.max(0, Math.min(2, +params.get("fx"))) : null;   // ?fx=2 pins the effects level (tests)

const sound = new Sound();
sound.muted = prefs.get("muted", "0") === "1";
const state = { phase: "menu", sets: [], chosen: null, set: null, cards: [], pulls: [], idx: 0, rate: null, mode: null,
  year: null, shelfStart: 0, token: 0 };

// --- three.js setup ---------------------------------------------------------------------------------
const renderer = new THREE.WebGLRenderer({ canvas: $("gl"), antialias: true, alpha: true, premultipliedAlpha: true });
renderer.setClearColor(0x000000, 0);
renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
renderer.xr.enabled = true;
renderer.xr.setReferenceSpaceType("local-floor");
UI.setAnisotropy(renderer.capabilities.getMaxAnisotropy());
const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(55, 1, 0.02, 30);
camera.position.set(0, 1.45, 0);
const world = new THREE.Group();                       // everything lives here: placed in front of you
world.rotation.order = "YXZ";
scene.add(world);
const keep = new Set();                                // things that stay when a screen is cleared (menu bar, hint, effects)
const persist = (...objs) => objs.forEach((o) => { keep.add(o); world.add(o); });

function resize() {
  if (renderer.xr.isPresenting) return;                  // the headset decides the size while in a session
  const w = window.innerWidth, h = window.innerHeight;
  renderer.setSize(w, h, false);
  camera.aspect = w / h;
  camera.updateProjectionMatrix();
}
window.addEventListener("resize", resize);
renderer.xr.addEventListener("sessionend", resize);      // (the window may have changed size meanwhile)
resize();

const loader = new THREE.TextureLoader();
loader.setCrossOrigin("anonymous");
const loadImage = (url) => new Promise((res, rej) => loader.load(url, res, undefined, rej));

// --- Tweens ------------------------------------------------------------------------------------------
// Tweens belong to the screen that started them: clearing a screen (or skipping ahead) drops them.
let epoch = 0;
const tweens = [];
const transients = [];                                 // short-lived things (banners) removed with the tweens
function tween(dur, fn, done) { tweens.push({ t: 0, dur, fn, done, epoch }); }
const wait = (sec, fn) => tween(sec, () => {}, fn);
function transient(obj) { world.add(obj); transients.push(obj); return obj; }
function endTransient(obj) { const i = transients.indexOf(obj); if (i >= 0) transients.splice(i, 1); world.remove(obj); UI.disposeTree(obj); }
function cancelTweens() { epoch++; [...transients].forEach(endTransient); }

// --- Particles ---------------------------------------------------------------------------------------
const bursts = [];
/** A burst of light specks at [origin] (world-local). */
function burst(origin, { count = 120, speed = 0.9, gravity = 1.4, life = 1.4, size = 0.012, color = null, up = 0, map = null } = {}) {
  if (fx.level < 1) count = Math.ceil(count / 3);
  const pos = new Float32Array(count * 3), col = new Float32Array(count * 3), vel = [];
  const tmp = new THREE.Color();
  for (let i = 0; i < count; i++) {
    pos.set([origin.x, origin.y, origin.z], i * 3);
    const a = Math.random() * Math.PI * 2, e = (Math.random() - 0.35) * Math.PI, s = speed * (0.3 + Math.random() * 0.9);
    vel.push(new THREE.Vector3(Math.cos(a) * Math.cos(e) * s, Math.sin(e) * s + up, Math.sin(a) * Math.cos(e) * s));
    (color ? tmp.copy(color(i)) : tmp.setHSL(Math.random(), 0.85, 0.65)).toArray(col, i * 3);
  }
  const geo = new THREE.BufferGeometry();
  geo.setAttribute("position", new THREE.BufferAttribute(pos, 3));
  geo.setAttribute("color", new THREE.BufferAttribute(col, 3));
  const mat = new THREE.PointsMaterial({ size, map: map || UI.glowTexture(), vertexColors: true, transparent: true, depthWrite: false,
    blending: THREE.AdditiveBlending, sizeAttenuation: true });
  const pts = new THREE.Points(geo, mat);
  pts.frustumCulled = false;
  pts.renderOrder = 40;
  world.add(pts);
  bursts.push({ pts, vel, pos, life, age: 0, gravity });
}
const goldColor = () => new THREE.Color().setHSL(0.1 + Math.random() * 0.05, 0.9, 0.6 + Math.random() * 0.25);

// --- Haptics ------------------------------------------------------------------------------------------
function pulse(strength = 0.5, ms = 40, only = null) {
  for (const p of pointers) {
    if (only && p !== only) continue;
    try {
      const a = p.gamepad?.hapticActuators?.[0];
      if (a?.pulse) a.pulse(strength, ms);
      else p.gamepad?.vibrationActuator?.playEffect?.("dual-rumble", { duration: ms, strongMagnitude: strength, weakMagnitude: strength });
    } catch (e) { /* hands have no haptics */ }
  }
}

// --- Textures ----------------------------------------------------------------------------------------
const placeholder = (() => { const t = new THREE.DataTexture(new Uint8Array([24, 22, 30, 255]), 1, 1); t.needsUpdate = true; return holoTexture(t); })();
const cardTextures = new Map();                         // artwork id -> Promise<Texture>
function cardTexture(pull) {
  if (!cardTextures.has(pull.artworkId)) {
    cardTextures.set(pull.artworkId, loadImage(P.cardImageUrl(pull.artworkId)).then(holoTexture).catch(() => {
      // Couldn't download: a plain card with the name, so it's never blank.
      const c = document.createElement("canvas"); c.width = 400; c.height = 583;
      const x = c.getContext("2d"); x.fillStyle = "#33303c"; x.fillRect(0, 0, 400, 583);
      x.fillStyle = "#fff"; x.font = "bold 34px sans-serif"; x.textAlign = "center"; x.fillText(pull.name.slice(0, 22), 200, 300);
      return holoTexture(new THREE.CanvasTexture(c));
    }));
  }
  return cardTextures.get(pull.artworkId);
}
/** Frees the card pictures of older packs (the headset has limited memory); keeps the ids in [ids]. */
function trimTextures(ids) {
  for (const [id, job] of cardTextures) if (!ids.has(id)) { cardTextures.delete(id); job.then((t) => t.dispose()); }
}
let backTexture = null;
function getBackTexture() {
  if (!backTexture) {
    const fallback = new THREE.CanvasTexture(drawBackFallback());
    fallback.colorSpace = THREE.SRGBColorSpace;
    fallback.userData.shared = true;
    backTexture = fallback;
    loadImage(P.CARD_BACK_URL).then((t) => { backTexture.dispose(); backTexture.image = t.image; backTexture.needsUpdate = true; }).catch(() => {});
  }
  return backTexture;
}
const maskTexture = roundedMask();

// --- Set data (cards of a set + the wrapper art), cached --------------------------------------------
const setData = new Map();                              // set name -> Promise<{ cards, art }>, least recently used first
const SET_CACHE = 24;                                   // (the pictures take memory: keep the last couple of dozen sets)
function loadSetData(set) {
  let job = setData.get(set.name);
  if (job) { setData.delete(set.name); setData.set(set.name, job); return job; }      // used again: move it to the back
  job = (async () => {
    const cards = await P.loadSetCards(set);
    if (!P.canOpen(cards)) throw new Error("This set doesn't have enough cards to make a pack.");
    const f = P.featured(cards);
    const art = f ? await loadImage(P.croppedArtUrl(f.artworkId)).catch(() => null) : null;
    return { cards, art: art ? art.image : null };
  })();
  job.catch(() => setData.delete(set.name));
  setData.set(set.name, job);
  while (setData.size > SET_CACHE) setData.delete(setData.keys().next().value);
  return job;
}
/** Loads [sets] two at a time (stopping once [stale]() says nobody is waiting); onEach(set, data) gets each as it arrives. */
function prefetch(sets, onEach, stale) {
  const queue = [...sets];
  const work = async () => {
    while (queue.length && !stale()) {
      const s = queue.shift();
      try { onEach(s, await loadSetData(s)); } catch (e) { /* that set just keeps its plain wrapper */ }
    }
  };
  work(); work();
}

const fmt = (usd) => {
  if (usd == null) return "—";
  const n = state.rate ? usd * state.rate : usd;
  return (state.rate ? "R" : "$") + n.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
};
const pick = () => (state.mode === "flat" ? "Click" : "Pinch");        // wording for the mouse preview vs hands
const hold = () => (state.mode === "flat" ? "Press" : "Pinch");

// --- Panels: hint, card readout, progress dots, spinner ---------------------------------------------
const hint = UI.makePanel(0.38, 0.064, UI.drawHint);
hint.position.set(0, -0.225, 0.07);
const info = UI.makePanel(0.32, 0.07, UI.drawInfo);
info.position.set(0, -0.12, 0.1);
const pips = UI.makePanel(0.14, 0.014, UI.drawPips);
pips.position.set(0, 0.139, 0.1);
const spinner = makeSpinner(0.07);
spinner.position.set(0, 0.02, 0.07);
spinner.visible = false;
persist(hint, info, pips, spinner);
let hintKey = "";
function setHint(main = "", sub = "") {
  const key = main + "|" + sub;
  if (key === hintKey) return;
  hintKey = key;
  hint.userData.redraw({ main, sub });
}
function showInfo(pull) { info.userData.redraw({ name: pull.name, rarity: pull.rarity, price: fmt(pull.priceUsd), tier: pull.tier }); info.material.opacity = 0; info.userData.on = true; }
function hideInfo() { info.userData.redraw({}); info.userData.on = false; }
function setPips(at) { pips.userData.redraw(at == null ? {} : { n: cards.length, at }); }

// --- Fx ----------------------------------------------------------------------------------------------
const fx = createFx(world, HOLD);
keep.add(fx.group);
fx.setLevel(FX_PINNED ?? 2);

// --- Pointers (controllers, hands, or the mouse in the flat preview) ----------------------------------
const pointers = [];
const raycaster = new THREE.Raycaster();
const reticleGeo = new THREE.SphereGeometry(0.006, 12, 8);
function makePointer(obj, kind) {
  const reticle = new THREE.Mesh(reticleGeo, new THREE.MeshBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.85, depthTest: false }));
  reticle.renderOrder = 100; reticle.visible = false;
  scene.add(reticle);
  const p = { obj, kind, down: false, was: false, pendingDown: false, pendingUp: false, grab: null, tear: null, hit: null, hist: [], vel: new THREE.Vector3(), reticle, gamepad: null, hoverOwner: null, hoverTick: -9 };
  pointers.push(p);
  return p;
}
for (let i = 0; i < 2; i++) {
  const c = renderer.xr.getController(i);
  scene.add(c);
  const p = makePointer(c, "xr");
  p.index = i;
  c.addEventListener("selectstart", () => { p.down = true; p.pendingDown = true; });
  c.addEventListener("selectend", () => { p.down = false; p.pendingUp = true; });
  c.addEventListener("connected", (e) => { p.gamepad = e.data?.gamepad || null; p.isHand = !!e.data?.hand; });
  c.addEventListener("disconnected", () => { p.isHand = false; });
}
// Direct touch: with hand tracking you can reach out and pinch things where they float.
const handPtrs = [];
for (let i = 0; i < 2; i++) {
  const hand = renderer.xr.getHand(i);
  scene.add(hand);
  const anchor = new THREE.Object3D();                  // the pinch point (follows the wrist's turn)
  scene.add(anchor);
  const p = makePointer(anchor, "hand");
  Object.assign(p, { hand, index: i, near: null, tracked: false });
  handPtrs.push(p);
}
const mouseObj = new THREE.Object3D();
scene.add(mouseObj);
const mouse = makePointer(mouseObj, "mouse");
const ndc = new THREE.Vector2();
$("gl").addEventListener("pointermove", (e) => { ndc.set((e.clientX / innerWidth) * 2 - 1, -(e.clientY / innerHeight) * 2 + 1); });
$("gl").addEventListener("pointerdown", (e) => { ndc.set((e.clientX / innerWidth) * 2 - 1, -(e.clientY / innerHeight) * 2 + 1); mouse.down = true; mouse.pendingDown = true; });
window.addEventListener("pointerup", () => { if (mouse.down) { mouse.down = false; mouse.pendingUp = true; } });

const _o = new THREE.Vector3(), _d = new THREE.Vector3(), _n = new THREE.Vector3(), _g = new THREE.Vector3(), _pt = new THREE.Vector3();
/** Where the pointer's ray meets the plane of [group], in the group's own coordinates. */
function rayOnPlane(p, group) {
  p.obj.updateWorldMatrix(true, false);
  _o.setFromMatrixPosition(p.obj.matrixWorld);
  _d.set(0, 0, -1).transformDirection(p.obj.matrixWorld);
  group.updateWorldMatrix(true, false);
  _n.set(0, 0, 1).transformDirection(group.matrixWorld);
  _g.setFromMatrixPosition(group.matrixWorld);
  const denom = _n.dot(_d);
  if (Math.abs(denom) < 1e-4) return null;
  const t = _n.dot(_g.clone().sub(_o)) / denom;
  if (t < 0) return null;
  return group.worldToLocal(_pt.copy(_o).addScaledVector(_d, t));
}

/** The pointer's position on [group]'s plane, in the group's coordinates: the pinch point for a hand, else the ray. */
function pointerLocal(p, group) {
  if (p.kind === "hand") {
    p.obj.updateWorldMatrix(true, false);
    return group.worldToLocal(_pt.setFromMatrixPosition(p.obj.matrixWorld));
  }
  return rayOnPlane(p, group);
}

const _s = new THREE.Vector3(), _lp = new THREE.Vector3();
/** The closest target within reach of a pinch at [pos] (world coordinates), as a hit like a ray's. */
function nearestTarget(pos, targets) {
  let best = null;
  for (const mesh of targets) {
    const g = mesh.geometry.parameters;
    if (!g?.width) continue;
    mesh.updateWorldMatrix(true, false);
    const sc = mesh.getWorldScale(_s).x || 1;
    const lp = mesh.worldToLocal(_lp.copy(pos));
    const reachZ = 0.06 / sc, margin = 0.035 / sc;     // 6 cm in front of / behind it, 3.5 cm beyond its edges
    if (Math.abs(lp.z) > reachZ || Math.abs(lp.x) > g.width / 2 + margin || Math.abs(lp.y) > g.height / 2 + margin) continue;
    // Closest in the plane first (so neighbouring buttons don't fight), then closest in depth.
    const ex = Math.max(0, Math.abs(lp.x) - g.width / 2), ey = Math.max(0, Math.abs(lp.y) - g.height / 2);
    const score = (ex * ex + ey * ey) * sc * sc + Math.abs(lp.z) * sc * 0.02;
    if (!best || score < best.score) {
      best = { object: mesh, point: pos.clone(), uv: new THREE.Vector2(lp.x / g.width + 0.5, lp.y / g.height + 0.5), dz: Math.abs(lp.z), score };
    }
  }
  return best;
}

const _ja = new THREE.Vector3(), _jb = new THREE.Vector3(), _jm = new THREE.Vector3();
const jointPos = (hand, name, out) => { const j = hand.joints?.[name]; return j && j.visible ? j.getWorldPosition(out) : null; };

/** One hand: where its pinch is, what's within reach, and when it pinches (thumb and index finger meet). */
function updateHandPointer(p, targets) {
  const thumb = jointPos(p.hand, "thumb-tip", _ja), index = jointPos(p.hand, "index-finger-tip", _jb);
  p.tracked = !!(thumb && index);
  if (!p.tracked) {                                     // tracking lost: let go
    if (p.down) { p.down = false; p.pendingUp = true; }
    p.near = null; p.reticle.visible = false;
    return;
  }
  const mid = _jm.copy(thumb).add(index).multiplyScalar(0.5);
  p.obj.position.copy(mid);
  const wrist = p.hand.joints?.wrist;
  if (wrist?.visible) wrist.getWorldQuaternion(p.obj.quaternion);
  p.obj.updateMatrixWorld(true);
  const gap = thumb.distanceTo(index);
  if (!p.down) p.near = nearestTarget(mid, targets);
  if (!p.down && p.near && gap < 0.024) { p.down = true; p.pendingDown = true; p.hit = p.near; }
  else if (p.down && gap > 0.042) { p.down = false; p.pendingUp = true; }
  if (!p.down) p.hit = p.near;
  p.reticle.visible = true;
  p.reticle.position.copy(mid);
  p.reticle.scale.setScalar(p.down ? 1.1 : p.near ? 2 : 1);
  p.reticle.material.color.set(p.down ? 0xffd970 : p.near ? 0x66ddff : 0xffffff);
}

function grab(p, owner) {
  if (owner.held) return;
  owner.held = p; p.grab = owner;
  owner.returning = false;
  p.obj.attach(owner.group);                            // it now moves and turns with your hand
  pulse(0.25, 25, p);
}
function release(p) {
  const o = p.grab;
  if (!o) return;
  p.grab = null; o.held = null;
  world.attach(o.group);
  o.onRelease?.(p.vel.clone());
}
/** Lets go of anything in a hand without the usual follow-up (a screen is being replaced). */
function dropGrabs() {
  for (const p of pointers) {
    if (p.grab) { const o = p.grab; o.held = null; p.grab = null; world.attach(o.group); }
    p.tear = null;
  }
}

// What the pointers can hit right now.
const screenButtons = [];                               // buttons of the current screen (year chips, summary buttons)
function interactives() {
  const out = [];
  for (const b of allButtons()) if (b.interactive()) out.push(b.face);
  const ph = state.phase;
  if (ph === "shelf") shelf.forEach((s) => out.push(s.whole));
  else if (ph === "pack" && pack) out.push(pack.whole);
  else if (ph === "cards") { const c = cards[state.idx]; if (c && c.state === "revealed") out.push(c.front); }
  else if (ph === "summary") cards.forEach((c) => out.push(c.front));
  return out;
}

function updatePointers(dt) {
  if (!renderer.xr.isPresenting) {                       // flat preview: aim from the eye through the mouse
    raycaster.setFromCamera(ndc, camera);
    // A 'hand' 35 cm in front of the eye along the mouse ray (so a flick has a speed, like a thrown card).
    mouseObj.position.copy(raycaster.ray.origin).addScaledVector(raycaster.ray.direction, 0.35);
    mouseObj.quaternion.setFromUnitVectors(new THREE.Vector3(0, 0, -1), raycaster.ray.direction);   // a ray runs along -Z
    mouseObj.updateMatrixWorld(true);
  }
  const targets = interactives();
  for (const p of pointers) {
    const active = p.kind === "mouse" ? !renderer.xr.isPresenting : renderer.xr.isPresenting;
    if (!active) { p.reticle.visible = false; continue; }
    // A hand that is touching something uses direct pinching; its pointing ray stays out of the way.
    const touching = p.kind === "xr" && p.isHand && handPtrs[p.index]?.near;
    if (p.kind === "hand") updateHandPointer(p, targets);
    else {
      p.obj.updateWorldMatrix(true, false);
      _o.setFromMatrixPosition(p.obj.matrixWorld);
      _d.set(0, 0, -1).transformDirection(p.obj.matrixWorld);
      raycaster.set(_o, _d);
      const hit = touching ? null : raycaster.intersectObjects(targets, false)[0] || null;
      p.hit = hit;
      p.reticle.visible = !touching;
      p.reticle.position.copy(hit ? hit.point : _o.clone().addScaledVector(_d, 0.5));
      p.reticle.scale.setScalar(hit ? 1.4 : 0.7);
    }
    if (p.kind === "hand" && !p.tracked) {
      // Tracking lost mid-grab: let go now (not when the hand reappears, with the card frozen in the air).
      if (p.pendingUp || p.was) { onUp(p); p.pendingUp = false; p.was = false; }
      continue;
    }
    p.obj.updateWorldMatrix(true, false);
    _o.setFromMatrixPosition(p.obj.matrixWorld);

    // Hovering: whatever the pointer is over lights up, with a tick.
    const over = !p.grab && !p.tear ? p.hit?.object?.userData?.owner || null : null;
    if (over) over.hoverAt = elapsed;
    if (over !== p.hoverOwner) {
      p.hoverOwner = over;
      if (over && elapsed - p.hoverTick > 0.15) { p.hoverTick = elapsed; sound.ui("hover", 0.4); pulse(0.12, 8, p); }   // (not a chatter at an edge)
    }

    // Hand speed (for throwing)
    p.hist.push({ t: performance.now(), pos: _o.clone() });
    while (p.hist.length > 2 && performance.now() - p.hist[0].t > 120) p.hist.shift();
    if (p.hist.length > 1) {
      const a = p.hist[0], b = p.hist[p.hist.length - 1];
      p.vel.copy(b.pos).sub(a.pos).multiplyScalar(1000 / Math.max(1, b.t - a.t));
    }

    // Edges are latched by the events, so a tap shorter than a frame still counts.
    const downEdge = !touching && (p.pendingDown || (p.down && !p.was));
    const upEdge = p.pendingUp || (!p.down && p.was);
    if (downEdge) onDown(p);
    if (p.down) onDrag(p);
    if (upEdge) onUp(p);
    p.pendingDown = p.pendingUp = false;
    p.was = p.down;
  }
}

function onDown(p) {
  const h = p.hit;
  if (!h) return;
  const { kind, owner } = h.object.userData;
  if (kind === "shelfPack") choosePack(owner);
  else if (kind === "button") { if (owner.press()) { sound.ui("click"); pulse(0.4, 25, p); } }
  else if (kind === "card") grab(p, owner);
  else if (kind === "pack") {
    if (!owner.ready) { setHint("Still getting the cards ready…"); return; }
    const fromTop = 1 - h.uv.y;
    if (fromTop < 0.2 && !owner.torn) {                   // the top strip: tear it
      const lp = pointerLocal(p, owner.group);
      p.tear = { owner, x0: lp ? lp.x : 0, lastStep: 0 };
    } else { grab(p, owner); setHint("Let go to put it back"); }
  }
}
function onDrag(p) {
  if (!p.tear) return;
  const { owner } = p.tear;
  const lp = pointerLocal(p, owner.group);
  if (!lp || owner.torn) return;
  const prog = Math.min(1, Math.abs(lp.x - p.tear.x0) / (PACK_W * 0.7));
  if (prog > owner.tearP) {
    owner.tearP = prog;
    const step = Math.floor(prog * 10);
    if (step > p.tear.lastStep) { p.tear.lastStep = step; sound.play("tear", 0.7, 0.85 + Math.random() * 0.3); pulse(0.35, 20, p); }
    if (prog >= 1) rip(owner);
  }
}
function onUp(p) {
  p.tear = null;
  release(p);
}

// --- The menu bar (always at hand), pager and year chips ---------------------------------------------------
const DOCK = [["shelf", "shelf", "Shelf"], ["prev", "undo", "Previous"], ["skip", "skip", "Skip"], ["sets", "sets", "Sets"],
  ["recenter", "recenter", "Recenter"], ["sound", "sound", "Sound"], ["exit", "exit", "Exit"]];
const dock = new THREE.Group();
dock.position.set(0, -0.325, 0.12);
dock.rotation.x = -0.5;                                 // tilted up toward your eyes, like a tablet on a stand
persist(dock);
const dockBtn = {};
for (const [id, ic, label] of DOCK) {
  const b = UI.makeButton({ w: 0.072, h: 0.088, draw: UI.drawRound, state: { icon: ic, label }, onPress: () => dockAction(id) });
  b.show(false);
  dock.add(b.group);
  dockBtn[id] = b;
}
const pager = {
  l: UI.makeButton({ w: 0.072, h: 0.088, draw: UI.drawRound, state: { icon: "left", label: "Newer" }, onPress: () => buildShelf({ start: state.shelfStart - 5 }) }),
  r: UI.makeButton({ w: 0.072, h: 0.088, draw: UI.drawRound, state: { icon: "right", label: "Older" }, onPress: () => buildShelf({ start: state.shelfStart + 5 }) }),
};
pager.l.group.position.set(-0.25, -0.236, 0.07);
pager.r.group.position.set(0.25, -0.236, 0.07);
pager.l.show(false); pager.r.show(false);
persist(pager.l.group, pager.r.group);
const allButtons = () => [...Object.values(dockBtn), pager.l, pager.r, ...screenButtons];

let shelfArmed = 0, exitArmed = 0;                      // "tap again to confirm" windows (seconds on the clock)
function dockAction(id) {
  switch (id) {
    case "shelf": {
      if (state.phase === "cards" && !(elapsed < shelfArmed)) { shelfArmed = elapsed + CONFIRM_S; sound.ui("warn"); return; }   // mid-pack: ask first
      shelfArmed = 0; goShelf(); break;
    }
    case "prev": previousCard(); break;
    case "skip": skipToSummary(); break;
    case "sets": openYears(); break;
    case "recenter": placeWorld(); break;
    case "sound":
      sound.muted = !sound.muted; prefs.set("muted", sound.muted ? "1" : "0"); $("mute").checked = sound.muted;
      if (!sound.muted) sound.ui("click");
      break;
    case "exit":
      if (!(elapsed < exitArmed)) { exitArmed = elapsed + CONFIRM_S; sound.ui("warn"); return; }
      exitArmed = 0; leave(); break;
  }
}
const setIf = (b, patch) => { for (const k in patch) if (b.st[k] !== patch[k]) { b.set(patch); return; } };

/** Which menu-bar buttons make sense right now, and how they look. Cheap enough to run every frame. */
function refreshDock(dt) {
  const ph = state.phase, inCards = ph === "cards", cur = cards[state.idx];
  const want = {
    shelf: ph === "years" || ph === "loading" || ph === "pack" || inCards,
    prev: inCards, skip: inCards,
    sets: ph === "shelf" || ph === "summary",
    recenter: ph !== "menu", sound: ph !== "menu", exit: ph !== "menu",
  };
  if (elapsed > shelfArmed) shelfArmed = 0;
  if (elapsed > exitArmed) exitArmed = 0;
  setIf(dockBtn.shelf, ph === "years" ? { icon: "back", label: "Back", tone: "glass" }
    : shelfArmed ? { icon: "shelf", label: "Sure?", tone: "danger" } : { icon: "shelf", label: "Shelf", tone: "glass" });
  setIf(dockBtn.exit, exitArmed ? { label: "Sure?", tone: "danger" } : { label: "Exit", tone: "glass" });
  setIf(dockBtn.sound, sound.muted ? { icon: "mute", label: "Muted" } : { icon: "sound", label: "Sound" });
  dockBtn.prev.setEnabled(inCards && state.idx > 0 && cur?.state === "revealed" && !cur.held);
  const visible = DOCK.map(([id]) => id).filter((id) => want[id]);
  for (const [id] of DOCK) {
    const b = dockBtn[id];
    b.show(!!want[id]);
    if (want[id]) b.tx = (visible.indexOf(id) - (visible.length - 1) / 2) * 0.078;
    if (b.tx != null) b.group.position.x = b.showK < 0.05 ? b.tx : b.group.position.x + (b.tx - b.group.position.x) * (1 - Math.exp(-dt * 12));
  }
  const list = viewList(), paged = ph === "shelf" && list.length > 5;
  pager.l.show(paged); pager.r.show(paged);
  pager.l.setEnabled(state.shelfStart > 0); pager.r.setEnabled(state.shelfStart + 5 < list.length);
}

// --- Pack --------------------------------------------------------------------------------------------
let pack = null;
const shelf = [];
let packGeo = null;
function packGeometry() {
  if (packGeo) return packGeo;
  packGeo = new THREE.PlaneGeometry(PACK_W, PACK_H, 20, 40);
  packGeo.userData.shared = true;
  const pos = packGeo.attributes.position;
  for (let i = 0; i < pos.count; i++) {                  // a puffy foil bag, flat at the crimped ends
    const u = pos.getX(i) / PACK_W + 0.5, v = 1 - (pos.getY(i) / PACK_H + 0.5);
    const t = clamp01((v - 0.085) / (0.93 - 0.085));
    const inBody = v > 0.085 && v < 0.93;
    pos.setZ(i, inBody ? 0.026 * Math.sqrt(Math.max(Math.sin(Math.PI * u), 0)) * Math.sqrt(Math.max(Math.sin(Math.PI * t), 0)) : 0);
  }
  return packGeo;
}

function makePack(set) {
  const group = new THREE.Group();
  const tex = holoTexture(new THREE.CanvasTexture(drawPack(set, null)));
  const mat = makeHoloMaterial({ mode: P.MODE.PARALLEL, art: [0, 0, 1, 1], pack: true });
  mat.uniforms.uImage.value = tex;
  mat.uniforms.texel.value.set(1 / 640, 1 / 1110);
  const whole = new THREE.Mesh(packGeometry(), mat);
  const obj = { group, whole, mat, set, tearP: 0, torn: false, ready: false, held: null, tex, hoverAt: -9, hoverK: 0, hasArt: false, returning: false };
  whole.userData = { kind: "shelfPack", owner: obj };
  const glow = makeSoftGlow(PACK_W * 1.42, PACK_H * 1.26, 0x9fd8ff);
  glow.position.z = -0.004;
  const tag = UI.makePanel(0.26, 0.052, UI.drawTag);
  tag.userData.redraw({ name: set.name, sub: `${set.code} · ${set.date.slice(0, 4)}` });
  tag.position.set(0, -PACK_H / 2 - 0.04, 0.012);
  const guide = makeTearGuide(PACK_W * 0.9);
  guide.position.set(0, PACK_H * (0.5 - 0.115), 0.034);
  group.add(glow, whole, tag, guide);
  Object.assign(obj, { glow, tag, guide });
  world.add(group);
  return obj;
}

function setPackArt(p, img) {
  const next = holoTexture(new THREE.CanvasTexture(drawPack(p.set, img)));
  p.mat.uniforms.uImage.value = next;
  p.tex.dispose();
  p.tex = next;
  p.hasArt = true;
}

let viewKey = null, viewSets = null, viewCache = [];
/** The sets the shelf pages through: all of them, or one year's (cached: the menu bar asks every frame). */
function viewList() {
  if (viewSets !== state.sets || viewKey !== state.year) {
    viewSets = state.sets; viewKey = state.year;
    viewCache = state.year ? state.sets.filter((s) => s.date.slice(0, 4) === state.year) : state.sets;
  }
  return viewCache;
}

/** The shelf: five packs on an arc, a window onto the (newest first) list of sets. */
function buildShelf({ focus = null, start = null } = {}) {
  state.token++;
  clearScene();
  setPhase("shelf");
  fx.tint(0x6a4cff, 0.1);
  const token = state.token;
  const list = viewList();
  let s0 = start;
  if (s0 == null) { const at = focus ? list.findIndex((s) => s.name === focus.name) : -1; s0 = at < 0 ? 0 : at - 2; }
  s0 = Math.max(0, Math.min(s0, Math.max(0, list.length - 5)));
  state.shelfStart = s0;
  const sets = list.slice(s0, s0 + 5), n = sets.length;
  sets.forEach((set, i) => {
    const x = (i - (n - 1) / 2) * 0.27;
    const p = makePack(set);
    p.baseX = x; p.baseZ = -Math.abs(x) * 0.22;
    p.group.position.set(p.baseX, 0, p.baseZ + 0.4);
    p.group.rotation.y = Math.atan2(-x, 0.6 - p.baseZ);
    p.group.scale.setScalar(0.001);
    tween(0.6 + i * 0.1, (k) => { p.group.scale.setScalar(SHELF_SCALE * easeOut(clamp01(k))); p.group.position.z = p.baseZ + 0.4 * (1 - easeOut(clamp01(k))); });
    shelf.push(p);
  });
  const label = state.year ? `Sets from ${state.year}` : "Newest sets first";
  setHint(`${pick()} a pack to pick it`, `${label} · ${s0 + 1}–${s0 + n} of ${list.length}`);
  hideInfo(); setPips(null);
  // The real artwork on the wrappers, middle ones first, as it arrives.
  const order = shelf.slice().sort((a, b) => Math.abs(shelf.indexOf(a) - (n - 1) / 2) - Math.abs(shelf.indexOf(b) - (n - 1) / 2));
  prefetch(order.map((s) => s.set), (set, data) => {
    const p = shelf.find((s) => s.set.name === set.name);
    if (token === state.token && p && !p.hasArt && data.art) setPackArt(p, data.art);
  }, () => token !== state.token);                        // another screen took over: stop fetching for this one
}

function clearScene() {
  dropGrabs();
  cancelTweens();
  for (const g of [...world.children]) {
    if (keep.has(g)) continue;
    world.remove(g);
    UI.disposeTree(g);
  }
  for (const p of [...shelf, ...(pack ? [pack] : [])]) { UI.disposeTree(p.group); p.tex?.dispose(); p.mat.dispose(); }
  shelf.length = 0; cards.length = 0; pack = null; screenButtons.length = 0;
  bursts.length = 0;
  spinner.visible = false;
  state.idx = 0;
}

function setPhase(p) { state.phase = p; }

// --- Picking a pack, going back -----------------------------------------------------------------------
function choosePack(chosen) {
  if (state.phase !== "shelf") return;
  const token = ++state.token;
  setPhase("loading");
  state.set = chosen.set;
  sound.ui("pick"); sound.play("twinkle", 0.6);
  pulse(0.3, 30);
  shelf.forEach((s) => { if (s !== chosen) tween(0.5, (k) => s.group.scale.setScalar(SHELF_SCALE * (1 - easeInOut(k))), () => world.remove(s.group)); });
  const from = chosen.group.position.clone(), fromRot = chosen.group.rotation.y;
  const moved = new Promise((res) => tween(0.6, (k) => {
    const e = easeInOut(k);
    chosen.group.position.lerpVectors(from, new THREE.Vector3(0, 0, 0), e);
    chosen.group.rotation.y = fromRot * (1 - e);
    chosen.group.scale.setScalar(SHELF_SCALE + (PICKED_SCALE - SHELF_SCALE) * e);
  }, res));
  chosen.tag.visible = false;
  chosen.glow.material.color.set(0xffd970);
  chosen.whole.userData.kind = "pack";
  pack = chosen;
  beginPack(chosen, token, moved);
}

/** Another pack of the same set, straight away (from the summary). */
function openAnother() {
  if (state.phase !== "summary") return;
  const token = ++state.token;
  const set = state.set;
  clearScene();
  setPhase("loading");
  const p = makePack(set);
  p.tag.visible = false;
  p.whole.userData.kind = "pack";
  p.group.scale.setScalar(0.001);
  pack = p;
  sound.play("twinkle", 0.5);
  const arrived = new Promise((res) => tween(0.55, (k) => {
    const e = easeOut(k);
    p.group.scale.setScalar(PICKED_SCALE * e);
    p.group.position.set(0, 0.12 * (1 - e), 0.25 * (1 - e));
    p.group.rotation.y = (1 - e) * 1.5;
  }, res));
  beginPack(p, token, arrived);
}

/** Gets the cards of [p]'s set and rolls the pack; ready to tear once that and [arrived] are done. */
function beginPack(p, token, arrived) {
  p.onRelease = () => { p.returning = true; setHint(`${hold()} the top edge and drag across to tear it open`, "Let go early to close it again"); };
  fx.tint(0xffd970, 0.12);
  spinner.visible = true;
  setHint(`Opening ${p.set.name}…`, "Getting the cards ready");
  hideInfo(); setPips(null);
  Promise.all([loadSetData(p.set), arrived]).then(([data]) => {
    if (token !== state.token) return;
    const before = state.pulls;
    state.cards = data.cards;
    state.pulls = P.openPack(data.cards);
    trimTextures(new Set([...state.pulls, ...before].map((c) => c.artworkId)));
    state.pulls.forEach(cardTexture);                     // start downloading the pulled cards now
    if (data.art && !p.hasArt) setPackArt(p, data.art);
    p.ready = true;
    spinner.visible = false;
    setPhase("pack");
    setHint(`${hold()} the top edge and drag across to tear it open`, "Let go early to close it again");
  }).catch((e) => {
    if (token !== state.token) return;
    spinner.visible = false;
    setHint(`Couldn't load that set: ${e.message}`);
    wait(3, () => { if (token === state.token) buildShelf({ focus: state.set }); });
  });
}

/** Back to the shelf from wherever you are. */
function goShelf() {
  const ph = state.phase;
  if (ph === "years") { buildShelf({ start: state.shelfStart }); return; }
  if (!["loading", "pack", "cards", "summary"].includes(ph)) return;
  const token = ++state.token;                            // also abandons a pack that is still loading
  dropGrabs();
  setPhase("leaving");
  fx.rays(false);
  hideInfo(); setPips(null); setHint("");
  spinner.visible = false;
  const gone = [pack, ...cards.map((c) => ({ group: c.group, base: c.group.scale.x }))].filter(Boolean);
  gone.forEach((o) => { const base = o.base ?? o.group.scale.x; tween(0.3, (k) => o.group.scale.setScalar(Math.max(0.001, base * (1 - easeInOut(k))))); });
  sound.play("whoosh", 0.35, 1.4);
  wait(0.32, () => { if (token === state.token) buildShelf({ focus: state.set }); });
}

/** The "Sets" screen: jump to a year. */
function openYears() {
  if (state.phase !== "shelf" && state.phase !== "summary") return;
  state.token++;
  clearScene();
  setPhase("years");
  fx.rays(false); fx.tint(0x6a4cff, 0.1);
  hideInfo(); setPips(null);
  setHint("Pick a year to see its sets", "Newest first within each year");
  const years = [...new Set(state.sets.map((s) => s.date.slice(0, 4)).filter(Boolean))].sort().reverse();
  // "All sets" on a row of its own, then the years in rows of nine (newest first).
  const cols = 9, w = 0.056, h = 0.038, gx = 0.062, gy = 0.048;   // (chips big enough to pinch)
  const yearRows = Math.ceil(years.length / cols);
  const yTop = 0.05 + (yearRows * gy + 0.012) / 2;
  const title = UI.makePanel(0.3, 0.05, UI.drawTitle);
  title.userData.redraw({ text: "Jump to a year", sub: `${state.sets.length} sets, ${years[years.length - 1]}–${years[0]}` });
  title.position.set(0, yTop + 0.065, 0.06);
  world.add(title);
  const items = [{ text: "All sets", year: null, x: 0, y: yTop, w: 0.14 },
    ...years.map((y, i) => {
      const r = Math.floor(i / cols), inRow = Math.min(cols, years.length - r * cols);
      return { text: y, year: y, x: ((i % cols) - (inRow - 1) / 2) * gx, y: yTop - 0.012 - (r + 1) * gy, w };
    })];
  items.forEach((it, i) => {
    const b = UI.makeButton({ w: it.w, h, draw: UI.drawChip, state: { text: it.text, active: state.year === it.year },
      onPress: () => { state.year = it.year; sound.ui("pick"); buildShelf({ start: 0 }); } });
    b.group.position.set(it.x, it.y, 0.06);
    b.show(false);
    world.add(b.group);
    screenButtons.push(b);
    wait(i * 0.012, () => b.show(true));
  });
}

function rip(p) {
  if (p.torn) return;
  p.torn = true;
  state.phase = "ripping";
  setHint("");
  p.whole.visible = false; p.glow.visible = false; p.guide.visible = false;
  const mk = (mode) => { const m = p.mat.clone(); m.uniforms.uTearMode.value = mode; m.uniforms.uTearProgress.value = 0; return new THREE.Mesh(packGeometry(), m); };
  const strip = mk(1), rest = mk(2);
  p.group.add(strip, rest);
  const edge = new THREE.Vector3(0, PACK_H * (0.5 - 0.115), 0.03).add(p.group.position);
  sound.play("rip", 1); sound.play("shimmer", 0.55);
  pulse(1, 120);
  fx.flash(0.55, 0xffffff); fx.shock(edge, 0xffffff, 0.55); fx.shock(edge, 0xffd970, 0.85, 0.85);
  burst(edge, { count: 200, speed: 1.1, gravity: 1.2, life: 1.6, size: 0.014, up: 0.6 });
  burst(edge, { count: 40, speed: 0.8, gravity: 0.6, life: 1.6, size: 0.02, up: 0.5, map: starTexture(), color: goldColor });
  tween(0.9, (k) => {
    const e = easeOut(k);
    strip.position.set(e * 0.18, e * 0.12, e * 0.08); strip.rotation.z = -e * 0.9; strip.rotation.x = e * 0.5;
    strip.material.uniforms.uFade.value = clamp01((k - 0.55) / 0.45);
    rest.position.y = -k * k * 0.5; rest.rotation.x = k * 0.5;
    rest.material.uniforms.uFade.value = clamp01((k - 0.3) / 0.7);
    for (const m of [strip, rest]) m.material.uniforms.tilt.value.copy(p.mat.uniforms.tilt.value);
  }, () => { world.remove(p.group); UI.disposeTree(p.group); });
  wait(0.45, () => spawnCards());
}

// --- Cards -------------------------------------------------------------------------------------------
const cards = [];
const GLOW = [0, 0.2, 0.34, 0.5, 0.62, 0.72];            // how strongly a card of each tier glows
const SIDE_COLOR = new THREE.Color(0x9fd8ff);

function makeCard(pull, index) {
  const group = new THREE.Group(), inner = new THREE.Group();
  group.add(inner);
  const geo = new THREE.PlaneGeometry(CARD_W, CARD_H);
  const mat = makeHoloMaterial({ mode: P.foilMode(pull.rarity), art: P.artRect(pull.type.toLowerCase().includes("pendulum")) });
  mat.uniforms.uImage.value = placeholder;
  mat.depthWrite = true;                                  // so cards in a stack hide the ones behind them
  const front = new THREE.Mesh(geo, mat);
  front.position.z = 0.0006;
  const back = new THREE.Mesh(geo, new THREE.MeshBasicMaterial({ map: getBackTexture(), alphaMap: maskTexture, transparent: true, alphaTest: 0.5, depthWrite: true }));
  back.rotation.y = Math.PI; back.position.z = -0.0006;
  const glow = makeSoftGlow(CARD_W * 1.58, CARD_H * 1.4, rarityColor(pull.tier));
  glow.position.z = -0.004;
  group.add(glow);
  inner.add(front, back);
  const card = { pull, group, inner, front, back, mat, backMat: back.material, glow, glowColor: new THREE.Color(rarityColor(pull.tier)),
    state: "stack", held: null, index, faded: 0, hoverAt: -9, hoverK: 0, seen: false, glowOn: false, baseScale: 1, holdScale: 1, introDone: false };
  front.userData = { kind: "card", owner: card };
  cardTexture(pull).then((t) => { mat.uniforms.uImage.value = t; mat.uniforms.texel.value.set(1 / (t.image?.width || 813), 1 / (t.image?.height || 1185)); });
  world.add(group);
  return card;
}
const setFade = (c, f) => { c.faded = f; c.mat.uniforms.uFade.value = f; c.backMat.opacity = 1 - f; };
const STACK_AT = (i, cur) => new THREE.Vector3(0, 0, -0.03 - 0.004 * (i - cur));

function spawnCards() {
  state.phase = "cards";
  fx.tint(0x6a4cff, 0.08);
  state.pulls.forEach((pull, i) => {
    const c = makeCard(pull, i);
    c.faceDown = pull.tier >= TIER.RARE;
    c.inner.rotation.y = Math.PI;                         // all start face-down in the stack
    c.group.position.set(0, 0, -0.02 - 0.004 * (state.pulls.length - i));
    cards.push(c);
  });
  showCard(0);
}

/** The cards behind [cur] settle into a neat stack. */
function restack(cur) {
  cards.forEach((o, j) => {
    if (j <= cur) return;
    const to = STACK_AT(j, cur), from = o.group.position.clone();
    tween(0.4, (k) => o.group.position.lerpVectors(from, to, easeOut(k)));
  });
}

function showCard(i) {
  state.idx = i;
  const c = cards[i];
  c.state = "intro";
  setHint("");
  hideInfo();
  setPips(i);
  fx.tint(0x6a4cff, 0.08);
  restack(i);
  const from = c.group.position.clone(), fromRot = c.inner.rotation.y;
  tween(0.5, (k) => {
    c.group.position.lerpVectors(from, HOLD, easeOut(k));
    if (c.seen) c.inner.rotation.y = fromRot * (1 - easeOut(k));         // a card you've seen turns face up as it comes
  }, () => {
    if (c.seen) reveal(c, true);
    else if (c.faceDown) wait(0.45, () => flipCard(c));
    else reveal(c);
  });
}

function flipCard(c) {
  const pull = c.pull, big = pull.tier >= TIER.SUPER, ultra = pull.tier >= TIER.ULTRA;
  const build = big ? (pull.tier >= TIER.SECRET ? 1.1 : 0.65) : 0;
  c.state = "flipping";
  if (big) {
    sound.play("buildup", 0.7, 1.3 / build); pulse(0.5, build * 1000); fx.flash(0.25, rarityColor(pull.tier));
    c.glowOn = true; c.tease = true;                       // the card glows in its rarity's colour before it turns over
  }
  tween(build || 0.01, (k) => { c.inner.rotation.z = Math.sin(k * 40) * 0.04 * k; }, () => {
    c.inner.rotation.z = 0;
    c.tease = false;
    sound.play("flip", 0.8);
    const span = ultra ? 1.15 : 0.35, total = ultra ? Math.PI * 3 : Math.PI;     // Ultra and up spin round 1½ times
    tween(span, (k) => { c.inner.rotation.y = Math.PI - total * easeOut(k); c.group.scale.setScalar(1 + (ultra ? 0.12 * Math.sin(k * Math.PI) : 0)); }, () => {
      c.inner.rotation.y = 0; c.group.scale.setScalar(1);
      reveal(c);
    });
  });
}

/** A big word that pops above the card (the rarity). */
function banner(text, color, at) {
  const b = UI.makePanel(0.36, 0.055, UI.drawBanner, { order: 56 });
  b.userData.redraw({ text, color });
  b.position.copy(at);
  b.material.opacity = 0;
  transient(b);
  tween(2.4, (k) => {
    const inK = clamp01(k / 0.12), out = clamp01((k - 0.78) / 0.22);
    b.scale.setScalar(0.55 + 0.45 * easeOutBack(inK));
    b.material.opacity = inK * (1 - out);
    b.position.y = at.y + 0.025 * k;
  }, () => endTransient(b));
}

function reveal(c, quiet = false) {
  c.inner.rotation.y = 0;
  c.state = "revealed";
  c.seen = true;
  const pull = c.pull, t = pull.tier, col = rarityColor(t);
  showInfo(pull);
  setHint(state.idx + 1 >= cards.length ? "Throw the card to see everything you pulled" : "Grab the card to tilt it · throw it away for the next");
  setPips(state.idx);
  c.glowOn = t >= TIER.RARE;
  fx.rays(t >= TIER.ULTRA, col);
  if (t >= TIER.SUPER) fx.tint(col, 0.09);
  if (!quiet) {
    const at = HOLD.clone();
    if (t >= TIER.SUPER) {
      sound.play(t >= TIER.ULTRA ? "reveal_big" : "reveal", 0.9);
      pulse(t >= TIER.ULTRA ? 0.9 : 0.5, t >= TIER.ULTRA ? 250 : 120);
      const tint = () => new THREE.Color(col).lerp(new THREE.Color("#fff"), Math.random() * 0.6);
      burst(at, { count: t >= TIER.ULTRA ? 140 : 70, speed: 0.55, gravity: 0.2, life: 1.5, size: 0.011, color: tint });
      burst(at, { count: t >= TIER.ULTRA ? 36 : 14, speed: 0.5, gravity: 0.1, life: 1.7, size: 0.022, color: tint, map: starTexture() });
      fx.shock(new THREE.Vector3(at.x, at.y, at.z - 0.01), col, t >= TIER.ULTRA ? 0.6 : 0.4);
      fx.flash(t >= TIER.ULTRA ? 0.4 : 0.22, col);
      banner(pull.rarity.toUpperCase(), col, new THREE.Vector3(0, HOLD.y + CARD_H / 2 + 0.07, HOLD.z + 0.02));
    } else if (t >= TIER.RARE) sound.play("twinkle", 0.4);
    if (t >= TIER.SECRET) burst(new THREE.Vector3(0, 0.3, 0.1), { count: 160, speed: 0.5, gravity: 0.9, life: 2.4, size: 0.012, color: goldColor, up: 0.2 });
  }
  c.onRelease = (vel) => {
    const d = c.group.position.distanceTo(HOLD);
    if (vel.length() > 0.9 || d > 0.25) throwCard(c, vel);   // a flick, or carried well away
    else c.state = "returning";
  };
}

function throwCard(c, vel) {
  c.state = "thrown";
  hideInfo();
  setHint("");
  fx.rays(false);
  sound.play("whoosh", 0.6, 0.9 + Math.random() * 0.2);
  const v = vel.clone();
  v.applyQuaternion(world.getWorldQuaternion(new THREE.Quaternion()).invert());
  if (v.length() < 0.8) v.set((Math.random() < 0.5 ? -1 : 1) * 1.2, 0.3, 0.2);
  c.side = Math.sign(v.x) || 1;
  const start = c.group.position.clone(), spin = (Math.random() - 0.5) * 6;
  tween(0.55, (k) => {
    c.group.position.copy(start).addScaledVector(v, k * 0.55);
    c.group.rotation.z = spin * k;
    setFade(c, clamp01((k - 0.3) / 0.7));
  }, () => {
    c.group.visible = false;
    c.state = "gone";
    if (state.idx + 1 < cards.length) showCard(state.idx + 1); else summary();
  });
}

/** Undo the last throw: the card you just sent away comes back, and the one in front goes back on the pile. */
function previousCard() {
  const cur = cards[state.idx];
  if (state.phase !== "cards" || state.idx === 0 || !cur || cur.state !== "revealed" || cur.held) return;
  const prev = cards[state.idx - 1];
  hideInfo(); setHint("");
  fx.rays(false);
  sound.play("flip", 0.5, 1.2);
  cur.state = "stack";
  cur.glowOn = false;
  const from = cur.group.position.clone(), to = STACK_AT(state.idx, state.idx - 1), fromRot = cur.inner.rotation.y;
  tween(0.35, (k) => { const e = easeOut(k); cur.group.position.lerpVectors(from, to, e); cur.inner.rotation.y = fromRot + (Math.PI - fromRot) * e; });
  state.idx -= 1;
  restack(state.idx);
  setPips(state.idx);
  prev.group.visible = true; prev.group.rotation.set(0, 0, 0); prev.group.scale.setScalar(1);
  prev.inner.rotation.set(0, 0, 0); setFade(prev, 0);
  const start = new THREE.Vector3((prev.side || 1) * 0.3, 0.1, 0.18);
  prev.state = "intro";
  tween(0.5, (k) => { prev.group.position.lerpVectors(start, HOLD, easeOut(k)); }, () => reveal(prev, true));
}

/** Jump to the summary (the nine cards laid out) from the middle of a pack. */
function skipToSummary() {
  if (state.phase !== "cards") return;
  summary();
}

// --- Summary -----------------------------------------------------------------------------------------
function summary() {
  cancelTweens();                                         // whatever was still flying
  dropGrabs();
  setPhase("summary");
  fx.rays(false); fx.tint(0xffd970, 0.1);
  hideInfo(); setPips(null);
  const order = cards.slice().reverse();                  // best first
  const total = state.pulls.reduce((a, p) => a + (p.priceUsd || 0), 0);
  const gx = -0.13, gy = 0.02, dx = 0.088, dy = 0.122;
  order.forEach((c, i) => {
    const col = i % 3, row = Math.floor(i / 3);
    const home = new THREE.Vector3(gx + (col - 1) * dx, gy + (1 - row) * dy, 0.02);
    Object.assign(c, { home, baseScale: 0.78, holdScale: 1.3, introDone: false, held: null, tease: false, glowOn: c.pull.tier >= TIER.RARE });
    c.group.visible = true; c.group.rotation.set(0, 0, 0); c.group.scale.setScalar(0.001); setFade(c, 0);
    c.inner.rotation.set(0, 0, 0);
    c.group.position.copy(HOLD);
    c.state = "summary";
    wait(i * 0.07, () => tween(0.5, (k) => { c.group.position.lerpVectors(HOLD, home, easeOut(k)); c.group.scale.setScalar(0.78 * easeOut(k)); }, () => { c.introDone = true; }));
    c.onRelease = () => { c.state = "returning"; };
  });
  // The plate beside the cards, and what to do next.
  const counts = [];
  for (const pull of state.pulls.slice().sort((a, b) => a.tier - b.tier)) {
    const last = counts[counts.length - 1];
    if (last && last.label === pull.rarity) last.n++; else counts.push({ label: pull.rarity, tier: pull.tier, n: 1 });
  }
  const bestPull = state.pulls.reduce((a, p) => (!a || p.tier > a.tier || (p.tier === a.tier && (p.priceUsd || 0) > (a.priceUsd || 0)) ? p : a), null);
  const side = UI.makePanel(0.27, 0.205, UI.drawSummary);
  side.userData.redraw({ set: state.set.name, total: fmt(total), best: bestPull && { name: bestPull.name, rarity: bestPull.rarity, tier: bestPull.tier }, counts });
  side.position.set(0.16, 0.094, 0.03);
  world.add(side);
  const again = UI.makeButton({ w: 0.25, h: 0.05, draw: UI.drawPill, glow: UI.THEME.gold, state: { text: "Open another pack", icon: "again", tone: "primary" }, onPress: openAnother });
  again.group.position.set(0.16, -0.045, 0.03);
  const toShelf = UI.makeButton({ w: 0.25, h: 0.042, draw: UI.drawPill, state: { text: "Back to shelf", icon: "shelf" }, onPress: goShelf });
  toShelf.group.position.set(0.16, -0.1, 0.03);
  for (const b of [again, toShelf]) { b.show(false); world.add(b.group); screenButtons.push(b); }
  wait(0.7, () => again.show(true));
  wait(0.85, () => toShelf.show(true));
  setHint(`${hold()} any card to look closer`, "Open another pack of this set, or go back to the shelf");
  sound.play("shimmer", 0.4);
  fx.flash(0.3, 0xffd970);
  if (bestPull && bestPull.tier >= TIER.SUPER) burst(new THREE.Vector3(gx, 0.1, 0.1), { count: 60, speed: 0.5, gravity: 0.2, life: 1.6, size: 0.011, color: goldColor, map: starTexture() });
}

// --- Per-frame ---------------------------------------------------------------------------------------
const head = new THREE.Vector3(), headQuat = new THREE.Quaternion();
let lastTime = 0, elapsed = 0;
let needsPlace = false;
const perf = { ema: 1 / 72, t: 0, slow: 0 };
const PACK_HOME = new THREE.Vector3();

const _eye = new THREE.Vector3();
/**
 * Reads where your head is (between the eyes) and which way it faces. On the headset that's the pose of this
 * frame's views (the plain `camera` only gets it when rendering, a frame late); in the preview it's the
 * camera. Returns false until the headset has reported a pose.
 */
function readHead() {
  if (renderer.xr.isPresenting) {
    const views = renderer.xr.getCamera().cameras;
    if (!views.length) return false;
    head.set(0, 0, 0);
    for (const v of views) { v.updateMatrixWorld(true); head.add(_eye.setFromMatrixPosition(v.matrixWorld)); }
    head.divideScalar(views.length);
    headQuat.setFromRotationMatrix(views[0].matrixWorld);
    return true;
  }
  camera.updateMatrixWorld(true);
  head.setFromMatrixPosition(camera.matrixWorld);
  headQuat.setFromRotationMatrix(camera.matrixWorld);
  return true;
}

function placeWorld() {
  readHead();
  const fwd = new THREE.Vector3(0, 0, -1).applyQuaternion(headQuat);
  fwd.y = 0;
  if (fwd.lengthSq() < 1e-4) fwd.set(0, 0, -1);
  fwd.normalize();
  world.position.copy(head).addScaledVector(fwd, 0.55);   // within arm's reach
  world.position.y = head.y - 0.2;
  world.rotation.set(STAGE_TILT, Math.atan2(-fwd.x, -fwd.z), 0);    // its +Z faces you (leaning back a little)
  world.updateMatrixWorld(true);
}

const smooth = (cur, target, dt, rate) => cur + (target - cur) * (1 - Math.exp(-dt * rate));

function frame(now = performance.now()) {
  const dt = Math.min(Math.max((now - (lastTime || now)) / 1000, 0), 0.05);
  lastTime = now; elapsed += dt;
  const time = elapsed;
  const posed = readHead();
  if (needsPlace && state.mode && posed) { placeWorld(); needsPlace = false; beginExperience(); }

  // On the headset, thin out the extras if it isn't holding its frame rate.
  if (renderer.xr.isPresenting && FX_PINNED == null) {
    perf.ema = smooth(perf.ema, dt, dt, 1.5);
    perf.t += dt;
    if (perf.t > 3) {                                   // every 3 s: slow twice in a row (not just a loading hitch) = thin out
      perf.t = 0;
      if (perf.ema > 1 / 52) { if (++perf.slow >= 2 && fx.level > 0) { fx.setLevel(fx.level - 1); perf.slow = 0; } } else perf.slow = 0;
    }
  }

  updatePointers(dt);

  for (const t of [...tweens]) {
    if (t.epoch !== epoch) { tweens.splice(tweens.indexOf(t), 1); continue; }
    t.t += dt;
    const k = clamp01(t.t / t.dur);
    t.fn(k);
    if (k >= 1) { tweens.splice(tweens.indexOf(t), 1); t.done?.(); }
  }

  // Menu bar, buttons, readouts.
  refreshDock(dt);
  for (const b of allButtons()) b.update(dt, time);
  info.material.opacity = smooth(info.material.opacity, info.userData.on ? 1 : 0, dt, 12);
  info.visible = info.material.opacity > 0.01;
  spinner.userData.update(dt);

  // Packs: shelf ones bob; the picked one hovers; the foil follows the light; hovering lifts and glows.
  const packs = shelf.length ? shelf : pack ? [pack] : [];
  packs.forEach((s, i) => {
    s.hoverK = smooth(s.hoverK, time - s.hoverAt < 0.12 && !s.held ? 1 : 0, dt, 14);
    if (!s.group.parent) return;
    s.whole.scale.setScalar(1 + 0.05 * s.hoverK);
    s.glow.visible = !s.torn && s.hoverK > 0.01;
    s.glow.material.opacity = 0.7 * s.hoverK;
    if (s.torn) return;
    if (state.phase === "shelf") s.group.position.y = Math.sin(time * 1.2 + i) * 0.008 + 0.012 * s.hoverK;
    if (s.returning && !s.held) {                       // let go of a pack that wasn't torn: it floats back
      PACK_HOME.set(0, 0, 0);
      s.group.position.lerp(PACK_HOME, 1 - Math.exp(-dt * 10));
      s.group.quaternion.slerp(new THREE.Quaternion(), 1 - Math.exp(-dt * 10));
      s.group.scale.setScalar(smooth(s.group.scale.x, PICKED_SCALE, dt, 10));
      if (s.group.position.distanceTo(PACK_HOME) < 0.004) s.returning = false;
    } else if (s === pack && (state.phase === "pack" || state.phase === "loading") && !s.held) {
      s.group.position.y = Math.sin(time * 1.2) * 0.006;
      s.group.rotation.y = Math.sin(time * 0.7) * 0.06;
    }
    const active = pointers.find((p) => p.tear && p.tear.owner === s);
    if (!active && s.tearP > 0) s.tearP = Math.max(0, s.tearP - dt * 1.5);
    s.mat.uniforms.uTearProgress.value = s.tearP;
    s.guide.userData.update(dt, time, s === pack && state.phase === "pack" && s.tearP < 0.03 && !s.held);
    updateTilt(s.whole, head, time);
  });

  // Cards.
  for (const c of cards) {
    c.hoverK = smooth(c.hoverK, time - c.hoverAt < 0.12 ? 1 : 0, dt, 14);
    const live = c.group.visible && c.state !== "stack" && c.state !== "gone";
    if (live) {
      if (c.state === "returning" && !c.held) {
        c.group.position.lerp(c.home || HOLD, 1 - Math.exp(-dt * 10));
        c.group.quaternion.slerp(new THREE.Quaternion(), 1 - Math.exp(-dt * 10));
        if (c.group.position.distanceTo(c.home || HOLD) < 0.004) c.state = state.phase === "summary" ? "summary" : "revealed";
      }
      if (c.state === "revealed" && !c.held) c.group.position.y = HOLD.y + Math.sin(time * 1.3) * 0.004;
      if (state.phase === "summary" && c.introDone) c.group.scale.setScalar(smooth(c.group.scale.x, c.baseScale * (c.held ? c.holdScale : 1 + 0.06 * c.hoverK), dt, 12));
      updateTilt(c.front, head, time);
    }
    const breathe = c.tease ? 1.5 + 0.7 * Math.sin(time * 12) : 0.85 + 0.15 * Math.sin(time * 2 + c.index);
    const g = live ? ((c.glowOn ? GLOW[c.pull.tier] * breathe : 0) + 0.45 * c.hoverK) * (1 - c.faded) : 0;
    c.glow.visible = g > 0.01;
    c.glow.material.opacity = Math.min(1, g);
    c.glow.material.color.copy(c.glowOn ? c.glowColor : SIDE_COLOR);
  }

  // Particles.
  for (let i = bursts.length - 1; i >= 0; i--) {
    const b = bursts[i];
    b.age += dt;
    const arr = b.pos;
    b.vel.forEach((v, j) => {
      v.y -= b.gravity * dt;
      arr[j * 3] += v.x * dt; arr[j * 3 + 1] += v.y * dt; arr[j * 3 + 2] += v.z * dt;
    });
    b.pts.geometry.attributes.position.needsUpdate = true;
    b.pts.material.opacity = clamp01(1 - b.age / b.life);
    if (b.age >= b.life) { world.remove(b.pts); b.pts.geometry.dispose(); b.pts.material.dispose(); bursts.splice(i, 1); }
  }
  fx.update(dt, time);

  renderer.render(scene, camera);
}
renderer.setAnimationLoop(frame);

// --- Menu / sessions ---------------------------------------------------------------------------------
function beginExperience() {
  state.year = null;
  state.chosen = menuSet || state.sets[0];
  buildShelf({ focus: state.chosen });
}

function showMenu(show) { $("menu").style.display = show ? "flex" : "none"; }

/** Back to the start page (the headset session ended, or Exit in the preview). */
function toMenu() {
  state.token++;
  state.mode = null;
  clearScene();
  setPhase("menu");
  hideInfo(); setPips(null); setHint("");
  fx.rays(false);
  world.scale.setScalar(1);
  showMenu(true);
}

function leave() {
  const session = renderer.xr.getSession?.();
  if (session) session.end().catch(() => toMenu());
  else toMenu();
}

async function startAR() {
  try {
    sound.init();                                         // starts audio inside the tap; the sounds load meanwhile
    const q = QUALITY[$("quality").value] || QUALITY.sharp;
    renderer.xr.setFramebufferScaleFactor(q.scale);
    renderer.xr.setFoveation(q.foveation);
    const session = await navigator.xr.requestSession("immersive-ar", {
      requiredFeatures: ["local-floor"], optionalFeatures: ["hand-tracking"],
    });
    session.addEventListener("end", toMenu);
    await renderer.xr.setSession(session);
    state.mode = "xr"; needsPlace = true;
    showMenu(false);
  } catch (e) {
    $("status").textContent = "Couldn't start AR: " + (e.message || e);
  }
}

async function startFlat() {
  sound.init();
  camera.fov = 75; camera.updateProjectionMatrix();       // wide, like the headset's view
  camera.lookAt(0, 1.2, -0.6);                            // look down at the packs, as you would in the headset
  world.scale.setScalar(1.1);                             // (life-size in the headset; bigger on a flat screen)
  state.mode = "flat"; needsPlace = true;
  showMenu(false);
}

// --- The start page ------------------------------------------------------------------------------------
let menuSet = null;
let previewTimer = 0;
function selectSet(s) {
  menuSet = s;
  for (const el of $("setList").children) el.classList.toggle("sel", el.dataset.name === s.name);
  $("pickName").textContent = s.name;
  $("pickSub").textContent = `${s.code} · ${s.date.slice(0, 4)} · ${s.cardCount} cards`;
  previewPack(s);
}

/** The wrapper in the corner of the start page: the plain one at once, with its artwork when that arrives. */
function previewPack(set) {
  const cv = $("preview"), ctx = cv.getContext("2d");
  const paint = (img) => { ctx.clearRect(0, 0, cv.width, cv.height); ctx.drawImage(drawPack(set, img), 0, 0, cv.width, cv.height); };
  paint(null);
  clearTimeout(previewTimer);
  previewTimer = setTimeout(() => {
    loadSetData(set).then((d) => { if (menuSet === set && d.art) paint(d.art); }).catch(() => {});
  }, 250);
}

function fillSets(filter) {
  const list = $("setList"), q = filter.trim().toLowerCase();
  list.innerHTML = "";
  const rows = state.sets.filter((s) => !q || s.name.toLowerCase().includes(q) || s.code.toLowerCase().includes(q)).slice(0, 80);
  for (const s of rows) {
    const b = document.createElement("button");
    b.type = "button"; b.className = "set"; b.dataset.name = s.name;
    b.innerHTML = '<span class="code"></span><span class="nm"></span><span class="yr"></span>';
    b.children[0].textContent = s.code; b.children[1].textContent = s.name; b.children[2].textContent = s.date.slice(0, 4);
    b.addEventListener("click", () => selectSet(s));
    list.appendChild(b);
  }
  const keepSel = rows.find((s) => menuSet && s.name === menuSet.name) || rows[0];
  if (keepSel) selectSet(keepSel); else { menuSet = null; $("pickName").textContent = "No sets match"; $("pickSub").textContent = ""; }
}

async function init() {
  $("status").textContent = "Loading the sets…";
  $("mute").checked = sound.muted;
  $("quality").value = prefs.get("quality", "sharp");
  $("quality").addEventListener("change", (e) => prefs.set("quality", e.target.value));
  try {
    state.sets = await P.loadSets();
  } catch (e) {
    $("status").textContent = "Couldn't load the sets: " + e.message + ". Check the connection and reload.";
    return;
  }
  fillSets("");
  $("filter").addEventListener("input", (e) => fillSets(e.target.value));
  $("status").textContent = `${state.sets.length} sets ready.`;
  fetch("https://open.er-api.com/v6/latest/USD").then((r) => r.json()).then((j) => { state.rate = j.rates?.ZAR || null; }).catch(() => {});
  const supported = navigator.xr ? await navigator.xr.isSessionSupported("immersive-ar").catch(() => false) : false;
  $("enterAR").disabled = !supported;
  $("arNote").textContent = supported ? "" : "Augmented reality isn't available in this browser: open this page in the Meta Quest Browser. You can still try it on screen.";
  $("enterAR").addEventListener("click", startAR);
  $("flat").addEventListener("click", startFlat);
  $("mute").addEventListener("change", (e) => { sound.muted = e.target.checked; prefs.set("muted", sound.muted ? "1" : "0"); });
  window.__packvr = {                                   // for automated tests (tools/e2e*.mjs)
    state, startFlat, startAR, pointers, handPtrs, cards, shelf, dockBtn, pager, screenButtons, fx, interactives, world, camera,
    /** For each thing a pinch could grab: where [pos] (world) is in its own frame, and whether it's within reach. */
    reachOf(pos) {
      const p = new THREE.Vector3(...pos);
      return interactives().map((m) => { const lp = m.worldToLocal(p.clone()), g = m.geometry.parameters; return { what: m.userData.owner?.st?.label || m.userData.owner?.st?.text || m.userData.kind, local: lp.toArray().map((v) => +v.toFixed(3)), size: [g.width, g.height], scale: m.getWorldScale(new THREE.Vector3()).x }; });
    },
    get pack() { return pack; },
    toWorld(local) { const v = new THREE.Vector3(...local); world.localToWorld(v); return v.toArray(); },
    project(local) { const v = new THREE.Vector3(...local); world.localToWorld(v); v.project(camera); return { x: (v.x + 1) / 2 * innerWidth, y: (1 - v.y) / 2 * innerHeight }; },
    /** The middle of a button on screen / in the room. */
    buttonAt(b) { const v = new THREE.Vector3(); b.face.getWorldPosition(v); const w = v.toArray(); v.project(camera); return { world: w, x: (v.x + 1) / 2 * innerWidth, y: (1 - v.y) / 2 * innerHeight }; },
  };
}

init();
