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
1. Enter previous debt (optional), previous and current water readings, price per KL, number of tokens and price per token.
2. Tap **Calculate**, then **Copy** or **Share** (e.g. straight to WhatsApp).
3. Next month tap **Start new month**: the current reading moves to "previous", prices are remembered.

## Build
```
./gradlew assembleRelease
```
Needs the Android SDK (set `sdk.dir` in `local.properties` or `ANDROID_HOME`).
