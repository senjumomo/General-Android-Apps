// Shared setup for the end-to-end checks: a local web server for PackVR, headless Chromium with software
// WebGL, and external requests (YGOPRODeck, the image relay, Yugipedia, exchange rates) fetched with curl
// and cached, so the page sees the real data. Needs Playwright installed globally.
import { createRequire } from "node:module";
import { execFileSync, execSync, spawn } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";

const require = createRequire(import.meta.url);
const { chromium } = require(execSync("npm root -g").toString().trim() + "/playwright");

export async function launch(out, { width = 1000, height = 700, port = 8765, init = null } = {}) {
  const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..");
  const cache = path.join(out, "cache");
  fs.mkdirSync(cache, { recursive: true });
  const server = spawn("python3", ["-m", "http.server", String(port), "--bind", "127.0.0.1"], { cwd: root, stdio: "ignore" });
  await new Promise((r) => setTimeout(r, 800));

  const fetchExternal = (url) => {
    let real = url;
    const m = url.match(/^https:\/\/wsrv\.nl\/\?url=([^&]+)/);
    if (m) real = "https://" + decodeURIComponent(m[1]);          // the relay only adds a header; get the original
    const f = path.join(cache, crypto.createHash("md5").update(real).digest("hex"));
    if (!fs.existsSync(f)) execFileSync("curl", ["-s", "-L", "-A", "CardBinder/2.0 (Android)", "-o", f, real]);
    const type = /\.png/.test(real) ? "image/png" : /\.jpg/.test(real) ? "image/jpeg" : real.includes("api") ? "application/json" : "application/octet-stream";
    return { body: fs.readFileSync(f), type };
  };

  const browser = await chromium.launch({ args: ["--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist", "--autoplay-policy=no-user-gesture-required"] });
  const page = await browser.newPage({ viewport: { width, height } });
  const errors = [];
  page.on("console", (m) => { if (m.type() === "error" || (m.type() === "warning" && !/GL Driver Message/.test(m.text()))) errors.push(m.type() + ": " + m.text()); });
  page.on("pageerror", (e) => errors.push("PAGEERROR: " + e.message));
  if (init) await page.addInitScript(init);
  await page.route(/^https:\/\//, async (route) => {
    try {
      const { body, type } = fetchExternal(route.request().url());
      await route.fulfill({ body, contentType: type, headers: { "access-control-allow-origin": "*" } });
    } catch (e) { await route.abort(); }
  });

  const h = {
    page, browser, errors, url: `http://127.0.0.1:${port}/index.html`,
    shot: (name) => page.screenshot({ path: path.join(out, name + ".png") }),
    sleep: (ms) => page.waitForTimeout(ms),
    hook: (fn, arg) => page.evaluate(fn, arg),
    async must(promise, label) {
      try { return await promise; } catch (e) {
        console.log("STUCK at:", label, "| phase:", await page.evaluate(() => __packvr.state.phase).catch(() => "?"));
        console.log("problems so far:\n" + [...new Set(errors)].slice(0, 12).join("\n"));
        await page.screenshot({ path: path.join(out, "stuck.png") });
        await h.close(); process.exit(1);
      }
    },
    wait: (fn, arg, label, timeout = 60000) => h.must(page.waitForFunction(fn, arg, { timeout }), label),
    async close() { await browser.close(); server.kill(); },
  };
  return h;
}
