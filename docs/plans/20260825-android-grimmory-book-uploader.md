# Android-приложение для загрузки книг в Grimmory

## Overview

С нуля создать Android-приложение на Kotlin для загрузки FB2, FB2.ZIP, EPUB и PDF в Grimmory через системные меню «Поделиться» и «Открыть с помощью», а также по HTTP(S)-ссылке. Приложение будет сохранять незавершённую операцию при переавторизации, проверять фактический формат, потоково распаковывать FB2.ZIP и пересжимать EPUB без сохранения преобразованного результата.

DJVU не поддерживается и не регистрируется как допустимый формат.

## Context

- Репозиторий пуст; вся структура проекта создаётся с нуля.
- Application ID: `io.github.kemko.grimmoryuploader`.
- Kotlin, Jetpack Compose, single-activity, один Gradle-модуль `app`.
- `minSdk 35`, `compileSdk/targetSdk 36`: API 37 недоступен в установленном SDK; preview SDK не используется.
- Базовый toolchain: JDK 26, Gradle 9.7.1+, AGP 9.2+, Kotlin 2.4.10; зависимости фиксируются на последних совместимых стабильных версиях.
- macOS уже подготовлена: Xcode CLI, Homebrew, JDK 26, Android command-line tools, platform/build-tools 36 и `actionlint` установлены.
- Проверенные API Grimmory:
  - `GET /api/v1/healthcheck`
  - `GET /api/v1/public-settings`
  - `POST /api/v1/auth/login`
  - `POST /api/v1/auth/refresh`
  - `GET /api/v1/auth/oidc/state`
  - `POST /api/v1/auth/oidc/mobile/callback`
  - `GET /api/v1/users/me`
  - `POST /api/v1/files/upload?libraryId=...&pathId=...`
- Автоопределение авторизации использует `oidcEnabled` и `oidcForceOnlyMode` из public settings. Если сервер допускает оба режима, пользователь выбирает локальную или OIDC-авторизацию; выбор запоминается и переопределяется в настройках.
- OIDC выполняется через системный браузер с Authorization Code + PKCE. Redirect URI приложения должен быть добавлен в Grimmory/IdP.
- Источник, переданный как временный `content://`, копируется без изменений в приватный `noBackupFilesDir/pending` до авторизации. Это необходимо, потому что Android может отозвать временный URI grant. Распакованный FB2 и пересжатый EPUB на диск никогда не записываются. Исходник удаляется после успеха, отмены или окончательной ошибки.
- Длительные передачи выполняются через нативный User-Initiated Data Transfer Job (`JobScheduler`, API 34+), а не WorkManager. Задание переживает завершение процесса и перезагрузку.
- Сервер Grimmory принимает потоковый multipart upload. Для преобразованных файлов длина заранее неизвестна, поэтому используется chunked transfer; reverse proxy перед Grimmory тоже должен его принимать.
- Повтор после обрыва начинает скачивание/загрузку заново: API Grimmory не предоставляет resumable upload.
- HTTP разрешается только после явного предупреждения. HTTPS используется по умолчанию. Для динамического HTTP-хоста Android cleartext разрешается на уровне manifest, но блокируется прикладной проверкой без сохранённого подтверждения.
- Android не гарантирует фильтрацию chooser по расширению для `content://`. Поэтому `ACTION_SEND` и `ACTION_VIEW` используют точные MIME-типы и ограниченные suffix-фильтры для generic MIME, а после запуска формат обязательно проверяется по содержимому.
- `ACTION_VIEW` регистрируется только для локальных книг через `content://`/`file://`; HTTP(S)-ссылки принимаются через `ACTION_SEND text/plain`, чтобы приложение не перехватывало обычную веб-навигацию.
- Зависимости:
  - AndroidX Compose, Navigation, Lifecycle и DataStore;
  - Room + KSP для устойчивой очереди;
  - OkHttp и MockWebServer;
  - AppAuth for Android для OIDC/PKCE;
  - Kotlin Coroutines и Serialization;
  - стандартные `ZipFile`, `ZipInputStream`, `ZipOutputStream`, `Deflater.BEST_COMPRESSION`;
  - Robolectric, Compose UI tests, coroutine-test и Kover;
  - ktlint и OWASP Dependency-Check.
