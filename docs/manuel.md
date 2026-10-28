# Manuel — ce qui existe, ce qui n'existe pas

<!-- Tenu par `architect`. Décrit l'état au 2026-10-17, complété le 2026-10-28 (`$sqlquery-run`) ; toute affirmation porte la
     commande qui la vérifie. Mis à jour dans la même invocation que l'événement qui le
     périme. -->

Ce document s'adresse à qui veut savoir **où en est le projet** sans lire le code.

## 1. En bref

- Le chart (`kubernetes/chart/`) déploie un **serveur FHIR Blaze** derrière un reverse proxy
  nginx. Il sert l'**image du module** : le Blaze publié, inchangé, plus le jar du module
  (`build/blaze-addon.jar`) posé devant son jar (`build/Dockerfile`, `docs/construction.md`).
- Un **module** écrit ici (`modules/sql-on-fhir/`) sait **calculer une vue tabulaire**
  (« ViewDefinition » de SQL on FHIR) sur des ressources qu'on lui donne, **en mémoire**. Il
  passe les 144 cas de la suite de conformité officielle.
- Le serveur **rend les lignes d'une vue par HTTP** (`POST /fhir/$viewdefinition-run`), sur
  les données qu'il porte, en NDJSON, JSON ou CSV. Sans `_format`, un en-tête `Accept` à type
  unique servi choisit le format (§4).
- Il **exporte des vues vers des fichiers** en tâche de fond (`POST /fhir/$viewdefinition-export`
  avec `Prefer: respond-async`) : un fichier par vue, téléchargeable par une URL à jeton.
  Annulation, plafond d'espace, expiration et reprise après redémarrage existent
  (`docs/configuration.md` pour les réglages).
- Les deux opérations acceptent une **vue rangée** dans une `Library`, désignée par sa canonique
  (`viewReference` en `url` ou `url|version`) ; autres formes refusées (§4).
- Il **exécute une requête SQL sur les vues** (`POST /fhir/$sqlquery-run`, F-039) : `SELECT` seul,
  sur les données courantes, dans une base en mémoire ouverte puis fermée à chaque appel ; allumée
  par défaut, sans authentification. Usage, limites et paramètres : `README.md`, « Requêtes SQL sur
  les vues » ; décision, limites L1 à L18 et retrait : ADR 0014.
- Les **écarts assumés à la spécification** SQL on FHIR sont réunis au §4.

## 2. Ce qui existe, et comment le vérifier

Les commandes se lancent depuis la racine du dépôt. Celles du module demandent d'abord
`eval "$(scripts/outils.sh env)"` (outils installés une fois par `make outils`).

