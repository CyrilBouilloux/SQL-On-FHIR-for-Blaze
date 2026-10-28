# Points d'entrée du dépôt. Les outils viennent de .outils/ (make outils).
# Le module se construit et se teste contre l'image publiée de Blaze (ADR 0003).
SHELL := /bin/bash
include versions.env
export
ENV := eval "$$(scripts/outils.sh env)";
MODULES := $(wildcard modules/*)
IMAGE := blaze-platform-module:dev

outils:        ## installe l'outillage, extrait le jar publié, clone amont/ (lecture seule)
	scripts/outils.sh installer
	scripts/outils.sh jar
	scripts/outils.sh amont

nav:           ## N0 : la définition d'un symbole de Blaze se résout vers amont/
	python3 scripts/verifier_navigation.py

-include controles-internes.mk   # contrôles propres au dépôt de travail : facultatif, ajoute CONTROLES_INTERNES

check: jar $(CONTROLES_INTERNES)   ## N0 : jar à jour, versions, ADR 0003 (+ leurs témoins rouges)
	python3 scripts/check_versions.py
	python3 scripts/check_module.py --test
	python3 scripts/check_module.py
	python3 scripts/check_gardefou7.py

fmt lint test test-coverage: $(MODULES)
$(MODULES):
	$(ENV) $(MAKE) -C $@ $(MAKECMDGOALS)

jar:           ## jar du module (src + resources suivis), à poser devant le jar de Blaze
	build/construire-jar.sh

image: jar       ## FROM l'image publiée + jar du module devant celui de Blaze + jar du moteur SQL (empreinte vérifiée)
	docker build -f build/Dockerfile -t $(IMAGE) \
	  --build-arg BLAZE_VERSION=$(BLAZE_VERSION) --build-arg BLAZE_IMAGE_DIGEST=$(BLAZE_IMAGE_DIGEST) \
	  --build-arg DUCKDB_JDBC_VERSION=$(DUCKDB_JDBC_VERSION) --build-arg DUCKDB_JDBC_SHA256=$(DUCKDB_JDBC_SHA256) build

.PHONY: outils nav check fmt lint test test-coverage $(MODULES) jar image
