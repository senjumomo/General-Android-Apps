// Set lists, card lists and pack generation: a port of the Android app's PackData.kt / HoloStyle.kt,
// so the pull rates are the same. Data comes from YGOPRODeck (which allows web pages to read it).

const API = "https://db.ygoprodeck.com/api/v7";

// --- Rarity -> foil mode (HoloStyle.mode) and tier (Tier.of) --------------------------------------
export const MODE = { COMMON: 0, RARE: 1, SUPER: 2, ULTRA: 3, SECRET: 4, PRISMATIC: 5, ULTIMATE: 6, GHOST: 7,
  STARLIGHT: 8, COLLECTORS: 9, PLATINUM: 10, QUARTER_CENTURY: 11, GOLD: 12, PARALLEL: 13 };

export function foilMode(rarity) {
  const r = rarity.toLowerCase();
  const has = (s) => r.includes(s);
  if (has("quarter century")) return MODE.QUARTER_CENTURY;
  if (has("starlight")) return MODE.STARLIGHT;
  if (has("collector")) return MODE.COLLECTORS;
  if (has("ghost")) return MODE.GHOST;
  if (has("ultimate")) return MODE.ULTIMATE;
  if (has("platinum")) return MODE.PLATINUM;
  if (has("prismatic")) return MODE.PRISMATIC;
  if (has("secret")) return MODE.SECRET;
  if (has("gold")) return MODE.GOLD;
  if (has("starfoil") || has("mosaic") || has("shatterfoil") || has("parallel")) return MODE.PARALLEL;
  if (has("ultra")) return MODE.ULTRA;
  if (has("super")) return MODE.SUPER;
  if (has("rare")) return MODE.RARE;
  return MODE.COMMON;
}

export const TIER = { COMMON: 0, RARE: 1, SUPER: 2, ULTRA: 3, SECRET: 4, PREMIUM: 5 };

export function tierOf(rarity) {
  switch (foilMode(rarity)) {
    case MODE.COMMON: return TIER.COMMON;
    case MODE.RARE: return TIER.RARE;
    case MODE.SUPER: case MODE.PARALLEL: return TIER.SUPER;
    case MODE.ULTRA: case MODE.ULTIMATE: case MODE.GOLD: return TIER.ULTRA;
    case MODE.SECRET: case MODE.PRISMATIC: case MODE.PLATINUM: case MODE.GHOST: return TIER.SECRET;
    default: return TIER.PREMIUM;
  }
}

/** Pendulum artwork is wider and shorter (HoloStyle.artRect). */
export const artRect = (pendulum) => pendulum ? [0.067, 0.178, 0.933, 0.629] : [0.119, 0.180, 0.881, 0.705];

export const baseRarity = (r) => r.split(" / ")[0].replace(/^1st Edition /, "").replace(/^Limited Edition /, "").trim();

// --- Data ----------------------------------------------------------------------------------------
const EXCLUDED = ["structure deck", "starter deck", "speed duel", "promo", "prize", "tournament", "sneak peek",
  "participation", "championship", "starter set", "deck:", "demo", "token", "jumbo", "duelist league"];

/** Booster-like sets only (enough cards, not decks/promos), newest first. */
export function parseSets(list) {
  const seen = new Set();
  return list
    .map((o) => ({ name: o.set_name || "", code: o.set_code || "", cardCount: o.num_of_cards || 0, date: o.tcg_date || "" }))
    .filter((s) => s.cardCount >= 30 && !EXCLUDED.some((e) => s.name.toLowerCase().includes(e)))
    .filter((s) => (seen.has(s.name) ? false : (seen.add(s.name), true)))
    .sort((a, b) => (a.date < b.date ? 1 : a.date > b.date ? -1 : 0));
}

export async function loadSets() {
  const res = await fetch(`${API}/cardsets.php`);
  if (!res.ok) throw new Error("Couldn't load the sets (" + res.status + ")");
  return parseSets(await res.json());
}

/** Cards printed in [setName], one entry per printing (a card can appear in several rarities). */
export function parseSetCards(json, setName) {
  const out = [];
  const seen = new Set();
  for (const c of json.data || []) {
    const artwork = c.card_images?.[0]?.id ?? c.id;
    const cardPrice = parseFloat(c.card_prices?.[0]?.tcgplayer_price);
    for (const s of c.card_sets || []) {
      if ((s.set_name || "").toLowerCase() !== setName.toLowerCase()) continue;
      const setPrice = parseFloat(s.set_price);
      const key = s.set_code + "|" + s.set_rarity;
      if (seen.has(key)) continue;
      seen.add(key);
      out.push({
        name: c.name, code: s.set_code, rarity: s.set_rarity, artworkId: artwork, type: c.type || "",
        priceUsd: setPrice > 0 ? setPrice : cardPrice > 0 ? cardPrice : null,
      });
    }
  }
  return out.map((c) => ({ ...c, tier: tierOf(c.rarity) }));
}

