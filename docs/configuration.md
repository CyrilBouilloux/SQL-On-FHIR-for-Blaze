# Configuration — où se règle quoi · where each setting lives

<!-- Tenu par `architect`. Chaque section nomme le fichier qui fait foi ; les défauts
     recopiés dans les tables de paramètres le sont parce que F-034 l'exige (« nom, rôle,
     défaut ») : en cas d'écart, le fichier nommé l'emporte. Aucune version, aucun secret.
     État au 2026-10-17. -->

*Français ci-dessous — [English below](#english).*

## Français

### Où se règle quoi

| Réglage | Fichier qui fait foi | Lu par | Remarque |
|---|---|---|---|
| Versions et empreintes d'images (Blaze, nginx, outils du poste, chart) | `versions.env` | `build/construire-jar.sh`, `kubernetes/chart/deriver.sh`, `scripts/outils.sh`, `Makefile` (`make image`), `scripts/check_module.py` | seule source des versions, jamais `latest` ; contrôlée par `python3 scripts/check_versions.py` |
| Versions servies par le chart | `kubernetes/chart/values-versions.yaml`, `Chart.yaml` | Helm | **dérivés** par `kubernetes/chart/deriver.sh`, jamais édités ni versionnés |
| Paramètres de la livraison : namespace, images, URL publique, tas JVM, volume, exposition, ressources, `NetworkPolicy` | `kubernetes/chart/values.yaml` | Helm | une ligne par clé : « Clés de `kubernetes/chart/values.yaml` » ; variables Blaze supplémentaires : clé `blaze.env` |
| Image servie par le chart (module) | dépôt : `kubernetes/chart/values.yaml` (`image.blaze.repository`) ; étiquette : `image.blaze.tag`, **sans défaut** (le chart refuse de se rendre sans elle) | Helm | à poser au déploiement : version `BLAZE_VERSION` de `versions.env` + 12 premiers hexadécimaux de l'identifiant de l'image `make image` (bloc « Déployer » du `README.md`) ; `latest` refusé ; sans `image.blaze.registry` : image locale `<repository>:<tag>`, sans empreinte ; avec `image.blaze.registry` : `image.blaze.digest` (`sha256:<64 hexadécimaux>`) **obligatoire**, image `<registry>/<repository>:<tag>@<digest>` ; nginx : toujours `@sha256:` dérivé de `versions.env` |
| `$viewdefinition-run` : plafond de lignes, interrupteur | `kubernetes/chart/values.yaml` (`blaze.viewdefinitionRun.plafondLignes`, `blaze.viewdefinitionRun.actif`) | Helm → variables `VIEWDEFINITION_RUN_MAX_ROWS`, `DISABLE_VIEWDEFINITION_RUN` (posée seulement si `actif: false`) | refusés par le chart (`fail`, rc 1) : `actif` non booléen (à passer par `--set`, jamais `--set-string`), plafond autre qu'un entier ≥ 1 sans zéro de tête ; lues par l'overlay généré `modules/sql-on-fhir/resources/blaze.edn` (`modules/sql-on-fhir/generer-overlay.py`) ; au-delà du plafond, refus 422 `too-costly` |
| `$viewdefinition-export` : interrupteur, dossier, plafond d'octets, conservation, période de nettoyage, volume | `kubernetes/chart/values.yaml` (`blaze.viewdefinitionExport.*`) | Helm → `VIEWDEFINITION_EXPORT_DIR`, `_MAX_BYTES`, `_RETENTION_SECONDS`, `_CLEANUP_PERIOD_SECONDS` (toujours posées), `DISABLE_VIEWDEFINITION_EXPORT=true` (seulement si `actif: false`) ; PVC `<release>-export` (`kubernetes/chart/templates/export-pvc.yaml`) monté sur `dossier`, rendu dans les deux états | refusés par le chart (`fail`, rc 1) : `actif` non booléen, `dossier` non absolu ou sous `/app/data` ou `/tmp`, entier autre que ≥ 1, `stockage.taille` non entière ; `DISABLE_VIEWDEFINITION_EXPORT=false` (et `DISABLE_VIEWDEFINITION_RUN=false`) se pose par `blaze.env` ; le PVC survit à `helm uninstall` (`helm.sh/resource-policy: keep`), il se supprime à la main |
| Plafond `_include` / `_revinclude` | déclaré : `kubernetes/chart/values.yaml` (`proxy.plafondInclude`) ; appliqué : `nginx/nginx.conf` | nginx | les deux doivent coïncider : `python3 scripts/check_gardefou7.py` |
| Codes de sortie des scripts | `lib/codes.sh` | scripts de `build/`, `scripts/`, `kubernetes/` | table unique |
| Dépendances et alias du module (`:test`, `:kaocha`, `:coverage`) | `modules/sql-on-fhir/deps.edn` | Clojure CLI | le jar de Blaze vient de `.work/` (`make outils`) ; `amont/` (sources de Blaze, lecture seule) n'entre que par l'alias `:test`. Porte aussi les versions des bibliothèques de test : écart connu à la règle « `versions.env` seule source » |
| Listes d'exclusion des suites amont (`L`, `I`, `A`) | `modules/sql-on-fhir/exclusions/` | cibles `test-amont*` du `Makefile` du module | — |
| Suite de conformité (commit épinglé) | `modules/sql-on-fhir/conformance/sql-on-fhir-js/` | `make -C modules/sql-on-fhir conformance` | provenance et licence : ADR 0006 |

Absents à ce jour : `blaze/search-parameters.json` (aucun paramètre ajouté), réglage du
validateur et d'OpenID — aucun n'est déployé (`docs/architecture.md`, « Architecture cible »).

### Le jar du module devant celui de Blaze

Le jar du module (`build/blaze-addon.jar`, `docs/construction.md`) se pose **avant** le jar
de Blaze dans le classpath : `java -cp "/app/addon/blaze-addon.jar:/app/*" blaze.core`
(`build/Dockerfile`). La JVM lit la première ressource trouvée : le `blaze.edn` du module —
l'overlay qui déclare les opérations du module et lit les variables ci-dessous — masque
celui de Blaze. Aucune autre entrée n'est commune aux deux jars (contrôle M4,
`python3 scripts/check_module.py`) : le module n'ajoute que des espaces de noms
`blaze.addon.*`. Placé après, le jar du module laisserait le `blaze.edn` de Blaze en vigueur
et ses opérations ne seraient pas déclarées.

