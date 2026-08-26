SHELL := /bin/bash
GRADLEW := ./gradlew
DEPENDENCY_CHECK_UPDATE ?= false

ifeq ($(filter true 1,$(strip $(CI))),)
ifeq ($(strip $(ANDROID_HOME)$(ANDROID_SDK_ROOT)),)
LOCAL_ANDROID_SDK := $(firstword $(wildcard $(HOME)/Library/Android/sdk $(HOME)/Android/Sdk))
ifneq ($(LOCAL_ANDROID_SDK),)
export ANDROID_HOME := $(LOCAL_ANDROID_SDK)
endif
endif
endif

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
	$(GRADLEW) testDebugUnitTest --rerun

coverage:
	$(GRADLEW) koverXmlReportDebug koverVerifyDebug

security:
	$(GRADLEW) --no-configuration-cache -PdependencyCheckAutoUpdate=$(DEPENDENCY_CHECK_UPDATE) dependencyCheckAnalyze

build:
	$(GRADLEW) assembleDebug

ci: bootstrap-check format-check lint test security coverage build

release-apk:
	$(GRADLEW) assembleRelease