export async function loadSetCards(set) {
  const res = await fetch(`${API}/cardinfo.php?cardset=${encodeURIComponent(set.name)}`);
  if (!res.ok) throw new Error("Couldn't load " + set.name + " (" + res.status + ")");
  return parseSetCards(await res.json(), set.name);
}

/** The card whose artwork goes on the pack: the rarest, most valuable one. */
export function featured(cards) {
  return cards.filter((c) => !c.type.toLowerCase().includes("token"))
    .reduce((best, c) => !best || c.tier > best.tier || (c.tier === best.tier && (c.priceUsd || 0) > (best.priceUsd || 0)) ? c : best, null);
}

// Card images: YGOPRODeck doesn't allow web pages to use its images in 3D (no CORS header), so they go
// through wsrv.nl, a free image relay that adds the header (and can shrink the file for the headset).
const relay = (path, w) => `https://wsrv.nl/?url=${encodeURIComponent("images.ygoprodeck.com/images/" + path)}${w ? "&w=" + w : ""}`;
export const cardImageUrl = (id) => relay(`cards/${id}.jpg`, 700);
export const croppedArtUrl = (id) => relay(`cards_cropped/${id}.jpg`, 512);
export const CARD_BACK_URL = "https://img.yugipedia.com/e/e5/Back-EN.png";

// --- Pack generation (PackGenerator) ------------------------------------------------------------
export const PACK_SIZE = 9;
const SPECIAL_ODDS = [
  ["Ultra Rare", 1 / 6], ["Gold Rare", 1 / 6], ["Secret Rare", 1 / 12], ["Ultimate Rare", 1 / 12],
  ["Platinum Secret Rare", 1 / 24], ["Prismatic Secret Rare", 1 / 24], ["Quarter Century Secret Rare", 1 / 24],
  ["Collector's Rare", 1 / 48], ["Ghost Rare", 1 / 288], ["Starlight Rare", 1 / 288],
];

export const canOpen = (cards) => new Set(cards.map((c) => c.code)).size >= PACK_SIZE;

const pick = (list, rnd) => list[Math.floor(rnd() * list.length)];

export function openPack(cards, rnd = Math.random) {
  const byRarity = {};
  for (const c of cards) (byRarity[baseRarity(c.rarity)] ||= []).push(c);
  const commons = [...(byRarity["Common"] || []), ...(byRarity["Short Print"] || [])];
  const rares = byRarity["Rare"] || [];
  const supers = byRarity["Super Rare"] || [];
  const pack = [];
  const pickFrom = (list) => {
    const fresh = list.filter((c) => !pack.some((p) => p.code === c.code));
    return pick(fresh.length ? fresh : list, rnd);
  };
  const foilSlot = () => {
    const present = SPECIAL_ODDS.filter(([r]) => byRarity[r]?.length);
    const baseline = ["Super Rare", "Rare", "Common"].find((r) => byRarity[r]?.length);
    const roll = rnd();
    const sum = present.reduce((a, [, p]) => a + p, 0);
    const scale = !baseline && present.length ? 1 / sum : 1;
    let acc = 0, chosen = null;
    for (const [r, p] of present) { acc += p * scale; if (roll < acc) { chosen = r; break; } }
    const pool = byRarity[chosen ?? baseline ?? Object.keys(byRarity)[0]] || [];
    const fresh = pool.filter((c) => !pack.some((p) => p.code === c.code));
    return pick(fresh.length ? fresh : pool, rnd);
  };
  if (commons.length >= 7) {
    for (let i = 0; i < 7; i++) pack.push(pickFrom(commons));
    if (rares.length) pack.push(pickFrom(rares));
    else if (supers.length) pack.push(pickFrom(supers));
    else pack.push(foilSlot());
    pack.push(foilSlot());
  } else {
    // All-foil set: fill what commons there are, then foil slots.
    const shuffled = [...commons].sort(() => rnd() - 0.5);
    shuffled.slice(0, Math.floor(PACK_SIZE / 2)).forEach((c) => pack.push(c));
    while (pack.length < PACK_SIZE) pack.push(foilSlot());
  }
  // Least exciting first, the big pull last.
  return pack.map((c, i) => [c, i]).sort((a, b) => a[0].tier - b[0].tier || a[1] - b[1]).map(([c]) => c);
}
