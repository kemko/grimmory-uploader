# Changelog

All notable changes to Grimmory Uploader are documented here.

## [0.2.0](https://github.com/kemko/grimmory-uploader/compare/v0.1.0...v0.2.0) (2026-08-26)


### Features

* add CI and release automation ([3f8d26e](https://github.com/kemko/grimmory-uploader/commit/3f8d26e51420adf1f61ad4ef97aa6c83be750e25))
* add Grimmory auth foundation ([0d895bc](https://github.com/kemko/grimmory-uploader/commit/0d895bc048b57ea087eadba237c114f42e2b2725))
* add resilient background transfers ([0116786](https://github.com/kemko/grimmory-uploader/commit/01167863495efb39641e497c45add2d6c781ea75))
* implement book intake and transforms ([367efdc](https://github.com/kemko/grimmory-uploader/commit/367efdc98b353e029652bd7e4dcea0aa75eb6988))
* implement onboarding and upload UI ([43e542b](https://github.com/kemko/grimmory-uploader/commit/43e542befd624621fedb435fbc5a00b4a1885fd3))
* scaffold Android uploader ([2d3dbcb](https://github.com/kemko/grimmory-uploader/commit/2d3dbcb6ce5878ac0b6be11b08fe5d00604becdd))
* update project documentation ([d399883](https://github.com/kemko/grimmory-uploader/commit/d3998832ea000dcdf097ddb20f141e119485e6fc))
* verify acceptance criteria ([07c10c0](https://github.com/kemko/grimmory-uploader/commit/07c10c04f32f226b51fcfa22c742017a3e91eb12))


### Bug Fixes

* address code review findings ([7c21a14](https://github.com/kemko/grimmory-uploader/commit/7c21a14483705664ba947a170e8dc32944d41851))
* address code review findings ([3383e58](https://github.com/kemko/grimmory-uploader/commit/3383e5844086286a582dc825e7fc84b5a64515b6))
* address code review findings ([acb622d](https://github.com/kemko/grimmory-uploader/commit/acb622d6835de0715ba82d73bf5609e84cfe4020))
* address code review findings ([c573f7b](https://github.com/kemko/grimmory-uploader/commit/c573f7b2bd83cea2e49a28d7d5869eafe6a1a4be))
* address code review findings ([2d0fe7a](https://github.com/kemko/grimmory-uploader/commit/2d0fe7aeb96579ad593a4013a4e1f42f06698182))
* address code review findings ([fc9bc9a](https://github.com/kemko/grimmory-uploader/commit/fc9bc9a968c6e70181273d8b88bd7586ab594f5a))

## [Unreleased]

- Added source-aware OIDC errors and Pocket ID setup guidance.
- Added Android app foundation with Jetpack Compose and a single `app` module.
- Added local and OIDC/PKCE authentication with encrypted Android Keystore tokens.
- Added Share/Open with intake for FB2, FB2.ZIP, EPUB, PDF, and HTTP(S) links.
- Added content validation, ZIP safety guards, streaming FB2/EPUB transforms, and private staging cleanup.
- Added User-Initiated Data Transfer jobs, progress notifications, retry, cancellation, auth pause/resume, CI, coverage, security checks, and release automation.
