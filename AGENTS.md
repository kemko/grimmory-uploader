# Grimmory Uploader development rules

## Architecture

- Keep the app single-activity and use Jetpack Compose for UI.
- Keep network, persistence, authentication, queueing, format detection, and upload orchestration in separate packages under `data`, `format`, and `upload`.
- Use the small manual `AppContainer`; do not add Hilt, WorkManager, or extra Gradle modules.
- Treat every URL, URI, filename, MIME type, XML document, ZIP entry, and server response as untrusted input.

## Required commands

- `make format-check`
- `make lint`
- `make test`
- `make security`
- `make build`
- `make ci`

Task-specific validation must pass before the next plan task starts. Add or update tests with every implementation change.

## Security and data handling

- Accept only explicitly supported HTTP(S) endpoints and book formats; reject DJVU.
- Do not request shared-storage permissions.
- Keep tokens encrypted with Android Keystore. Never persist login credentials or passwords.
- Copy temporary incoming content URIs to private no-backup staging before interactive authentication.
- Never write transformed FB2 or EPUB output to disk. Transform them as streams with bounded memory.
- Remove staging files after success, cancellation, or final failure and reconcile orphan files.
- Require explicit user confirmation for cleartext HTTP.

## Network commands

The sandbox has no network access. Any command that may download dependencies, contact a remote API, or otherwise require network access must be started immediately with an escalation request. Do not retry the same network command inside the sandbox first.
