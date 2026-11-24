// Card Binder AR: open booster packs in front of you (Meta Quest passthrough). Pack opening only.
import * as THREE from "three";
import * as P from "./packs.js";
import { makeHoloMaterial, holoTexture, updateTilt } from "./shaders.js";
import { drawPack, drawBackFallback, roundedMask, makeLabel } from "./art.js";
import { Sound } from "./sound.js";

const $ = (id) => document.getElementById(id);
const CARD_W = 0.095, CARD_H = CARD_W * 86 / 59;       // a real card is 5.9 × 8.6 cm: a bit bigger so it reads
const PACK_W = 0.2, PACK_H = PACK_W * 1110 / 640;
const HOLD = new THREE.Vector3(0, 0.03, 0.1);          // where the current card hovers (world-local)
const clamp01 = (x) => Math.min(1, Math.max(0, x));
const easeOut = (k) => 1 - Math.pow(1 - k, 3);
const easeInOut = (k) => (k < 0.5 ? 4 * k * k * k : 1 - Math.pow(-2 * k + 2, 3) / 2);

const sound = new Sound();
const state = { phase: "menu", sets: [], chosen: null, set: null, cards: [], pulls: [], idx: 0, rate: null, mode: null };

// --- three.js setup ---------------------------------------------------------------------------------
const renderer = new THREE.WebGLRenderer({ canvas: $("gl"), antialias: true, alpha: true, premultipliedAlpha: true });
renderer.setClearColor(0x000000, 0);
renderer.xr.enabled = true;
renderer.xr.setReferenceSpaceType("local-floor");
const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(55, 1, 0.02, 30);
camera.position.set(0, 1.45, 0);
const world = new THREE.Group();                       // everything lives here: placed in front of you
scene.add(world);

function resize() {
  const w = window.innerWidth, h = window.innerHeight;
  renderer.setSize(w, h, false);
  camera.aspect = w / h;
  camera.updateProjectionMatrix();
}
window.addEventListener("resize", resize);
resize();

const loader = new THREE.TextureLoader();
loader.setCrossOrigin("anonymous");
const loadImage = (url) => new Promise((res, rej) => loader.load(url, res, undefined, rej));

const dotTex = (() => {
  const c = document.createElement("canvas"); c.width = c.height = 64;
  const x = c.getContext("2d"), g = x.createRadialGradient(32, 32, 0, 32, 32, 32);
  g.addColorStop(0, "rgba(255,255,255,1)"); g.addColorStop(0.4, "rgba(255,255,255,0.5)"); g.addColorStop(1, "rgba(255,255,255,0)");
  x.fillStyle = g; x.fillRect(0, 0, 64, 64);
  return new THREE.CanvasTexture(c);
})();

// --- Tweens ------------------------------------------------------------------------------------------
const tweens = [];
function tween(dur, fn, done) { tweens.push({ t: 0, dur, fn, done }); }
function wait(sec, fn) { tween(sec, () => {}, fn); }

// --- Particles ---------------------------------------------------------------------------------------
const bursts = [];
/** A burst of light specks at [origin] (world-local). */
function burst(origin, { count = 120, speed = 0.9, gravity = 1.4, life = 1.4, size = 0.012, color = null, up = 0 } = {}) {
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
  const mat = new THREE.PointsMaterial({ size, map: dotTex, vertexColors: true, transparent: true, depthWrite: false,
    blending: THREE.AdditiveBlending, sizeAttenuation: true });
  const pts = new THREE.Points(geo, mat);
  pts.frustumCulled = false;
  pts.renderOrder = 40;
  world.add(pts);
  bursts.push({ pts, vel, pos, life, age: 0, gravity });
}
const goldColor = () => new THREE.Color().setHSL(0.1 + Math.random() * 0.05, 0.9, 0.6 + Math.random() * 0.25);

// --- Haptics / sound --------------------------------------------------------------------------------
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
let backTexture = null;
function getBackTexture() {
  if (!backTexture) {
    const fallback = new THREE.CanvasTexture(drawBackFallback());
    fallback.colorSpace = THREE.SRGBColorSpace;
    backTexture = fallback;
    loadImage(P.CARD_BACK_URL).then((t) => { backTexture.dispose(); backTexture.image = t.image; backTexture.needsUpdate = true; }).catch(() => {});
  }
  return backTexture;
}
const maskTexture = roundedMask();

