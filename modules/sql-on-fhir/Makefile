# Memes cibles que les modules de Blaze (docs/methode-module.md) : `make -C modules/sql-on-fhir test`.
# Les outils viennent de .outils/ : eval "$(scripts/outils.sh env)" d'abord.
fmt:
	cljfmt check src test conformance/blaze deps.edn tests.edn

lint:
	clj-kondo --fail-level error --lint src test conformance/blaze deps.edn

test:
	clojure -M:test:kaocha --profile :ci

test-focus:
	clojure -M:test:kaocha --profile :ci --focus "$(FOCUS)"

# Banc de conformite (INF-02) : une ligne par fichier de la suite copiee, synthese REUSSIS n/144.
# Sortie 0 tout passe, 8 au moins un ECHEC, 3 sinon (API absente : NON-BRANCHE).
conformance:
	clojure -M:test -m blaze.addon.sql-on-fhir.conformance

# Temoin de la regle de comparaison : moteur factice vert, puis une valeur alteree en memoire.
conformance-temoin:
	clojure -M:test -m blaze.addon.sql-on-fhir.conformance temoin

# Independance de la base (F-005) : JVM neuve dediee, 0 namespace blaze.db / blaze.interaction apres les 144 cas.
independance:
	clojure -M:test -m blaze.addon.sql-on-fhir.independance

# Temoin rouge : (require 'blaze.db.api) avant le controle le rend rouge ; sortie 0 si c'est le cas.
independance-temoin:
	clojure -M:test -m blaze.addon.sql-on-fhir.independance temoin

# Oracle des N2 (T-030, F-006) : execute en memoire la vue VUE (JSON) sur RESSOURCES (NDJSON, sortie de
# `blazectl download`), ecrit une ligne NDJSON par ligne de vue sur stdout. Code 0 ; 3 fichier absent
# (NON-BRANCHE) ; 8 anomalie du moteur (categorie et message sur stderr) ; 2 usage. Make rend 2 pour tout echec.
vue-ndjson:
	@clojure -M:test -m blaze.addon.sql-on-fhir.view.fichier "$(VUE)" "$(RESSOURCES)"

test-coverage:
	clojure -M:test:kaocha:coverage

# AMONT_ARGS : options kaocha en plus (ex. --focus :fhir-path). SORTIE : prefixe des fichiers de
# target/ (une execution = un prefixe, pour ne pas ecraser une autre).
# Suites amont fhir-path et db contre le jar publie (FP-15), avec et sans le module.
# Les tests amont lisent des fichiers relatifs a leur module (`../../.github/...`) : ils tournent
# donc DEPUIS amont/blaze/modules/db, classpath explicite (le jar, jamais amont/src).
# F-004 « Indexation inchangee » : (a) fhir-path, (b) db prive de L, (d) releves complets egaux ;
# (c) controle de L : `make test-amont-controle` (29 JVM).
AMONT_CWD = ../../amont/blaze/modules/db
AMONT_CP = $$(clojure -Spath -A:test:kaocha | tr ':' '\n' | xargs realpath -m | paste -sd:)
AMONT_RUN = cp="$(AMONT_CP)" && cd $(AMONT_CWD) && MODULE_SRC=$(CURDIR)/src java -cp "$$cp" clojure.main -m kaocha.runner --config-file $(CURDIR)/tests-amont.edn $(AMONT_ARGS)
SORTIE ?= amont
# Liste d'exclusion L (un identifiant de test par ligne), par module amont.
EXCLUSION ?= exclusions/db.txt
SKIP_L = $(shell sed 's/^/--skip /' $(EXCLUSION))
# Liste I des tests instables (un identifiant par ligne), sautes en (b) et des deux cotes de (d).
INSTABLES ?= exclusions/db-instables.txt
SKIP_I = $(shell sed 's/^/--skip /' $(INSTABLES))
# Liste A des tests dont l'attendu est l'absence d'une construction que le module ajoute : sautes en (b) seulement.
ABSENCES ?= exclusions/db-absences.txt
ABSENCES_PREUVES ?= exclusions/db-absences.preuves
SKIP_A = $(shell sed 's/^/--skip /' $(ABSENCES))
ATTENDUS ?= test/blaze/addon/sql_on_fhir/fhir_path/attendus.edn
TESTS_MODULE ?= test
# Preuves 2 de I : sorties du jar seul avec au moins 1 echec / 1 succes du test ; regenerees depuis un clone
# neuf par `make test-amont-instables` (T-026). N : borne d'essais par test de I (le test seul a echoue 2 fois en 139 essais
# (1 sur 40, puis 1 sur 99) : releve du 2026-10-10, docs/comptes-rendus/T-026.md ; ~20 s l'essai ; INDETERMINE si N est atteint).
INSTABLES_ECHEC ?= target/flaky-jar.out
INSTABLES_SUCCES ?= target/complet-jar.out
TESTS_AMONT ?= ../../amont/blaze/modules/db/test
MACRO_AMONT ?= ../../amont/blaze/modules/test-util/src/blaze/test_util.clj
INSTABLES_N ?= 100
FHIR_PATH_ATTENDU = 25
DB_ATTENDU = 280
COMPLET_ATTENDU = 335

test-amont-jar:
	mkdir -p target
	-$(AMONT_RUN) > $(CURDIR)/target/$(SORTIE)-jar.out 2>&1
	./releve-amont.sh target/$(SORTIE)-jar.out | tee target/$(SORTIE)-jar.releve

