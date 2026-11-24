// End-to-end check of the AR page in its flat on-screen preview (no headset needed).
// The mouse stands in for a hand: pick a pack, put it back, tear it, look at the cards, go back a card,
// skip ahead, inspect cards in the summary, open another pack, browse by year, exit.
//   node PackVR/tools/e2e.mjs [outDir] [setName]
import { launch } from "./harness.mjs";

const out = process.argv[2] || "/tmp/packvr-e2e";
const setName = process.argv[3] || "Rage of the Abyss";
const t = await launch(out, { width: 1100, height: 760 });
const { page, hook, sleep, shot, wait } = t;
const fails = [];
const check = (ok, what) => { console.log((ok ? "ok   " : "FAIL ") + what); if (!ok) fails.push(what); };
const phase = () => hook(() => __packvr.state.phase);

// Buttons are found through the page's test hook ("dockBtn.shelf", "pager.r", "screenButtons.3") and clicked with the mouse.
const btnAt = (path) => hook((p) => { const b = p.split(".").reduce((o, k) => o?.[k], __packvr); return b && b.interactive() ? __packvr.buttonAt(b) : null; }, path);
const clickBtn = async (path, label = path) => {
  await wait((p) => { const b = p.split(".").reduce((o, k) => o?.[k], __packvr); return b && b.interactive(); }, path, `button ${label} to be usable`, 20000);
  await sleep(150);
  const at = await btnAt(path);
  await page.mouse.move(at.x, at.y);
  await sleep(200);
  await page.mouse.down(); await sleep(80); await page.mouse.up();
  await sleep(150);
};
const waitPhase = (p, label, ms = 30000) => wait((p) => __packvr.state.phase === p, p, label || `phase ${p}`, ms);
const waitRevealed = (i) => wait((i) => __packvr.state.idx === i && __packvr.cards[i]?.state === "revealed", i, `card ${i + 1} revealed`, 40000);

async function flick() {                                           // grab the card in front of you and throw it sideways
  const c = await hook(() => __packvr.project([0, 0.03, 0.1]));
  await page.mouse.move(c.x, c.y);
  await sleep(150);
  await page.mouse.down();
  await sleep(150);
  for (let k = 1; k <= 5; k++) { await page.mouse.move(c.x + k * 110, c.y - k * 6); await sleep(40); }
  await page.mouse.up();
}
async function pickMiddlePack() {
  const p = await hook(() => __packvr.project([0, 0, 0]));
  await page.mouse.move(p.x, p.y);
  await sleep(250);
  await page.mouse.down(); await page.mouse.up();                  // even a very quick tap counts
}
async function tear() {
  const top = await hook(() => __packvr.project([-0.07, 0.347 * 1.2 * 0.4, 0.02]));
  await page.mouse.move(top.x, top.y);
  await sleep(200);
  await page.mouse.down();
  await sleep(150);
  for (let k = 1; k <= 16; k++) { await page.mouse.move(top.x + k * 30, top.y); await sleep(60); if (k === 5) await shot("4-tearing"); }
  await page.mouse.up();
}

await page.goto(t.url + "?fx=2");                                  // ?fx=2: keep every effect on (the slow test machine would trigger the thinning-out)
await wait(() => document.getElementById("status").textContent.includes("sets ready"), null, "sets load");
await page.fill("#filter", setName);
await sleep(1500);
await shot("1-menu");
await page.click("#flat");
await waitPhase("shelf", "shelf");
await sleep(4000);
await shot("2-shelf");
check(await hook(() => __packvr.shelf.length === 5), "the shelf has five packs");
check(await hook(() => __packvr.shelf[2].set.name) === setName, "the chosen set is in the middle");

// --- Pick a pack, then put it back --------------------------------------------------------------
await pickMiddlePack();
await waitPhase("pack", "pick pack", 90000);
await sleep(1200);
await shot("3-pack");
check(await hook(() => __packvr.dockBtn.shelf.interactive()), "the Shelf button is there once a pack is picked");
await clickBtn("dockBtn.shelf", "Shelf");
await waitPhase("shelf", "back to the shelf from a picked pack");
check(true, "back to the shelf from a picked pack");
await sleep(2500);
check(await hook(() => __packvr.shelf[2].set.name) === setName, "the same set is back in the middle");