// --- Labels ------------------------------------------------------------------------------------------
const hintLabel = makeLabel(0.34, 0.05);
hintLabel.position.set(0, -0.31, 0.08);
const infoLabel = makeLabel(0.30, 0.075);
infoLabel.position.set(0, -0.2, 0.1);
world.add(hintLabel, infoLabel);
const setHint = (text) => hintLabel.userData.set(text ? [{ text }] : []);
const fmt = (usd) => usd == null ? "—" : state.rate ? `R${(usd * state.rate).toFixed(2)}` : `$${usd.toFixed(2)}`;
const tierName = ["Common", "Rare", "Super", "Ultra", "Secret", "Premium"];

// --- Pointers (controllers, hands, or the mouse in the flat preview) ----------------------------------
const pointers = [];
const raycaster = new THREE.Raycaster();
const reticleGeo = new THREE.SphereGeometry(0.006, 12, 8);
function makePointer(obj, kind) {
  const reticle = new THREE.Mesh(reticleGeo, new THREE.MeshBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.85, depthTest: false }));
  reticle.renderOrder = 100; reticle.visible = false;
  scene.add(reticle);
  const p = { obj, kind, down: false, was: false, pendingDown: false, pendingUp: false, grab: null, tear: null, hit: null, hist: [], vel: new THREE.Vector3(), reticle, gamepad: null };
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
    if (!best || Math.abs(lp.z) < best.dz) {
      best = { object: mesh, point: pos.clone(), uv: new THREE.Vector2(lp.x / g.width + 0.5, lp.y / g.height + 0.5), dz: Math.abs(lp.z) };
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

function interactives() {
  const out = [];
  if (state.phase === "shelf") shelf.forEach((s) => out.push(s.whole));
  else if (state.phase === "pack" && pack) out.push(pack.whole);
  else if (state.phase === "cards") { const c = cards[state.idx]; if (c && c.state === "revealed") out.push(c.front); }
  else if (state.phase === "summary") { cards.forEach((c) => out.push(c.front)); if (summaryButton) out.push(summaryButton); }
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
    if (p.kind === "hand" && !p.tracked) continue;
    p.obj.updateWorldMatrix(true, false);
    _o.setFromMatrixPosition(p.obj.matrixWorld);

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
  else if (kind === "button") owner.action();
  else if (kind === "card") grab(p, owner);
  else if (kind === "pack") {
    if (!owner.ready) { setHint("Still getting the cards ready…"); return; }
    const fromTop = 1 - h.uv.y;
    if (fromTop < 0.2 && !owner.torn) {                   // the top strip: tear it
      const lp = pointerLocal(p, owner.group);
      p.tear = { owner, x0: lp ? lp.x : 0, lastStep: 0 };
    } else grab(p, owner);
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

// --- Pack --------------------------------------------------------------------------------------------
let pack = null;
const shelf = [];
let packGeo = null;
function packGeometry() {
  if (packGeo) return packGeo;
  packGeo = new THREE.PlaneGeometry(PACK_W, PACK_H, 20, 40);
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
  const obj = { group, whole, mat, set, tearP: 0, torn: false, ready: false, held: null, tex };
  whole.userData = { kind: "shelfPack", owner: obj };
  group.add(whole);
  const label = makeLabel(0.24, 0.045);
  label.userData.set([{ text: set.name }]);
  label.position.set(0, -PACK_H / 2 - 0.035, 0.02);
  group.add(label);
  obj.label = label;
  world.add(group);
  return obj;
}

function buildShelf() {
  clearScene();
  state.phase = "shelf";
  const sets = [state.chosen, ...state.sets.filter((s) => s.name !== state.chosen.name).slice(0, 4)];
  const xs = [0, -0.27, 0.27, -0.54, 0.54];
  sets.forEach((set, i) => {
    const p = makePack(set);
    p.baseX = xs[i]; p.baseZ = -Math.abs(xs[i]) * 0.22;
    p.group.position.set(p.baseX, 0, p.baseZ + 0.4);
    p.group.rotation.y = Math.atan2(-xs[i], 0.6 - p.baseZ);
    p.group.scale.setScalar(0.001);
    tween(0.6 + i * 0.1, (k) => { p.group.scale.setScalar(0.85 * easeOut(clamp01(k))); p.group.position.z = p.baseZ + 0.4 * (1 - easeOut(clamp01(k))); });
    shelf.push(p);
  });
  setHint("Pinch a pack to pick it (reach out, or point and pinch)");
  infoLabel.userData.set([]);
}

function clearScene() {
  for (const g of [...world.children]) if (g !== hintLabel && g !== infoLabel) world.remove(g);
  shelf.length = 0; cards.length = 0; pack = null; summaryButton = null;
  bursts.length = 0; tweens.length = 0;
  for (const p of pointers) { p.grab = null; p.tear = null; }
  state.idx = 0;
}

async function choosePack(chosen) {
  if (state.phase !== "shelf") return;
  state.phase = "loading";
  state.set = chosen.set;
  sound.play("twinkle", 0.6);
  pulse(0.3, 30);
  shelf.forEach((s) => { if (s !== chosen) tween(0.5, (k) => s.group.scale.setScalar(0.85 * (1 - easeInOut(k))), () => world.remove(s.group)); });
  const from = chosen.group.position.clone(), fromRot = chosen.group.rotation.y;
  tween(0.6, (k) => {
    const e = easeInOut(k);
    chosen.group.position.lerpVectors(from, new THREE.Vector3(0, 0, 0), e);
    chosen.group.rotation.y = fromRot * (1 - e);
    chosen.group.scale.setScalar(0.85 + 0.35 * e);
  });
  chosen.label.visible = false;
  chosen.whole.userData.kind = "pack";
  pack = chosen;
  setHint(`Loading ${state.set.name}…`);
  try {
    const cardsOfSet = await P.loadSetCards(state.set);
    if (!P.canOpen(cardsOfSet)) throw new Error("This set doesn't have enough cards to make a pack.");
    state.cards = cardsOfSet;
    state.pulls = P.openPack(cardsOfSet);
    state.pulls.forEach(cardTexture);                     // start downloading the pulled cards now
    const f = P.featured(cardsOfSet);
    if (f) {                                              // the set's top card on the wrapper
      const art = await loadImage(P.croppedArtUrl(f.artworkId)).catch(() => null);
      if (art) {
        const next = holoTexture(new THREE.CanvasTexture(drawPack(state.set, art.image)));
        pack.mat.uniforms.uImage.value = next;
        pack.tex.dispose(); pack.tex = next;
      }
    }
    pack.ready = true;
    state.phase = "pack";
    setHint("Pinch the top edge and drag across to tear it open");
  } catch (e) {
    setHint(`Couldn't load that set: ${e.message}`);
    wait(3, () => buildShelf());
  }
}

function rip(p) {
  if (p.torn) return;
  p.torn = true;
  state.phase = "ripping";
  setHint("");
  p.whole.visible = false;
  const mk = (mode) => { const m = p.mat.clone(); m.uniforms.uTearMode.value = mode; m.uniforms.uTearProgress.value = 0; return new THREE.Mesh(packGeometry(), m); };
  const strip = mk(1), rest = mk(2);
  p.group.add(strip, rest);
  const edge = new THREE.Vector3(0, PACK_H * (0.5 - 0.115), 0.03).add(p.group.position);
  sound.play("rip", 1); sound.play("shimmer", 0.55);
  pulse(1, 120);
  burst(edge, { count: 200, speed: 1.1, gravity: 1.2, life: 1.6, size: 0.014, up: 0.6 });
  tween(0.9, (k) => {
    const e = easeOut(k);
    strip.position.set(e * 0.18, e * 0.12, e * 0.08); strip.rotation.z = -e * 0.9; strip.rotation.x = e * 0.5;
    strip.material.uniforms.uFade.value = clamp01((k - 0.55) / 0.45);
    rest.position.y = -k * k * 0.5; rest.rotation.x = k * 0.5;
    rest.material.uniforms.uFade.value = clamp01((k - 0.3) / 0.7);
    for (const m of [strip, rest]) m.material.uniforms.tilt.value.copy(p.mat.uniforms.tilt.value);
  }, () => { world.remove(p.group); });
  wait(0.45, () => spawnCards());
}

// --- Cards -------------------------------------------------------------------------------------------
const cards = [];
let summaryButton = null;

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
  inner.add(front, back);
  const card = { pull, group, inner, front, back, mat, backMat: back.material, state: "stack", held: null, index, faded: 0 };
  front.userData = { kind: "card", owner: card };
  cardTexture(pull).then((t) => { mat.uniforms.uImage.value = t; mat.uniforms.texel.value.set(1 / (t.image?.width || 813), 1 / (t.image?.height || 1185)); });
  world.add(group);
  return card;
}
const setFade = (c, f) => { c.faded = f; c.mat.uniforms.uFade.value = f; c.backMat.opacity = 1 - f; };

function spawnCards() {
  state.phase = "cards";
  state.pulls.forEach((pull, i) => {
    const c = makeCard(pull, i);
    c.faceDown = pull.tier >= P.TIER.RARE;
    c.inner.rotation.y = Math.PI;                         // all start face-down in the stack
    c.group.position.set(0, 0, -0.02 - 0.004 * (state.pulls.length - i));
    cards.push(c);
  });
  showCard(0);
}

const STACK_AT = (i, cur) => new THREE.Vector3(0, 0, -0.03 - 0.004 * (i - cur));

function showCard(i) {
  state.idx = i;
  const c = cards[i];
  c.state = "intro";
  setHint("");
  infoLabel.userData.set([{ text: `${i + 1} / ${cards.length}`, size: 1 }]);
  // The cards behind settle into a neat stack.
  cards.forEach((o, j) => { if (j > i) { const to = STACK_AT(j, i); const from = o.group.position.clone(); tween(0.4, (k) => o.group.position.lerpVectors(from, to, easeOut(k))); } });
  const from = c.group.position.clone();
  tween(0.5, (k) => c.group.position.lerpVectors(from, HOLD, easeOut(k)), () => {
    if (c.faceDown) wait(0.45, () => flipCard(c)); else reveal(c);
  });
  // Rare and better build up first: they shake and the room hums.
}

function flipCard(c) {
  const pull = c.pull, big = pull.tier >= P.TIER.SUPER, ultra = pull.tier >= P.TIER.ULTRA;
  const build = big ? (pull.tier >= P.TIER.SECRET ? 1.1 : 0.65) : 0;
  c.state = "flipping";
  if (big) { sound.play("buildup", 0.7, 1.3 / build); pulse(0.5, build * 1000); }
  tween(build || 0.01, (k) => { c.inner.rotation.z = Math.sin(k * 40) * 0.04 * k; }, () => {
    c.inner.rotation.z = 0;
    sound.play("flip", 0.8);
    const span = ultra ? 1.15 : 0.35, total = ultra ? Math.PI * 3 : Math.PI;     // Ultra and up spin round 1½ times
    tween(span, (k) => { c.inner.rotation.y = Math.PI - total * easeOut(k); c.group.scale.setScalar(1 + (ultra ? 0.12 * Math.sin(k * Math.PI) : 0)); }, () => {
      c.inner.rotation.y = 0; c.group.scale.setScalar(1);
      reveal(c);
    });
  });
}

function reveal(c) {
  c.inner.rotation.y = 0;
  c.state = "revealed";
  const pull = c.pull, t = pull.tier;
  const colors = ["#c8c8d2", "#dce1eb", "#78c8ff", "#ffcd50", "#ff78dc", "#aaf0ff"];
  infoLabel.userData.set([
    { text: pull.name, size: 1.1 },
    { text: `${pull.rarity} · ${fmt(pull.priceUsd)}`, color: colors[t], size: 0.9 },
  ]);
  setHint(state.idx + 1 >= cards.length ? "Throw the card to see everything you pulled" : "Grab the card to tilt it · throw it away for the next");
  const at = HOLD.clone();
  if (t >= P.TIER.SUPER) {
    sound.play(t >= P.TIER.ULTRA ? "reveal_big" : "reveal", 0.9);
    pulse(t >= P.TIER.ULTRA ? 0.9 : 0.5, t >= P.TIER.ULTRA ? 250 : 120);
    burst(at, { count: t >= P.TIER.ULTRA ? 140 : 70, speed: 0.55, gravity: 0.2, life: 1.5, size: 0.011, color: () => new THREE.Color(colors[t]).lerp(new THREE.Color("#fff"), Math.random() * 0.6) });
  } else if (t >= P.TIER.RARE) sound.play("twinkle", 0.4);
  if (t >= P.TIER.SECRET) {
    burst(new THREE.Vector3(0, 0.3, 0.1), { count: 160, speed: 0.5, gravity: 0.9, life: 2.4, size: 0.012, color: goldColor, up: 0.2 });
  }
  c.onRelease = (vel) => {
    const d = c.group.position.distanceTo(HOLD);
    if (vel.length() > 0.9 || d > 0.25) throwCard(c, vel);   // a flick, or carried well away
    else c.state = "returning";
  };
}

function throwCard(c, vel) {
  c.state = "thrown";
  infoLabel.userData.set([]);
  setHint("");
  sound.play("whoosh", 0.6, 0.9 + Math.random() * 0.2);
  const v = vel.clone();
  v.applyQuaternion(world.getWorldQuaternion(new THREE.Quaternion()).invert());
  if (v.length() < 0.8) v.set((Math.random() < 0.5 ? -1 : 1) * 1.2, 0.3, 0.2);
  const start = c.group.position.clone(), spin = (Math.random() - 0.5) * 6;
  tween(0.55, (k) => {
    c.group.position.copy(start).addScaledVector(v, k * 0.55);
    c.group.rotation.z = spin * k;
    setFade(c, clamp01((k - 0.3) / 0.7));
  }, () => {
    c.group.visible = false;
    if (state.idx + 1 < cards.length) showCard(state.idx + 1); else summary();
  });
}

// --- Summary -----------------------------------------------------------------------------------------
function summary() {
  state.phase = "summary";
  const order = cards.slice().reverse();                  // best first
  const total = state.pulls.reduce((a, p) => a + (p.priceUsd || 0), 0);
  const dx = 0.1, dy = 0.14;
  order.forEach((c, i) => {
    const col = i % 3, row = Math.floor(i / 3);
    const home = new THREE.Vector3((col - 1) * dx, 0.03 + (1 - row) * dy, 0.02);
    c.home = home;
    c.group.visible = true; c.group.rotation.set(0, 0, 0); c.group.scale.setScalar(0.001); setFade(c, 0);
    c.inner.rotation.set(0, 0, 0);
    c.group.position.copy(HOLD);
    c.state = "summary";
    wait(i * 0.07, () => tween(0.5, (k) => { c.group.position.lerpVectors(HOLD, home, easeOut(k)); c.group.scale.setScalar(0.82 * easeOut(k)); }));
    c.onRelease = () => { c.state = "returning"; };
  });
  infoLabel.userData.set([{ text: `This pack ≈ ${state.rate ? "R" + (total * state.rate).toFixed(2) : "$" + total.toFixed(2)}`, color: "#ffd970", size: 1.1 }]);
  setHint("Grab any card to look closer");
  const btn = makeLabel(0.22, 0.05);
  btn.userData.set([{ text: "Open another pack", color: "#fff" }]);
  btn.position.set(0.3, 0.03, 0.04);                      // beside the cards
  btn.userData.kind = "button";
  btn.userData.owner = { action: () => buildShelf() };
  world.add(btn);
  summaryButton = btn;
  sound.play("shimmer", 0.4);
}

// --- Per-frame ---------------------------------------------------------------------------------------
const head = new THREE.Vector3();
let lastTime = 0, elapsed = 0;
let needsPlace = false;

function placeWorld() {
  camera.updateMatrixWorld(true);
  head.setFromMatrixPosition(camera.matrixWorld);
  const fwd = new THREE.Vector3(0, 0, -1).transformDirection(camera.matrixWorld);
  fwd.y = 0;
  if (fwd.lengthSq() < 1e-4) fwd.set(0, 0, -1);
  fwd.normalize();
  world.position.copy(head).addScaledVector(fwd, 0.55);   // within arm's reach
  world.position.y = head.y - 0.2;
  world.rotation.set(0, Math.atan2(-fwd.x, -fwd.z), 0);    // its +Z faces you
  world.updateMatrixWorld(true);
}

function frame(now = performance.now()) {
  const dt = Math.min(Math.max((now - (lastTime || now)) / 1000, 0), 0.05);
  lastTime = now; elapsed += dt;
  const time = elapsed;
  camera.updateMatrixWorld(true);
  head.setFromMatrixPosition(camera.matrixWorld);
  if (needsPlace && state.mode) { placeWorld(); needsPlace = false; beginExperience(); }

  updatePointers(dt);

  for (const t of [...tweens]) {
    t.t += dt;
    const k = clamp01(t.t / t.dur);
    t.fn(k);
    if (k >= 1) { tweens.splice(tweens.indexOf(t), 1); t.done?.(); }
  }

  // Shelf packs bob; the chosen pack hovers; the foil follows the light.
  shelf.forEach((s, i) => { if (state.phase === "shelf") s.group.position.y = Math.sin(time * 1.2 + i) * 0.008; });
  const packs = shelf.length ? shelf : pack ? [pack] : [];
  for (const s of packs) {
    if (!s.torn) {
      if (!s.group.parent) continue;
      if (state.phase === "pack" || state.phase === "loading") s.group.position.y = Math.sin(time * 1.2) * 0.006;
      const active = pointers.find((p) => p.tear && p.tear.owner === s);
      if (!active && s.tearP > 0) s.tearP = Math.max(0, s.tearP - dt * 1.5);
      s.mat.uniforms.uTearProgress.value = s.tearP;
      updateTilt(s.whole, head, time);
    }
  }
  for (const c of cards) {
    if (c.state === "stack" || !c.group.visible) continue;
    if (c.state === "returning" && !c.held) {
      c.group.position.lerp(c.home || HOLD, 1 - Math.exp(-dt * 10));
      c.group.quaternion.slerp(new THREE.Quaternion(), 1 - Math.exp(-dt * 10));
      if (c.group.position.distanceTo(c.home || HOLD) < 0.004) c.state = state.phase === "summary" ? "summary" : "revealed";
    }
    if (c.state === "revealed" && !c.held) c.group.position.y = HOLD.y + Math.sin(time * 1.3) * 0.004;
    updateTilt(c.front, head, time);
  }
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
  // Labels face you.
  for (const l of [hintLabel, infoLabel, summaryButton]) if (l) l.lookAt(head);
  shelf.forEach((s) => s.label.lookAt(head));

  renderer.render(scene, camera);
}
renderer.setAnimationLoop(frame);

// --- Menu / sessions ---------------------------------------------------------------------------------
function beginExperience() {
  state.chosen = state.sets.find((s) => s.name === $("setList").value) || state.sets[0];
  buildShelf();
}

function showMenu(show) { $("menu").style.display = show ? "flex" : "none"; }

async function startAR() {
  try {
    sound.init();                                         // starts audio inside the tap; the sounds load meanwhile
    const session = await navigator.xr.requestSession("immersive-ar", {
      requiredFeatures: ["local-floor"], optionalFeatures: ["hand-tracking"],
    });
    session.addEventListener("end", () => { state.mode = null; state.phase = "menu"; clearScene(); world.scale.setScalar(1); showMenu(true); });
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

async function init() {
  $("status").textContent = "Loading the sets…";
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
  $("mute").addEventListener("change", (e) => { sound.muted = e.target.checked; });
  window.__packvr = {                                   // for automated tests (tools/e2e.mjs)
    state, startFlat, startAR, pointers, handPtrs,
    get pack() { return pack; }, cards, shelf,
    toWorld(local) { const v = new THREE.Vector3(...local); world.localToWorld(v); return v.toArray(); },
    project(local) { const v = new THREE.Vector3(...local); world.localToWorld(v); v.project(camera); return { x: (v.x + 1) / 2 * innerWidth, y: (1 - v.y) / 2 * innerHeight }; },
  };
}

function fillSets(filter) {
  const list = $("setList"), q = filter.trim().toLowerCase(), prev = list.value;
  list.innerHTML = "";
  state.sets.filter((s) => !q || s.name.toLowerCase().includes(q) || s.code.toLowerCase().includes(q)).slice(0, 60).forEach((s) => {
    const o = document.createElement("option");
    o.value = s.name; o.textContent = `${s.name}  (${s.date.slice(0, 4)})`;
    list.appendChild(o);
  });
  if ([...list.options].some((o) => o.value === prev)) list.value = prev; else list.selectedIndex = 0;
}

init();
