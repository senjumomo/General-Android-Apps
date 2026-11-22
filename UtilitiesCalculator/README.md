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
1. Enter previous debt (optional), last and this month's water readings, price per kl, number of tokens and price per token.
2. Totals update as you type. When everything is filled in, the message appears: tap **Copy** or **Share** (e.g. straight to WhatsApp).
3. Next month tap the ↻ button at the top (**Start new month**): this month's reading moves to "last month", prices are remembered.

Material 3 design with Material You colours (follows your wallpaper on Android 12+) and dark mode.
Screenshots: `app/src/test/snapshots/images/`.

## Build
```
./gradlew assembleRelease
./gradlew testDebugUnitTest verifyPaparazziDebug   # unit + screenshot tests
```
Needs the Android SDK (set `sdk.dir` in `local.properties` or `ANDROID_HOME`).
