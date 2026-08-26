# Понятные OIDC-ошибки и настройка Grimmory/Pocket ID

## Overview

Исправить обработку ошибок во всём OIDC-пути, явно показывать источник сбоя и документировать полную настройку Pocket ID, Grimmory и приложения.

## Context

- Основные файлы:
  - `app/src/main/java/io/github/kemko/grimmoryuploader/data/network/GrimmoryApi.kt`
  - `app/src/main/java/io/github/kemko/grimmoryuploader/data/network/ApiModels.kt`
  - `app/src/main/java/io/github/kemko/grimmoryuploader/data/auth/OidcCoordinator.kt`
  - `app/src/main/java/io/github/kemko/grimmoryuploader/ui/auth/AuthViewModel.kt`
  - `app/src/main/java/io/github/kemko/grimmoryuploader/MainActivity.kt`
  - `app/src/main/java/io/github/kemko/grimmoryuploader/ui/AppNavHost.kt`
  - `README.md`
  - `CHANGELOG.md`
- Текущий запрос к `/api/v1/auth/oidc/mobile/callback` совпадает с актуальным контрактом Grimmory: передаются `code`, `code_verifier`, `redirect_uri`, `nonce` и `state`.
- Authorization code обменивает не Android-приложение, а Grimmory. Наблюдаемый `invalid_client` означает, что Pocket ID отклонил аутентификацию Grimmory на token endpoint.
- Для Pocket ID клиент должен быть public, а Client Secret в Grimmory — пустым. Один клиент должен разрешать web callback Grimmory и callback приложения.
- Grimmory отдельно проверяет callback приложения по списку Allowed Mobile Redirect URIs.
- Обнаруженная ошибка приложения: AppAuth возвращает отмену и OAuth-ошибки без `AuthorizationResponse.state`; текущий код маскирует их сообщением `OIDC state mismatch` и не всегда очищает pending-запрос.
- Grimmory возвращает ошибки в JSON-конверте `{status, message, timestamp, details}`, но приложение сейчас показывает необработанное тело ответа.
- Новые зависимости не нужны: достаточно OkHttp, kotlinx.serialization и AppAuth.
- Внешние контракты:
  - https://grimmory.org/api/operations/oidchandlemobilecallback/
  - https://grimmory.org/docs/authentication/pocket-id/
  - https://grimmory.org/docs/authentication/oidc-settings/
  - https://pocket-id.org/docs/guides/oidc-client-authentication

## Development Approach

- **Testing approach**: TDD — сначала добавить регрессионные сценарии, затем минимальную реализацию.
- Каждый task полностью завершается и проходит свой тест до следующего.
- Сохранить текущие лимиты ответа и не выводить необработанный HTML, длинные тела или секреты.
- Не менять рабочий Authorization Code + PKCE протокол и не добавлять собственный token exchange.
- **CRITICAL: every task MUST include new/updated tests**.
- **CRITICAL: all tests must pass before starting next task**.

## Implementation Steps

### Task 1: Структурировать ошибки Grimmory и OIDC provider

**Files:**

- Modify: `app/src/main/java/io/github/kemko/grimmoryuploader/data/network/ApiModels.kt`
- Modify: `app/src/main/java/io/github/kemko/grimmoryuploader/data/network/GrimmoryApi.kt`
- Modify: `app/src/test/java/io/github/kemko/grimmoryuploader/network/GrimmoryApiTest.kt`

- [x] Добавить модели стандартной ошибки Grimmory и OAuth-полей `error`/`error_description`.
- [x] Расширить `ApiException` источником запроса: Grimmory или OIDC provider, сохранив текущую семантику HTTP status для refresh и upload.
- [x] Помечать обычные API-вызовы как Grimmory, а discovery-запрос — как OIDC provider.
- [x] Для non-2xx извлекать краткое сообщение и OAuth-код; при неизвестном или не-JSON теле использовать ограниченный безопасный fallback.
- [x] Добавить тест наблюдаемого ответа Grimmory с вложенным `invalid_client`, прямой provider error, пустого/не-JSON ответа и существующих ограничений размера.
- [x] Run `./gradlew :app:testDebugUnitTest --tests '*GrimmoryApiTest'`; тест должен пройти до Task 2.

### Task 2: Исправить обработку AppAuth callback

**Files:**

- Modify: `app/src/main/java/io/github/kemko/grimmoryuploader/data/auth/OidcCoordinator.kt`
- Modify: `app/src/test/java/io/github/kemko/grimmoryuploader/auth/OidcCoordinatorTest.kt`

