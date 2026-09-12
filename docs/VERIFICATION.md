# Проверка 0.1.0 · 12 сентября 2026

- `testDebugUnitTest`: 16 тестов, 0 ошибок. Проверены optimistic actions, архивирование, recurrence oracle из веб-сервиса, пересечение дней, шифруемый durable store contract, CAS/retry, receipt replay без повторного POST, reauth с очередью, отказ записи и HTML redirect.
- `lintDebug`: выполнен успешно. Остаются информационные предупреждения о более новых зависимостях и compatibility attributes; baseline и отключение ошибок не использовались.
- `assembleDebug`, `assembleDebugAndroidTest`, `assembleRelease`: выполнены успешно.
- Android 16 / API 36, ARM64 emulator: 6 instrumentation-тестов, 0 ошибок. Проверены реальный Keystore, encrypted cache/outbox, быстрый enqueue при занятой сети, empty-outbox refresh, вход через Compose, capture/completion, Glance Inbox callback, расписание, месяц и переключение месяца/«Сегодня».
- Виджеты проверены через Android AppWidgetHost в отдельной debug-версии. [Inbox](screenshots/widget-inbox.png), [расписание](screenshots/widget-agenda.png), [месяц](screenshots/widget-month.png). Все данные на скриншотах искусственные.
- Подписанный release APK установлен и запущен в эмуляторе. `apksigner verify` проходит; minSdk 26, targetSdk 36, release package `com.personallifeos.mobile`. Debug host и HTTP test configuration в release APK отсутствуют; backup и cleartext выключены.

Проверка не создавала токены, задачи или события на личном сервере. На физическом телефоне пользователя приложение ещё не устанавливалось. Расписание фоновой синхронизации зависит от Android/энергосбережения; автоматическое обновление upstream ICS/CalDAV остаётся ответственностью сервиса. Native event editing сейчас ограничен созданием одноразового события и просмотром; повторяющиеся серии редактируются в веб-приложении.
