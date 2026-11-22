# Card Binder

Android app for keeping track of a Yu-Gi-Oh! card collection.

## Install
Download `apk/CardBinder.apk` to your phone and open it (allow "install unknown apps" when asked).

## Features
- **Import** a collection export (JSON list with `product_name`, `set_name`, `discriminator`, `rarity`,
  `quantity`, `value_per_unit`, `paid_per_unit`, ...). Choose **Merge** (adds new cards, updates matching
  ones by id) or **Replace**.
- **Collection list** with total value, card count, unique printings and gain vs what you paid.
- **Search** by name, set, code or rarity; filter by **set** or **rarity**; sort by name, value, recently
  added, set or quantity.
- **Add / edit** cards: quantity stepper, condition, value and paid price, link to TCGplayer. Delete with undo.
- **Export** the collection back to the same JSON format as a backup.
- Material 3, Material You colours, dark mode. Data stored on the phone (Room database).

## Build
```
./gradlew assembleRelease
./gradlew testDebugUnitTest verifyPaparazziDebug   # unit + screenshot tests
CARD_EXPORT=/path/to/export.json ./gradlew testDebugUnitTest   # also check a real export parses
```
Needs the Android SDK (set `sdk.dir` in `local.properties` or `ANDROID_HOME`).
Screenshots: `app/src/test/snapshots/images/`.
