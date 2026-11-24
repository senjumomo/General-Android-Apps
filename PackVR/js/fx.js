// Light and atmosphere for the AR scene: a soft glow behind the stage, drifting motes of light, god rays
// behind big pulls, shockwave rings, the tear guide on a pack and a loading ring. Everything is additive,
// cheap (a handful of quads and ~90 points) and can be thinned out by setLevel() if the headset struggles.
import * as THREE from "three";
import { glowTexture } from "./ui.js";

function canvasTexture(w, h, paint) {
  const c = document.createElement("canvas");
  c.width = w; c.height = h;
  const ctx = c.getContext("2d");
  paint(ctx, w, h);
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  t.userData.shared = true;
  return t;
}

let _rays, _ring, _soft, _star, _arc, _dash;

/** Soft wedges radiating from the middle, fading toward the edge. */
const raysTexture = () => _rays ||= canvasTexture(512, 512, (x, w, h) => {
  const N = 14, c = w / 2;
  x.filter = "blur(3px)";
  for (let i = 0; i < N; i++) {
    const a = (i / N) * Math.PI * 2, half = Math.PI / N * 0.42;
    const g = x.createRadialGradient(c, c, 10, c, c, c);
    g.addColorStop(0, "rgba(255,255,255,0.95)"); g.addColorStop(0.6, "rgba(255,255,255,0.35)"); g.addColorStop(1, "rgba(255,255,255,0)");
    x.fillStyle = g;
    x.beginPath(); x.moveTo(c, c); x.arc(c, c, c, a - half, a + half); x.closePath(); x.fill();
  }
});

/** A thin glowing ring (for shockwaves). */
const ringTexture = () => _ring ||= canvasTexture(256, 256, (x, w) => {
  const c = w / 2, g = x.createRadialGradient(c, c, c * 0.55, c, c, c);
  g.addColorStop(0, "rgba(255,255,255,0)"); g.addColorStop(0.62, "rgba(255,255,255,0.15)"); g.addColorStop(0.8, "rgba(255,255,255,1)");
  g.addColorStop(0.9, "rgba(255,255,255,0.25)"); g.addColorStop(1, "rgba(255,255,255,0)");
  x.fillStyle = g; x.fillRect(0, 0, w, w);
});

/** A soft rounded rectangle (a glow that hugs a card or pack). */
export const softRectTexture = () => _soft ||= canvasTexture(256, 384, (x, w, h) => {
  x.filter = "blur(12px)";                             // (the solid part is ~77% × 84% of the plane: size planes for that)
  x.fillStyle = "#fff";
  x.beginPath(); x.roundRect(30, 30, w - 60, h - 60, 24); x.fill();
});

/** A four-point sparkle. */
export const starTexture = () => _star ||= canvasTexture(128, 128, (x, w) => {
  const c = w / 2;
  const g = x.createRadialGradient(c, c, 0, c, c, c);
  g.addColorStop(0, "rgba(255,255,255,1)"); g.addColorStop(0.18, "rgba(255,255,255,0.55)"); g.addColorStop(1, "rgba(255,255,255,0)");
  x.fillStyle = g; x.fillRect(0, 0, w, w);
  x.fillStyle = "#fff";
  x.beginPath(); x.moveTo(c, 2); x.quadraticCurveTo(c + 5, c - 5, w - 2, c); x.quadraticCurveTo(c + 5, c + 5, c, w - 2);
  x.quadraticCurveTo(c - 5, c + 5, 2, c); x.quadraticCurveTo(c - 5, c - 5, c, 2); x.fill();
});

const arcTexture = () => _arc ||= canvasTexture(128, 128, (x, w) => {
  const c = w / 2;
  for (let i = 0; i < 40; i++) {                                    // a comet-like arc fading out
    const a0 = (i / 40) * Math.PI * 1.5;
    x.strokeStyle = `rgba(255,255,255,${(i / 40) ** 1.6})`;
    x.lineWidth = 7; x.lineCap = "round";
    x.beginPath(); x.arc(c, c, c - 12, a0, a0 + 0.2); x.stroke();
  }
});

const dashTexture = () => _dash ||= canvasTexture(256, 16, (x, w, h) => {
  x.fillStyle = "#fff";
  for (let i = 0; i < 8; i++) { x.beginPath(); x.roundRect(i * 32 + 4, 3, 18, h - 6, 5); x.fill(); }
});

const add = (map, color, opacity = 0) => new THREE.MeshBasicMaterial({ map, color, transparent: true, opacity, depthWrite: false, blending: THREE.AdditiveBlending, toneMapped: false });

/** A glowing outline behind a card/pack; the owner sets glow.material.opacity / color. */
export function makeSoftGlow(w, h, color = 0xffffff) {
  const m = new THREE.Mesh(new THREE.PlaneGeometry(w, h), add(softRectTexture(), color));
  m.renderOrder = -5;
  m.visible = false;
  return m;
}

/** A pulsing, marching dashed line for the tear line of a pack. */
export function makeTearGuide(width) {
  const group = new THREE.Group();
  const tex = dashTexture().clone();
  tex.userData.shared = false;
  tex.wrapS = THREE.RepeatWrapping; tex.repeat.set(width / 0.09, 1); tex.needsUpdate = true;
  const line = new THREE.Mesh(new THREE.PlaneGeometry(width, 0.0075), add(tex, 0xffffff, 0));
  const glow = new THREE.Mesh(new THREE.PlaneGeometry(width * 1.05, 0.05), add(glowTexture(), 0xffd970, 0));
  line.renderOrder = glow.renderOrder = 45;
  group.add(glow, line);
  group.userData.update = (dt, time, on) => {
    const k = group.userData.k = (group.userData.k || 0) + ((on ? 1 : 0) - (group.userData.k || 0)) * (1 - Math.exp(-dt * 8));
    tex.offset.x -= dt * 0.35;
    line.material.opacity = k * (0.55 + 0.35 * Math.sin(time * 4));
    glow.material.opacity = k * (0.25 + 0.15 * Math.sin(time * 4));
    group.visible = k > 0.01;
  };
  group.userData.dispose = () => { tex.dispose(); line.geometry.dispose(); line.material.dispose(); glow.geometry.dispose(); glow.material.dispose(); };
  return group;
}

