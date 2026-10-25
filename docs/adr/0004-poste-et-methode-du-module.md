# ADR 0004 — Poste de développement et méthode du module complémentaire

- **Statut** : acceptée
- **Date** : 2026-10-08
- **Élément de backlog** : F-003
- **Décideur** : humain (consigne du 2026-10-08 : sources accessibles, navigation, procédure du document d'architecture à respecter, document de couverture)

## Contexte

L'ADR 0003 impose un module complémentaire d'une image publiée. Il reste à fixer où vivent
les sources de Blaze (pour les lire), comment on navigue dedans depuis l'éditeur, comment le
module se teste, et comment la procédure de développement du document d'architecture
(`S-FN`, `S-PUR`… ; test en échec d'abord ; couverture ≥ 95 %) s'articule avec le pilotage
de ce dépôt (`F-xxx`, `T-xxx`, verdicts).

## Décision

1. **Disposition** (la seule pour les sources écrites ici) :
   - `modules/<nom>/` — un module = un projet Clojure (`deps.edn`, `Makefile`, `tests.edn`,
     `src/`, `test/`, `resources/`, `conformance/`), mêmes cibles `make` que Blaze ;
   - `build/` — `Dockerfile` (cible) ; `build/staging/` est généré, ignoré ;
   - `amont/blaze/` — sources de Blaze au tag, **lecture seule**, ignoré par git ;
   - `.work/blaze-standalone.jar` — jar extrait de l'image publiée, vérifié par empreinte ;
   - `.outils/` — JDK, Clojure CLI, clj-kondo, clojure-lsp, cljfmt (versions : `versions.env`).
2. **Trois classpaths, jamais mélangés** (`deps.edn` racine) : `:nav` (sources de Blaze, pour
   l'éditeur), `:publie` (jar, pour exécuter), `:modules` (nos sources). Un module se teste
   avec `:test` = jar + `blaze.test-util` (chemin seul) ; le canari
   `blaze.addon.canari-test` rend rouge si Blaze se charge depuis autre chose que le jar.
3. **Navigation** : clojure-lsp (via Calva), configuré par `.lsp/config.edn` sur `:nav`.
   *Mesuré le 2026-10-08* : requête LSP `textDocument/definition` sur `fp/compile` depuis un
   fichier du module → `amont/blaze/modules/fhir-path/src/blaze/fhir_path.clj`, ligne 585.
4. **Méthode** : `docs/methode-module.md` reprend, sans les réécrire, les stratégies `S-xx` et
   les règles du document d'architecture, avec la correspondance vers `F-xxx` / `T-xxx`.
   Les règles du fichier `AGENTS.md` de Blaze (au tag) font foi pour le style du code.
5. **Couverture** : `docs/couverture.md` relie fonctionnalité → tâche → source → tests →
   état. `scripts/check_module.py` (M5) rend ECHEC si une source ou un test du module n'y
   figure pas, ou si une ligne dite réalisée cite un fichier absent.
6. **Un module n'écrit jamais dans `amont/`** ; `make outils` le recrée à l'identique.

## Conséquences

- Poids mesuré le 2026-10-08 (`du -sh`) : `amont/` 1,2 Go (dépôt préparé : `npm` pour l'IG),
  `.outils/` 717 Mo, `.work/` 264 Mo, `~/.m2` 518 Mo. Rien n'est installé hors du dépôt, sauf
  le cache Maven et gitlibs de l'utilisateur.
- `:nav` demande la préparation amont (génération de l'IG, compilation Java) : première
  mise en place longue (`make build-ig` puis `clojure -X:deps prep` dans `amont/blaze`, une fois par version de Blaze).
- `blaze.test-util` est référencé par **chemin** (`../../amont/…/test-util/src`) : Clojure
  affiche un avertissement de dépréciation. Toléré ; l'alternative (`:local/root`) ramènerait
  les sources de Blaze dans le classpath de test.
- Le document d'architecture reste la **référence** des 88 opérations ; le suivi vivant est
  `docs/plan/` et `docs/couverture.md`.

## Alternatives écartées

- **Sous-module git de Blaze** : fige un commit dans l'historique du dépôt, et invite à le modifier.
- **Navigation dans le jar seul** : les sources y sont (`.clj`), mais sans l'arborescence des
  modules, sans les tests ni la documentation amont.
- **Tester contre les sources de Blaze** : ne teste pas l'artefact qui sera servi (ADR 0003).

## Amendements

- 2026-10-17 (F-034, T-100) — Décision 1, `build/` : `build/staging/` n'existe plus (cible
  `staging` retirée du `Makefile`, `/usr/bin/grep -c 'staging' Makefile` → `0`). `build/` porte
  `Dockerfile` (cible) et `construire-jar.sh` ; `build/blaze-addon.jar` est généré par `make jar`,
  ignoré par git (`build/*.jar` dans `.gitignore`).
