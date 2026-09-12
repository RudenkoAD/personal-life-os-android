# Life OS Android

Use native Kotlin/Compose and Glance widgets. Keep the working UI compact, Russian labels short, touch targets accessible, and user text readable. The repository is independent of the web service.

All mutations go through the durable optimistic outbox. Never replace server state wholesale. Preserve mutation IDs and timestamps across retries; keep confirmed revision separate from optimistic projections. Do not discard queued changes on errors or reconnection.

Never put credentials, passwords, tokens, signed feed URLs or private production data in source, screenshots, logs or tests. Use fake data and isolated test servers. Production tests are read-only unless explicitly authorized.

Run focused unit tests, `lintDebug`, and `assembleDebug` after changes. For widgets, verify on an emulator/device when available. Preserve unrelated work. Do not open a browser without the user's explicit permission.