- Hilt, WorkManager, отдельные domain-модули и нативные архиваторы не нужны; зависимости собираются вручную через небольшой `AppContainer`.

## Development Approach

- **Testing approach**: Regular — сначала минимальная реализация задачи, затем её unit/integration/UI-тесты.
- Каждая задача завершается полностью до начала следующей.
- API и файловая обработка проектируются потоковыми; в памяти остаются только ограниченные буферы.
- Все внешние данные, URI, URL, имена файлов, XML и ZIP-записи считаются недоверенными.
- Поскольку в песочнице нет сети, при первой необходимости скачать зависимости, проверить удалённый API или выполнить другую сетевую операцию агент сразу запрашивает эскалацию команды, не повторяя заведомо недоступный сетевой вызов в песочнице.
- **CRITICAL: every task MUST include new/updated tests**
- **CRITICAL: all tests must pass before starting next task**

## Implementation Steps

### Task 1: Создать Android-проект и базовые правила разработки

**Files:**

- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Create: `gradle/wrapper/*`
- Create: `gradlew`
- Create: `gradlew.bat`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/GrimmoryUploaderApp.kt`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/MainActivity.kt`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/di/AppContainer.kt`
- Create: `app/src/main/res/**`
- Create: `app/src/test/**/AppSmokeTest.kt`
- Create: `.gitignore`
- Create: `AGENTS.md`

- [x] Настроить Kotlin/Compose-приложение с `minSdk 35`, `compileSdk/targetSdk 36`, JVM target 17 и запуском Gradle на JDK 26.
- [x] Зафиксировать стабильные версии в version catalog; включить configuration cache, dependency locking и Gradle dependency verification.
- [x] Добавить разрешения `INTERNET`, `ACCESS_NETWORK_STATE`, `RUN_USER_INITIATED_JOBS`, `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS`; не запрашивать доступ к общему хранилищу.
- [x] Создать минимальный `AppContainer` для DataStore, Room, HTTP-клиента, auth и upload-компонентов.
- [x] Записать в `AGENTS.md` архитектурные границы, обязательные Make-команды, правила тестирования, безопасности, потоковой обработки и запрет сохранения преобразованных файлов.
- [x] Записать в `AGENTS.md`, что любые требующие сети команды должны сразу запускаться с запросом эскалации, поскольку сеть внутри песочницы недоступна.
- [x] Добавить smoke-тест создания приложения и базового Compose-экрана.
- [x] Запустить `./gradlew testDebugUnitTest assembleDebug`; обе команды должны пройти до Task 2.

### Task 2: Реализовать серверные настройки и авторизацию Grimmory

**Files:**

- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/data/settings/AppSettingsRepository.kt`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/data/network/ServerUrl.kt`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/data/network/GrimmoryApi.kt`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/data/network/ApiModels.kt`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/data/auth/AuthRepository.kt`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/data/auth/TokenStore.kt`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/data/auth/AuthInterceptor.kt`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/data/auth/OidcCoordinator.kt`
- Create: `app/src/test/**/settings/*`
- Create: `app/src/test/**/auth/*`
- Create: `app/src/test/**/network/*`

- [ ] Сохранять в DataStore нормализованный server URL, `libraryId=1`, `pathId=1`, включённое пересжатие EPUB, HTTP-подтверждение и режим авторизации `AUTO/LOCAL/OIDC`.
- [ ] Проверять только HTTP(S)-адреса, поддержать сервер под path prefix, удалять credentials при смене сервера и требовать отдельного подтверждения cleartext HTTP.
- [ ] Реализовать healthcheck, public settings, login, refresh, current-user и multipart upload API.
- [ ] Хранить access/refresh tokens зашифрованными AES-GCM с ключом Android Keystore; не сохранять логин или пароль.
- [ ] Сериализовать параллельные refresh-запросы; перед запросом обновлять истёкший access token, на 401 обновлять и повторять запрос только один раз.
- [ ] Реализовать AUTO-выбор: forced OIDC, только local либо выбор обоих режимов; при недоступном public settings использовать сохранённое ручное переопределение.
- [ ] Реализовать OIDC discovery, server-generated state, PKCE, nonce, Custom Tab callback и обмен кода через `/auth/oidc/mobile/callback`.
- [ ] Покрыть тестами URL-нормализацию, HTTP-защиту, шифрованное хранилище, конкурентный refresh, 401 retry, определение auth mode и OIDC state/PKCE/callback.
- [ ] Запустить `./gradlew testDebugUnitTest`; все тесты должны пройти до Task 3.

### Task 3: Реализовать приём, очередь, распознавание и преобразование книг

**Files:**

- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/share/IncomingIntentParser.kt`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/upload/db/UploadJobEntity.kt`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/upload/db/UploadJobDao.kt`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/upload/db/UploadDatabase.kt`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/upload/UploadQueueRepository.kt`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/upload/StagingStore.kt`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/format/BookFormat.kt`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/format/BookFormatDetector.kt`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/format/BookTransformer.kt`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/format/ZipGuards.kt`
- Create: `app/src/test/resources/books/**`
- Create: `app/src/test/**/share/*`
- Create: `app/src/test/**/upload/*`
- Create: `app/src/test/**/format/*`

- [ ] Зарегистрировать `ACTION_SEND` для EPUB, PDF, FB2/XML, ZIP и generic binary MIME, а также `text/plain` для HTTP(S)-ссылок; не регистрировать DJVU и `ACTION_SEND_MULTIPLE`.
- [ ] Зарегистрировать `ACTION_VIEW` для «Открыть с помощью» с EPUB, PDF, FB2/XML, ZIP и безопасными generic MIME/suffix-фильтрами для `.fb2`, `.fb2.zip`, `.epub` и `.pdf`; не перехватывать HTTP(S)-ссылки и DJVU.
- [ ] Принимать ровно один `content://`/`file://` файл из `ACTION_SEND` или `ACTION_VIEW` либо одну HTTP(S)-ссылку из `ACTION_SEND`; безопасно получать и очищать display name, Content-Disposition и URL filename.
- [ ] До интерактивной авторизации копировать входной URI без изменений в `noBackupFilesDir/pending` и создавать Room-запись с состояниями `STAGED`, `AWAITING_AUTH`, `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`.
- [ ] Сохранять в задании снимок server URL, library/path IDs и настройки EPUB, чтобы последующая смена настроек не перенаправила уже подтверждённую книгу.
- [ ] Определять формат по содержимому, а расширение и MIME использовать только как подсказку: `%PDF-`, XML root `FictionBook`, EPUB mimetype entry, один валидный FB2 внутри ZIP.
- [ ] Отклонять DJVU, обычные ZIP, HTML/error pages, несколько FB2 в архиве, повреждённые XML/ZIP и несовпадение допустимого формата.
- [ ] Добавить ограничения числа ZIP entries, суммарного распакованного размера, compression ratio, размера имени и глубины пути; не разрешать внешние XML entities.
- [ ] Для FB2.ZIP после предварительной проверки повторно открыть staging-файл и потоково передать только FB2 entry с именем без `.zip`.
- [ ] Для EPUB сохранить `mimetype` первым и без compression, остальные entries потоково перепаковать через `Deflater.BEST_COMPRESSION`; при выключенной настройке передавать исходный EPUB.
- [ ] Гарантировать тестами, что преобразованный FB2/EPUB не создаётся в файловой системе и память не зависит от полного размера книги.
- [ ] Покрыть тестами `ACTION_SEND`/`ACTION_VIEW` manifest filters, URI/text parsing, Room-переходы, очистку staging, все допустимые форматы, повреждённые/опасные fixtures и максимальное EPUB-сжатие.
- [ ] Запустить `./gradlew testDebugUnitTest`; все тесты должны пройти до Task 4.

### Task 4: Реализовать устойчивую фоновую загрузку и прогресс

**Files:**

- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/upload/TransferScheduler.kt`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/upload/TransferJobService.kt`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/upload/UploadPipeline.kt`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/upload/ProgressRequestBody.kt`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/upload/TransferNotificationManager.kt`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/upload/PendingJobReconciler.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/test/**/upload/TransferSchedulerTest.kt`
- Create: `app/src/test/**/upload/TransferJobServiceTest.kt`
- Create: `app/src/test/**/upload/UploadPipelineTest.kt`

- [ ] Планировать видимое пользователю UIDT-задание с network/storage constraints, estimated bytes, persisted extras, exponential backoff и обязательным progress notification.
- [ ] Для URL выполнять до пяти HTTP(S)-redirects без передачи Grimmory-токена, запрещать смену на небезопасную схему и спрашивать подтверждение перед cleartext download.
- [ ] Скачивать URL в приватный staging с determinate progress по Content-Length либо indeterminate progress, затем проверять фактический формат до upload.
- [ ] Передавать файл multipart-потоком в `/api/v1/files/upload` с зафиксированными `libraryId` и `pathId`; отображать отдельные стадии download, validation, recompression и upload.
- [ ] При отсутствии/истечении access token сначала использовать refresh; при окончательной 401 перевести то же задание в `AWAITING_AUTH` и открыть авторизацию через notification action.
- [ ] После успешной авторизации повторно запланировать все `AWAITING_AUTH` jobs без потери исходника или ссылки.
- [ ] На сетевые и системные остановки сохранять состояние и повторять всю передачу; invalid format и серверные 4xx считать окончательными ошибками с понятным уведомлением.
- [ ] Добавить отмену, очистку staging после terminal state, удаление orphan files и reconciliation прерванных jobs при следующем запуске.
- [ ] Через MockWebServer проверить download/upload progress, chunked transformed multipart, redirect policy, refresh/retry, auth pause/resume, transient backoff, unsupported format, отмену и cleanup.
- [ ] Запустить `./gradlew testDebugUnitTest`; все тесты должны пройти до Task 5.

### Task 5: Реализовать onboarding, домашний экран, входящие intent и настройки

**Files:**

- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/ui/AppNavHost.kt`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/ui/onboarding/OnboardingScreen.kt`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/ui/auth/AuthScreen.kt`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/ui/auth/OidcRedirectActivity.kt`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/ui/home/HomeScreen.kt`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/ui/incoming/IncomingBookScreen.kt`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/ui/settings/SettingsScreen.kt`
- Create: `app/src/main/java/io/github/kemko/grimmoryuploader/ui/**/**ViewModel.kt`
- Modify: `app/src/main/java/io/github/kemko/grimmoryuploader/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/**`
- Create: `app/src/test/**/ui/*`

