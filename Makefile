SHELL := /bin/bash
GRADLEW := ./gradlew
DEPENDENCY_CHECK_UPDATE ?= false

.PHONY: bootstrap-check format format-check lint test coverage security build ci release-apk

bootstrap-check:
	command -v actionlint >/dev/null
	command -v java >/dev/null
	$(GRADLEW) --version

format:
	$(GRADLEW) ktlintFormat

format-check:
	$(GRADLEW) ktlintCheck

lint:
	$(GRADLEW) lintDebug
	actionlint .github/workflows/*.yml

test:
	$(GRADLEW) --no-configuration-cache --refresh-dependencies --dependency-verification=strict testDebugUnitTest

coverage:
	$(GRADLEW) koverXmlReportDebug koverVerifyDebug

security:
	$(GRADLEW) --no-configuration-cache --dependency-verification=strict -PdependencyCheckAutoUpdate=$(DEPENDENCY_CHECK_UPDATE) dependencyCheckAnalyze

build:
	$(GRADLEW) assembleDebug

ci: bootstrap-check format-check lint test security coverage build

release-apk:
	$(GRADLEW) assembleRelease