### Variables lues par l'image

Domaine (a) de F-034 : variables lues par l'overlay, posées par le chart, arguments du
`Dockerfile`. Relevé : `79` noms, même liste que la commande

```bash
{ /usr/bin/grep -ohE '#blaze/cfg \["[A-Z][A-Z0-9_]+"' modules/sql-on-fhir/resources/blaze.edn | /usr/bin/grep -oE '[A-Z][A-Z0-9_]+'; /usr/bin/grep -ohE '\{ name: [A-Z][A-Z0-9_]+' kubernetes/chart/templates/*.yaml | awk '{print $3}'; /usr/bin/grep -ohE '^ARG [A-Z_]+' build/Dockerfile | awk '{print $2}'; } | LC_ALL=C sort -u
```

Défaut : celui de `modules/sql-on-fhir/resources/blaze.edn`, qui fait foi (il masque celui
de Blaze, section précédente) ; pour les répertoires, l'image publiée pose déjà la valeur
indiquée (`docker image inspect -f '{{json .Config.Env}}'`). Le chart pose `BASE_URL`,
`JAVA_TOOL_OPTIONS` et les variables `VIEWDEFINITION_*` depuis `kubernetes/chart/values.yaml` ;
toute autre se pose par la clé `blaze.env`. Sans `docker run -e` ni chart, une variable
« aucun défaut » est absente : la fonction qu'elle règle est inactive, sauf mention
« exigée ».

**Module `sql-on-fhir`, construction, chart**

| Variable | Rôle | Défaut |
|---|---|---|
| `VIEWDEFINITION_EXPORT_DIR` | dossier des fichiers de `$viewdefinition-export`, chemin absolu (créé au besoin) | aucun défaut — **exigée** tant que l'export est allumé ; chart : `blaze.viewdefinitionExport.dossier` |
| `VIEWDEFINITION_EXPORT_MAX_BYTES` | plafond d'octets occupés par les exports (fichiers `.part` compris), entier ≥ 1 | aucun défaut — **exigée** ; chart : `blaze.viewdefinitionExport.plafondOctets` |
| `VIEWDEFINITION_EXPORT_RETENTION_SECONDS` | durée de conservation d'un export terminé, en secondes, entier ≥ 1 | aucun défaut — **exigée** ; chart : `blaze.viewdefinitionExport.conservationSecondes` |
| `VIEWDEFINITION_EXPORT_CLEANUP_PERIOD_SECONDS` | période du nettoyage des exports expirés, en secondes, entier ≥ 1 | aucun défaut — **exigée** ; chart : `blaze.viewdefinitionExport.periodeNettoyageSecondes` |
| `DISABLE_VIEWDEFINITION_EXPORT` | interrupteur inverse : `true` éteint `$viewdefinition-export`, et les quatre variables ci-dessus ne sont plus exigées | absent : opération allumée |
| `VIEWDEFINITION_RUN_MAX_ROWS` | plafond de lignes rendues par `$viewdefinition-run` ; au-delà, 422 `too-costly` | `10000` |
| `DISABLE_VIEWDEFINITION_RUN` | interrupteur inverse : `true` éteint `$viewdefinition-run` | absent : opération allumée |
| `BASE_URL` | URL sous laquelle les clients joignent Blaze ; l'API est sous `BASE_URL` + `CONTEXT_PATH` | Blaze : `http://localhost:8080` ; chart : `blaze.baseUrl` |
| `JAVA_TOOL_OPTIONS` | options de la JVM, ajoutées à celles de l'image | vide ; chart : `-Xmx` + `blaze.xmx` |
| `BLAZE_VERSION` | argument de construction (`ARG`) : étiquette de l'image `samply/blaze` du `FROM` | aucun défaut — passé par `make image` depuis `versions.env` |
| `BLAZE_IMAGE_DIGEST` | argument de construction (`ARG`) : empreinte de l'image `samply/blaze` du `FROM` | aucun défaut — passé par `make image` depuis `versions.env` |

**Blaze : serveur et API**

| Variable | Rôle | Défaut |
|---|---|---|
| `CONTEXT_PATH` | chemin de l'API FHIR | `/fhir` |
| `FHIR_VERSION` | version de FHIR servie | FHIR R4 (valeur littérale : `blaze.edn`) |
| `ENFORCE_REFERENTIAL_INTEGRITY` | intégrité référentielle à la création, modification, suppression | `true` |
| `ALLOW_MULTIPLE_DELETE` | suppression conditionnelle de plusieurs ressources | `false` |
| `PAGE_STORE_EXPIRE` | durée (ISO-8601) de vie des pages de résultats | `PT1H` |
| `DB_SYNC_TIMEOUT` | attente maximale, en ms, de l'état de base le plus récent par une lecture ; au-delà, 503 | `10000` |
| `DB_MAX_IN_FLIGHT_TRANSACTIONS` | transactions soumises non encore indexées ; au-delà, 503 | `1024` |
| `DB_SEARCH_PARAM_BUNDLE` | fichier d'un bundle de `SearchParameter` **ajoutés** aux paramètres R4 ; n'indexe que les écritures suivantes | aucun défaut (paramètres R4 seuls ; `blaze/search-parameters.json` absent à ce jour) |
| `JVM_METRICS_LOGGER_INTERVAL` | intervalle (ISO-8601) de journalisation des métriques de la JVM | `PT5M` |
| `FHIR_OPERATION_EVALUATE_MEASURE_THREADS` | fils d'exécution de `$evaluate-measure` | nombre de processeurs |
| `FHIR_OPERATION_EVALUATE_MEASURE_TIMEOUT` | délai, en ms, d'un `$evaluate-measure` synchrone | `3600000` |
| `FHIR_OPERATION_EVALUATE_MEASURE_REPORT_PERSISTENCE` | `true` : le `MeasureReport` d'un `POST $evaluate-measure` est enregistré (201) ; `false` : rendu sans l'enregistrer (200) | `true` |
| `FHIR_OPERATION_GRAPHQL_THREADS` | fils d'exécution des requêtes GraphQL | `4` |
| `CQL_EXPR_CACHE_SIZE` | taille, en Mio, du cache d'expressions CQL (dans le tas) | aucun défaut (cache désactivé) |
| `CQL_EXPR_CACHE_REFRESH` | durée (ISO-8601) après laquelle un filtre de Bloom du cache CQL est recalculé | `PT24H` |
| `CQL_EXPR_CACHE_THREADS` | calculs de filtres de Bloom en parallèle | `4` |

