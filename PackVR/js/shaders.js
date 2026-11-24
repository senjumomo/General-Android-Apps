// The app's foil shader, on three.js materials. The shader text comes from the Android app
// (gen/holo_shaders.js, produced by sync_from_app.py), so every rarity looks the same as in the app.
import * as THREE from "three";
import { PREFIX, CORE, EXTRAS, MAIN } from "../gen/holo_shaders.js";

const VERT = `
varying vec2 vUv;
void main() {
  vUv = vec2(uv.x, 1.0 - uv.y);          // the app's UVs run top-down
  gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
}`;
const FRAG = PREFIX + CORE + EXTRAS + MAIN;

/** A card (default) or a foil pack (pack = true) lit with the holo shader. */
export function makeHoloMaterial({ mode = 0, art = [0.119, 0.18, 0.881, 0.705], pack = false } = {}) {
  const uniforms = {
    uImage: { value: null },
    uSrc: { value: new THREE.Vector4(0, 0, 1, 1) },
    resolution: { value: new THREE.Vector2(512, 745) },
    texel: { value: new THREE.Vector2(1 / 813, 1 / 1185) },
    tilt: { value: new THREE.Vector2() },
    time: { value: 0 },
    mode: { value: mode },
    photo: { value: 0 },
    artRect: { value: new THREE.Vector4(...art) },
    uShapeAspect: { value: pack ? 1110 / 640 : 0 },
    uCornerRadius: { value: pack ? 0.012 : 0 },
    uTearY: { value: 0.115 },
    uTearMode: { value: 0 },
    uTearProgress: { value: 0 },
    uFade: { value: 0 },
    uPillow: { value: pack ? 1 : 0 },
  };
  return new THREE.ShaderMaterial({
    uniforms, vertexShader: VERT, fragmentShader: FRAG,
    transparent: true, premultipliedAlpha: true, depthWrite: false,
    side: pack ? THREE.DoubleSide : THREE.FrontSide,
  });
}

/** Image textures for the holo shader: row 0 = top of the image, like the Android app. */
export function holoTexture(tex) {
  tex.flipY = false;
  tex.minFilter = THREE.LinearMipmapLinearFilter;
  tex.anisotropy = 4;
  return tex;
}

// A fixed light in the room; the foil's "tilt" is where the reflection of it falls on the surface,
// seen from the viewer's eye: turn the card or move your head and the foil sweeps.
const LIGHT = new THREE.Vector3(-0.3, 0.45, 0.85).normalize();
const _inv = new THREE.Matrix4();
const _v = new THREE.Vector3();
const _l = new THREE.Vector3();

export function updateTilt(mesh, head, time) {
  mesh.updateWorldMatrix(true, false);
  _inv.copy(mesh.matrixWorld).invert();
  _v.copy(head).applyMatrix4(_inv).normalize();         // direction to the eye, in the surface's frame
  _l.copy(LIGHT).transformDirection(_inv);              // direction to the light, likewise
  _v.add(_l).normalize();                               // the half vector
  const t = mesh.material.uniforms.tilt.value;
  const sway = 0.07;                                    // a little life even when held still
  t.set(THREE.MathUtils.clamp(_v.x * 2.4 + Math.sin(time * 0.8) * sway, -1.2, 1.2),
        THREE.MathUtils.clamp(-_v.y * 2.4 + Math.cos(time * 0.6) * sway, -1.2, 1.2));
  mesh.material.uniforms.time.value = time;
}
