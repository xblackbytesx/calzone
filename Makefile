# CalZone Android. Everything runs in Docker; no host toolchain needed.
#
#   make core         (re)build the Rust core (calzone-ffi .so + Kotlin bindings)
#                     in ../calzone-server; run it after Rust changes
#   make test         JVM tests (incl. the real Rust core via JNA and the server's golden files)
#   make build        debug APK -> build-output/calzone-debug.apk
#   make release      release APK (signed when keystore.properties / env vars exist)
#   make lint         Android lint
#   make screenshots  every screen -> build-output/screenshots/
#   make keystore     create the release signing key (once)
#   make install      adb install the debug APK
#
# The other targets build the core first when it is missing (a fresh
# checkout); after changing Rust code, run `make core` yourself.

COMPOSE = docker compose -f docker/docker-compose.yml
CORE    = ../calzone-server/dist

.PHONY: core core-if-missing test build release lint screenshots keystore install

core:
	$(MAKE) -C ../calzone-server android-core

# Everything an APK needs: bindings, both ABIs, the host lib for tests.
core-if-missing:
	@if [ ! -f $(CORE)/android/kotlin/uniffi/calzone_ffi/calzone_ffi.kt ] \
	   || [ ! -f $(CORE)/android/jniLibs/arm64-v8a/libcalzone_ffi.so ] \
	   || [ ! -f $(CORE)/android/jniLibs/x86_64/libcalzone_ffi.so ] \
	   || [ ! -f $(CORE)/host/libcalzone_ffi.so ]; then \
	  echo ">> Rust core missing in $(CORE): building it (make core)"; \
	  $(MAKE) core; \
	fi

test: core-if-missing
	$(COMPOSE) run --rm --build test

build: core-if-missing
	$(COMPOSE) run --rm --build build

release: core-if-missing
	$(COMPOSE) run --rm --build build-release

lint: core-if-missing
	$(COMPOSE) run --rm --build lint

screenshots: core-if-missing
	$(COMPOSE) run --rm --build screenshots

keystore:
	$(COMPOSE) run --rm --build keystore

install:
	adb install -r build-output/calzone-debug.apk