**Blaze : stockage standalone (RocksDB)**

| Variable | Rôle | Défaut |
|---|---|---|
| `INDEX_DB_DIR` | répertoire de la base d'index | `/app/data/index` (image ; overlay : `index`) |
| `INDEX_DB_WAL_DIR` | répertoire du journal d'écriture anticipée (WAL) de la base d'index | vide : celui de la base |
| `TRANSACTION_DB_DIR` | répertoire du journal de transactions | `/app/data/transaction` (image ; overlay : `transaction`) |
| `TRANSACTION_DB_WAL_DIR` | WAL du journal de transactions | vide : celui de la base |
| `RESOURCE_DB_DIR` | répertoire du magasin de ressources | `/app/data/resource` (image ; overlay : `resource`) |
| `RESOURCE_DB_WAL_DIR` | WAL du magasin de ressources | vide : celui de la base |
| `ADMIN_INDEX_DB_DIR` | répertoire de la base d'index d'administration (tâches de fond) | `/app/data/admin-index` (image ; overlay : `admin-index`) |
| `ADMIN_TRANSACTION_DB_DIR` | journal de transactions de la base d'administration | `/app/data/admin-transaction` (image ; overlay : `admin-transaction`) |
| `DB_BLOCK_CACHE_SIZE` | cache de blocs RocksDB, en Mio, hors tas | `128` |
| `DB_RESOURCE_CACHE_SIZE_RATIO` | part du tas allouée au cache de ressources (0 : désactivé, 0,8 au plus) | `0.25` |
| `DB_TRANSACTION_CACHE_SIZE` | entrées du cache des transactions | `100000` |
| `DB_MAX_BACKGROUND_JOBS` | tâches de fond RocksDB (compactions) | `4` |
| `DB_MAX_SUBCOMPACTIONS` | découpage d'une compaction en sous-compactions | `1` |
| `DB_COMPACTION_READAHEAD_SIZE` | lecture anticipée des compactions RocksDB, en octets | aucun défaut (valeur de RocksDB) |
| `DB_RESOURCE_INDEXER_THREADS` | fils d'indexation des ressources | `4` |
| `DB_RESOURCE_STORE_KV_THREADS` | fils de lecture et d'écriture des ressources | `4` |

**Blaze : variante `distributed`** — non employée ici (stockage standalone) ; lues seulement
si `STORAGE=distributed`.

| Variable | Rôle | Défaut |
|---|---|---|
| `DB_KAFKA_BOOTSTRAP_SERVERS` | serveurs d'amorçage du journal de transactions Kafka | `localhost:9092` |
| `DB_KAFKA_MAX_REQUEST_SIZE` | taille maximale, en octets, d'une transaction envoyée à Kafka | `1048576` |
| `DB_KAFKA_COMPRESSION_TYPE` | compression des transactions envoyées | `snappy` |
| `DB_KAFKA_SECURITY_PROTOCOL` | protocole vers les courtiers (`PLAINTEXT`, `SSL`) | `PLAINTEXT` |
| `DB_KAFKA_SSL_TRUSTSTORE_LOCATION` | magasin de confiance TLS | aucun défaut |
| `DB_KAFKA_SSL_TRUSTSTORE_PASSWORD` | secret du magasin de confiance | aucun défaut |
| `DB_KAFKA_SSL_KEYSTORE_LOCATION` | magasin de clés TLS (authentification mutuelle) | aucun défaut |
| `DB_KAFKA_SSL_KEYSTORE_PASSWORD` | secret du magasin de clés | aucun défaut |
| `DB_KAFKA_SSL_KEY_PASSWORD` | secret de la clé privée | aucun défaut |
| `DB_CASSANDRA_CONTACT_POINTS` | points de contact du magasin de ressources Cassandra | `localhost:9042` |
| `DB_CASSANDRA_USERNAME` | utilisateur Cassandra | `cassandra` (défaut public de Blaze) |
| `DB_CASSANDRA_PASSWORD` | secret de l'utilisateur Cassandra | `cassandra` (défaut public de Blaze) |
| `DB_CASSANDRA_KEY_SPACE` | espace de clés de la table `resources` | `blaze` |
| `DB_CASSANDRA_PUT_CONSISTENCY_LEVEL` | niveau de cohérence des écritures | `TWO` |
| `DB_CASSANDRA_MAX_CONCURRENT_REQUESTS` | requêtes Cassandra simultanées | `1024` |
| `DB_CASSANDRA_MAX_REQUEST_QUEUE_SIZE` | file d'attente des requêtes Cassandra | `100000` |
| `DB_CASSANDRA_REQUEST_TIMEOUT` | délai, en ms, d'une requête Cassandra | `2000` |

**Blaze : authentification, validation, terminologie** — non déployées par le chart
(`docs/architecture.md`, « Architecture cible »).

