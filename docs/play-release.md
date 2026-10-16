# Despiértame Aquí — Google Play internal release

- Package: `com.angel.despiertameaqui`; version: `1.3` (4).
- Minimum API 24; target API 36; compile API 37.
- Privacy: https://mangelbarboza.github.io/DespiertameAqui/
- Support: Angelbarbozareyes29@gmail.com.

## Build and private signing

Keep the upload key and passwords outside Git. Configure the ignored root `keystore.properties` with `storeFile`, `storePassword`, `keyPassword`, and `keyAlias`. Release tasks fail when that file is missing. Configure the Android SDK in ignored `local.properties` (escape a Windows drive colon as `C\:`).

Run `./gradlew.bat bundleRelease testDebugUnitTest lintRelease` using JDK 21 and installed API 37. The bundle is `app/build/outputs/bundle/release/app-release.aab`.

## Validation

Release build and lint passed. Lint reported 0 errors, 41 warnings and 1 hint, largely existing dependency/deprecation and UI advice. The existing single arithmetic example unit test passed; it provides no functional location coverage. JAR signature verified. No emulator/physical-device test of GPS, screen-off delivery, alarm timing or vendor battery restrictions was performed in this preparation.

## Privacy and permission changes

The app starts its location foreground service from the visible activity after the user starts a trip. The unused ACCESS_BACKGROUND_LOCATION manifest permission was removed; tracking continues with the visible foreground service and while-in-use permission. An explanatory location notice is shown before the initial permission prompt, and the full privacy policy is accessible inside the app. Starting a trip without location permission returns to that explanation. App-local broadcasts now target this package and the receiver uses the AndroidX non-exported flag on every supported Android version. The map includes OpenStreetMap attribution.

Location distance is calculated locally. Map tiles can disclose the displayed region and IP to OpenStreetMap; centering on the device can disclose its location. Android geocoding may send the user's search text to its provider. Data safety must include these external services rather than claiming all app activity stays offline.

## Internal testing limits

Test starting, pausing, resuming and ending a trip; notification denial, approximate-only permission, location revocation; screen-off delivery and power-saving modes; alarm behavior. Verify process death/reopening: current persistence stores state flags, while the service's destination is held in memory. It is a proximity aid, not a guaranteed wake-up or emergency service.

Do not commit private signing material. Do not publish to production before the required closed test and policy review.
