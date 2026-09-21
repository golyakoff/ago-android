# ago-android

AGO Chat's native Android operator client — Kotlin, Jetpack Compose, Material 3.

This repository is documentation-first: everything about why the app is shaped the way it is —
scope, screens, navigation, architecture, identity, tenancy, realtime, offline — lives in
[`docs/`](docs/), not here.

| Question | File |
|---|---|
| Why this app exists, what ships in what order | [`docs/plan.md`](docs/plan.md) |
| Which of `ago-console`'s routes port, and why | [`docs/scope-inventory.md`](docs/scope-inventory.md) |
| Which screen leads to which | [`docs/navigation.md`](docs/navigation.md) |
| Modules, layers, transport, identity, offline | [`docs/architecture.md`](docs/architecture.md) |
| Repository conventions | [`docs/README.md`](docs/README.md) |

## Building

Requires JDK 17 and the Android SDK (`platforms;android-34`, `build-tools;34.0.0` at minimum).

```bash
cd ago-android
./gradlew assembleDebug   # builds app/build/outputs/apk/debug/app-debug.apk
./gradlew test            # JVM unit tests, including :core:domain
```

## License

MIT — see [`LICENSE`](LICENSE).

Everything in this repository is public. The same rule `ago-root/CLAUDE.md` states applies here
verbatim: no secret, no token, no node address, nobody's data.