| Variable | Rôle | Défaut |
|---|---|---|
| `OPENID_PROVIDER_URL` | fournisseur OpenID Connect ; renseignée, elle active l'authentification | aucun défaut (API ouverte) |
| `OPENID_PROVIDER_ISSUER` | `iss` attendu des jetons | aucun défaut (non vérifié) |
| `OPENID_AUDIENCE` | `aud` attendu des jetons | aucun défaut (non vérifié) |
| `OPENID_CLIENT_TRUST_STORE` | magasin de confiance PKCS #12 vers le fournisseur | aucun défaut |
| `OPENID_CLIENT_TRUST_STORE_PASS` | secret de ce magasin | aucun défaut |
| `EXTERN_VALIDATOR_URL` | validateur externe de profils, appelé avant persistance ; renseignée, elle active la validation | aucun défaut (aucune validation de profils) |
| `EXTERN_VALIDATOR_MAX_CONCURRENCY` | requêtes simultanées vers le validateur | `4` |
| `EXTERN_VALIDATOR_CLIENT_TRUST_STORE` | magasin de confiance PKCS #12 vers le validateur | aucun défaut |
| `EXTERN_VALIDATOR_CLIENT_TRUST_STORE_PASS` | secret de ce magasin | aucun défaut |
| `EXTERN_TERMINOLOGY_SERVICE_URL` | serveur de terminologie externe pour CQL | aucun défaut (inactif) |
| `EXTERN_TERMINOLOGY_SERVICE_CLIENT_TRUST_STORE` | magasin de confiance PKCS #12 vers ce serveur | aucun défaut |
| `EXTERN_TERMINOLOGY_SERVICE_CLIENT_TRUST_STORE_PASS` | secret de ce magasin | aucun défaut |
| `EXTERN_TERMINOLOGY_SERVICE_OPENID_PROVIDER_URL` | fournisseur OpenID du serveur de terminologie (flux « client credentials ») ; renseignée, l'active | aucun défaut |
| `EXTERN_TERMINOLOGY_SERVICE_OPENID_CLIENT_ID` | identifiant de client de ce flux | aucun défaut |
| `EXTERN_TERMINOLOGY_SERVICE_OPENID_CLIENT_SECRET` | secret de client de ce flux | aucun défaut |
| `EXTERN_TERMINOLOGY_SERVICE_OPENID_CLIENT_TRUST_STORE` | magasin de confiance PKCS #12 vers ce fournisseur | aucun défaut |
| `EXTERN_TERMINOLOGY_SERVICE_OPENID_CLIENT_TRUST_STORE_PASS` | secret de ce magasin | aucun défaut |
| `TERMINOLOGY_SERVICE_GRAPH_CACHE_SIZE` | concepts tenus par le cache de graphe du service de terminologie interne | `100000` |
| `SNOMED_CT_RELEASE_PATH` | répertoire d'une publication officielle de SNOMED CT (service interne) | aucun défaut |

### Clés de `versions.env` lues par la construction

Domaine (b) de F-034. Le script de construction lit les deux premières ; `scripts/outils.sh jar`
lit aussi la troisième. Les valeurs sont dans `versions.env`, seule source.

| Clé | Rôle | Défaut |
|---|---|---|
| `BLAZE_VERSION` | étiquette de l'image Blaze publiée devant le jar de laquelle se pose celui du module ; argument `BLAZE_VERSION` du `Dockerfile` | aucun défaut — clé absente : `build/construire-jar.sh` rend 3 |
| `BLAZE_IMAGE_DIGEST` | empreinte de cette image (`FROM samply/blaze:<version>@<empreinte>`) | aucun défaut — clé absente : `build/construire-jar.sh` rend 3 |
| `BLAZE_JAR_SHA256` | empreinte attendue du jar extrait par `scripts/outils.sh jar` | aucun défaut — écart : `scripts/outils.sh jar` refuse |

### Clés de `kubernetes/chart/values.yaml`

Domaine (c) de F-034 : `35` clés feuilles, relevé
`python3 -c "import yaml; n=lambda v: sum(map(n, v.values())) if isinstance(v, dict) and v else 1; print(n(yaml.safe_load(open('kubernetes/chart/values.yaml'))))"`.
Défaut : `kubernetes/chart/values.yaml`, qui fait foi ; se change par `-f` ou `--set`.

