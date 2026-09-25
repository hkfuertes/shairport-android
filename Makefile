.PHONY: help fetch patch clean

help:
	@echo "make fetch  Download pinned Shairport Sync and NQPTP sources"
	@echo "make patch  Apply the active Android/Bionic and portable patches"
	@echo "make clean  Remove downloaded upstream sources"

fetch:
	./scripts/fetch-upstream.sh

patch:
	./scripts/apply-patches.sh

clean:
	rm -rf third_party
