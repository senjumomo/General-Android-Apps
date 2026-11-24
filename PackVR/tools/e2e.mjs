// End-to-end check of the AR page in its flat on-screen preview (no headset needed).
// The mouse stands in for a hand: pick a pack, tear it, look at the cards, throw them, see the summary.
//   node PackVR/tools/e2e.mjs [outDir] [setName]
import { launch } from "./harness.mjs";

const out = process.argv[2] || "/tmp/packvr-e2e";
const setName = process.argv[3] || "Rage of the Abyss";
const t = await launch(out);
const { page, hook, sleep, shot, wait } = t;

await page.goto(t.url);
await wait(() => document.getElementById("status").textContent.includes("sets ready"), null, "sets load");
await page.fill("#filter", setName);
await shot("1-menu");
await page.click("#flat");
await sleep(2500);
await shot("2-shelf");

let p = await hook(() => __packvr.project([0, 0, 0]));
await page.mouse.move(p.x, p.y);
await sleep(200);
await page.mouse.down(); await page.mouse.up();                   // even a very quick tap counts
await wait(() => __packvr.state.phase === "pack", null, "pick pack", 90000);
await sleep(1200);
await shot("3-pack");

// Tear: press on the top strip, drag across.
const top = await hook(() => __packvr.project([-0.07, 0.347 * 1.2 * 0.4, 0.02]));
await page.mouse.move(top.x, top.y);
await sleep(150);
await page.mouse.down();
await sleep(150);
for (let k = 1; k <= 5; k++) { await page.mouse.move(top.x + k * 30, top.y); await sleep(60); }
await shot("4-tearing");
for (let k = 6; k <= 16; k++) { await page.mouse.move(top.x + k * 30, top.y); await sleep(60); }
await page.mouse.up();
await wait(() => __packvr.state.phase === "cards", null, "rip", 20000);

// The cards: wait until each is revealed, look at it, flick it away.
for (let i = 0; i < 9; i++) {
  await wait((i) => __packvr.state.idx === i && __packvr.cards[i]?.state === "revealed", i, `reveal card ${i + 1}`, 30000);
  await sleep(i >= 7 ? 1800 : 500);
  if (i === 0 || i >= 7) await shot(`7-card-${i + 1}`);
  const c = await hook(() => __packvr.project([0, 0.03, 0.1]));
  await page.mouse.move(c.x, c.y);
  await sleep(120);
  await page.mouse.down();
  await sleep(150);                                              // a real grab lasts more than a frame
  if (i === 8) { await page.mouse.move(c.x + 60, c.y - 40, { steps: 4 }); await sleep(300); await shot("8-card-in-hand"); }
  for (let k = 1; k <= 5; k++) { await page.mouse.move(c.x + k * 110, c.y - k * 6); await sleep(40); }   // a flick
  await page.mouse.up();
}
await wait(() => __packvr.state.phase === "summary", null, "summary", 20000);
await sleep(2000);
await shot("9-summary");
console.log("phase:", await hook(() => __packvr.state.phase));
console.log("pulls:", (await hook(() => __packvr.state.pulls.map((c) => c.name + " (" + c.rarity + ")"))).join("; "));
console.log(t.errors.length ? "console problems:\n" + [...new Set(t.errors)].slice(0, 12).join("\n") : "no console errors");
await t.close();
