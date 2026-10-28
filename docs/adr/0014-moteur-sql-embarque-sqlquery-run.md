# ADR 0014 — Moteur SQL embarqué pour `$sqlquery-run` (`SQ-02`)

- **Statut** : acceptée le 2026-10-27 par l'humain — moteur (point 2) sur les mesures de T-112, et les cinq points de la section « Acceptation » (poids, jar publié tel quel, L14, L12, L15), recommandations retenues ; décisions humaines antérieures : 2026-10-26 (points 1, 3, 4, 5) et 2026-10-27 (point 6 ; point 5 étendu à la concurrence, L11, L12)
- **Date** : 2026-10-26
- **Élément de backlog** : F-039
- **Décideur** : humain

## Contexte

F-039 demande qu'un analyste exécute du SQL sur les vues de ses données, sur le serveur.
L'ADR 0005 (amendement du 2026-10-14, Conséquences 3) subordonne la reprise à un besoin écrit
(F-039, 2026-10-26), à la version visée (`SPK-05`) puis à cette ADR. Blaze n'a pas de moteur
SQL : il faut une brique embarquée dans le module (§1.10), sans base relationnelle (§1.1) ni
stockage parallèle (§1.2) — les tables n'existent que le temps d'une requête.

Relevé de l'opération visée (`curl -sL https://build.fhir.org/ig/FHIR/sql-on-fhir-v2/package.tgz | tar -xz`,
`package.json` : `2.1.0-pre`, `20260521085346`, 2026-10-26) :

| # | Source | Valeur relevée |
|---|---|---|
| Q1 | `OperationDefinition-SQLQueryRun.json` | `code` `sqlquery-run`, système/type/instance, `resource` `Library`, `status` `active` ; entrées `_format`, `header`, `queryReference`, `queryResource` (Library SQLQuery), `parameters` (Parameters), `source`, `_limit` ; sortie `return` (Binary, ou Parameters si `_format=fhir`) |
| Q2 | `StructureDefinition-SQLQuery.json` | `Library.type` = `sql-query` ; `relatedArtifact.label` 1..1 = nom de table, `relatedArtifact.resource` 1..1 = canonique de la ViewDefinition ; `content.contentType` `application/sql` ou `;dialect=…` (`CodeSystem-SQLContentTypeCodes.json` : `application/sql;dialect=duckdb` présent) |
| Q3 | page `OperationDefinition-SQLQueryRun.html` (balises retirées) | « Implementations MUST ensure parameter values are safely bound » ; « Simple string interpolation MUST NOT be used » ; sans `_format`, « ndjson » ; erreurs : 400 paramètre manquant, inconnu ou mal typé ; 404 Library ou ViewDefinition introuvable ; 422 erreur d'exécution SQL ou type de colonne non pris en charge |

Poids du candidat C de `SPK-07`, relevé par `architect` le 2026-10-26 :

| Mesure | Commande | Valeur |
|---|---|---|
| dernière version publiée | `curl -sL https://repo1.maven.org/maven2/org/duckdb/duckdb_jdbc/maven-metadata.xml \| grep '<release>'` | `2.0.0.0` (`<lastUpdated>` `20261021140730`) ; inscrite dans `versions.env` par T-112, seule source |
| taille du jar | `stat -c %s` du jar téléchargé | `79362050` octets |
| dépendances déclarées | `grep -c '<dependency>'` du `.pom` | `0` |
| bibliothèques natives embarquées | `unzip -l` | 4 (linux amd64 `70943536`, linux arm64, macos arm64, windows amd64) |
| entrées en double avec le jar de Blaze, hors `META-INF/` | `comm -12` des listes triées (`LC_ALL=C`) contre `.work/blaze-standalone.jar` | `0` sur `98` |
| image du module sans moteur | `docker image inspect blaze-platform-module:dev --format '{{.Size}}'` | `600220116` octets (amd64) |

## Décision

1. **Version** (humain, 2026-10-26) : IG `2.1.0-pre` maintenue ; l'opération est `$sqlquery-run`,
   à côté de `$viewdefinition-run`, au niveau système ; requête en `Library` SQLQuery (Q1, Q2).
2. **Moteur** (humain, 2026-10-27, sur les mesures de T-112 ; option C rouverte le 2026-10-26) : `org.duckdb/duckdb_jdbc`, jar publié tel quel,
   base **en mémoire, une par requête**, alimentée par les lignes des vues calculées par le moteur
   F-005, fermée à la fin de la requête. Lecture seule, sans fichier, sans réseau, sans extension,
   plafonds de durée et de mémoire : chaque levier **mesuré efficace** par T-112 (table ci-dessous,
   2026-10-27), sous réserve de l'ordre des réglages ; métadonnées du moteur encore lisibles (L14,
   acceptée par l'humain le 2026-10-27).
   **Colle écrite, sans analyseur SQL** (précision du 2026-10-28, verdict T-121) : la liaison par nom
   (Q3) est une petite colle par expression régulière (`sql/query.clj`, `named->positional`) qui
   remplace chaque `:nom` par un `?` positionnel en sautant littéraux, identifiants entre guillemets,
   commentaires, `$$…$$` et le cast `::` (test U `query_test.clj`) ; elle ne décide rien de la
   lecture seule, dont le contrôle reste confié à `json_serialize_sql` de DuckDB (ligne « lecture
   seule » de la table ci-dessous).
3. **Interrupteur** (humain) : opération **allumée par défaut**, interrupteur inverse comme
   `DISABLE_VIEWDEFINITION_RUN`.
4. **Authentification** (humain) : aucune exigée. L'opération est servie sans OpenID ; le risque
   est accepté (Conséquences ; ADR 0001, amendement du 2026-10-26).
5. **Plafonds de durée et de mémoire** (humain) : chacun réglé par un paramètre à valeur par
   défaut, documenté comme option de mise en route (`docs/configuration.md`, `kubernetes/chart/values.yaml`),
   à réviser selon l'usage. Défauts maintenus par l'humain le 2026-10-27 sur les mesures de T-118
   (`bench/baselines/T-118/`) : **30 s** (L3 : plafond de protection, à ajuster au volume et à la
   machine ; la vue `observation` de 168 981 lignes prend 121 à 137 s sans plafond et sort en 422
   `timeout` au défaut) ; **256 Mio** hors tas (L5).
   **Concurrence** (humain, 2026-10-27) : nombre de `$sqlquery-run` simultanées plafonné par un
   paramètre à défaut, documenté de même ; défaut **2**, maintenu par l'humain le 2026-10-27 sur
   les mesures de T-118 (L7). Au dépassement : **refus immédiat**, sans file d'attente, avant toute
   ouverture de base. L'IG `2.1.0-pre` ne prescrit aucun code pour ce cas (domaine : les 25
   fichiers de `package.tgz` et la page `OperationDefinition-SQLQueryRun.html`,
   `grep -E '\b(429|503)\b'` et `grep -iE 'concurren|too many requests|throttl'` → 0, relevé
   2026-10-27) ; code retenu : **503**, `OperationOutcome` `issue.code` `throttled`, mesuré (T-118 :
   la N + 1 refusée en 2,5 à 6,3 ms, N = 2 et N = 1, 5 essais sur 5, puis `GET /fhir/metadata` → 200).
   **Limite du pod** (humain, 2026-10-27, sur T-118) : `ressources.blaze.limits.memory` `2Gi` →
   **`2560Mi`**. Calcul, memtables RocksDB comprises :

   | Poste | Mio | Source | État |
   |---|---|---|---|
   | tas (`blaze.xmx: 1g`) | 1024 | `kubernetes/chart/values.yaml` | réservé, résident non mesurable sur l'image (ni `jcmd` ni NMT, T-118) |
   | cache de blocs RocksDB (`DB_BLOCK_CACHE_SIZE`) | 128 | défaut de Blaze, `amont/blaze/docs/deployment/environment-variables.md` l. 99 | non surchargé par le chart |
   | memtables RocksDB, cinq bases, au plus | 1640 | `kubernetes/banc-local.yaml` (commentaire de T-038, `blaze.edn` au tag) | borne lue, non mesurée pleine |
   | réserve JVM hors tas (métaespace, piles, code) | 384 | décision | *décidé, non mesuré* (humain, 2026-10-27) ; T-038 a relevé 240 Mio hors tas JVM sur le banc |
   | moteur, N × `plafondMemoire` | 2 × 256 = 512 | L5, L7 | plafonné, mesuré (L5) |
   | chargement des vues en tas | inclus au tas | L17 | ≈ 201 à 238 Mio mesurés (T-118, 1 essai) |

   Somme des bornes : 1024 + 128 + 1640 + 384 + 512 = **3688 Mio > 2560 Mio** : la limite **ne
   couvre pas** la borne (memtables pleines et deux requêtes saturantes en même temps). Elle est
   décidée sur le **pic mesuré** : `VmHWM` 1 823 Mio à N = 2, deux tris saturants, sous `2Gi`/`1g`
   (T-118, condition B, `B-chart-defauts-m/`), marge 737 Mio sous 2560 Mio ; à 2560 Mio, démarrage
   sur le volume chargé et lancement N2 sans redémarrage, `VmHWM` 1 522 Mio relevé après IT-057 (`bench/baselines/T-114/lancement4/`).
   L'ancien calcul (2048 − 1024 − 128 − 384 = 512 = 2 × 256) omettait les memtables : il ne vaut pas
   preuve que N = 4 tiendrait sous 2560 Mio. Risque restant (L6) : écriture massive pendant des
   requêtes saturantes, *à mesurer*.