| Quoi | Commande | Résultat attendu | Dernier relevé |
|---|---|---|---|
| Poste du module : outils, jar de l'image publiée, sources de Blaze en lecture | `scripts/outils.sh etat` | une ligne par outil (clojure, clj-kondo, clojure-lsp, cljfmt, blazectl, jdk), aucune « ABSENT » | 6 lignes, aucune « ABSENT », code 0, 2026-10-17 |
| Jar du module, rejouable (`docs/construction.md`) | `build/construire-jar.sh` | 4 lignes `étape n/4 : …`, `sha256 : <empreinte>` égale à `sha256sum build/blaze-addon.jar`, code 0 | `b49d3156…4e355`, code 0, 2026-10-17 (copie du dossier publié) |
| Règle « module complémentaire » contrôlée (ADR 0003) | `python3 scripts/check_module.py` | `SUCCES` sur M1 à M6 (jar du module construit ; absent : `NON-BRANCHE` M4) | 6 `SUCCES`, code 0, 2026-10-17 |
| Contrôles N0 : jar, versions, module complémentaire, plafond `_include` | `make check` | code 0, `SUCCES` M1 à M6 | identique, 2026-10-17 (copie du dossier publié, après `scripts/outils.sh jar`) |
| Plafond de `_include` déclaré par le chart égal à celui de nginx | `python3 scripts/check_gardefou7.py` | `plafond _include = 5 (values.yaml = nginx.conf)` | identique, code 0, 2026-10-17 |
| Image du module, construite **sur** l'image Blaze publiée, jar du module devant celui de Blaze | `make image` | code 0, image `blaze-platform-module:dev` | code 0, 2026-10-17 (copie du dossier publié, `IMAGE=` étiquette d'essai) |
| L'image démarre et déclare les deux opérations du module | bloc `docker run` … `curl` du « Démarrage rapide » du `README.md` | `2` | `2`, 2026-10-17 ; sans les quatre variables `VIEWDEFINITION_EXPORT_*` : arrêt, code 1, `Assertion failed on key :blaze.job/viewdefinition-export` |
| Fonctions et opérateurs FHIRPath ajoutés à Blaze (`join`, `lowBoundary`, `or`, `<`, `+`, `%rowIndex`, constantes typées…) ; fonctions de texte `startsWith`, `endsWith`, `contains`, `upper`, `lower`, `length`, `substring`, `indexOf`, `replace` et opérateurs `~`, `!~` (entrée vide → vide, `~` faux, `!~` vrai ; plusieurs éléments → 422 nommant fonction et cardinalité ; `matches`, `replaceMatches`, `trim`, `toChars` refusées en nommant la fonction). *Décidé, non mesuré* contre une implémentation de référence : `substring(start)` avec `start` égal à la longueur rend `''` ; `~` entre non-chaînes délègue à `=` de Blaze (décimaux non arrondis, sans la règle d'équivalence des dates) | `make -C modules/sql-on-fhir test` | `… 0 failures.` | `207 tests, 1618 assertions, 0 failures.`, code 0, 2026-10-17 |
| Moteur de vues en mémoire : la suite de conformité | `make -C modules/sql-on-fhir conformance` | `REUSSIS 144/144  ECHEC 0  NON-BRANCHE 0`, code 0 | identique, 2026-10-17 |
| Le moteur n'a pas besoin de la base de Blaze | `make -C modules/sql-on-fhir independance` | `SUCCES`, code 0 | `SUCCES : REUSSIS 144/144  ECHEC 0  NON-BRANCHE 0 ; 0 namespace blaze.db / blaze.interaction chargé`, code 0, 2026-10-17 |
| Requête SQL sur les vues (`$sqlquery-run`) : tables = vues, lignes et calculs égaux à `$viewdefinition-run`, paramètre lié, lecture seule, refus 400/404/422, plafonds de durée, de mémoire, de concurrence et de lignes, interrupteur | `bash tests/integration/test.sh IT-049 IT-050 IT-051 IT-052 IT-053 IT-054 IT-055 IT-056 IT-057 IT-058 IT-059 IT-060 IT-061` (banc kind, `scripts/banc.sh`) | `SYNTHESE` sans `echec` sur un banc dont les vues tiennent sous les plafonds par défaut ; passages à plafonds abaissés ou relevés : `docs/tests.md` §3 | `SYNTHESE cas=13 succes=26 echec=6 non-branche=1 total=33`, code 8, 2026-10-27 : les 6 échecs et le `NON-BRANCHE` sont les rouges attendus d'un banc dont la vue `observation` (168 981 lignes) dépasse les plafonds par défaut, verts à leurs passages dédiés (`bench/baselines/T-114/lancement4/`, `docs/tests.md` §3) |
| Couverture des tests du module | `make -C modules/sql-on-fhir test-coverage` | ligne `ALL FILES` ≥ 95 % | `ALL FILES` `95,32`, code 0, 2026-10-17 |

Les opérations HTTP (`$viewdefinition-run`, `$viewdefinition-export`, `viewReference`) sont
éprouvées par des cas d'intégration qui supposent une plateforme en marche ; ils ne sont pas
diffusés avec ce dossier. Liste fonction par fonction, avec fichiers et tests :
`docs/couverture.md`. Suite de conformité copiée : `modules/sql-on-fhir/conformance/sql-on-fhir-js/`
(provenance, ADR 0006).

## 3. Ce qui est partiel ou limité

Toutes ces limites sont admises et consignées dans `docs/couverture.md` §2 et §3.

- **Bornes de date-heure et d'heure** (`lowBoundary`, `highBoundary`) : rendues comme du texte,
  pas comme une date ; on ne peut pas les comparer à une date dans un filtre. La précision lue
  est perdue : `12:34` donne une borne haute `12:34:00.999`.
- **Bornes avec précision explicite** (`lowBoundary(6)`) sur décimal, date-heure, heure :
  refusées (`unsupported`). Seule la forme sans argument est exigée.
- **`forEachOrNull` sur une liste vide** : la ligne vide disparaît si un `forEach` est imbriqué
  dedans. Conforme à la suite, non spécifié ailleurs.
- **`repeat`** : profondeur plafonnée à 1 000 ; un cycle se détecte par élément identique à un
  ancêtre.
- **Constructions FHIRPath non gérées** (7, dont `implies`, `in`, `contains`, `-x`, quantités
  littérales, heures littérales, `$index`, `$total`) : la vue est refusée avec une erreur qui
  nomme la construction (`unsupported`), sans plantage.
- **Non-régression de l'indexation de Blaze** : la preuve qu'un test amont aléatoire
  (`blaze.db.api-test/transact-create-property-test`) est instable sur le Blaze publié — une
  sortie où il échoue, une où il passe — est conservée sous `modules/sql-on-fhir/target/`
  (`flaky-jar.out`, `complet-jar.out`). **Coût d'un `make -C modules/sql-on-fhir clean`** : il
  l'efface, et `make -C modules/sql-on-fhir test-amont-controle SORTIE=…` répond alors `INDETERMINE`
  jusqu'à régénération par `make -C modules/sql-on-fhir test-amont-instables`, qui relance le test
  seul (≈ 20 s l'essai) jusqu'à un échec et un succès.
  - *Valeur par défaut* : au plus `INSTABLES_N` = 100 essais, sorties sous `target/`
    (`modules/sql-on-fhir/Makefile`, cible `test-amont-instables`).
  - *Justification* : le test seul a échoué 2 fois en 139 essais, taux p ≈ 1,4 % — estimation,
    non mesurée au-delà. Durée attendue ≈ 1/p ≈ 70 essais ≈ 23 min ; `INSTABLES_N` ne borne que
    le pire cas (100 essais ≈ 33 min), atteint environ 1 fois sur 4 à ce taux (estimation,
    (1 − 2/139)^100 ≈ 0,24).
  - *Borne atteinte* : `INDETERMINE`, rien n'est écrit ; relancez la commande.
  - *Changer la borne* : `make -C modules/sql-on-fhir test-amont-instables INSTABLES_N=200` (≈ 6 % de
    `INDETERMINE`, ≈ 67 min au pire, mêmes estimations).
  - *Codes* : `SUCCES` → 0 ; `ECHEC` et `INDETERMINE` → **2 tous deux** à travers `make`, seul le message
    les distingue (le script rend 1 et 2, `make` rend 2 pour toute recette en échec).
- **Ordre des colonnes** (`expectColumns`) : contrôlé par la suite de conformité, avec témoin rouge
  (`expect-columns-test`, `modules/sql-on-fhir/test/blaze/addon/sql_on_fhir/conformance_test.clj`) ;
  limite : les colonnes se lisent sur la première ligne, un résultat vide avec `expectColumns` est un ECHEC.
- **`$viewdefinition-run`, message d'une vue incorrecte** : le 422 `invalid` porte tel quel le message
  interne du moteur (ex. `No implementation of method: :-compile …` pour `id.(`), pas une phrase
  lisible par l'analyste.
- **`$viewdefinition-run`, mémoire** : jusqu'à plafond + 1 lignes (10 001 par défaut) sont tenues en
  mémoire avant la première écrite ; aucun seuil de mémoire n'est mesuré.
- **Export, tâche `ready` au moment d'un arrêt brutal** : elle reste `ready` ; la reprise ne la
  touche pas, et l'expiration ne purge que les tâches terminées. Le lieu de suivi pourrait alors
  rendre 202 sans fin ; le client l'annule par `DELETE`. *Décidé, non mesuré*.
- **`$sqlquery-run`** : dix-huit limites d'utilisation (L1 à L18, ADR 0014), dont plafond de durée
  de protection et non de précision (L3), chargement des vues en tas hors plafond de mémoire (L17),
  aucune authentification (L8). Risque résiduel documenté (décision humaine du 2026-10-28) : la
  limite mémoire du pod ne couvre pas la somme des bornes (ADR 0014 point 5) ; écriture massive
  pendant des requêtes saturantes non mesurée, aucune tâche. La démonstration docker compose
  (`demo/`) ne pose pas le jar du moteur : l'opération y rend 500 (relevé du 2026-10-28).
- **Un seul nœud Blaze** (variante `standalone`) : ni plusieurs nœuds, ni stockage réseau ne
  sont éprouvés.

## 4. Écarts à la spécification

Référence : IG SQL on FHIR de la version `SQL_ON_FHIR_IG_VERSION` de `versions.env`, construction du 2026-05-21, pages
`OperationDefinition-ViewDefinitionRun.html` (« run ») et `OperationDefinition-ViewDefinitionExport.html`
(« export ») de `https://build.fhir.org/ig/FHIR/sql-on-fhir-v2/`, et leurs `OperationDefinition`
du paquet `package.tgz` ; relues le 2026-10-13 ; pour `$sqlquery-run` (« sql »),
`OperationDefinition-SQLQueryRun.json` du même paquet, relu le 2026-10-27 (T-119). Une ligne par
écart assumé.

| Sujet | Ce que dit l'IG | Ce que fait le module |
|---|---|---|
| Niveaux d'appel | run et export : `system`, `type`, `instance` à `true` | niveau système seul (`POST /fhir/$viewdefinition-run`, `…/$viewdefinition-export`) : Blaze n'a pas de type `ViewDefinition` |
| `viewReference` | 0..1, trois formats au choix du serveur (MAY) ; formats pris en charge documentés dans le `CapabilityStatement` (SHALL) | seule la canonique (`url` ou `url\|version`) d'une `Library` rangée sur ce serveur, cherchée dans la base de la requête, jamais téléchargée ; documentée dans `rest[0].operation[].documentation` de `GET metadata` ; forme sans `scheme:` (`ViewDefinition/<id>`, `Library/<id>`) → 400 `not-supported` ; aucune `Library` → 400 `not-found` ; plusieurs sans `\|version` → 400 `multiple-matches` ; contenu de `Library` inutilisable → 422 ; export : vue résolue au lancement, une `Library` remplacée ensuite ne change pas les fichiers |
| `Accept` sans `_format` (run) | « Servers MAY honour the HTTP Accept header to negotiate an alternative format when `_format` is not supplied » ; `_format` prioritaire (SHALL) ; types `application/json`, `application/x-ndjson`, `text/csv` | `Accept` à **type unique** de ces trois servi dans son format, paramètres (`charset`, `q` > 0) ignorés ; poids `q=0` (« not acceptable », RFC 9110 §12.4.2), liste de plusieurs types (pondérée ou non : tout `Accept` contenant une virgule), `*/*` ou autre type → NDJSON ; `_format` l'emporte ; export : `Accept` non lu pour le format |
| `_format=parquet` (export) | `_format` : `csv`, `ndjson`, `parquet`, `json` (extensible) | refusé, 400 `not-supported` |
| Patient ou groupe introuvable | export, exemple 4 « Patient or Group Not Found » : 404 ; run, exemple 6 : 400 | 400 `not-found`, run et export |
| Vue invalide | table des codes et exemple 2 des deux pages : 422 ; export, texte du lancement et exemple 5 : 400 | 422 (`invalid` ou `not-supported`), run et export |
| Accès non autorisé aux URLs d'export | 401 ou 403 | 404 `not-found` (URL à jeton de 192 bits, voie « Non-guessable URLs » de l'IG) |
| `patient` ou `group` répétés (export) | `patient` 0..\*, `group` 0..\* ; paramètre non géré : 400 + `OperationOutcome` | 400 `not-supported` « Only one `patient` and one `group` are supported. » |
| `DELETE` d'un export terminé | `DELETE` du lieu de suivi = annulation (SHOULD) : 202, puis 404 ; rien sur une tâche terminée ; `cancelUrl` 0..1 | 202, `Task` et fichiers supprimés, lieu à 404 ensuite ; `DELETE` sur résultat et fichier : 405 |
| Niveaux d'appel (sql) | `system`, `type`, `instance` à `true`, `resource` `Library` | niveau système seul : `POST /fhir/Library/$sqlquery-run` → 405, `POST /fhir/Library/<id>/$sqlquery-run` → 404 ; la `Library` se passe par `queryReference` ou `queryResource` |
| `_format=fhir` (sql) | sortie `Parameters` | refusé, 400 `not-supported` ; servis : `ndjson` (défaut), `json`, `csv` |
| `_format=parquet` (sql) | valeur de l'ensemble des formats (extensible) | refusé, 400 `not-supported` |
| `source` (sql) | entrée `source` (string) | refusée, 400 `not-supported` |

Écarts relevés mais **non tranchés** : paramètre 0..1 répété, documentation du
`CapabilityStatement`.

## 5. Ce qui n'existe pas encore

| Manque | Mesure de l'absence (domaine écrit) |
|---|---|
| Image du module publiée dans un registre | `kubernetes/chart/values.yaml` : `image.blaze.tag` sans défaut, posé au déploiement (bloc « Déployer » du `README.md`) ; aucun registre fourni |
| Relecture d'un export en colonnes (Parquet) | `/usr/bin/grep -rlE 'parquet' modules/sql-on-fhir/src` → aucun fichier, code 1, 2026-10-17 |
| Validateur de profils, fournisseur OpenID, frontend Blaze, Ingress, serveur de terminologie | `docs/architecture.md`, « Architecture cible » : composants non déployés, relevé `helm template` du 2026-10-17 |
| Search parameters ajoutés | `blaze/search-parameters.json` absent |
