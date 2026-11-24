// Screenshots of the big-pull effects (rarity banner, god rays, glow, shockwave) in the flat preview.
// It swaps the pack's nine cards for a hand-picked line-up (commons, a Rare, a Super, an Ultra, a Secret),
// because waiting for a real one would take forever. Look at the PNGs it writes.
//   node PackVR/tools/shots-rare.mjs [outDir] [setName]
import { launch } from "./harness.mjs";

const out = process.argv[2] || "/tmp/packvr-rare";
const setName = process.argv[3] || "Rage of the Abyss";
const t = await launch(out, { width: 1100, height: 760 });
const { page, hook, sleep, shot, wait } = t;

await page.goto(t.url + "?fx=2");
await wait(() => document.getElementById("status").textContent.includes("sets ready"), null, "sets load");
await page.fill("#filter", setName);
await sleep(1200);
await page.click("#flat");
await wait(() => __packvr.state.phase === "shelf", null, "shelf", 30000);
await sleep(2500);
let p = await hook(() => __packvr.project([0, 0, 0]));
await page.mouse.move(p.x, p.y); await sleep(700);
await shot("s0-shelf-hover");                                       // the pack under the pointer lifts and glows
await page.mouse.down(); await page.mouse.up();
await wait(() => __packvr.state.phase === "pack", null, "pick pack", 90000);

// Swap in the line-up (the rest of the page reads state.pulls when the pack rips).
const names = await hook(() => {
  const by = (r) => __packvr.state.cards.filter((c) => c.rarity.includes(r));
  const pick = (...rs) => { for (const r of rs) { const l = by(r); if (l.length) return l[Math.floor(l.length / 2)]; } return __packvr.state.cards[0]; };
  const commons = by("Common").slice(0, 5);
  const line = [...commons, pick("Rare"), pick("Super"), pick("Ultra"), pick("Secret", "Starlight", "Ghost", "Gold")];
  const pulls = __packvr.state.pulls;
  pulls.splice(0, pulls.length, ...line.slice(0, 9));
  return pulls.map((c) => c.name + " (" + c.rarity + ")");
});
console.log(names.join("\n"));
await sleep(1500);

const top = await hook(() => __packvr.project([-0.07, 0.347 * 1.2 * 0.4, 0.02]));
await page.mouse.move(top.x, top.y); await sleep(200);
await page.mouse.down(); await sleep(150);
for (let k = 1; k <= 16; k++) { await page.mouse.move(top.x + k * 30, top.y); await sleep(60); }
await shot("r1-tearing-end");
await page.mouse.up();
await sleep(350);
await shot("r2-ripped");

async function flick() {
  const c = await hook(() => __packvr.project([0, 0.03, 0.1]));
  await page.mouse.move(c.x, c.y); await sleep(150);
  await page.mouse.down(); await sleep(150);
  for (let k = 1; k <= 5; k++) { await page.mouse.move(c.x + k * 110, c.y - k * 6); await sleep(40); }
  await page.mouse.up();
}
for (let i = 0; i < 9; i++) {
  await wait((i) => __packvr.state.phase === "cards" && __packvr.state.idx === i && __packvr.cards[i]?.state === "revealed", i, `card ${i + 1}`, 60000);
  if (i >= 5) {
    await shot(`c${i + 1}-a`);                                      // just revealed: effects at their peak
    await sleep(900);
    await shot(`c${i + 1}-b`);
  } else await sleep(150);
  if (i === 8) { await sleep(1200); await shot("c9-c"); break; }
  await flick();
}
console.log(t.errors.length ? "console problems:\n" + [...new Set(t.errors)].slice(0, 12).join("\n") : "no console errors");
await t.close();