- [ ] При первом запуске потребовать server URL, выполнить healthcheck/public-settings discovery, показать HTTP-предупреждение и перейти к выбранной авторизации.
- [ ] При обычном запуске проверить access/refresh token и `/users/me`; показать «Авторизация актуальна» либо форму входа.
- [ ] При запуске через «Поделиться» или «Открыть с помощью» сначала устойчиво зафиксировать вход, затем проверить/обновить auth; после login/OIDC автоматически продолжить именно исходную загрузку.
- [ ] Показывать полноэкранный прогресс всех стадий, результат, повтор и отмену; после ухода из Activity продолжать через системное уведомление.
- [ ] Запрашивать notification permission перед первой фоновой передачей и объяснять ограничение фонового прогресса при отказе.
- [ ] В настройках позволить изменить server URL, auth mode, library ID, path ID и выключить EPUB recompression.
- [ ] При смене сервера показать подтверждение, отменить задания старого сервера, удалить их staging и tokens, затем потребовать новую авторизацию.
- [ ] Для неподдерживаемой ссылки/файла показать полноэкранную ошибку и системное уведомление без попытки upload.
- [ ] Покрыть Compose/Robolectric-тестами first run, обычный launch, `ACTION_SEND`, `ACTION_VIEW`, local/OIDC login, pending input resume, progress/error states, HTTP confirmation и все настройки.
- [ ] Запустить `./gradlew testDebugUnitTest`; все тесты должны пройти до Task 6.