| Clé | Rôle | Défaut |
|---|---|---|
| `namespace.create` | le chart déclare le namespace, avec la politique `restricted` ; `false` s'il existe déjà hors Helm | `true` |
| `namespace.name` | namespace de tous les objets | `blaze` |
| `image.blaze.registry` | hôte du registre de l'image du module (`hôte[:port]`) ; renseigné, `image.blaze.digest` devient obligatoire | vide : image locale |
| `image.blaze.repository` | dépôt de l'image du module | `blaze-platform-module` |
| `image.blaze.tag` | étiquette de l'image du module ; `latest` refusé (`image.blaze.tag : latest interdit`) | aucun défaut — le chart refuse de se rendre sans elle |
| `image.blaze.digest` | empreinte `sha256:<64 hexadécimaux>` ; obligatoire avec registre, refusée sans registre ou mal formée | vide |
| `image.blaze.pullPolicy` | politique de téléchargement de l'image du module | `IfNotPresent` |
| `image.nginx.repository` | dépôt de l'image nginx (non root) ; étiquette et empreinte dérivées de `versions.env` | `nginxinc/nginx-unprivileged` |
| `image.nginx.pullPolicy` | politique de téléchargement de l'image nginx | `IfNotPresent` |
| `securite.runAsUser` | utilisateur imposé aux pods | vide : celui que le cluster impose |
| `securite.fsGroup` | groupe propriétaire des volumes | `1001` |
| `blaze.baseUrl` | URL publique de l'API, sans `/fhir` → `BASE_URL` | `http://localhost:8088` |
| `blaze.xmx` | tas maximal de la JVM → `JAVA_TOOL_OPTIONS=-Xmx…` ; la limite mémoire doit rester au-dessus | `1g` |
| `blaze.env` | variables Blaze supplémentaires, nom → valeur | vide |
| `blaze.viewdefinitionRun.actif` | `false` pose `DISABLE_VIEWDEFINITION_RUN=true` ; booléen | `true` |
| `blaze.viewdefinitionRun.plafondLignes` | → `VIEWDEFINITION_RUN_MAX_ROWS` ; entier ≥ 1 | `10000` |
| `blaze.viewdefinitionExport.actif` | `false` pose `DISABLE_VIEWDEFINITION_EXPORT=true` ; booléen | `true` |
| `blaze.viewdefinitionExport.dossier` | → `VIEWDEFINITION_EXPORT_DIR` ; chemin absolu hors `/app/data` et `/tmp`, point de montage du PVC d'export | `/app/export` |
| `blaze.viewdefinitionExport.plafondOctets` | → `VIEWDEFINITION_EXPORT_MAX_BYTES` | `8589934592` (8 Gio) |
| `blaze.viewdefinitionExport.conservationSecondes` | → `VIEWDEFINITION_EXPORT_RETENTION_SECONDS` | `86400` (24 h) |
| `blaze.viewdefinitionExport.periodeNettoyageSecondes` | → `VIEWDEFINITION_EXPORT_CLEANUP_PERIOD_SECONDS` | `300` |
| `blaze.viewdefinitionExport.stockage.taille` | taille du PVC d'export (quantité entière, ex. `10Gi`) | `10Gi` |
| `blaze.viewdefinitionExport.stockage.classe` | `StorageClass` du PVC d'export | vide : classe par défaut du cluster |
| `blaze.stockage.taille` | taille du volume de données de Blaze (`/app/data`) | `10Gi` |
| `blaze.stockage.classe` | `StorageClass` de ce volume | vide : classe par défaut du cluster |
| `proxy.plafondInclude` | plafond déclaré de `_include`/`_revinclude`, égal à celui de `nginx/nginx.conf` | `5` |
| `exposition.type` | type du `Service` nginx : `ClusterIP` ou `NodePort` | `ClusterIP` |
| `exposition.nodePort` | port publié sur chaque nœud si `NodePort` | `30088` |
| `reseau.politiques` | rend les `NetworkPolicy` (refus par défaut, nginx seul joint Blaze) | `true` |
| `ressources.blaze.requests.cpu` | CPU demandé par Blaze | `500m` |
| `ressources.blaze.requests.memory` | mémoire demandée par Blaze | `1536Mi` |
| `ressources.blaze.limits.memory` | limite mémoire de Blaze (au-dessus de `blaze.xmx` : cache RocksDB hors tas) | `2Gi` |
| `ressources.nginx.requests.cpu` | CPU demandé par nginx | `50m` |
| `ressources.nginx.requests.memory` | mémoire demandée par nginx | `64Mi` |
| `ressources.nginx.limits.memory` | limite mémoire de nginx | `256Mi` |

---

## English

### Where each setting lives

| Setting | Authoritative file | Read by | Notes |
|---|---|---|---|
| Versions and image digests (Blaze, nginx, workstation tools, chart) | `versions.env` | `build/construire-jar.sh`, `kubernetes/chart/deriver.sh`, `scripts/outils.sh`, `Makefile` (`make image`), `scripts/check_module.py` | single source of versions, never `latest`; checked by `python3 scripts/check_versions.py` |
| Versions served by the chart | `kubernetes/chart/values-versions.yaml`, `Chart.yaml` | Helm | **derived** by `kubernetes/chart/deriver.sh`, never edited nor committed |
| Delivery parameters: namespace, images, public URL, JVM heap, volume, exposure, resources, `NetworkPolicy` | `kubernetes/chart/values.yaml` | Helm | one row per key: "Keys of `kubernetes/chart/values.yaml`"; extra Blaze variables: key `blaze.env` |
| Image served by the chart (module) | `image.blaze.*` of `kubernetes/chart/values.yaml`; `image.blaze.tag` has **no default** | Helm | set at deployment time (French "Déployer" block of `README.md`); `latest` refused; no registry: local image `<repository>:<tag>`; with `image.blaze.registry`: `image.blaze.digest` **mandatory**, image `<registry>/<repository>:<tag>@<digest>`; nginx: always `@sha256:` derived from `versions.env` |
| `$viewdefinition-run` and `$viewdefinition-export` | `blaze.viewdefinitionRun.*`, `blaze.viewdefinitionExport.*` of `kubernetes/chart/values.yaml` | Helm → `VIEWDEFINITION_*`, `DISABLE_VIEWDEFINITION_*` variables; export PVC `<release>-export` | invalid values refused by the chart (`fail`, rc 1); the export PVC survives `helm uninstall` |
| `_include` / `_revinclude` cap | declared: `kubernetes/chart/values.yaml` (`proxy.plafondInclude`); enforced: `nginx/nginx.conf` | nginx | both must match: `python3 scripts/check_gardefou7.py` |
| Script exit codes | `lib/codes.sh` | scripts of `build/`, `scripts/`, `kubernetes/` | single table |
| Module dependencies and aliases | `modules/sql-on-fhir/deps.edn` | Clojure CLI | Blaze jar from `.work/` (`make outils`); `amont/` (Blaze sources, read only) only through the `:test` alias; also pins test library versions (known exception to "`versions.env` only") |
| Upstream suite exclusion lists | `modules/sql-on-fhir/exclusions/` | `test-amont*` targets of the module `Makefile` | — |
| Conformance suite (pinned commit) | `modules/sql-on-fhir/conformance/sql-on-fhir-js/` | `make -C modules/sql-on-fhir conformance` | provenance and licence: ADR 0006 |

Not present yet: `blaze/search-parameters.json` (no added search parameter), validator and
OpenID settings — none is deployed (`docs/architecture.md`, "Architecture cible").

### The module jar in front of Blaze's