// --- Pick again, tear it, go through some cards ------------------------------------------------
await pickMiddlePack();
await waitPhase("pack", "pick pack again", 90000);
await sleep(1200);
await tear();
await waitPhase("cards", "rip");
await waitRevealed(0);
await sleep(600);
await shot("5-card-1");
check(await hook(() => !__packvr.dockBtn.prev.enabled), "Previous is off on the first card");
await flick();
await waitRevealed(1);
await sleep(500);
check(await hook(() => __packvr.dockBtn.prev.enabled && __packvr.dockBtn.prev.interactive()), "Previous is on from the second card");
await clickBtn("dockBtn.prev", "Previous");
await waitRevealed(0);
check(await hook(() => __packvr.state.idx === 0 && __packvr.cards[0].group.visible), "Previous brings the first card back");
await sleep(500);
await shot("6-previous");
await flick(); await waitRevealed(1);
await flick(); await waitRevealed(2);
await sleep(400);

// Leaving mid-pack asks first.
await clickBtn("dockBtn.shelf", "Shelf (mid pack)");
await sleep(500);
check(await phase() === "cards", "Shelf in the middle of a pack asks first (still on the cards)");
await shot("6b-asks");
await sleep(6000);
check(await hook(() => __packvr.dockBtn.shelf.st.label === "Shelf"), "the question goes away by itself");

// --- Skip to the summary, look at a card, open another ------------------------------------------
await clickBtn("dockBtn.skip", "Skip");
await waitPhase("summary", "summary after Skip");
await sleep(2500);
await shot("7-summary");
check(await hook(() => __packvr.cards.every((c) => c.group.visible && c.state === "summary")), "all nine cards are laid out");

const ci = await hook(() => { const c = __packvr.cards[8]; const v = c.home.toArray(); return __packvr.project(v); });
await page.mouse.move(ci.x, ci.y);
await sleep(300);
await shot("7b-hover");
await page.mouse.down();
await sleep(200);
await page.mouse.move(ci.x + 40, ci.y - 60, { steps: 4 });
await sleep(600);
await shot("7c-grabbed");
check(await hook(() => __packvr.cards[8].held !== null), "a card in the summary can be picked up");
await page.mouse.up();
await sleep(1500);
check(await hook(() => __packvr.cards[8].state === "summary" && !__packvr.cards[8].held), "and it goes back to its place");

await clickBtn("screenButtons.0", "Open another pack");
await waitPhase("pack", "another pack of the same set", 60000);
check(await hook(() => __packvr.state.set.name) === setName, "Open another pack gives the same set");
await sleep(1200);
await tear();
await waitPhase("cards", "rip the second pack");
await waitRevealed(0);
await clickBtn("dockBtn.skip", "Skip");
await waitPhase("summary", "second summary");
await sleep(2000);

// --- Back to the shelf from the summary, browse by year ----------------------------------------
await clickBtn("screenButtons.1", "Back to shelf");
await waitPhase("shelf", "back to the shelf from the summary");
await sleep(2500);
check(await hook((n) => __packvr.shelf.some((s) => s.set.name === n), setName), "Back to shelf from the summary shows the set you were opening");
await clickBtn("dockBtn.sets", "Sets");
await waitPhase("years", "year picker");
await sleep(1500);
await shot("8-years");
await clickBtn("dockBtn.shelf", "Back (from the year picker)");
await waitPhase("shelf", "closing the year picker");
await sleep(2000);
check(await hook((n) => __packvr.shelf.some((s) => s.set.name === n), setName), "closing the year picker leaves the shelf as it was");
await clickBtn("dockBtn.sets", "Sets (again)");
await waitPhase("years", "year picker again");
await sleep(800);
const year = await hook(() => __packvr.screenButtons[3].st.text);
await clickBtn("screenButtons.3", "a year chip");
await waitPhase("shelf", "shelf for that year");
await sleep(3500);
await shot("9-year-shelf");
check(await hook((y) => __packvr.state.year === y && __packvr.shelf.length > 0 && __packvr.shelf.every((s) => s.set.date.startsWith(y)), year), `the shelf shows only sets from ${year}`);

// --- Exit ---------------------------------------------------------------------------------------
await clickBtn("dockBtn.exit", "Exit");
await sleep(400);
check(await phase() === "shelf", "Exit asks before leaving");
await clickBtn("dockBtn.exit", "Exit (confirm)");
await waitPhase("menu", "back to the start page");
check(await page.isVisible("#menu"), "Exit shows the start page again");
await shot("10-menu-again");

console.log(t.errors.length ? "console problems:\n" + [...new Set(t.errors)].slice(0, 12).join("\n") : "no console errors");
if (t.errors.length) fails.push("console errors");
console.log(fails.length ? `${fails.length} problem(s)` : "all good");
await t.close();
process.exit(fails.length ? 1 : 0);