- [x] Разделить успешный callback, OAuth-ошибку provider, отмену, внутреннюю ошибку AppAuth и state mismatch.
- [x] Для OAuth error брать `state` из redirect URI в `Intent.data`, проверять его и сохранять отдельно `error` и `error_description`.
- [x] Не заменять отмену, отсутствие браузера и другие AppAuth general errors ложным `OIDC state mismatch`.
- [x] Очищать pending-запрос после всех терминальных результатов; при поддельном state не выполнять token exchange.
- [x] Сохранить привязку pending-запроса к Grimmory server URL и точную передачу `code`, verifier, redirect URI, nonce и state.
- [x] Добавить тесты успешного результата, `access_denied`, отмены, отсутствующего браузера, неверного state, replay и отсутствия вызова Grimmory при ошибке provider.
- [x] Run `./gradlew :app:testDebugUnitTest --tests '*OidcCoordinatorTest'`; тест должен пройти до Task 3.

### Task 3: Показывать источник и понятное описание ошибки

**Files:**

- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/ui/auth/AuthErrorPresenter.kt`
- Modify: `app/src/main/java/io/github/kemko/grimmoryuploader/ui/auth/AuthViewModel.kt`
- Modify: `app/src/main/java/io/github/kemko/grimmoryuploader/MainActivity.kt`
- Modify: `app/src/main/java/io/github/kemko/grimmoryuploader/ui/AppNavHost.kt`
- Create: `app/src/test/java/io/github/kemko/grimmoryuploader/ui/AuthErrorPresenterTest.kt`
- Modify: `app/src/test/java/io/github/kemko/grimmoryuploader/ui/AuthScreenTest.kt`
- Modify: `README.md`
- Modify: `CHANGELOG.md`

- [x] Ввести небольшой UI-модель ошибки: источник, краткое описание, действие пользователя и необязательный безопасный технический код.
- [x] Различать `OIDC provider`, `Grimmory`, `Grimmory → OIDC provider` и локальную ошибку приложения.
- [x] Для `invalid_client` показывать, что provider отклонил аутентификацию Grimmory, и предложить проверить Client ID, режим Public Client и пустой Client Secret.
- [x] Покрыть известные ошибки: `invalid_grant`, `access_denied`, provider unavailable, invalid token, OIDC disabled/misconfigured, invalid redirect URI/state и user not provisioned.
- [x] Для неизвестных ошибок показывать источник и HTTP status без необработанного JSON, HTML, URL token endpoint или длинного server body.
- [x] Использовать единое представление для local login, OIDC start и результата AppAuth; на AuthScreen явно вывести источник отдельно от описания.
- [x] Расширить README полным пайплайном:
  - создать в Pocket ID один public OIDC client с PKCE;
  - добавить web Redirect URI из Grimmory Provider Configuration Reference и точный `io.github.kemko.grimmoryuploader:/oauth2redirect`;
  - скопировать Client ID и Issuer URI;
  - в Grimmory оставить Client Secret пустым, проверить scopes и Test Connection;
  - добавить callback приложения в Allowed Mobile Redirect URIs без wildcard;
  - включить OIDC Login и настроить auto-provisioning либо заранее создать пользователя;
  - описать последовательность app → provider → app → Grimmory → provider → Grimmory tokens;
  - добавить troubleshooting для `invalid_client`, redirect mismatch, provider reachability и user not provisioned;
  - отметить, что Test Connection не подтверждает корректность client authentication на реальном token exchange.
- [x] Добавить краткую запись об исправлении в `CHANGELOG.md`.
- [x] Добавить unit/UI-тесты точного `invalid_client`, известных Grimmory/provider ошибок, fallback и обновления ошибки после возврата из AppAuth.
- [x] Run `./gradlew :app:testDebugUnitTest --tests '*AuthErrorPresenterTest' --tests '*AuthScreenTest'`; тесты должны пройти до Task 4.

### Task 4: Verify acceptance criteria

- [x] Проверить тестом, что наблюдаемый ответ с `invalid_client` отображается как отказ OIDC provider при аутентификации Grimmory.
- [x] Проверить тестом, что `access_denied` и отмена больше не отображаются как state mismatch.
- [x] Проверить тестом, что собственные ошибки redirect/state/provisioning обозначаются как ошибки Grimmory.
- [x] Проверить точное совпадение callback URI в коде, тестах и README.
- [x] Run `make format-check`.
- [x] Run `make lint`.
- [x] Run `make test`.
- [x] Run `make coverage` and verify coverage remains at least 80%.
- [x] Run `make security`.
- [x] Run `make build`.
- [x] Run `make ci`.

## Post-Completion

Живая проверка требует настроенных Pocket ID и Grimmory:

- Корректный public client с обоими callback URI должен завершать вход.
- Отключённый Public Client или неверный Client ID должен давать понятную ошибку стороны provider.
- Удалённый из Grimmory mobile callback должен давать ошибку стороны Grimmory.
- Отмена в Pocket ID должна отображаться как отмена, а не state mismatch.