### Task 6: Добавить локальные проверки, CI, автоверсионирование и GitHub Releases

**Files:**

- Create: `Makefile`
- Modify: `build.gradle.kts`
- Modify: `app/build.gradle.kts`
- Create: `version.properties`
- Create: `gradle/verification-metadata.xml`
- Create: `config/dependency-check-suppressions.xml`
- Create: `.github/workflows/ci.yml`
- Create: `.github/workflows/release.yml`
- Create: `.github/dependabot.yml`
- Create: `release-please-config.json`
- Create: `.release-please-manifest.json`
- Create: `app/src/test/**/build/VersioningTest.kt`

- [ ] Добавить Make targets `bootstrap-check`, `format`, `format-check`, `lint`, `test`, `coverage`, `security`, `build`, `ci` и `release-apk`; `make ci` должен быть единственной проверочной точкой CI.
- [ ] В `lint` включить ktlint, Android Lint и actionlint; в `security` — Gradle verification/locking и OWASP Dependency-Check с fail threshold.
- [ ] Настроить Kover с минимумом 80% line coverage для собственного production-кода и исключением сгенерированных Compose/Room-классов.
- [ ] Настроить CI на pull request и push: JDK 26, SDK 36, Gradle cache, `make ci`, сохранение test/lint/security reports и debug APK.
- [ ] Закрепить GitHub Actions на commit SHA и выдать workflow минимальные permissions.
- [ ] Настроить Dependabot для Gradle и GitHub Actions.
- [ ] Настроить Release Please и Conventional Commits; хранить SemVer в `version.properties`, а Android `versionCode` вычислять детерминированно из stable SemVer.
- [ ] После создания release собирать подписанный release APK из base64 keystore и GitHub Secrets, вычислять SHA-256 и прикреплять оба файла к GitHub Release.
- [ ] Не выводить signing secrets в лог и гарантированно удалять временный keystore в `always()` step.
- [ ] Добавить тесты парсинга version properties, versionCode и release build configuration.
- [ ] Запустить `make ci`; все проверки должны пройти до Task 7.

