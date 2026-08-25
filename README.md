# Grimmory Uploader

Grimmory Uploader is an Android 15+ app for sending books to a Grimmory server. It accepts FB2, FB2.ZIP, EPUB, PDF, and one HTTP(S) download link from Android's Share and Open with menus.

## Install and build

Install a released APK from the GitHub Release assets. For a local debug APK:

```sh
make build
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The project uses one `app` module, Kotlin, Jetpack Compose, `minSdk 35`, and `compileSdk/targetSdk 36`. Run the complete local gate with `make ci`.

Development on macOS requires Xcode Command Line Tools, Homebrew, JDK 26, Android command-line tools with platform/build-tools 36, and `actionlint`.

Available Make targets are:

- `bootstrap-check`: verify Java, actionlint, and the Gradle toolchain.
- `format` / `format-check`: format or check Kotlin and Gradle scripts.
- `lint`: run Android Lint and actionlint.
- `test`: run debug unit tests.
- `coverage`: generate and verify Kover coverage.
- `security`: run strict Gradle dependency verification and OWASP Dependency-Check.
- `build`: assemble the debug APK.
- `ci`: run the complete verification gate used by CI.
- `release-apk`: assemble the release APK.

Dependency-Check feed updates are disabled by default for repeatable local runs. Use `make security DEPENDENCY_CHECK_UPDATE=true` to refresh vulnerability data; this requires network access. Before `make release-apk`, set `ANDROID_SIGNING_STORE_FILE`, `ANDROID_SIGNING_STORE_PASSWORD`, `ANDROID_SIGNING_KEY_ALIAS`, and `ANDROID_SIGNING_KEY_PASSWORD`.

## First run and authentication

1. Enter the Grimmory server URL, including any path prefix. Only `http://` and `https://` URLs are accepted.
2. The app runs the server health check and reads public settings.
3. For HTTP, enable the explicit warning confirmation. HTTPS is the default and recommended scheme.
4. Sign in locally with the server login form, or choose OIDC when the server exposes it. `AUTO` follows the server's `oidcEnabled` and `oidcForceOnlyMode` settings.

The app stores no username or password. Access and refresh tokens are encrypted with Android Keystore using AES-GCM. OIDC uses Authorization Code + PKCE, a server-generated state value, and a nonce. Register this redirect URI with Grimmory and the IdP:

```text
io.github.kemko.grimmoryuploader:/oauth2redirect
```

The redirect URI registered in the server and IdP must exactly match the URI used by the server's OIDC configuration.

## Send a book

Use Share for one local file or one plain-text HTTP(S) URL. Use Open with for one local `content://` or `file://` book. The app does not accept multiple files, does not intercept ordinary web navigation, and does not accept DJVU.

Before authentication, a temporary local source is copied unchanged to the app's private no-backup staging directory. This protects a `content://` grant that Android may revoke while the user is signing in. After success, cancellation, or final failure, the staged source is removed.

The queued job stores a snapshot of the server URL, library ID, path ID, and EPUB recompression setting. Later settings changes therefore do not redirect an already accepted book.

## Supported formats and validation

- FB2: XML whose root element is `FictionBook`.
- FB2.ZIP: ZIP containing exactly one valid FB2 entry; the uploaded name drops `.zip`.
- EPUB: ZIP with an uncompressed first `mimetype` entry containing `application/epub+zip`.
- PDF: content beginning with `%PDF-`.

File extension and MIME type are hints only. Broad Android MIME filters are needed because Android may not filter chooser results reliably by extension; the app checks the bytes after receiving the intent. HTML/error pages, ordinary ZIP archives, malformed XML or ZIP, unsafe ZIP paths, oversized/high-ratio archives, multiple FB2 entries, and DJVU are rejected before upload. XML external entities are disabled.

ZIP input is limited to 2,000 entries, 512 MiB per entry and in total, a 100:1 compression ratio, 240-character entry names, and 16 path components. Any staged local or downloaded source is limited to 512 MiB.

FB2.ZIP is unpacked directly into the upload stream. When EPUB recompression is enabled, `mimetype` remains first and uncompressed and all other entries are streamed through `Deflater.BEST_COMPRESSION`. Neither transformed FB2 nor transformed EPUB is written as an intermediate file. With recompression disabled, the validated original EPUB is uploaded unchanged.

## Links, progress, and cancellation

HTTP(S) links are downloaded to private staging before validation. The download follows at most five redirects, never sends the Grimmory token to the source server, and rejects HTTPS-to-HTTP redirects. Cleartext HTTP requires explicit confirmation.

Transfers run as Android User-Initiated Data Transfer jobs and continue after the activity closes. The notification reports download, validation, recompression, and upload stages, with determinate progress when a size is known. Android 13+ requires the notification permission for background progress; if it is denied, the transfer can continue but its background progress notification is unavailable.

Cancel from the home screen or transfer notification. Network/system interruptions retry the complete transfer because Grimmory does not provide resumable uploads. Final format errors and server 4xx responses are not retried. If authentication expires, the job pauses in `AWAITING_AUTH`; signing in resumes it without losing the original input.

FB2.ZIP extraction and recompressed EPUB uploads have unknown final length and use chunked multipart transfer. Grimmory and any reverse proxy in front of it must accept chunked request bodies.

## Settings

Settings control the normalized server URL, authentication mode (`AUTO`, `LOCAL`, or `OIDC`), positive `libraryId`, positive `pathId`, and EPUB recompression. Changing the server cancels and removes jobs for the old server, clears its encrypted tokens, removes their staging files, and requires authentication for the new server.

## CI and releases

CI runs on pull requests and pushes to `master` with JDK 26 and Android SDK 36. It executes `make ci`, refreshes the Dependency-Check database, and stores reports and the debug APK as workflow artifacts. Configure the optional `NVD_API_KEY` secret to speed up NVD updates. Use short Conventional Commits such as `feat: add upload retry` or `fix: reject unsafe ZIP path`; Release Please uses these commits to propose releases and updates `version.properties`. The version is stable SemVer, and Android `versionCode` is derived deterministically from it.

Release Please runs on pushes to `master`. When a GitHub Release is published, the release workflow builds and uploads a signed APK and its SHA-256 file. Configure these GitHub Actions secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_SIGNING_KEY_ALIAS`, `ANDROID_SIGNING_STORE_PASSWORD`, and `ANDROID_SIGNING_KEY_PASSWORD`. The workflow writes the decoded keystore only to runner temporary storage and removes it in an `always()` step; signing values are not printed.
