// End-to-end check of the real headset path: an emulated Meta Quest 3 (Meta's IWER) starts an
// immersive-ar session, and an emulated controller points at things and pulls its trigger (a pinch
// does the same). Verifies picking, tearing, grabbing and throwing through the XR input code.
//   node PackVR/tools/e2e-xr.mjs [outDir] [setName]
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
      window.__device = device;
      const norm = (v) => { const l = Math.hypot(...v); return v.map((x) => x / l); };
      // Point the right controller from [o] at [target] (a ray runs along the controller's -Z).
      window.__aim = (o, target) => {
        const c = device.controllers.right;
        c.position.set(...o);
        const a = [0, 0, -1], b = norm([target[0] - o[0], target[1] - o[1], target[2] - o[2]]);
        const dot = a[0]*b[0] + a[1]*b[1] + a[2]*b[2];
        let q;
        if (dot < -0.9999) q = [0, 1, 0, 0];
        else { const cx = [a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]]; q = norm([cx[0], cx[1], cx[2], 1 + dot]); }
        c.quaternion.set(q[0], q[1], q[2], q[3]);
      };
      window.__trigger = (v) => device.controllers.right.updateButtonValue("trigger", v);
    })();`,
});
const { page, hook, sleep, shot, wait } = t;
const frames = (n) => sleep(n * 130);                                   // software rendering is slow: ~8 fps
const world = (l) => hook((l) => __packvr.toWorld(l), l);
const hand = (target, off = [0.12, -0.12, 0.3]) => hook(([t, off]) => __aim([t[0] + off[0], t[1] + off[1], t[2] + off[2]], t), [target, off]);

await page.goto(t.url);
await wait(() => document.getElementById("status").textContent.includes("sets ready"), null, "sets load");
console.log("AR available:", await hook(() => !document.getElementById("enterAR").disabled));
await page.fill("#filter", setName);
await page.click("#enterAR");
await wait(() => __packvr.state.phase === "shelf", null, "enter AR / place the shelf", 30000);
console.log("session mode:", await hook(() => __packvr.state.mode), "| world placed at", (await world([0, 0, 0])).map((v) => +v.toFixed(2)));
await sleep(1500);
await shot("xr-1-shelf");

// Point at the middle pack and pinch.
await hand(await world([0, 0, 0]));
await frames(4);
console.log("hovering:", await hook(() => __packvr.pointers.filter((p) => p.kind === "xr").map((p) => !!p.hit)));
await hook(() => __trigger(1)); await frames(3); await hook(() => __trigger(0));
await wait(() => __packvr.state.phase === "pack", null, "pick a pack by pinching", 90000);
await frames(8);

// Tear: pinch the top edge and drag across.
const strip = (k) => world([-0.07 + k * 0.03, 0.347 * 1.2 * 0.4, 0.02]);
await hand(await strip(0));
await frames(3);
await hook(() => __trigger(1));
await frames(3);
for (let k = 1; k <= 12; k++) { await hand(await strip(k)); await frames(1.5); }
await hook(() => __trigger(0));
await wait(() => __packvr.state.phase === "cards", null, "tear the pack with a drag", 30000);

// Cards: wait for the reveal, grab it, flick it sideways.
for (let i = 0; i < 9; i++) {
  await wait((i) => __packvr.state.idx === i && __packvr.cards[i]?.state === "revealed", i, `reveal card ${i + 1}`, 40000);
  await frames(2);
  const at = await world([0, 0.03, 0.1]);
  await hand(at);
  await frames(2);
  await hook(() => __trigger(1));
  await frames(3);
  if (i === 8) await shot("xr-2-card-in-hand");
  for (let k = 1; k <= 5; k++) { await hook(([at, k]) => __aim([at[0] + 0.1 + k * 0.1, at[1] - 0.12, at[2] + 0.3], [at[0] + k * 0.1, at[1], at[2]]), [at, k]); await sleep(60); }
  await hook(() => __trigger(0));
}
await wait(() => __packvr.state.phase === "summary", null, "throw the last card", 30000);
await frames(8);
await shot("xr-3-summary");
console.log("phase:", await hook(() => __packvr.state.phase));
console.log("pulls:", (await hook(() => __packvr.state.pulls.map((c) => c.name + " (" + c.rarity + ")"))).join("; "));
console.log(t.errors.length ? "console problems:\n" + [...new Set(t.errors)].slice(0, 12).join("\n") : "no console errors");
await t.close();
