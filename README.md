# Grimmory Uploader

Grimmory Uploader is an Android 15+ app for sending books to a Grimmory server. It accepts FB2, FB2.ZIP, EPUB, PDF, and one HTTP(S) download link from Android's Share and Open with menus.

## Install and build

Install a released APK from the GitHub Release assets. For a local debug APK:

```sh
make build
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The project uses one `app` module, Kotlin, Jetpack Compose, `minSdk 35`, `compileSdk 37.1`, and `targetSdk 37`. Run the complete local gate with `make ci`.

Development on macOS requires Xcode Command Line Tools, Homebrew, JDK 26, Android command-line tools with platform 37.1/build-tools 37, and `actionlint`.
Outside CI, Make automatically uses an SDK installed at `~/Library/Android/sdk` or `~/Android/Sdk` when neither `ANDROID_HOME` nor `ANDROID_SDK_ROOT` is set.

Available Make targets are:

- `bootstrap-check`: verify Java, actionlint, and the Gradle toolchain.
- `format` / `format-check`: format or check Kotlin and Gradle scripts.
- `lint`: run Android Lint and actionlint.
- `test`: run debug unit tests.
- `coverage`: generate and verify Kover coverage.
- `security`: scan release runtime dependencies with OWASP Dependency-Check.
- `build`: assemble the debug APK.
- `ci`: run the complete verification gate used by CI.
- `release-apk`: assemble the release APK.

Dependency-Check feed updates are disabled by default for repeatable local runs. Use `make security DEPENDENCY_CHECK_UPDATE=true` to refresh vulnerability data; this requires network access. The security gate reports all findings and fails only for CVSS 9.0+ issues in release runtime dependencies. False-positive suppressions must identify the affected artifact and reason; a zero-finding report is not a dependency-upgrade goal. Use only stable dependency releases unless a pre-release is explicitly required for application functionality.

Before `make release-apk`, set `ANDROID_SIGNING_STORE_FILE`, `ANDROID_SIGNING_STORE_PASSWORD`, `ANDROID_SIGNING_KEY_ALIAS`, and `ANDROID_SIGNING_KEY_PASSWORD`.

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

### Pocket ID and Grimmory setup

Mobile OIDC setup requires Grimmory 3.1.0 or later. Upgrade if Allowed Mobile Redirect URIs is unavailable.

Use one Pocket ID OIDC client for Grimmory web login and this app:

1. In Pocket ID, create an OIDC client with PKCE and turn on Public Client. Add the web Redirect URI shown in Grimmory's OIDC Provider Configuration Reference (for example, `https://books.example.com/oauth2-callback`) and the exact mobile URI `io.github.kemko.grimmoryuploader:/oauth2redirect`. Do not use a wildcard.
2. Copy the Pocket ID Client ID and Issuer URI.
3. In Grimmory Settings > OIDC, paste the Client ID, leave Client Secret empty, and configure scopes containing `openid profile email groups offline_access`. Run Test Connection, save, and enable OIDC Login.
4. In Grimmory's Allowed Mobile Redirect URIs, add `io.github.kemko.grimmoryuploader:/oauth2redirect` exactly, without a wildcard.
5. Enable OIDC auto-provisioning or create the Grimmory user before signing in.

The mobile flow is app → Pocket ID → app → Grimmory → Pocket ID → Grimmory tokens. The app starts authorization with PKCE; Grimmory performs the code exchange and returns its tokens. Test Connection confirms that Grimmory can reach the provider, but does not confirm client authentication during the real token exchange.

Troubleshooting:

- `invalid_client`: copy the Client ID again, enable Public Client in Pocket ID, and leave Client Secret empty in Grimmory.
- Redirect mismatch: copy Grimmory's web Redirect URI from Provider Configuration Reference and compare both callback URIs character by character; the mobile URI must be `io.github.kemko.grimmoryuploader:/oauth2redirect`.
- Provider unreachable: make the Pocket ID Issuer URI reachable from the Grimmory server/container, including DNS and firewall access.
- User not provisioned: enable auto-provisioning or create a Grimmory user with the matching Pocket ID username.
- OIDC disabled or misconfigured: enable OIDC Login and check the Issuer URI, Client ID, scopes, and callback allowlist.

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

ZIP input is limited to 2,000 entries, a 16 MiB central directory, 512 MiB per entry and in total, a 100:1 compression ratio, 240-character entry names, and 16 path components. ZIP64 metadata is rejected. Any staged local or downloaded source is limited to 512 MiB.

FB2.ZIP is unpacked directly into the upload stream. When EPUB recompression is enabled, `mimetype` remains first and uncompressed and all other entries are streamed through `Deflater.BEST_COMPRESSION`. Neither transformed FB2 nor transformed EPUB is written as an intermediate file. With recompression disabled, the validated original EPUB is uploaded unchanged.

## Links, progress, and cancellation

HTTP(S) links are downloaded to private staging before validation. The download follows at most five redirects, never sends the Grimmory token to the source server, and rejects HTTPS-to-HTTP redirects. Cleartext HTTP requires explicit confirmation.

Transfers run as Android User-Initiated Data Transfer jobs and continue after the activity closes. The notification reports download, validation, recompression, and upload stages, with determinate progress when a size is known. Android 13+ requires the notification permission for background progress; if it is denied, the transfer can continue but its background progress notification is unavailable.

Cancel from the home screen or transfer notification. Network/system interruptions retry the complete transfer because Grimmory does not provide resumable uploads. Final format errors and server 4xx responses are not retried. If authentication expires, the job pauses in `AWAITING_AUTH`; signing in resumes it without losing the original input.

FB2.ZIP extraction and recompressed EPUB uploads have unknown final length and use chunked multipart transfer. Grimmory and any reverse proxy in front of it must accept chunked request bodies.

## Settings

Settings control the normalized server URL, authentication mode (`AUTO`, `LOCAL`, or `OIDC`), positive `libraryId`, positive `pathId`, and EPUB recompression. Changing the server cancels and removes jobs for the old server, clears its encrypted tokens, removes their staging files, and requires authentication for the new server.

## CI and releases

CI runs on pull requests and pushes to `master` with JDK 26 and Android SDK 37.1. It executes `make ci`, refreshes the Dependency-Check database, and stores reports and the debug APK as workflow artifacts. Use short Conventional Commits such as `feat: add upload retry` or `fix: reject unsafe ZIP path`; Release Please uses these commits to propose releases and updates `version.properties`. The version is stable SemVer, and Android `versionCode` is derived deterministically from it.

Release Please runs on pushes to `master`. When it creates a GitHub Release, the same workflow builds and uploads a signed APK and its SHA-256 file. Configure these GitHub Actions secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_SIGNING_KEY_ALIAS`, `ANDROID_SIGNING_STORE_PASSWORD`, and `ANDROID_SIGNING_KEY_PASSWORD`. The workflow writes the decoded keystore only to runner temporary storage and removes it in an `always()` step; signing values are not printed.