6. **Limites et réversibilité** (humain, 2026-10-27, instruction faisant partie de l'ADR) :
   « Il faut bien enregistrer les limites d'utilisation du système. Note où les paramètres
   d'implémentation ont été placés, et tout ce que la solution apporte, afin de pouvoir retirer
   cette solution si elle ne convainc pas. » — sections « Limites d'utilisation » et « Empreinte
   et retrait », tenues par chaque tâche de F-039 qui pose quelque chose.

Noms proposés, acceptés avec l'ADR : `DISABLE_SQLQUERY_RUN`, `SQLQUERY_RUN_TIMEOUT_SECONDS`,
`SQLQUERY_RUN_MAX_MEMORY`, `SQLQUERY_RUN_MAX_CONCURRENT` ; clés
`blaze.sqlqueryRun.{actif,delaiSecondes,plafondMemoire,plafondSimultanees}`. Taille du résultat
(humain, 2026-10-27) : aucun paramètre neuf, `VIEWDEFINITION_RUN_MAX_ROWS` existant
(`blaze.viewdefinitionRun.plafondLignes`, défaut `10000`) s'applique aussi à `$sqlquery-run` (L11).

### Leviers et témoins (mesurés par T-112, 2026-10-27)

Le témoin discrimine : il change quand le levier est retiré. Sorties brutes : `docs/comptes-rendus/T-112.md`.
Table corrigée le 2026-10-27 (T-113) : « sans extension » et le témoin du verrou étaient inexacts.

| Exigence (F-039) | Levier | Témoin, attendu posé / retiré | Obtenu (T-112) posé / retiré |
|---|---|---|---|
| sans fichier | `enable_external_access = false` | `SELECT * FROM read_csv('/etc/passwd')` et `COPY (SELECT 1) TO '/tmp/x.csv'` → refus / exécutés | `Permission Error … file system operations are disabled`, fichier non créé / `rows=44`, fichier créé |
| sans réseau | idem | `SELECT * FROM read_csv('http://127.0.0.1:<port>/x.csv')` → refus, 0 connexion reçue par l'écoute de l'essai / connexion reçue | refus, `0` connexion / `4` connexions |
| sans extension | `enable_external_access = false` (les trois réglages `autoinstall_known_extensions`, `autoload_known_extensions`, `allow_community_extensions = false` sont **sans effet** sur `INSTALL`/`LOAD` explicites, mesuré) | `INSTALL httpfs; LOAD httpfs` → refus / chargée | `Permission Error … Cannot access directory`, `Loading external extensions is disabled` / `EXECUTE-OK` ; les trois réglages seuls : `EXECUTE-OK` posés comme retirés |
| configuration verrouillée | `lock_configuration = true` | `SET memory_limit = '100GB'` dans la requête → refus / accepté (`SET enable_external_access = true` ne discrimine pas : refusé aussi sans verrou) | `the configuration has been locked` / `EXECUTE-OK` |
| ordre des réglages | `SET` dans cet ordre : `temp_directory = ''`, `memory_limit`, `enable_external_access = false`, `lock_configuration = true` | chaque `SET` sans erreur / `temp_directory` après l'accès externe → refus | `OK / OK / OK / OK` / `Permission Error: Modifying the temp_directory has been disabled` |
| lecture seule | garde par la brique : `SELECT json_serialize_sql(?) ->> 'error'` → `true` ⇒ refus, avant exécution | `CREATE TABLE`, `INSERT`, `DROP`, lot `SELECT 1; DROP …` → refus / exécutés | 19 instructions refusées, `SELECT`, `WITH`, `UNION`, `SELECT 1; SELECT 2` admis / exécutées, table supprimée |
| plafond mémoire | `memory_limit`, `temp_directory = ''` (pas de débordement disque) | requête de tri saturante → erreur puis requête suivante servie / mémoire du pod au-delà | `Out of Memory Error` en `694 ms`, `VmHWM` `594 MiB` / terminée en `21026 ms`, `VmHWM` `2017 MiB` (conteneur 2 Gio) |
| plafond durée (phase SQL ; le chargement des vues est borné par l'échéance du module, L3) | `Statement.setQueryTimeout` (ou `Statement.cancel`) | requête longue → interrompue avant défaut + marge / terminée | `INTERRUPT Error` à `2007 ms` pour 2 s / terminée en `27434 ms` |
| paramètre lié | `PreparedStatement`, `?` positionnels ; liaison par nom (Q3) à la charge du module | valeur `'); DROP TABLE patient; --` → traitée comme valeur / effet | `rows=0`, table présente / table supprimée |

### Poids (mesurés par T-112, relevé ci-dessous), comme en `SPK-07`

Image et jar du module avec et sans moteur ; démarrage (`/fhir/metadata` 200 après lancement) avec
et sans ; mémoire hors tas (RSS du processus moins tas) au repos et sous la requête de référence ;
chargement de la bibliothèque native sous les quatre exigences `restricted` du chart
(`readOnlyRootFilesystem`, utilisateur non root, `capabilities: drop ALL`, sans élévation),
`/tmp` en `emptyDir`. Piège : un `tmpfs` Docker est `noexec` par défaut, un `emptyDir` ne l'est
pas — l'essai Docker emploie `--tmpfs /tmp:exec` ou le dit.

**Condition d'escalade** : si une mesure contredit le point 2 (un levier sans effet, la
bibliothèque native qui ne se charge pas sous `restricted`, un coût jugé excessif), `architect`
escalade à l'humain avec les relevés ; aucun autre moteur n'est retenu sans lui.

### Relevé T-112 (2026-10-27, `clojure-engineer`, jar `duckdb_jdbc` `2.0.0.0`)

Détail, commandes et sorties brutes : `docs/comptes-rendus/T-112.md`. Accepté par `architect` (T-112), puis par l'humain (2026-10-27).

| Mesure | Sans moteur | Avec moteur |
|---|---|---|
| image du module (`docker image inspect … --format '{{.Size}}'`) | `600220116` octets | `679582166` octets (`+79362050` = le jar, `+13,2 %`) |
| jar ajouté (`stat -c %s`) ; jar du module | jar du module `65876` | `79362050` (sha256 `5170daa9…7186`) |
| `/fhir/metadata` 200 après lancement, 3 essais chacun (`restricted`, 2 Gio, tas 1 Gio) | `11679`, `11716`, `11690` ms | `11691`, `11690`, `11441` ms (jar sur le classpath, moteur non chargé) |
| RSS − tas réservé au repos (3 essais chacun) | `657`, `642`, `658` MiB | `689`, `662`, `670` MiB |
| bibliothèque native sous `restricted` + `/tmp` `exec` | — | chargée, requête rendue |
| idem `/tmp` `noexec` (défaut `--tmpfs`) / sans `/tmp` | — | `UnsatisfiedLinkError … failed to map segment from shared object` / `Read-only file system` |
| bibliothèques natives du jar (`unzip -Z`, décompressées) | — | linux amd64 `70943536`, linux arm64 `60931992`, macos `63426864`, windows `41084928` octets |
| hors tas, chargement natif + 1ʳᵉ connexion / requête de référence (jointure 2 M × 2 M, 1000 lignes, `171 ms`) | — | `+14` MiB / `+129` MiB de RSS |
| deux saturations simultanées à 256 MB (L7) | — | 2 × `Out of Memory Error` propres, pic `VmHWM` `1719 MiB` (< 2048), Blaze répond |

**Leviers** : table « Leviers et témoins » ci-dessus, corrigée sur ce relevé (2026-10-27).
**Ensemble posé, dans l'ordre** : `read_csv`, `read_text`, `read_parquet('http://…')`, `glob`, `INSTALL`, `LOAD`, `SET`,
`ATTACH`, `COPY … TO`, `duckdb_extensions()`, `duckdb_secrets()` → refusés, aucun fichier créé ; restent lisibles
`current_setting('home_directory')`, `duckdb_settings()`, `information_schema.tables` (L14).

## Limites d'utilisation

État : **décidée** (par qui), **mesurée** (tâche, relevé reporté ici), **à mesurer** (tâche), ou
**non décidée** (à trancher par l'humain). Mesurées au 2026-10-27 (T-112, levier ou plateforme) : L1, L5, L12, L14, L15 ; de bout en bout (N2 IT-049…IT-061, `architect`, `bench/baselines/T-116/`, 2ᵉ lancement `bench/baselines/T-116/iter2/`) : L1, L3 (limite de dépassement mesurée, acceptée par l'humain le 2026-10-27), L5, L7, L8, L10, L11, L15 ; performance (T-118, `bench/baselines/T-118/`) : L3, L5, L6, L7, L9, L17 ; conformité (T-119) : L18.

| # | Limite | Paramètre, défaut, où le régler | État |
|---|---|---|---|
| L1 | `SELECT` seul ; toute autre instruction refusée (4xx, `OperationOutcome`) | — | décidée (point 2) ; levier mesuré T-112 (garde `json_serialize_sql`, 19 refus sur 19) ; livrée en U (T-116, acceptée le 2026-10-27 : `check-select-test` de `sql/engine_test.clj` : 15 refus avant exécution, 14 non-`SELECT` et une syntaxe fausse ; témoin garde retirée rouge ; `make -C modules/sql-on-fhir test` → `243 tests, 2006 assertions, 0 failures.`, relevé `architect` 2026-10-27) ; de bout en bout mesurée (N2 `architect` 2026-10-27, banc kind, `bench/baselines/T-116/`) : IT-055 16 refus 4xx, 0 effet, 0 fichier ; IT-056 4 refus, 0 connexion sortante |
| L2 | Dialecte DuckDB (`application/sql;dialect=duckdb`, Q2) ; requête d'un autre dialecte → 422 possible (Q3) | — | décidée (point 1) ; non éprouvée par T-119 (jeu de refus en dialecte DuckDB seul) : à mesurer, aucune tâche |
| L3 | Durée d'une requête plafonnée, chargement des vues compris (échéance unique) | `SQLQUERY_RUN_TIMEOUT_SECONDS`, défaut 30 s ; `blaze.sqlqueryRun.delaiSecondes` (`kubernetes/chart/values.yaml`), `docs/configuration.md` | décidée (point 5 ; humain, 2026-10-27 : le plafond couvre toute la requête, dépassement → 422 `timeout`) ; livrée en U (T-116 itération 2, `sqlquery-test/plafond-de-duree-chargement`, `plafond-de-duree-total`) ; **mesurée** (N2 `architect` 2026-10-27, 2ᵉ lancement, banc kind, image `c4a45b92b537` du commit `a9ea124`, `bench/baselines/T-116/iter2/`) : phase moteur, plafond 2 s : `2051`, `2049`, `2052` ms (IT-057 E01, 3/3) ; chargement de la vue `observation` (168 981 lignes) : plafond 30 s → `422` `timeout` à `30033` ms (sonde, IT-049 E02 au défaut) ; plafond 2 s → `422` `timeout` à `2032`, `2033`, `2035` ms, `metadata` 200, `restartCount` 0 (sonde-architect-delai2.txt ; IT-057 E02, défaut du cas, ne l'a pas mesuré) ; **limite mesurée** — plafond 70 s, même vue : `200` en `74382`, `72622`, `73900` ms (3/3, dépassement de 2,6 à 4,4 s, réponse servie au lieu de 422 ; sonde-architect-limite-insert.txt) ; cause lue dans le code, non isolée par la mesure : l'échéance n'est vérifiée qu'entre deux ressources lues (`operation/sqlquery.clj`, `before-deadline`), pas pendant le calcul des lignes, le `CREATE` ni l'`INSERT` (`sql/tables.clj`, `load-tables!`), et l'exécution reçoit 1 s au moins (`remaining-seconds`) ; **limite acceptée** (humain, 2026-10-27, amendement du même jour) : plafond **de protection, non de précision** ; dépassement de D ≤ 5 s servi en `200` admis (tolérance du critère « Plafond de durée D » de F-039 ; mesuré 4,4 s au plus sur ce lot), au-delà du lot non garanti ; critère « Plafond de durée D » **mesuré** à D = 5 s (3ᵉ lancement N2, `architect`, 2026-10-27, `bench/baselines/T-114/lancement3/`) : vue `observation` → `422` `timeout` en 5036, 5034, 5033 ms (sonde) et 5043, 5037, 5038 ms (IT-057 E02), `metadata` 200, `restartCount` 0, toute réponse ≤ D + 5 s (max 5054 ms) ; aucun changement de code ; valeur **à ajuster par l'humain** au volume de données et à la machine d'exécution — se règle par `blaze.sqlqueryRun.delaiSecondes` (→ `SQLQUERY_RUN_TIMEOUT_SECONDS`), sans reconstruction ; consigne portée à côté du paramètre dans `kubernetes/chart/values.yaml` et `docs/configuration.md` (T-117, reprise T-122) et dans `README.md` (T-120) ; au-delà de 600 s, voir L4 ; T-118 |
| L4 | L3 réglé au-delà de 600 s : nginx coupe la requête avant (`proxy_read_timeout 600s`, `nginx/nginx.conf` l. 22, relevé 2026-10-27) | `nginx/nginx.conf` | effet à mesurer ; aucune tâche ne relève L3 > 600 s |
| L5 | Mémoire du moteur plafonnée, sans débordement disque (`temp_directory = ''`) | `SQLQUERY_RUN_MAX_MEMORY`, défaut 256 Mio hors tas ; `blaze.sqlqueryRun.plafondMemoire`, `docs/configuration.md` | décidée (point 5) ; levier mesuré T-112 (`VmHWM` `594 MiB` contre `2017 MiB`, conteneur 2 Gio) ; interruption mesurée (N2 `architect` 2026-10-27, banc kind, `bench/baselines/T-116/`, IT-058) : 256MiB `987`, `829`, `826` ms ; 64MiB `212`, `209`, `210` ms, 3/3, `restartCount` inchangé ; **mesurée T-118** (`bench/baselines/T-118/`, 5 essais sur 5) : 256 Mio → 422 `too-costly` en 0,84 à 0,87 s, 64 Mio → en 0,19 à 0,20 s, tri de 1 000 lignes servi ; défaut maintenu (humain, 2026-10-27) |
| L6 | Mémoire hors tas prise sous la limite du pod, partagée avec le tas, le cache et les memtables RocksDB | `ressources.blaze.limits.memory`, `2560Mi` (`kubernetes/chart/values.yaml`) | **décidée** `2Gi` → `2560Mi` (humain, 2026-10-27, sur T-118 : pic `VmHWM` 1 823 Mio à N = 2 sous `2Gi`/`1g`, marge 225 Mio, à 2560 Mio 737 Mio) ; **mesurée** à 2560 Mio (T-114, 4ᵉ lancement N2, `architect`, 2026-10-27, `bench/baselines/T-114/lancement4/`) : démarrage sur le volume chargé (Patient 6001, Observation 168 981), IT-049…IT-061 et IT-057 à D = 5 s, `restartCount` 0, `VmHWM` 1 522 Mio relevé après IT-057 ; **ne couvre pas la borne** memtables pleines comprises (3688 Mio, point 5) : écriture massive simultanée de requêtes saturantes et chargement complet du jeu à 2560 Mio *à mesurer*, aucune tâche (réingestion = destruction du volume, non autorisée) |
| L7 | Concurrence plafonnée : au plus N requêtes simultanées (N bases, N × L5 sous L6) ; la N + 1 refusée sur-le-champ, 503 `throttled` (code choisi, l'IG ne prescrit rien) | `SQLQUERY_RUN_MAX_CONCURRENT`, défaut 2 ; `blaze.sqlqueryRun.plafondSimultanees`, `docs/configuration.md` | décidée (humain, 2026-10-27, point 5) ; mesurée (N2 `architect` 2026-10-27, banc kind, `bench/baselines/T-116/`, IT-059) : N = 2 et N = 1, 3/3, la N + 1 → 503 `throttled` en 12 à 14 ms ; **sous charge, mesurée T-118** (5 essais sur 5) : N requêtes de 11 à 12 s vivantes, la N + 1 → 503 en 2,5 à 6,3 ms, les N servies 200 ; N = 2 à 256 Mio : pic 1 823 Mio ; défaut 2 maintenu (humain, 2026-10-27) ; N > 2 non mesuré |
| L8 | Aucune authentification : tout client qui joint le serveur lit toutes les données atteintes par les vues | interrupteur `DISABLE_SQLQUERY_RUN`, `blaze.sqlqueryRun.actif` (défaut : allumée) ; `NetworkPolicy` (`reseau.politiques`) | décidée (point 4) ; interrupteur mesuré (N2 `architect` 2026-10-27, banc kind, `bench/baselines/T-116/`, IT-060) : coupé → 404, 0 déclaration ; défaut → 200, 1 déclaration |
| L9 | Une base par requête : vues recalculées à chaque appel sur les données courantes ; aucune persistance, aucune vue matérialisée, aucun index | — | décidée (point 2) ; coût mesuré T-118, sans seuil (critère « Latence et mémoire », `bench/baselines/T-118/`, instrument `curl` `%{time_total}` et `VmRSS`/`VmHWM` de `/proc/1/status` à 0,1 s) : vue `patient` (6 001 lignes) 1,9 à 2,9 s ; vue `observation` (168 981 lignes) 121 à 137 s sans plafond ; refus 503 2 à 6 ms |
| L10 | Tables = vues désignées par `relatedArtifact` de la Library SQLQuery (Q2) ; vue introuvable → 404 (Q3) | — | décidée (point 1) ; mesurée T-115 : base neuve + tables chargées → `information_schema.tables` et `duckdb_tables()` ne rendent que ces tables (`[memory main patient]`), `duckdb_databases()` → 3 (`memory`, `system`, `temp`) ; vue introuvable → 404 mesurée (N2 `architect` 2026-10-27, banc kind, `bench/baselines/T-116/`, IT-054 E02, 2 refus sur 2) |
| L11 | Taille du résultat : entrée `_limit` (Q1) ; plafond de lignes partagé avec `$viewdefinition-run`, aucun paramètre neuf ; comportement au-delà à aligner sur `$viewdefinition-run` (422 `too-costly`), *décidé, non mesuré* | `VIEWDEFINITION_RUN_MAX_ROWS`, défaut `10000` ; `blaze.viewdefinitionRun.plafondLignes` (`kubernetes/chart/values.yaml` l. 47, relevé 2026-10-27), `docs/configuration.md` | décidée (humain, 2026-10-27) ; livrée en U (T-116 : au-delà, 422 `too-costly`, *décidé, non mesuré* sur le banc) ; mesurée (N2 `architect` 2026-10-27, banc kind, `bench/baselines/T-116/`, IT-061) : n = 10 000 et n = 10, n → 200, n + 1 → 422 `too-costly`, `_limit` → 200 |
| L12 | Plateformes : bibliothèque native linux amd64 et arm64 dans le jar (relevé `unzip -l`, 2026-10-26) ; image du module mesurée en amd64 seulement | — | amd64 mesurée T-112 (chargée sous `restricted`, `/tmp` `exec`, `679582166` octets) ; décidée (humain, 2026-10-27) : livrée en amd64 seule ; arm64 à mesurer, aucune tâche |
| L13 | Plantage natif = arrêt du processus Blaze, donc du pod et du serveur entier (§1.1, un seul nœud) | — | décidée (Conséquences), non mesurée |
| L14 | Métadonnées lisibles par toute requête malgré l'ensemble posé : `current_setting('home_directory')`, `duckdb_settings()` (dossier personnel du processus), `information_schema.tables` | — | mesurée T-112 ; décidée (humain, 2026-10-27) : acceptée et consignée, aucun filtre écrit (L8 expose déjà les données atteintes par les vues) |
| L15 | Bibliothèque native (`70943536` octets) extraite dans `java.io.tmpdir` à chaque démarrage : `/tmp` `noexec` → `UnsatisfiedLinkError`, sans `/tmp` → `Read-only file system` | `/tmp` `emptyDir` préexistant (`kubernetes/chart/templates/blaze.yaml` l. 97, 100) | mesurée T-112 en Docker ; risque accepté (humain, 2026-10-27) ; `emptyDir` d'un nœud : **mesurée** (N2 `architect` 2026-10-27, banc kind, `bench/baselines/T-116/`) — IT-055 et 12 autres cas servis en `200` sous `restricted`, `/tmp` `emptyDir` : bibliothèque native chargée |
| L16 | Décimal FHIR rendu en `DOUBLE` (pas `DECIMAL`) : précision au-delà de 15 à 17 chiffres significatifs perdue ; aucune vue de référence n'a de colonne décimale | `modules/sql-on-fhir/src/blaze/addon/sql_on_fhir/sql/tables.clj` (`sql-type`) | décidée (T-115, appelant, 2026-10-27) : acceptée, non éprouvée ; aucune tâche |
| L17 | Lignes des vues chargées **en tas** avant la base DuckDB (`sql/tables.clj`) : mémoire hors `SQLQUERY_RUN_MAX_MEMORY`, bornée seulement par L3 (durée) et L7 (au plus N chargements) ; `VIEWDEFINITION_RUN_MAX_ROWS` ne borne que le résultat | — (aucun plafond de chargement) | décidée (humain, 2026-10-27, sur T-118 : aucun plafond de chargement pour l'instant, N = 2 le borne) ; mesurée T-118, 1 essai chacune, reproductibilité non établie : vue `observation` seule +201 Mio de `VmRSS`, deux simultanées +238 Mio, `restartCount` 0 |
| L18 | Écarts à l'IG `2.1.0-pre`, non servis : `_format=fhir` (sortie `Parameters`, Q1) → 400 `not-supported` ; `_format=parquet` → 400 `not-supported` ; entrée `source` → 400 `not-supported` ; portée type `POST /fhir/Library/$sqlquery-run` → 405 ; portée instance `POST /fhir/Library/<id>/$sqlquery-run` → 404 | — | décidée (humain, 2026-10-27 : consignés, non servis ; `docs/manuel.md` §4) ; mesurée T-119, rejouée par `architect` le 2026-10-27 (`bench/baselines/T-114/lancement4/sondes-architect-T-119.txt` : S5, S4, S11 → 400, S10 → 404, T1 → 405) |

## Empreinte et retrait

**Registre vivant** : chaque tâche de F-039 y inscrit, dans la même livraison, tout ce qu'elle pose
(chemin exact, ou dossier terminé par `/` qui couvre tout son contenu) et passe la ligne de
`prévu` à `livré`. Une ligne `livré` sans le chemin exact est un écart.

| Emplacement | Nature de l'ajout | Tâche | État |
|---|---|---|---|
| `versions.env` | `DUCKDB_JDBC_VERSION` (`2.0.0.0`, relevé 2026-10-27) ; `DUCKDB_JDBC_SHA256` (empreinte sha256 du jar Maven, trois sources concordantes, relevé 2026-10-27) ; `SQL_ON_FHIR_IG_VERSION`, `SQL_ON_FHIR_IG_SHA256` (IG visée et empreinte du paquet relevée par T-119, lues par aucun script ; décision humaine du 2026-10-28, commit `f93edf1`) | T-112, T-116, T-119 | livré (version, T-112 ; empreinte, T-116 ; IG, `f93edf1`) |
| `modules/sql-on-fhir/deps.edn` | dépendance `org.duckdb/duckdb_jdbc` `2.0.0.0` en `:deps` (même valeur que `DUCKDB_JDBC_VERSION` de `versions.env`, que `deps.edn` ne peut pas lire : à tenir ensemble) ; version écrite dans `deps.edn` et dans les commentaires qui la citent : maintenue, sous contrôle de `scripts/check_versions.py` (décision humaine par défaut, 2026-10-28, verdict T-121) | T-115 | livré |
| `modules/sql-on-fhir/src/blaze/addon/sql_on_fhir/sql/tables.clj`, `modules/sql-on-fhir/src/blaze/addon/sql_on_fhir/sql/tables_spec.clj` | vues en tables d'une base DuckDB en mémoire (`SQ-01`) | T-115 | livré |
| `modules/sql-on-fhir/src/blaze/addon/sql_on_fhir/operation/sqlquery.clj`, `modules/sql-on-fhir/src/blaze/addon/sql_on_fhir/operation/sqlquery_spec.clj`, `modules/sql-on-fhir/src/blaze/addon/sql_on_fhir/operation/sqlquery_params.clj`, `modules/sql-on-fhir/src/blaze/addon/sql_on_fhir/operation/sqlquery_params_spec.clj`, `modules/sql-on-fhir/src/blaze/addon/sql_on_fhir/sql/engine.clj`, `modules/sql-on-fhir/src/blaze/addon/sql_on_fhir/sql/engine_spec.clj`, `modules/sql-on-fhir/src/blaze/addon/sql_on_fhir/sql/query.clj`, `modules/sql-on-fhir/src/blaze/addon/sql_on_fhir/sql/query_spec.clj` | opération `$sqlquery-run`, gardes, plafonds, interrupteur (T-116) | T-116 | livré |
| `modules/sql-on-fhir/src/blaze/addon/sql_on_fhir/operation/sortie.clj`, `modules/sql-on-fhir/src/blaze/addon/sql_on_fhir/view/run_spec.clj` | **modifiés** : l'enveloppe de sortie reconnaît la route `/$sqlquery-run` ; la spec de `view.run/run` accepte un réductible (retrait : revenir à la ligne `coll?`) | T-116 | livré |
| `modules/sql-on-fhir/test/blaze/addon/sql_on_fhir/sql/tables_test.clj` | tests U et témoins rouges de `SQ-01` | T-115 | livré |
| `modules/sql-on-fhir/test/blaze/addon/sql_on_fhir/operation/sqlquery_test.clj`, `modules/sql-on-fhir/test/blaze/addon/sql_on_fhir/operation/sqlquery_params_test.clj`, `modules/sql-on-fhir/test/blaze/addon/sql_on_fhir/sql/engine_test.clj`, `modules/sql-on-fhir/test/blaze/addon/sql_on_fhir/sql/query_test.clj` | tests U et témoins rouges de l'opération (T-116) | T-116 | livré |
| `modules/sql-on-fhir/test/blaze/addon/sql_on_fhir/operation/sortie_test.clj`, `modules/sql-on-fhir/test/blaze/addon/sql_on_fhir/overlay_test.clj` | **modifiés** : route `$sqlquery-run`, interrupteur `DISABLE_SQLQUERY_RUN`, variables typées (retrait : retirer les cas ajoutés, voir `docs/couverture.md`) | T-116 | livré |
| `modules/sql-on-fhir/resources/blaze.edn`, `modules/sql-on-fhir/generer-overlay.py` | overlay régénéré (`make -C modules/sql-on-fhir overlay`) : fonctionnalité `:sqlquery-run` ajoutée au générateur | T-116 | livré |
| `build/Dockerfile`, `build/construire-jar.sh`, `Makefile`, `docs/construction.md` | décidé (appelant, 2026-10-27) sur le constat de T-115 (le moteur n'est pas dans `blaze-addon.jar`) : `build/Dockerfile` pose le jar Maven `duckdb_jdbc` par `ADD --chmod=0644 --checksum=sha256:…` (empreinte de `versions.env` vérifiée, version par `--build-arg`) et le met sur le `-cp` du `CMD` ; `Makefile` (`image`) passe `DUCKDB_JDBC_VERSION` et `DUCKDB_JDBC_SHA256` ; `build/construire-jar.sh` **inchangé** (n'archive que `src` et `resources`) ; `docs/construction.md` (FR et EN) : l'image télécharge le jar de Maven Central à la construction | T-115 (constat), T-116 | livré (T-116) |
| `scripts/check_versions.py` | contrôle `DUCKDB_JDBC_VERSION` (`versions.env`) = version de `org.duckdb/duckdb_jdbc` dans `modules/sql-on-fhir/deps.edn`, avec témoin rouge | T-116 | livré |
| `DISABLE_SQLQUERY_RUN`, `SQLQUERY_RUN_TIMEOUT_SECONDS`, `SQLQUERY_RUN_MAX_MEMORY`, `SQLQUERY_RUN_MAX_CONCURRENT` | variables lues par le module (T-116), posées par le chart (T-117) ; `VIEWDEFINITION_RUN_MAX_ROWS` préexistante (F-006), lue aussi par `$sqlquery-run` (T-116), non posée par F-039 | T-116, T-117 | lues par le module : livré (T-116, `generer-overlay.py`) ; posées par le chart : livré (T-117) |
| `kubernetes/chart/values.yaml` | clés `blaze.sqlqueryRun.{actif,delaiSecondes,plafondMemoire,plafondSimultanees}` (T-117) ; commentaire `delaiSecondes` : limite L3 et consigne d'ajustement (T-122) ; `ressources.blaze.limits.memory` si révisée (T-118, humain ; départ `2Gi`) ; commentaire `plafondSimultanees` : somme des bornes memtables comprises (commit `f93edf1`) | T-117, T-118, T-122 | clés : livré (T-117) ; commentaire L3 : livré (T-122) ; limite mémoire : livré (T-118, décision humaine 2026-10-27 : `2Gi` → `2560Mi`) ; commentaire `plafondSimultanees` : livré (`f93edf1`) |
| `kubernetes/chart/templates/blaze.yaml` | variables et `fail` par clé (bloc `$sq`) ; `/tmp` en `emptyDir` **préexistant**, non posé par F-039 | T-117 | livré |
| `scripts/banc.sh` | **modifié**, commentaire d'usage seul : exemples `--set blaze.sqlqueryRun.delaiSecondes=5` et `plafondSimultanees=1` (T-117, reprise du 2026-10-27 ; retrait : retirer ces deux exemples) | T-117 | livré |
| `nginx/nginx.conf` | aucun changement prévu (L4) | — | prévu : aucun |
| `CapabilityStatement` (`/fhir/metadata`) | `rest[0].operation` `sqlquery-run`, produit par le module à l'exécution | T-116 | livré (relevé sur l'image d'essai : `["graphql","totals","versions","viewdefinition-run","sqlquery-run"]`) |
| `tests/integration/cas/`, `tests/integration/catalogue.tsv`, `docs/tests.md`, `tests/integration/sqr.sh`, `tests/integration/sqr_calcul.py`, `tests/integration/donnees/sqlquery-run/` | cas N2 IT-049…IT-061 (13 cas, 31 épreuves), aides `sqr.sh` et calcul hors serveur `sqr_calcul.py`, vues de référence `sq-*.json`, inventaire (T-114, 2026-10-27) ; état (`architect`, lancement N2 du 2026-10-27) | T-114 | livré |
| `bench/baselines/T-116/` | sorties brutes du lancement N2 IT-049…IT-061 et contexte (`architect`, 2026-10-27) ; 2ᵉ lancement et sondes de L3 sous `iter2/` | T-114, T-116, T-117 (verdict) | livré |
| `bench/baselines/T-114/lancement3/` | sorties brutes du 3ᵉ lancement N2 IT-049…IT-061, sonde de L3 à D = 5 s et contexte (`architect`, 2026-10-27) | T-114, T-117, T-122 (verdict) | livré |
| `bench/baselines/T-114/lancement4/` | 4ᵉ lancement N2 : IT-049…IT-061 au défaut et IT-057 à D = 5 s sous la limite `2560Mi`, état du banc à chaque redéploiement, sondes de T-119 rejouées, `make image` au commit `2cc1932` (`architect`, 2026-10-27) | T-114, T-116, T-118, T-119 (verdict) | livré |
| `bench/baselines/T-118/` | relevés de performance (plafonds de durée, de mémoire et de concurrence, chargement des vues en tas, limite du pod, conditions banc A et B), script `mesure.sh` (T-118, 2026-10-27) | T-118 | livré (T-118) |
| `docs/impacts-blaze.md` | IB-09 (doublons du moteur : 0 sur 98, T-115) ; nouvelles lignes d'extension | T-115, T-116 | IB-09 livré (T-115) ; IB-16 livré (T-116) |
| `docs/couverture.md` | lignes `SQ-01`…`SQ-03` | T-113, T-115, T-116 | livré (T-116 : ligne `SQ-03`) (ligne 88, ADR `acceptée`, 2026-10-27 ; § 5 bis, `SQ-01` réalisé, T-115) ; à tenir |
| `docs/configuration.md`, `docs/manuel.md` | paramètres L3, L5, L7, L8, L11 (lignes FR et EN, T-117 ; limite L3 et consigne d'ajustement, T-122 ; reprise éventuelle en tableau unique, T-120) ; commande et écarts ; `docs/manuel.md` §5 : ligne d'absence (T-113) | T-113, T-117, T-120, T-122 | §5 livré (T-113 ; ligne tenue au verdict de T-116, puis réécrite en absence de la documentation utilisateur au 3ᵉ lancement N2, 2026-10-27) ; `docs/configuration.md` paramètres : livré (T-117) ; limite L3 et consigne d'ajustement : livré (T-122) ; tableau unique « Options de mise en route de `$sqlquery-run` » (FR et EN), clés `DUCKDB_JDBC_*` et `SQL_ON_FHIR_IG_*` de `versions.env`, `docs/manuel.md` §1 à §5 (commande N2, écarts de T-119, limites, ligne d'absence retirée) : livré (T-120) |
| `README.md` | sections FR et EN « `$sqlquery-run` » : usage, limites, paramétrage, coupure, renvoi au retrait (exigence humaine du 2026-10-27, T-120) ; étape 5 « `make image` » (FR et EN) : jar du moteur téléchargé, réseau requis (`architect`, 2026-10-27, revue de caducité de T-116) | T-116 (verdict), T-120 | étape 5 : livré ; sections : livré (T-120) |
| `docs/essais-manuels.md` | §11 « Requêtes SQL sur les vues (`$sqlquery-run`) » (`testeur`, commit `80262f5` ; retrait : retirer le §11) | T-120 | livré |
| `docs/banc.md` | description de l'image (jar du moteur) ; ligne `$sqlquery-run` du tableau des réglages (`architect`, 2026-10-27, revue de caducité de T-116, T-117 ; tenue par T-120) ; ligne du banc local : limite et tas différents du chart, laissés (décision humaine du 2026-10-28, T-120) | T-116, T-117 (verdict), T-120 | livré |
| `docs/architecture.md` | composant et renvoi à cette ADR | T-113, T-116 (verdict), T-120 | livré (ligne 86, ADR `acceptée`, 2026-10-27 ; image et état de l'opération, 2026-10-27, verdict T-116 ; couche « Opération `$sqlquery-run` », T-120) ; à tenir |
| `docs/Architecture moteur SQL on FHIR pour Blaze.md` | états `SQ-01`…`SQ-03`, jalon `J5`, composant « Moteur SQL embarqué », phase 6 (lignes préexistantes à F-039) | T-120 | livré (T-120) |
| `docs/backlog/F-039.md`, `docs/backlog/README.md` | élément, archivé le 2026-10-28 sous `docs/backlog/archive/` ; avertissement « À reconsidérer » (l. 37) | `functional-analyst`, `architect` | livré |
| `docs/plan/` | fiches T-111…T-122 (retirées le 2026-10-28, `git show f529328:docs/plan/T-1xx.md`), ordre F-039 de `README.md` ; autorisation du banc consignée dans T-114, T-116, T-118 (2026-10-27) | `architect` | livré |
| `docs/adr/0014-moteur-sql-embarque-sqlquery-run.md`, `docs/adr/0001-architecture-cible-blaze.md`, `docs/adr/0005-specification-visee-et-viewdefinition-en-r4.md` | cette ADR ; amendements du 2026-10-26 | `architect` | livré |
| `docs/comptes-rendus/`, `.claude/metrics/`, `docs/plan-archive.md`, `docs/backlog-archive.md`, `docs/backlog/archive/` | journaux et archives | chaque tâche, `architect` | `docs/comptes-rendus/` : livré (T-112, T-114…T-119, T-122 ; T-120 n'en pose pas, compte rendu rendu à l'appelant) ; `.claude/metrics/` : livré (entrées de verdict) ; archives : prévu (clôture de F-039) |
| image `blaze-platform-module:dev` | `docker image inspect … --format '{{.Size}}'` → `600220116` octets (relevé 2026-10-27, image du 2026-10-17, `sha256:435dfd52…`) ; valeur avec moteur : `679582166` octets (T-112, 2026-10-27, image d'essai `blaze-platform-module:t112` retirée par ce nom, aucun autre objet posé ; `.work/t112/` ignoré par git) | T-112, T-116 | livré (T-112) ; T-116 : image d'essai `blaze-platform-module:t116` construite avec le jar du module complet et le moteur (`ADD --checksum`), `679596615` octets (`+79376499` sur `600220116`), retirée par ce nom ; `:dev` intacte ; `make image` au commit `aa3bcfb` (`architect`, 2026-10-27) : `blaze-platform-module:dev` `sha256:e5d8eaf1…`, `679596668` octets (`+79376552` sur `600220116`), couche `ADD` `79.4MB` ; `make image` au commit `a9ea124` (`architect`, 2026-10-27, 2ᵉ lancement N2) : `sha256:c4a45b92…`, `679597117` octets ; `make image` au commit `2cc1932` (`architect`, 2026-10-27, 4ᵉ lancement N2, commentaire de `blaze.edn` seul changé) : `sha256:106779a4…`, `679597186` octets |

**Registre = diff** (T-121) : bloc enregistré dans un fichier et lancé par `bash <fichier>` depuis la racine
(la réécriture des outils du shell ne s'applique pas à un script, CLAUDE.md §3.6.9). Attendu : `commits F-039 : <n>`
puis aucune ligne ; relevé 2026-10-28 (`architect`, verdict T-121) : `commits F-039 : 14`, aucune ligne, code `0`. Témoins rouges :
retirer la ligne `docs/essais-manuels.md` du registre → `absent du registre : docs/essais-manuels.md` (ligne restaurée) ;
`bash <fichier> F-034` → `commits F-034 : 8`, `152` lignes `absent du registre`.

```sh
m=${1:-F-039}
a=docs/adr/0014-moteur-sql-embarque-sqlquery-run.md
echo "commits $m : $(git rev-list --count --grep="$m" HEAD)"
mapfile -t p < <(sed -n '/^## Empreinte et retrait/,/^## /p' "$a" | awk -F'|' '/^\| `/{print $2}' | /usr/bin/grep -oE '`[^`]+`' | tr -d '`')
git rev-list --grep="$m" HEAD | xargs -r -n1 git diff-tree --no-commit-id --name-only -r | sort -u | while IFS= read -r f; do
  ok=; for q in "${p[@]}"; do [[ $f == "$q" || ( $q == */ && $f == "$q"* ) ]] && ok=1; done
  [ -n "$ok" ] || echo "absent du registre : $f"; done
```

**Ce qui ne se retire pas** (mémoire) : `docs/adr/` (cette ADR, ses amendements, ceux des ADR 0001
et 0005), `docs/backlog/archive/` et `docs/backlog-archive.md`, `docs/plan-archive.md` et
`docs/plan/README.md` (ordre et Escalades), `docs/comptes-rendus/`, `.claude/metrics/`,
`bench/baselines/T-114/`, `bench/baselines/T-116/`, `bench/baselines/T-118/`, `docs/Architecture moteur SQL on FHIR pour Blaze.md` (feuille de route
antérieure à F-039, `SQ-01`…`SQ-03` remis à « À faire »), le `emptyDir` `/tmp`.

**Procédure de retrait**, dans cet ordre, sur décision humaine :

1. F-039 → `rejeté`, ADR de rejet qui remplace celle-ci (statut `remplacée`), fichier de F-039
   déplacé dans `docs/backlog/archive/`.
2. Chart : clés `blaze.sqlqueryRun.*` et variables retirées ; `ressources.blaze.limits.memory`
   rendue à `2Gi` (révisée en `2560Mi` pour F-039, décision humaine du 2026-10-27).
3. Module : namespaces, tests, dépendance retirés ; overlay régénéré ; lignes de
   `docs/impacts-blaze.md` et `docs/couverture.md` retirées ; `build/` rendu à son état de départ
   s'il a changé ; ligne de `versions.env` retirée.
4. Cas N2 et lignes du catalogue retirés ; `docs/tests.md` tenu.
5. `docs/configuration.md`, `docs/architecture.md`, `docs/manuel.md`, `README.md`,
   `docs/backlog/README.md` (l. 37) tenus.
6. Vérifications, chacune avec sa valeur attendue :
   - recherche sur les fichiers suivis (domaine : `git ls-files`), liens vers cette ADR exclus :
     `git ls-files -z | xargs -0 /usr/bin/grep -noiE '0014-moteur-sql-embarque-sqlquery-run|sqlquery|duckdb' | /usr/bin/grep -viE ':0014-moteur-sql-embarque-sqlquery-run$' | cut -d: -f1 | sort -u | /usr/bin/grep -vE '^docs/(adr/|backlog/archive/|backlog-archive\.md|plan-archive\.md|plan/README\.md|comptes-rendus/|Architecture moteur SQL on FHIR pour Blaze\.md)|^\.claude/metrics/|^bench/baselines/T-11[468]/'`
     → aucune ligne ; relevé 2026-10-27 (témoin, solution non livrée, après T-113) : `docs/architecture.md`,
     `docs/backlog/README.md`, `docs/couverture.md`, `docs/manuel.md`, `versions.env` ;
   - `python3 scripts/check_module.py` → code `0`, `SUCCES` M1–M6 (relevé 2026-10-27 : code `0`) ;
     `make -C modules/sql-on-fhir overlay-controle` → code `0` (à mesurer) ;
   - `make image` puis `docker image inspect blaze-platform-module:dev --format '{{.Size}}'` →
     valeur de l'image construite au commit qui précède le premier commit F-039, `600220116`
     octets si rien d'autre n'a changé (à mesurer au retrait) ;
   - `curl -s <base>/fhir/metadata | jq '[.rest[0].operation[]?.name] | index("sqlquery-run")'`
     → `null` (décidé, non mesuré).

## Conséquences

- **Exposition sans authentification** (point 4) : tout client qui joint le serveur lit, par SQL,
  toutes les données que les vues atteignent. Seule parade livrée : l'interrupteur (point 3) et la
  `NetworkPolicy` du chart. À reconsidérer si OpenID est déployé (ADR 0001, point ouvert 2).
- **Poids** : `+79362050` octets de jar, dont trois bibliothèques natives d'autres plateformes ;
  image `600220116` → `679582166` octets, `+13,2 %` (T-112), accepté (humain, 2026-10-27). Jar réduit à linux amd64 : `24601767` octets
  (information, non retenu : artefact publié modifié). Mémoire hors tas ajoutée sous la limite du pod
  (`kubernetes/chart/values.yaml`, `ressources.blaze`), qui porte déjà le cache et les memtables
  RocksDB : limite révisée `2Gi` → `2560Mi` (humain, 2026-10-27, sur T-118), qui ne couvre
  pas la borne de 3688 Mio memtables pleines (point 5, L6).
- **Bibliothèque native** : un composant non JVM entre dans le processus Blaze ; un plantage natif
  arrête le pod entier. Montée de version : suivre la publication, relever à nouveau ce tableau.
- **Dialecte** : la requête est du SQL DuckDB (`application/sql;dialect=duckdb`, Q2) ; une requête
  écrite pour un autre dialecte peut échouer (422, Q3).
- **Parquet** (`SPK-07`, F-008) : inchangé, `parquet-hadoop`. Que le même moteur écrive aussi le
  Parquet de F-008 n'est pas décidé ici.
- **`SQ-04`** (index de Blaze pour les filtres des vues) : non ouvert (humain, 2026-10-26).

## Acceptation (humain, 2026-10-27)

Aucune mesure de T-112 ne contredit `duckdb_jdbc` (table « Leviers et témoins » corrigée). L'humain a accepté
l'ADR et retenu les cinq recommandations :

1. **Poids** : image `+79362050` octets, `+13,2 %` ; démarrage sans différence mesurable — accepté.
2. **Natives d'autres plateformes** (arm64, macos, windows) dans le jar — jar publié tel quel (empreinte
   vérifiable contre le dépôt Maven), non retouché.
3. **Fuite de métadonnées résiduelle** (L14) — acceptée et consignée ; aucun filtre (ce serait un analyseur écrit ici).
4. **arm64 non mesuré** (L12) — livraison amd64 seule ; arm64 « à mesurer », sans tâche.
5. **`emptyDir` `noexec` sur un nœud, non éprouvé** (L15) — risque accepté ; mesuré au premier lancement N2 sur
   le banc (T-116) : premier `200` d'IT-055 = chargement natif établi ; état de L15 mis à jour alors.

**Cible des essais N2 et N4 de F-039** : banc kind (`scripts/banc.sh`, port 8088, celui de tous les N2 précédents) —
**confirmée par l'humain le 2026-10-27** (Amendements). La consigne « utiliser le même » (humain, 2026-10-27) n'a pas été tranchée entre ce banc et
le banc à trois VM (`kubernetes/banc/`), qui n'a pas de Blaze déployé (`kubernetes/banc/README.md` l. 26, 57 : déploiement
et acheminement de l'image *à mesurer*). La démonstration de F-037 n'est pas retenue. **Redéploiement de la pile autorisé**
(humain, 2026-10-27) aux tâches T-114, T-116, T-118, sur le banc confirmé (kind : `scripts/banc.sh redeployer [--set …]`) ;
destruction et recréation d'un banc non autorisées. Extension de l'autorisation à T-119 et T-120 (d) : proposée, en
attente de l'humain (T-120 (d) non rejoué sur le banc au 2026-10-28 faute d'autorisation écrite).

## Alternatives écartées

- **H2 ou HSQLDB en mémoire** (JVM pure, sans natif) : *non mesuré* ; ni lecture de fichier ni
  extension à fermer, mais moteurs orientés lignes, sans le Parquet que C apporte pour F-008. Repli
  si la bibliothèque native est refusée sous `restricted`.
- **Apache Calcite sur les lignes des vues** : *non mesuré* ; aucun stockage, mais un adaptateur
  de schéma à écrire — davantage de colle.
- **Base relationnelle déployée à côté** (PostgreSQL) : écartée par §1.1 et « Ce que ce projet
  n'est pas » (stockage parallèle).
- **`3.0.0-ballot` et `$sql-run`** : écartée par l'humain le 2026-10-26 (point 1) ; elle
  remplacerait aussi `$viewdefinition-run` (ADR 0005, E9).

## Amendements

- **2026-10-27 (humain, consigné par `architect`)** : cible des essais N2 et N4 de F-039 = **banc kind**
  (`scripts/banc.sh`, port 8088), confirmée ; le banc à trois VM n'est pas retenu. § Acceptation : « à confirmer
  par l'humain » est levé par cet amendement. L1 et L11 : livrées en U par T-116 (`modules/sql-on-fhir/test/…/sql/engine_test.clj`,
  `operation/sqlquery_test.clj`), de bout en bout non mesurées — N2 IT-055, IT-056, IT-061 à lancer par `architect`.
- **2026-10-27 (`architect`, lancement N2)** : IT-049…IT-061 lancés sur le banc kind, image du commit `aa3bcfb` (`bench/baselines/T-116/`) ; L1, L5, L7, L8, L10, L11, L15 mesurées de bout en bout ; L3 mesurée en partie, écart sur la matérialisation des vues remonté à l'humain.
- **2026-10-27 (décision humaine, T-116 itération 2)** : le plafond de durée (L3) couvre toute la requête, chargement des vues compris ; échéance unique, annulation coopérative entre deux ressources, `setQueryTimeout` = temps restant (arrondi au-dessus, 1 s au moins) ; même réponse (422 `timeout`). Limite : une seule ressource ou une seule instruction SQL de chargement n'est pas interrompue en cours.
- **2026-10-27 (`architect`, 2ᵉ lancement N2, image du commit `a9ea124`, `bench/baselines/T-116/iter2/`)** : L3 mesurée sur le chargement des vues (422 `timeout` à 30 s et 2 s, dépassement ≤ 35 ms en phase de lecture) ; limite mesurée : dépassement de 2,6 à 4,4 s servi en `200` (plafond 70 s, vue de 168 981 lignes ; cause lue dans le code, non isolée) — remontée à l'humain.
- **2026-10-27 (décision humaine sur L3, consignée par `architect`)** : la limite mesurée de L3 est **acceptée** — le plafond de durée est un plafond de protection, non de précision ; un dépassement de D jusqu'à 5 s, servi en `200`, est admis (critère « Plafond de durée D » de F-039, réécrit par `functional-analyst` le même jour ; mesuré 2,6 à 4,4 s, `bench/baselines/T-116/iter2/sonde-architect-limite-insert.txt`) ; au-delà du lot mesuré, non garanti. Aucun changement de code (l'alternative « échéance vérifiée aussi avant l'exécution » n'est pas retenue). La valeur de D est **à ajuster par l'humain** selon le volume de données et la machine d'exécution ; cette information est tracée à côté du paramètre (`kubernetes/chart/values.yaml` ligne `delaiSecondes`, `docs/configuration.md` variable et clé FR et EN : compte du critère « Paramètres documentés » ≥ `5`) et dans `README.md` (T-120). Registre : aucun fichier neuf ; lignes `kubernetes/chart/values.yaml` et `docs/configuration.md` complétées de T-122 (reprise du livrable de documentation de T-117).
- **2026-10-27 (`architect`, 3ᵉ lancement N2, image du commit `a9ea124` inchangée, `bench/baselines/T-114/lancement3/`)** : IT-049…IT-061 verts à leur passage ; L3 à D = 5 s : `422` `timeout` 3/3 en 5,03–5,04 s, réponses ≤ D + 5 s ; L7 (N = 1), L5 (64MiB), interrupteur, L11 (n = 10) reconfirmés ; ligne de registre `bench/baselines/T-114/lancement3/` ajoutée.
- **2026-10-27 (décisions humaines sur T-118 et T-119, consignées par `architect` ; 4ᵉ lancement N2, image du commit `2cc1932`, `bench/baselines/T-114/lancement4/`)** : défauts maintenus — `delaiSecondes` 30, `plafondMemoire` 256 Mio, `plafondSimultanees` 2 ; limite du pod `2Gi` → `2560Mi` ; aucun plafond de chargement des vues en tas pour l'instant (L17) ; réserve JVM de 384 Mio laissée *décidé, non mesuré* (non mesurable sur l'image, T-118). Point 5 recalculé memtables RocksDB comprises : borne 3688 Mio, non couverte par 2560 Mio, limite décidée sur le pic mesuré (1 823 Mio). L5, L6, L7, L9 passées à mesurées (T-118) ; L2 sans tâche ; écarts de T-119 consignés, non servis (L18, `docs/manuel.md` §4). À 2560 Mio : démarrage, IT-049…IT-061 (rouges attendus du banc inchangés), IT-057 à D = 5 s vert avec `issue.code` `timeout` et borne D + 5 s jugés par le cas, `restartCount` 0. Registre : `docs/comptes-rendus/` livré ; `bench/baselines/T-114/lancement4/` ajouté ; `bench/baselines/T-114/` porté dans « Ce qui ne se retire pas » et la vérification du retrait.
- **2026-10-28 (décisions humaines, consignées par `architect`, T-120)** : (1) version et empreinte de l'IG inscrites dans `versions.env` (`SQL_ON_FHIR_IG_VERSION`, `SQL_ON_FHIR_IG_SHA256`, commit `f93edf1`) ; (2) `kubernetes/banc-local.yaml` laissé à ses valeurs (`2776Mi`, `-Xmx768m`), différentes de celles du chart (`2560Mi`, `1g`), ce banc de test étant susceptible de changer (`docs/banc.md`) ; (3) **risque résiduel documenté, non traité** : la limite du pod (`2560Mi`) ne couvre pas la somme des bornes (3688 Mio, point 5) ; écriture massive pendant des requêtes saturantes non mesurée (L6) ; aucune tâche ouverte. Documentation utilisateur livrée (T-120) : `README.md` « Requêtes SQL sur les vues » (FR et EN : usage rejoué sur le banc kind, limites L1…L18, paramètres, coupure, renvoi au retrait), `docs/configuration.md` « Options de mise en route de `$sqlquery-run` ». Constat de T-120, *non traité* : la démonstration docker compose (`demo/compose.yaml`, F-037) ne pose pas `duckdb_jdbc` dans le classpath ; l'opération y est déclarée et rend 500 `No suitable driver found for jdbc:duckdb:` (image publiée + jar du module, conteneur d'essai `t120-demo-sans-moteur` retiré par ce nom, 2026-10-28) ; élément de backlog à ouvrir par `functional-analyst`.
