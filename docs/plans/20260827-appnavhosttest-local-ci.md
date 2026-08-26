# Устранить гонку AppNavHostTest и принудительно запускать тесты локально

## Overview

Исправить нестабильное завершение `AppNavHostTest`: тест закрывает Room раньше, чем Compose отменяет сбор `observeAll()`. Сделать `make test` и `make ci` принудительно выполнять unit-тесты, не принимая предыдущий успешный результат Gradle как `UP-TO-DATE`.

## Context

- Files involved:
  - `app/src/test/java/io/github/kemko/grimmoryuploader/ui/AppNavHostTest.kt`
  - `Makefile`
  - `README.md`
- Полный GitHub Actions stack trace проходит через `UploadJobDao_Impl.observeAll` и `collectAsState`: база закрывается строкой `container.database.close()`, пока композиция ещё активна.
- Это не Android Lint, а нестабильный Robolectric/Compose unit-тест.
- Локальная полная suite уже обнаруживала тот же сбой, но повторный запуск прошёл; последующие `make ci` могли использовать успешный результат `testDebugUnitTest` как `UP-TO-DATE`.
- Изолированный и принудительный полный запуск локально проходят, что подтверждает зависимость от порядка завершения и тайминга.
- GitHub CI корректно вызывает `make ci`; production-код и workflow менять не требуется.
- Related patterns:
  - JUnit `RuleChain` для гарантированного обратного порядка освобождения ресурсов.
  - Compose test rule должен завершить композицию и отменить Room Flow до закрытия базы.
  - Gradle task option `--rerun` повторяет только тестовую задачу, сохраняя инкрементальность её зависимостей.
- Dependencies: новых зависимостей нет.

## Development Approach

- **Testing approach**: TDD — сначала воспроизвести сбой принудительным запуском, затем исправить lifecycle теста.
- Complete each task fully before moving to the next.
- Не менять `AppNavHost`, Room DAO или production lifecycle: стек указывает на ошибку teardown теста.
- Каждый вызов локального test gate должен реально выполнять `testDebugUnitTest`.
- **CRITICAL: every task MUST include new/updated tests.**
- **CRITICAL: all tests must pass before starting next task.**

## Implementation Steps

### Task 1: Исправить lifecycle Compose/Room-теста и локальный test gate

**Files:**

- Modify: `app/src/test/java/io/github/kemko/grimmoryuploader/ui/AppNavHostTest.kt`
- Modify: `Makefile`

- [x] Перенести создание `AppContainer` в общий setup тестового класса и убрать закрытие базы из тел отдельных тестов.
- [x] Обернуть Compose rule и cleanup базы в `RuleChain`, где Compose rule является внутренним: сначала завершается композиция и отменяется `collectAsState`, затем закрывается `UploadDatabase`.
- [x] Перевести правило этого класса на Compose test API v2 и сохранить явные ожидания асинхронных результатов.
- [x] Сохранить проверки чтения metadata вне main thread, фиксации incoming intent до onboarding и ожидания startup reconciliation.
- [x] Изменить `make test` на `testDebugUnitTest --rerun`, чтобы `make test` и зависимый `make ci` не пропускали тестовую задачу после предыдущего успешного запуска.
- [x] Последовательно выполнить исправленный `AppNavHostTest` не менее пяти раз с `--rerun`; каждый запуск прошёл без `SQLiteConnectionPool` ошибок.
- [x] Запустить `make test` дважды; в обоих запусках `:app:testDebugUnitTest` был выполнен, а не отмечен `UP-TO-DATE`.

### Task 2: Verify acceptance criteria

- [x] Запустить `make format-check`.
- [x] Запустить `make lint`.
- [x] Запустить `make test`.
- [x] Запустить `make security`.
- [x] Запустить `make coverage` и подтвердить минимум 80% line coverage.
- [x] Запустить `make build`.
- [x] Запустить `make ci`; все проверки должны пройти, а `testDebugUnitTest` должен реально выполниться.

### Task 3: Update documentation

**Files:**

- Modify: `README.md`

- [x] Уточнить описание `make test`/`make ci`: unit-тесты принудительно выполняются при каждом вызове локального gate.
- [x] Исправить описание среды GitHub CI: workflow использует bundled JDK 25, тогда как локальная macOS-разработка документирована для JDK 26.
- [x] Сверить перечисленные Make targets с фактическими рецептами после изменения.

## Post-Completion

- После push GitHub Actions должен пройти без ошибки закрытого `SQLiteConnectionPool`.
- Если сбой сохранится после корректного teardown, скачать новый test artifact и сравнить stack trace; расширение production lifecycle не входит в текущий план без нового подтверждения.