The module jar (`build/blaze-addon.jar`, `docs/construction.md`) goes **before** the Blaze
jar on the classpath: `java -cp "/app/addon/blaze-addon.jar:/app/*" blaze.core`
(`build/Dockerfile`). The JVM reads the first resource it finds: the module's `blaze.edn` —
the overlay that declares the module operations and reads the variables below — hides
Blaze's own. No other entry is shared by both jars (check M4,
`python3 scripts/check_module.py`): the module only adds `blaze.addon.*` namespaces. Placed
after, the module jar would leave Blaze's `blaze.edn` in force and its operations would not
be declared.

### Variables read by the image

Domain (a) of F-034: variables read by the overlay, set by the chart, `Dockerfile`
arguments; `79` names, the list given by the command of the French section.

Default: the one of `modules/sql-on-fhir/resources/blaze.edn`, which is authoritative (it
hides Blaze's own, previous section); for directories, the published image already sets
the value shown. The chart sets `BASE_URL`, `JAVA_TOOL_OPTIONS` and the `VIEWDEFINITION_*`
variables from `kubernetes/chart/values.yaml`; any other one goes through the `blaze.env`
key. Without `docker run -e` nor chart, a "no default" variable is absent: the feature it
configures is inactive, unless marked "required".

**`sql-on-fhir` module, build, chart**

| Variable | Role | Default |
|---|---|---|
| `VIEWDEFINITION_EXPORT_DIR` | directory of the `$viewdefinition-export` files, absolute path (created when needed) | no default — **required** while export is on; chart: `blaze.viewdefinitionExport.dossier` |
| `VIEWDEFINITION_EXPORT_MAX_BYTES` | cap on bytes used by exports (`.part` files included), integer ≥ 1 | no default — **required**; chart: `blaze.viewdefinitionExport.plafondOctets` |
| `VIEWDEFINITION_EXPORT_RETENTION_SECONDS` | how long a finished export is kept, in seconds, integer ≥ 1 | no default — **required**; chart: `blaze.viewdefinitionExport.conservationSecondes` |
| `VIEWDEFINITION_EXPORT_CLEANUP_PERIOD_SECONDS` | period of the expired-export cleanup, in seconds, integer ≥ 1 | no default — **required**; chart: `blaze.viewdefinitionExport.periodeNettoyageSecondes` |
| `DISABLE_VIEWDEFINITION_EXPORT` | inverse switch: `true` turns `$viewdefinition-export` off, and the four variables above are no longer required | absent: operation on |
| `VIEWDEFINITION_RUN_MAX_ROWS` | cap on rows returned by `$viewdefinition-run`; beyond, 422 `too-costly` | `10000` |
| `DISABLE_VIEWDEFINITION_RUN` | inverse switch: `true` turns `$viewdefinition-run` off | absent: operation on |
| `BASE_URL` | URL under which clients reach Blaze; the API is under `BASE_URL` + `CONTEXT_PATH` | Blaze: `http://localhost:8080`; chart: `blaze.baseUrl` |
| `JAVA_TOOL_OPTIONS` | JVM options, added to the image's own | empty; chart: `-Xmx` + `blaze.xmx` |
| `BLAZE_VERSION` | build argument (`ARG`): tag of the `samply/blaze` image of the `FROM` | no default — passed by `make image` from `versions.env` |
| `BLAZE_IMAGE_DIGEST` | build argument (`ARG`): digest of the `samply/blaze` image of the `FROM` | no default — passed by `make image` from `versions.env` |

**Blaze: server and API**

| Variable | Role | Default |
|---|---|---|
| `CONTEXT_PATH` | path of the FHIR API | `/fhir` |
| `FHIR_VERSION` | FHIR version served | FHIR R4 (literal value: `blaze.edn`) |
| `ENFORCE_REFERENTIAL_INTEGRITY` | referential integrity on create, update, delete | `true` |
| `ALLOW_MULTIPLE_DELETE` | conditional delete of several resources | `false` |
| `PAGE_STORE_EXPIRE` | lifetime (ISO-8601) of result pages | `PT1H` |
| `DB_SYNC_TIMEOUT` | maximum wait, in ms, of a read for the newest database state; beyond, 503 | `10000` |
| `DB_MAX_IN_FLIGHT_TRANSACTIONS` | submitted transactions not yet indexed; beyond, 503 | `1024` |
| `DB_SEARCH_PARAM_BUNDLE` | file of a bundle of `SearchParameter` **added** to the R4 ones; only indexes later writes | no default (R4 parameters only; `blaze/search-parameters.json` not present yet) |
| `JVM_METRICS_LOGGER_INTERVAL` | interval (ISO-8601) of JVM metrics logging | `PT5M` |
| `FHIR_OPERATION_EVALUATE_MEASURE_THREADS` | threads of `$evaluate-measure` | number of processors |
| `FHIR_OPERATION_EVALUATE_MEASURE_TIMEOUT` | timeout, in ms, of a synchronous `$evaluate-measure` | `3600000` |
| `FHIR_OPERATION_EVALUATE_MEASURE_REPORT_PERSISTENCE` | `true`: the `MeasureReport` of a `POST $evaluate-measure` is stored (201); `false`: returned without storing (200) | `true` |
| `FHIR_OPERATION_GRAPHQL_THREADS` | threads of GraphQL queries | `4` |
| `CQL_EXPR_CACHE_SIZE` | size, in MiB, of the CQL expression cache (on heap) | no default (cache off) |
| `CQL_EXPR_CACHE_REFRESH` | duration (ISO-8601) after which a Bloom filter of the CQL cache is recomputed | `PT24H` |
| `CQL_EXPR_CACHE_THREADS` | parallel Bloom filter computations | `4` |

**Blaze: standalone storage (RocksDB)**

| Variable | Role | Default |
|---|---|---|
| `INDEX_DB_DIR` | directory of the index database | `/app/data/index` (image; overlay: `index`) |
| `INDEX_DB_WAL_DIR` | directory of the index database write-ahead log (WAL) | empty: the database's own |
| `TRANSACTION_DB_DIR` | directory of the transaction log | `/app/data/transaction` (image; overlay: `transaction`) |
| `TRANSACTION_DB_WAL_DIR` | WAL of the transaction log | empty: the database's own |
| `RESOURCE_DB_DIR` | directory of the resource store | `/app/data/resource` (image; overlay: `resource`) |
| `RESOURCE_DB_WAL_DIR` | WAL of the resource store | empty: the database's own |
| `ADMIN_INDEX_DB_DIR` | directory of the admin index database (background jobs) | `/app/data/admin-index` (image; overlay: `admin-index`) |
| `ADMIN_TRANSACTION_DB_DIR` | transaction log of the admin database | `/app/data/admin-transaction` (image; overlay: `admin-transaction`) |
| `DB_BLOCK_CACHE_SIZE` | RocksDB block cache, in MiB, off heap | `128` |
| `DB_RESOURCE_CACHE_SIZE_RATIO` | share of the heap given to the resource cache (0: off, 0.8 at most) | `0.25` |
| `DB_TRANSACTION_CACHE_SIZE` | entries of the transaction cache | `100000` |
| `DB_MAX_BACKGROUND_JOBS` | RocksDB background jobs (compactions) | `4` |
| `DB_MAX_SUBCOMPACTIONS` | split of one compaction into subcompactions | `1` |
| `DB_COMPACTION_READAHEAD_SIZE` | RocksDB compaction readahead, in bytes | no default (RocksDB's value) |
| `DB_RESOURCE_INDEXER_THREADS` | resource indexing threads | `4` |
| `DB_RESOURCE_STORE_KV_THREADS` | resource read and write threads | `4` |

**Blaze: `distributed` variant** — not used here (standalone storage); only read when
`STORAGE=distributed`.

| Variable | Role | Default |
|---|---|---|
| `DB_KAFKA_BOOTSTRAP_SERVERS` | bootstrap servers of the Kafka transaction log | `localhost:9092` |
| `DB_KAFKA_MAX_REQUEST_SIZE` | maximum size, in bytes, of a transaction sent to Kafka | `1048576` |
| `DB_KAFKA_COMPRESSION_TYPE` | compression of the transactions sent | `snappy` |
| `DB_KAFKA_SECURITY_PROTOCOL` | protocol to the brokers (`PLAINTEXT`, `SSL`) | `PLAINTEXT` |
| `DB_KAFKA_SSL_TRUSTSTORE_LOCATION` | TLS trust store | no default |
| `DB_KAFKA_SSL_TRUSTSTORE_PASSWORD` | secret of the trust store | no default |
| `DB_KAFKA_SSL_KEYSTORE_LOCATION` | TLS key store (mutual authentication) | no default |
| `DB_KAFKA_SSL_KEYSTORE_PASSWORD` | secret of the key store | no default |
| `DB_KAFKA_SSL_KEY_PASSWORD` | secret of the private key | no default |
| `DB_CASSANDRA_CONTACT_POINTS` | contact points of the Cassandra resource store | `localhost:9042` |
| `DB_CASSANDRA_USERNAME` | Cassandra user | `cassandra` (Blaze public default) |
| `DB_CASSANDRA_PASSWORD` | secret of the Cassandra user | `cassandra` (Blaze public default) |
| `DB_CASSANDRA_KEY_SPACE` | key space of the `resources` table | `blaze` |
| `DB_CASSANDRA_PUT_CONSISTENCY_LEVEL` | consistency level of writes | `TWO` |
| `DB_CASSANDRA_MAX_CONCURRENT_REQUESTS` | concurrent Cassandra requests | `1024` |
| `DB_CASSANDRA_MAX_REQUEST_QUEUE_SIZE` | queue of Cassandra requests | `100000` |
| `DB_CASSANDRA_REQUEST_TIMEOUT` | timeout, in ms, of a Cassandra request | `2000` |

**Blaze: authentication, validation, terminology** — not deployed by the chart
(`docs/architecture.md`, "Architecture cible").

| Variable | Role | Default |
|---|---|---|
| `OPENID_PROVIDER_URL` | OpenID Connect provider; when set, enables authentication | no default (open API) |
| `OPENID_PROVIDER_ISSUER` | expected `iss` of tokens | no default (not checked) |
| `OPENID_AUDIENCE` | expected `aud` of tokens | no default (not checked) |
| `OPENID_CLIENT_TRUST_STORE` | PKCS #12 trust store towards the provider | no default |
| `OPENID_CLIENT_TRUST_STORE_PASS` | secret of that store | no default |
| `EXTERN_VALIDATOR_URL` | external profile validator, called before persistence; when set, enables validation | no default (no profile validation) |
| `EXTERN_VALIDATOR_MAX_CONCURRENCY` | concurrent requests to the validator | `4` |
| `EXTERN_VALIDATOR_CLIENT_TRUST_STORE` | PKCS #12 trust store towards the validator | no default |
| `EXTERN_VALIDATOR_CLIENT_TRUST_STORE_PASS` | secret of that store | no default |
| `EXTERN_TERMINOLOGY_SERVICE_URL` | external terminology server for CQL | no default (inactive) |
| `EXTERN_TERMINOLOGY_SERVICE_CLIENT_TRUST_STORE` | PKCS #12 trust store towards that server | no default |
| `EXTERN_TERMINOLOGY_SERVICE_CLIENT_TRUST_STORE_PASS` | secret of that store | no default |
| `EXTERN_TERMINOLOGY_SERVICE_OPENID_PROVIDER_URL` | OpenID provider of the terminology server (client credentials flow); when set, enables it | no default |
| `EXTERN_TERMINOLOGY_SERVICE_OPENID_CLIENT_ID` | client id of that flow | no default |
| `EXTERN_TERMINOLOGY_SERVICE_OPENID_CLIENT_SECRET` | client secret of that flow | no default |
| `EXTERN_TERMINOLOGY_SERVICE_OPENID_CLIENT_TRUST_STORE` | PKCS #12 trust store towards that provider | no default |
| `EXTERN_TERMINOLOGY_SERVICE_OPENID_CLIENT_TRUST_STORE_PASS` | secret of that store | no default |
| `TERMINOLOGY_SERVICE_GRAPH_CACHE_SIZE` | concepts held by the graph cache of the internal terminology service | `100000` |
| `SNOMED_CT_RELEASE_PATH` | directory of an official SNOMED CT release (internal service) | no default |

### Keys of `versions.env` read by the build

Domain (b) of F-034. The build script reads the first two; `scripts/outils.sh jar` also
reads the third. Values live in `versions.env`, the single source.

| Key | Role | Default |
|---|---|---|
| `BLAZE_VERSION` | tag of the published Blaze image whose jar the module jar goes in front of; `BLAZE_VERSION` argument of the `Dockerfile` | no default — missing key: `build/construire-jar.sh` exits 3 |
| `BLAZE_IMAGE_DIGEST` | digest of that image (`FROM samply/blaze:<version>@<digest>`) | no default — missing key: `build/construire-jar.sh` exits 3 |
| `BLAZE_JAR_SHA256` | expected digest of the jar extracted by `scripts/outils.sh jar` | no default — mismatch: `scripts/outils.sh jar` refuses |

### Keys of `kubernetes/chart/values.yaml`

Domain (c) of F-034: `35` leaf keys (command in the French section). Default:
`kubernetes/chart/values.yaml`, which is authoritative; override with `-f` or `--set`.

| Key | Role | Default |
|---|---|---|
| `namespace.create` | the chart declares the namespace, with the `restricted` policy; `false` if it already exists outside Helm | `true` |
| `namespace.name` | namespace of every object | `blaze` |
| `image.blaze.registry` | registry host of the module image (`host[:port]`); when set, `image.blaze.digest` becomes mandatory | empty: local image |
| `image.blaze.repository` | repository of the module image | `blaze-platform-module` |
| `image.blaze.tag` | tag of the module image; `latest` refused (`image.blaze.tag : latest interdit`) | no default — the chart refuses to render without it |
| `image.blaze.digest` | `sha256:<64 hex>` digest; mandatory with a registry, refused without one or when malformed | empty |
| `image.blaze.pullPolicy` | pull policy of the module image | `IfNotPresent` |
| `image.nginx.repository` | repository of the (non-root) nginx image; tag and digest derived from `versions.env` | `nginxinc/nginx-unprivileged` |
| `image.nginx.pullPolicy` | pull policy of the nginx image | `IfNotPresent` |
| `securite.runAsUser` | user enforced on pods | empty: the one the cluster enforces |
| `securite.fsGroup` | group owning the volumes | `1001` |
| `blaze.baseUrl` | public URL of the API, without `/fhir` → `BASE_URL` | `http://localhost:8088` |
| `blaze.xmx` | JVM maximum heap → `JAVA_TOOL_OPTIONS=-Xmx…`; the memory limit must stay above it | `1g` |
| `blaze.env` | extra Blaze variables, name → value | empty |
| `blaze.viewdefinitionRun.actif` | `false` sets `DISABLE_VIEWDEFINITION_RUN=true`; boolean | `true` |
| `blaze.viewdefinitionRun.plafondLignes` | → `VIEWDEFINITION_RUN_MAX_ROWS`; integer ≥ 1 | `10000` |
| `blaze.viewdefinitionExport.actif` | `false` sets `DISABLE_VIEWDEFINITION_EXPORT=true`; boolean | `true` |
| `blaze.viewdefinitionExport.dossier` | → `VIEWDEFINITION_EXPORT_DIR`; absolute path outside `/app/data` and `/tmp`, mount point of the export PVC | `/app/export` |
| `blaze.viewdefinitionExport.plafondOctets` | → `VIEWDEFINITION_EXPORT_MAX_BYTES` | `8589934592` (8 GiB) |
| `blaze.viewdefinitionExport.conservationSecondes` | → `VIEWDEFINITION_EXPORT_RETENTION_SECONDS` | `86400` (24 h) |
| `blaze.viewdefinitionExport.periodeNettoyageSecondes` | → `VIEWDEFINITION_EXPORT_CLEANUP_PERIOD_SECONDS` | `300` |
| `blaze.viewdefinitionExport.stockage.taille` | size of the export PVC (whole quantity, e.g. `10Gi`) | `10Gi` |
| `blaze.viewdefinitionExport.stockage.classe` | `StorageClass` of the export PVC | empty: cluster default class |
| `blaze.stockage.taille` | size of the Blaze data volume (`/app/data`) | `10Gi` |
| `blaze.stockage.classe` | `StorageClass` of that volume | empty: cluster default class |
| `proxy.plafondInclude` | declared `_include`/`_revinclude` cap, equal to the one of `nginx/nginx.conf` | `5` |
| `exposition.type` | type of the nginx `Service`: `ClusterIP` or `NodePort` | `ClusterIP` |
| `exposition.nodePort` | port published on every node with `NodePort` | `30088` |
| `reseau.politiques` | renders the `NetworkPolicy` objects (deny by default, only nginx reaches Blaze) | `true` |
| `ressources.blaze.requests.cpu` | CPU requested by Blaze | `500m` |
| `ressources.blaze.requests.memory` | memory requested by Blaze | `1536Mi` |
| `ressources.blaze.limits.memory` | memory limit of Blaze (above `blaze.xmx`: RocksDB cache is off heap) | `2Gi` |
| `ressources.nginx.requests.cpu` | CPU requested by nginx | `50m` |
| `ressources.nginx.requests.memory` | memory requested by nginx | `64Mi` |
| `ressources.nginx.limits.memory` | memory limit of nginx | `256Mi` |