/** A spinning arc: "working on it". */
export function makeSpinner(size = 0.06) {
  const m = new THREE.Mesh(new THREE.PlaneGeometry(size, size), add(arcTexture(), 0xffe6a0, 0.9));
  m.renderOrder = 55;
  m.userData.update = (dt) => { m.rotation.z -= dt * 5; };
  return m;
}

/** Everything ambient, plus one-shot rings. `HOLD` is where the card you're looking at floats. */
export function createFx(world, HOLD) {
  const group = new THREE.Group();
  group.userData.keep = true;
  world.add(group);
  let level = 2;

  // A soft haze of colour behind the stage.
  const aura = new THREE.Mesh(new THREE.PlaneGeometry(1.5, 1.0), add(glowTexture(), 0x6a4cff, 0));
  aura.position.set(0, 0.03, -0.18);
  aura.renderOrder = -20;
  group.add(aura);
  let auraBase = 0.0, flashK = 0;
  const auraColor = new THREE.Color(0x6a4cff);

  // God rays behind a big pull.
  const rays = new THREE.Mesh(new THREE.PlaneGeometry(0.66, 0.66), add(raysTexture(), 0xffcd50, 0));
  rays.position.set(HOLD.x, HOLD.y, HOLD.z - 0.03);
  rays.renderOrder = -15;
  group.add(rays);
  let raysTarget = 0;
  const raysColor = new THREE.Color(0xffcd50);

  // Motes of light drifting through the air in front of you.
  const N = 90;
  const mpos = new Float32Array(N * 3), mseed = [];
  const box = { x: 0.62, y0: -0.3, y1: 0.3, z0: -0.25, z1: 0.3 };
  for (let i = 0; i < N; i++) {
    mpos.set([(Math.random() * 2 - 1) * box.x, box.y0 + Math.random() * (box.y1 - box.y0), box.z0 + Math.random() * (box.z1 - box.z0)], i * 3);
    mseed.push({ v: 0.008 + Math.random() * 0.02, ph: Math.random() * 6.28 });
  }
  const mgeo = new THREE.BufferGeometry();
  mgeo.setAttribute("position", new THREE.BufferAttribute(mpos, 3));
  const motes = new THREE.Points(mgeo, new THREE.PointsMaterial({ size: 0.0065, map: glowTexture(), color: 0xffeebb, transparent: true, opacity: 0.55, depthWrite: false, blending: THREE.AdditiveBlending, sizeAttenuation: true }));
  motes.frustumCulled = false;
  motes.renderOrder = 30;
  group.add(motes);

  const rings = [];
  const api = {
    group, aura, rays,
    setLevel(l) { level = l; motes.visible = l >= 1; motes.geometry.setDrawRange(0, l >= 2 ? N : 36); },
    get level() { return level; },
    /** The colour the haze settles to, and how strong it is when nothing's happening. */
    tint(hex, base = 0.10) { auraColor.set(hex); auraBase = base; },
    flash(strength = 0.5, hex = null) { if (hex != null) auraColor.set(hex); flashK = Math.max(flashK, strength); },
    rays(on, hex = null) { raysTarget = on && level >= 2 ? 1 : 0; if (hex != null) raysColor.set(hex); },
    /** An expanding ring at [origin] (world-local), [size] metres across at the end. */
    shock(origin, hex = 0xffffff, size = 0.5, dur = 0.6) {
      if (level < 1) return;
      const m = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), add(ringTexture(), hex, 0.9));
      m.position.copy(origin); m.renderOrder = 44;
      group.add(m);
      rings.push({ m, age: 0, dur, size });
    },
    update(dt, time) {
      aura.material.color.lerp(auraColor, 1 - Math.exp(-dt * 3));
      flashK *= Math.exp(-dt * 2.2);
      aura.material.opacity = level >= 1 ? Math.min(0.55, auraBase + flashK) : 0;
      rays.material.color.lerp(raysColor, 1 - Math.exp(-dt * 6));
      const ro = rays.material.opacity;
      rays.material.opacity = ro + (raysTarget * 0.42 - ro) * (1 - Math.exp(-dt * 4));
      rays.visible = rays.material.opacity > 0.01;
      rays.rotation.z += dt * 0.25;
      if (motes.visible) {
        const arr = mgeo.attributes.position.array;
        for (let i = 0; i < N; i++) {
          const s = mseed[i];
          arr[i * 3 + 1] += s.v * dt;
          arr[i * 3] += Math.sin(time * 0.4 + s.ph) * 0.004 * dt;
          if (arr[i * 3 + 1] > box.y1) arr[i * 3 + 1] = box.y0;
        }
        mgeo.attributes.position.needsUpdate = true;
      }
      for (let i = rings.length - 1; i >= 0; i--) {
        const r = rings[i];
        r.age += dt;
        const k = Math.min(1, r.age / r.dur), e = 1 - Math.pow(1 - k, 3);
        r.m.scale.setScalar(0.05 + r.size * e);
        r.m.material.opacity = 0.9 * (1 - k);
        if (k >= 1) { group.remove(r.m); r.m.geometry.dispose(); r.m.material.dispose(); rings.splice(i, 1); }
      }
    },
  };
  api.setLevel(2);
  return api;
}