### Task 7: Verify acceptance criteria

**Files:**

- Modify: `app/src/test/**` при обнаружении пробелов покрытия
- Modify: `app/src/test/resources/books/**` при необходимости
- Modify: `Makefile` только если проверка выявит расхождение с CI

- [ ] Запустить `make ci`.
- [ ] Проверить, что Kover подтверждает минимум 80% line coverage.
- [ ] Проверить тестами регистрацию `ACTION_SEND` и `ACTION_VIEW` для FB2, FB2.ZIP, EPUB и PDF, регистрацию text URL только для share и отсутствие DJVU.
- [ ] Проверить тестами auth-valid, refresh-success, refresh-failure-login-resume и OIDC resume flows для обоих способов открытия файла.
- [ ] Проверить тестами допустимые и недопустимые URL/file formats, FB2.ZIP extraction и EPUB recompression on/off.
- [ ] Проверить тестами отсутствие преобразованных файлов, очистку staging и восстановление очереди после пересоздания процесса.
- [ ] Проверить тестами progress, cancel, retry, notification и server-change cleanup.
- [ ] Запустить `make lint`, `make security`, `make coverage` и `make build` отдельно; результаты должны совпасть с `make ci`.
- [ ] Проверить `release.yml` через actionlint и выполнить release APK build с временным тестовым keystore без публикации.

### Task 8: Update documentation

**Files:**

- Create: `README.md`
- Create: `CHANGELOG.md`
- Modify: `AGENTS.md`

- [ ] Описать установку APK, первый запуск, локальную/OIDC-авторизацию, OIDC redirect URI, «Поделиться», «Открыть с помощью», отправку ссылки, progress, отмену и настройки.
- [ ] Документировать поддерживаемые FB2, FB2.ZIP, EPUB и PDF, отсутствие DJVU и runtime-проверку широких MIME-фильтров.
- [ ] Объяснить ограничения Android при фильтрации chooser по расширению и фактическую проверку содержимого после получения intent.
- [ ] Объяснить приватный staging неизменённого источника, потоковые преобразования без intermediate output и автоматическую очистку.
- [ ] Описать HTTP-предупреждение, chunked multipart requirement, полный повтор после обрыва и notification permission.
- [ ] Описать все Make targets, требования macOS, CI, Conventional Commits, Release Please и GitHub signing secrets.
- [ ] Убедиться, что `AGENTS.md` содержит актуальные команды, архитектурные ограничения и требование сразу запрашивать эскалацию для сетевых операций; отдельный `CLAUDE.md` не создавать.
- [ ] Запустить `make ci` после документации.

## Post-Completion

- Для реальной публикации потребуются GitHub Secrets: base64 keystore, alias, store password и key password.
- Для OIDC администратор Grimmory/IdP должен разрешить redirect URI `io.github.kemko.grimmoryuploader:/oauth2redirect`.
- После появления доступного stable `platforms;android-37` SDK можно отдельной задачей поднять compile/target SDK; preview channel в этот план не входит.
- Финальный smoke-test на физическом Android-устройстве и реальном Grimmory нужен перед первой публичной публикацией, но не блокирует автоматические проверки плана.
