# Life OS Android

Use native Kotlin/Compose and Glance widgets. Keep the working UI compact, Russian labels short, touch targets accessible, and user text readable. The repository is independent of the web service.

All mutations go through the durable optimistic outbox. Never replace server state wholesale. Preserve mutation IDs and timestamps across retries; keep confirmed revision separate from optimistic projections. Do not discard queued changes on errors or reconnection.

Never put credentials, passwords, tokens, signed feed URLs or private production data in source, screenshots, logs or tests. Use fake data and isolated test servers. Production tests are read-only unless explicitly authorized.

Run focused unit tests, `lintDebug`, and `assembleDebug` after changes. For widgets, verify on an emulator/device when available. Preserve unrelated work. Do not open a browser without the user's explicit permission.

## Cursor Cloud specific instructions

JDK 17 is `JAVA_HOME` (`/usr/lib/jvm/java-17-openjdk-amd64`). Android SDK 36, Build Tools 35.0.0, and platform-tools are at `ANDROID_HOME` (`/opt/android-sdk`). Login shells export both, so `./gradlew testDebugUnitTest lintDebug assembleDebug` is the Android check.

The sibling `personal-life-os` app listens on `http://127.0.0.1:3000` after boot. Startup applies the `drizzle/` SQL files to the local D1 database before `npm run dev`. Open `/signin-with-chatgpt` for the local mock account. Node 22.22 from nvm is first on PATH; `npm test` needs it because the suite imports TypeScript. That command runs domain tests without a server. The HTTP contract files (`tests/api.test.mjs` and `tests/*-api.test.mjs`) share one workspace revision, so run them one file at a time with `LIFE_OS_TEST_URL=http://127.0.0.1:3000`. `npm run check` is the type check. `npm run lint` currently exits non-zero because of existing oxlint findings.

Do not point tests at a personal or production server. Use the local dev server and fake data.
