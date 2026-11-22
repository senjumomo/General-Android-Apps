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
2. **Calculate** tab: enter previous debt (optional), last and this month's water readings, and the number of tokens. Totals update as you type.
3. When everything is filled in, the message appears: tap **Copy** or **Share** (e.g. straight to WhatsApp).
4. Tap **Save to history** and pick the month. You can then start the new month straight from the confirmation.
5. **History** tab: every saved month with its total, water and electricity breakdown, plus the average per month and the overall total. Tap a month to see or re-share its message, or delete it.
6. The ↻ button (**Start new month**) moves this month's reading to "last month" and clears debt and tokens.

Material 3 design with Material You colours (follows your wallpaper on Android 12+) and dark mode.
Screenshots: `app/src/test/snapshots/images/`.

## Build
```
./gradlew assembleRelease
./gradlew testDebugUnitTest verifyPaparazziDebug   # unit + screenshot tests
```
Needs the Android SDK (set `sdk.dir` in `local.properties` or `ANDROID_HOME`).
