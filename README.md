# OpenBand – unofficial companion app for Pebble Qore 2 (and similar bands)

Install file: `OpenBand.apk` (signed, Android 8+). Share it with anyone; they install it and pair their own band.

## Data sources
1. **Health Connect (automatic, every 15 min):** reads every type: steps, calories, distance, heart rate,
   resting HR, SpO2, HRV, temperature, respiratory rate, sleep stages, BP, weight, VO2 max, exercise.
2. **Direct Bluetooth from the band:** standard HR/Battery/Device-info profiles, plus the built-in
   "QRing-family" OEM driver (steps, HR log, sleep, SpO2, live HR/SpO2/HRV/stress) if the band uses it.
   Data read from the band is also written back to Health Connect (can be switched off).
3. **Protocol Lab (auto-learn):** import an HCI snoop log of the official app. OpenBand replays the same
   commands on every sync and stores all responses. "Export lab report" gives a developer everything
   needed to write a full decoder.

## First-time setup
1. Install `OpenBand.apk` (allow "Install unknown apps").
2. **Health** tab → Grant access → *Allow all*.
3. **Band** tab → Allow Bluetooth → close/unpair the official app → Scan → tap your band.
4. **Band** tab → *Allow background* (for fully automatic sync).

## v2.0 – racing UI
- Dash: F1 shift lights, analog/sport/digital gauges (HR tacho, steps speedo, SpO2, HRV, temp, stress, fuel = battery)
- Garage (band), Pit wall (Health Connect), Laps (history), Livery (customisation), ECU diagnostics (protocol lab)
- Livery: 10 colour schemes + custom colours, gauge style, font, shift lights, carbon/OLED, layout order,
  goals, age/redline, units, driver name + car number
- Crash reporter: if the app ever crashes, the next launch shows the error with a Share button

## Build
```
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew assembleRelease
```
Output: `app/build/outputs/apk/release/app-release.apk` (signed with `keystore/openband.jks`, password `openband`).
Keep the keystore: future updates must be signed with the same key.

## Code map
- `ble/BandManager.kt`: connection, GATT queue, auto-reconnect, sync
- `ble/QRingProtocol.kt`, `ble/StandardProtocol.kt`, `ble/LearnedProtocol.kt`: protocol drivers (add new ones here)
- `ble/SnoopImporter.kt`: btsnoop / bug-report parser
- `health/HealthRepo.kt`: Health Connect import/export
- `sync/SyncWorker.kt`: 15-minute background job and boot receiver
- `ui/*`: Compose screens (Today, Band, Health, History, Lab)
