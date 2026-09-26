# Everything builds in Docker (see Dockerfile); results land in build/.
# The host's debug keystore, when present, keeps the APK signature stable across builds.
ifneq ($(wildcard $(HOME)/.android/debug.keystore),)
secret := --secret id=debug_keystore,src=$(HOME)/.android/debug.keystore
endif

.PHONY: all install plugin jnilibs src clean

all:
	docker build $(secret) --output build .

install: all
	adb install -r build/app-debug.apk

plugin:
	docker build --target plugin-out --output build/kiosk-plugin .

jnilibs:
	docker build --target jnilibs --output build/jniLibs .

src:
	docker build --target src --output build/src .

clean:
	rm -rf build
