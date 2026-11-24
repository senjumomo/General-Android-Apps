// End-to-end check of the real headset path: an emulated Meta Quest 3 (Meta's IWER) starts an
// immersive-ar session, and an emulated controller points at things and pulls its trigger (a pinch
// does the same). Verifies picking, tearing, grabbing and throwing through the XR input code.
//   node PackVR/tools/e2e-xr.mjs [outDir] [setName]
//   HANDS=1   bare hands, pointing and pinching from a distance (no controllers)
//   DIRECT=1  bare hands reaching out and pinching things where they float
import fs from "node:fs";
import path from "node:path";
import { launch } from "./harness.mjs";

const out = process.argv[2] || "/tmp/packvr-e2e-xr";
const setName = process.argv[3] || "Rage of the Abyss";
const iwer = fs.readFileSync(path.join(path.dirname(new URL(import.meta.url).pathname), "iwer.min.js"), "utf8");
const t = await launch(out, {
  init: iwer + `
    (() => {
      const device = new IWER.XRDevice(IWER.metaQuest3);
      device.installRuntime({ forceInstall: true });   // replace headless Chromium's own (empty) WebXR
      device.position.set(0, 1.6, 0);
      const hands = ${process.env.HANDS || process.env.DIRECT ? "true" : "false"};
      if (hands) device.primaryInputMode = "hand";
      const input = () => (hands ? device.hands.right : device.controllers.right);
      window.__device = device;
      const norm = (v) => { const l = Math.hypot(...v); return v.map((x) => x / l); };
      // Point the right controller from [o] at [target] (a ray runs along the controller's -Z).
      window.__aim = (o, target) => {
        const c = input();
        c.position.set(...o);
        const a = [0, 0, -1], b = norm([target[0] - o[0], target[1] - o[1], target[2] - o[2]]);
        const dot = a[0]*b[0] + a[1]*b[1] + a[2]*b[2];
        let q;
        if (dot < -0.9999) q = [0, 1, 0, 0];
        else { const cx = [a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]]; q = norm([cx[0], cx[1], cx[2], 1 + dot]); }
        c.quaternion.set(q[0], q[1], q[2], q[3]);
      };
      // Move the emulated right hand so its pinch point (between thumb and index finger) is at [target].
      window.__reach = (target) => {
        const h = device.hands.right, hp = h.position;
        const ptrs = __packvr.handPtrs.filter((p) => p.tracked);
        if (!ptrs.length) return null;
        let best = null, bd = 1e9;
        for (const p of ptrs) { const m = p.obj.position, d = Math.hypot(m.x - hp.x, m.y - hp.y, m.z - hp.z); if (d < bd) { bd = d; best = p; } }
        const m = best.obj.position;
        h.position.set(hp.x + target[0] - m.x, hp.y + target[1] - m.y, hp.z + target[2] - m.z);
        return Math.hypot(target[0] - m.x, target[1] - m.y, target[2] - m.z);
      };
      window.__trigger = (v) => (hands ? device.hands.right.updatePinchValue(v) : device.controllers.right.updateButtonValue("trigger", v));
    })();`,
});
const { page, hook, sleep, shot, wait } = t;
const frames = (n) => sleep(n * 130);                                   // software rendering is slow: ~8 fps
const world = (l) => hook((l) => __packvr.toWorld(l), l);
const DIRECT = !!process.env.DIRECT;
// Point at [target] from a distance, or (DIRECT) put the pinch point right on it.
const hand = async (target, off = [0.12, -0.12, 0.3]) => {
  if (!DIRECT) return hook(([t, off]) => __aim([t[0] + off[0], t[1] + off[1], t[2] + off[2]], t), [target, off]);
  for (let i = 0; i < 4; i++) { await hook((t) => __reach(t), target); await sleep(150); }
};

// Move the touching hand so its pinch point is at [to] (a few tries: each corrects the last).
const settleTo = async (to, n) => { for (let i = 0; i < n; i++) { await hook((t) => __reach(t), to); await frames(2); } };

// Start a pinch on [target]. By touch, fingers are already pinched as the hand moves in (as people do),
// and the pinch registers as it reaches the thing; otherwise aim from a distance and pull the trigger.
// [from] is where, relative to the target, the fingers close: far from everything, because a pinch also fires
// the pointing ray at whatever it points at (the emulated hand's closed pose isn't where its open one was).
const pinchAt = async (target, from = [0, 0, 0.3]) => {
  if (!DIRECT) { await hand(target); await frames(2); await hook(() => __trigger(1)); await frames(3); return; }
  const front = [target[0] + from[0], target[1] + from[1], target[2] + from[2]];
  await hook(() => __trigger(0));
  await settleTo(front, 3);
  await hook(() => __trigger(1));                       // fingers closed (and settled) before moving in
  await frames(8);
  await settleTo(front, 2);
  await settleTo(target, 3);                            // straight in: the pinch registers on reaching it
  await frames(2);
};

// Press a button: close the fingers well below it (where the ray hits nothing), then glide up onto it.
const touchAt = (target) => pinchAt(target, [0, -0.2, 0.3]);

const fails = [];
const check = (ok, what) => { console.log((ok ? "ok   " : "FAIL ") + what); if (!ok) fails.push(what); };
// The middle of a menu button in the room (null until it can be used).
const btnWorld = (path) => hook((p) => { const b = p.split(".").reduce((o, k) => o?.[k], __packvr); return b && b.interactive() ? __packvr.buttonAt(b).world : null; }, path);
const pressBtn = async (path, label) => {
  await wait((p) => { const b = p.split(".").reduce((o, k) => o?.[k], __packvr); return b && b.interactive(); }, path, `${label} button usable`, 30000);
  await frames(2);
  const target = await btnWorld(path);
  await touchAt(target);
  if (process.env.DEBUG) console.log(`pressing ${label} at`, target.map((v) => +v.toFixed(3)), JSON.stringify(await hook(() => __packvr.handPtrs.map((p) => ({ tracked: p.tracked, down: p.down, near: p.near && (p.near.object.userData.owner?.st?.label || p.near.object.userData.owner?.st?.text || p.near.object.userData.kind) })))));
  await hook(() => __trigger(0));
  await frames(3);
};

