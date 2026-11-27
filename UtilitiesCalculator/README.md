# Utilities Calculator

Simple Android app that works out the monthly utilities and builds a message to send to the landlord:

```
Previous Month's Debt: R900

Water: 810kl - 800kl = 10kl x R70 = R700

Electricity: 10 tokens x R100 = R1000

Owing = R2600
```

## Install
Download `apk/UtilitiesCalculator.apk` to your phone and open it (allow "install unknown apps" when asked).

## Use
1. **Settings** (gear icon, top right): set the water price per kl and the price per token once. They're shown on the Calculate page with a **Change** shortcut.
2. **Calculate** tab: check the month at the top (defaults to the current month; tap **Change** to pick another). Enter previous debt (optional), last and this month's water readings, and the number of tokens. Totals update as you type.
3. When everything is filled in, the message appears: tap **Copy** or **Share** (e.g. straight to WhatsApp).
4. Tap **Save to history** to save it under the selected month. You can then start the new month straight from the confirmation.
5. **History** tab: trends for water used, electricity tokens and total owing over the last 12 saved months — a bar chart (tap a bar for its value) with the average line, plus average, latest vs other months, lowest and highest. Below that, every saved month; tap one to see or re-share its message, or delete it.
6. The ↻ button (**Start new month**) moves on to the next month: this month's reading becomes "last month" and debt and tokens are cleared.

### Look and feel
- **Header**: a deep gradient (follows your wallpaper colours on Android 12+) with the big total, a bar
  showing how it splits between debt, water and electricity, and a "Message ready" shortcut. Scroll the page
  and the header shrinks down to a thin strip (total + split bar) that stays put; scroll back to the top and
  it grows again. It also folds down by itself while the keyboard is open.
- **Water is always blue, electricity amber, debt rose, totals indigo**, so they're recognisable at a glance
  (these colours don't follow the wallpaper; the header and the message bubble do).
- Big meter-style number boxes, a −/+ stepper for tokens, and the message shown as a chat bubble.
- History: gradient bar charts with a value bubble, green/red trend chips, a sparkline of recent months
  and a split bar on every saved month.
- Numbers count up, cards rise in, bars grow, and copy/share/save/stepper give a small haptic tick.
  Everything respects the system "remove animations" setting.
- Light and dark mode. Type is Manrope (bundled, SIL Open Font License; see `FONT-LICENSE-Manrope.txt`).

Screenshots: `app/src/test/snapshots/images/`.

## Build
```
./gradlew assembleRelease
./gradlew testDebugUnitTest verifyPaparazziDebug   # unit + screenshot tests
```
Needs the Android SDK (set `sdk.dir` in `local.properties` or `ANDROID_HOME`).