test-amont-module:
	mkdir -p target
	-$(AMONT_RUN) --profile :module > $(CURDIR)/target/$(SORTIE)-module.out 2>&1
	./releve-amont.sh target/$(SORTIE)-module.out | tee target/$(SORTIE)-module.releve

# (a) fhir-path : jar seul puis jar + module, chacun N tests et 0 echec, 0 erreur.
test-amont-a:
	$(MAKE) test-amont-jar test-amont-module SORTIE=$(SORTIE)-a AMONT_ARGS="--focus :fhir-path"
	./exiger-releve.sh target/$(SORTIE)-a-jar.releve $(FHIR_PATH_ATTENDU)
	./exiger-releve.sh target/$(SORTIE)-a-module.releve $(FHIR_PATH_ATTENDU)

# (b) sous-ensemble db = suite db privee de L, de I et de A, jar + module : N tests, 0 echec, 0 erreur.
test-amont-b:
	$(MAKE) test-amont-module SORTIE=$(SORTIE)-b AMONT_ARGS="--focus :db $(SKIP_L) $(SKIP_I) $(SKIP_A)"
	./exiger-releve.sh target/$(SORTIE)-b-module.releve $(DB_ATTENDU)

# (d) releves complets, I saute des deux cotes, A jamais : COMPLET_ATTENDU tests chacun, diff limite a A.
test-amont-d:
	$(MAKE) test-amont-jar test-amont-module AMONT_ARGS="$(SKIP_I)"
	./exiger-tests.sh target/$(SORTIE)-jar.releve $(COMPLET_ATTENDU)
	./exiger-tests.sh target/$(SORTIE)-module.releve $(COMPLET_ATTENDU)
	./exiger-diff-d.sh target/$(SORTIE)-jar.releve target/$(SORTIE)-module.releve $(ABSENCES)

test-amont: test-amont-a test-amont-b
	$(MAKE) test-amont-d

# (c) preuves 2 de I : rejoue chaque test de I seul sur le jar seul, au plus INSTABLES_N fois, jusqu'a 1 echec et
# 1 succes ; ecrit INSTABLES_ECHEC et INSTABLES_SUCCES. SUCCES 0, ECHEC 1, INDETERMINE 2 (N atteint : rien n'est ecrit).
# A travers make, ECHEC et INDETERMINE sortent TOUS DEUX en code 2 (make rend 2 pour toute recette en echec) :
# seul le message les distingue ; SUCCES sort en 0.
# INSTABLES_N = 100 et sorties sous target/ : valeur par defaut validee par l'humain le 2026-10-10. Taux d'echec
# du test seul p ~ 1,4 % (2 sur 139 essais, estimation non mesuree au-dela) : duree attendue ~ 1/p ~ 70 essais
# ~ 23 min ; N ne borne que le pire cas (100 essais ~ 33 min, atteint ~ 1 fois sur 4 a ce taux). N atteint :
# INDETERMINE, rien n'est ecrit, relancer. Changer : make test-amont-instables INSTABLES_N=200.
# `make clean` efface target/, donc ces preuves : test-amont-controle rend INDETERMINE jusqu'a regeneration.
# Documentation : docs/manuel.md section 3.
test-amont-instables:
	./rejouer-instables.sh $(INSTABLES) $(INSTABLES_N) $(INSTABLES_ECHEC) $(INSTABLES_SUCCES)

# (c) L est exactement l'ensemble des tests db en echec seuls sur le jar ; les autres echecs du releve
# complet jar seul (target/$(SORTIE)-jar.out) sont dans I ou joues dans (b) (target/$(SORTIE)-b-module.out) ;
# chaque test de I a ses 2 preuves. SORTIE est obligatoire : aucun releve par defaut (pas de releve perime).
test-amont-controle:
	@[ "$(origin SORTIE)" != file ] || { echo "INDETERMINE : SORTIE=<prefixe du releve a controler> obligatoire (make test-amont SORTIE=... d'abord)" >&2; exit 2; }
	./controle-exclusion.sh $(EXCLUSION) target/$(SORTIE)-jar.out target/$(SORTIE)-b-module.out $(INSTABLES)
	./controle-instables.sh $(INSTABLES) $(EXCLUSION) $(TESTS_AMONT) $(MACRO_AMONT) $(INSTABLES_ECHEC) $(INSTABLES_SUCCES)
	./controle-absences.sh $(ABSENCES) $(EXCLUSION) $(INSTABLES) $(ABSENCES_PREUVES) $(TESTS_AMONT) $(TESTS_MODULE) $(ATTENDUS)

clean:
	rm -rf .clj-kondo/.cache .cpcache target

.PHONY: vue-ndjson fmt lint conformance conformance-temoin independance independance-temoin test test-focus test-coverage test-amont test-amont-jar test-amont-module test-amont-a test-amont-b test-amont-d test-amont-instables test-amont-controle clean

# Overlay blaze.edn (ADR 0003, IB-08) : genere depuis le jar publie ; `overlay-controle` rend ECHEC (8) si
# le jar ou les ajouts ont change sans regeneration (empreinte du blaze.edn amont en tete du fichier).
overlay:
	python3 generer-overlay.py

overlay-controle:
	python3 generer-overlay.py --verifier