await page.goto(t.url + "?fx=2&confirm=30");                                       // ?fx=2: all effects on (the slow test machine would trigger the thinning-out); ?confirm=30: the emulated hand is slow
await wait(() => document.getElementById("status").textContent.includes("sets ready"), null, "sets load");
console.log("AR available:", await hook(() => !document.getElementById("enterAR").disabled));
await page.fill("#filter", setName);
await page.click("#enterAR");
await wait(() => __packvr.state.phase === "shelf", null, "enter AR / place the shelf", 30000);
console.log("input:", DIRECT ? "hands, touching and pinching" : process.env.HANDS ? "hands, pointing and pinching" : "controller (trigger)", "| session mode:", await hook(() => __packvr.state.mode), "| world placed at", (await world([0, 0, 0])).map((v) => +v.toFixed(2)));
await sleep(1500);
await shot("xr-1-shelf");

// The menu bar: Sets opens the year picker, Back closes it.
await pressBtn("dockBtn.sets", "Sets");
await wait(() => __packvr.state.phase === "years", null, "Sets opens the year picker", 30000);
check(true, "the Sets button opens the year picker");
await frames(10);
await shot("xr-1b-years");
await pressBtn("dockBtn.shelf", "Back");
await wait(() => __packvr.state.phase === "shelf", null, "Back closes the year picker", 30000);
check(await hook((n) => __packvr.shelf[2].set.name === n, setName), "Back is the shelf again, same sets");
await frames(10);

// Point at the middle pack and pinch.
await pinchAt(await world([0, 0, 0]));
console.log(DIRECT ? "touching (near a pack):" : "hovering:", await hook((d) => __packvr.pointers.filter((p) => p.kind === (d ? "hand" : "xr")).map((p) => !!(d ? p.near : p.hit)), DIRECT));
await hook(() => __trigger(0));
await wait(() => __packvr.state.phase === "pack", null, "pick a pack by pinching", 90000);
await frames(8);

// Tear: pinch the top edge and drag across.
const strip = (k) => world([-0.07 + k * 0.03, 0.347 * 1.2 * 0.4, 0.02]);
await pinchAt(await strip(0));
if (process.env.DEBUG) console.log("pinch state:", JSON.stringify(await hook(() => __packvr.handPtrs.map((p) => ({ tracked: p.tracked, down: p.down, near: !!p.near, uvy: p.near && +p.near.uv.y.toFixed(2), tear: !!p.tear })))));
for (let k = 1; k <= 12; k++) { await hand(await strip(k)); await frames(1.5); if (process.env.DEBUG) console.log("  step", k, "tearP", await hook(() => +__packvr.pack.tearP.toFixed(2)), "down", await hook(() => __packvr.handPtrs.map((p) => p.down))); }
await hook(() => __trigger(0));
await wait(() => __packvr.state.phase === "cards", null, "tear the pack with a drag", 30000);

// Cards: wait for the reveal, grab it, flick it sideways.
for (let i = 0; i < 9; i++) {
  await wait((i) => __packvr.state.idx === i && __packvr.cards[i]?.state === "revealed", i, `reveal card ${i + 1}`, 40000);
  await frames(2);
  const at = await world([0, 0.03, 0.1]);
  await pinchAt(at);
  if (i === 8) await shot("xr-2-card-in-hand");
  for (let k = 1; k <= 5; k++) {
    if (DIRECT) await hook(([at, k]) => __reach([at[0] + k * 0.1, at[1], at[2]]), [at, k]);
    else await hook(([at, k]) => __aim([at[0] + 0.1 + k * 0.1, at[1] - 0.12, at[2] + 0.3], [at[0] + k * 0.1, at[1], at[2]]), [at, k]);
    await sleep(60);
  }
  await hook(() => __trigger(0));
}
await wait(() => __packvr.state.phase === "summary", null, "throw the last card", 30000);
await frames(8);
await shot("xr-3-summary");
console.log("phase:", await hook(() => __packvr.state.phase));
console.log("pulls:", (await hook(() => __packvr.state.pulls.map((c) => c.name + " (" + c.rarity + ")"))).join("; "));

// Open another pack of the same set with its button, then leave with Exit (which asks first).
await pressBtn("screenButtons.0", "Open another pack");
await wait(() => __packvr.state.phase === "pack", null, "Open another pack", 60000);
check(await hook((n) => __packvr.state.set.name === n, setName), "Open another pack gives the same set");
await pressBtn("dockBtn.exit", "Exit");
check(await hook(() => __packvr.state.phase) === "pack", "Exit asks before leaving");
await pressBtn("dockBtn.exit", "Exit (confirm)");
await wait(() => __packvr.state.phase === "menu", null, "Exit ends the session", 30000);
check(await page.isVisible("#menu"), "the start page is back after Exit");

console.log(t.errors.length ? "console problems:\n" + [...new Set(t.errors)].slice(0, 12).join("\n") : "no console errors");
if (t.errors.length) fails.push("console errors");
console.log(fails.length ? `${fails.length} problem(s)` : "all good");
await t.close();
process.exit(fails.length ? 1 : 0);
