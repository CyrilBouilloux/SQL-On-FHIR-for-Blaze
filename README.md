# SQL On FHIR for Blaze

*Français ci-dessous — [English below](#english).*

Ce dépôt fournit un **module SQL on FHIR** pour **Blaze** (construit sur la version 1.11.0),
qui suit la release v2.0.0 de la norme. La couverture fonctionnelle n'est pas encore complète
(état d'avancement : [avancement.html](avancement.html)). Le développement est réalisé avec
l'aide d'agents Claude (Opus et Sonnet) ; les détails de l'implémentation sont dans la
documentation. La version actuelle est largement préliminaire : elle vise à valider le concept
et sera complétée au fur et à mesure de l'avancement.

## Avertissement et limitation de responsabilité

Ce logiciel est un **travail préliminaire à visée expérimentale et pédagogique**. Il est fourni
**« en l'état », sans garantie d'aucune sorte**, expresse ou implicite (qualité marchande,
adéquation à un usage particulier, absence de défaut, exactitude des résultats), conformément
aux sections 7 et 8 de la licence Apache-2.0 ([LICENSE](LICENSE)). En aucun cas l'auteur ou les
contributeurs ne peuvent être tenus responsables d'un dommage direct ou indirect résultant de
l'usage, de l'impossibilité d'usage ou du mauvais fonctionnement du logiciel, notamment une perte
de données, une interruption de service ou une décision prise sur la foi de ses résultats.

- **Pas un dispositif médical, pas d'usage clinique.** Ni logique de soin ni aide à la décision :
  ne pas employer pour le soin, le diagnostic ou toute décision touchant des personnes.
- **Données synthétiques uniquement.** N'y placez aucune donnée patient réelle. Si vous le faites
  malgré tout, la conformité (RGPD, hébergement de données de santé, secret médical, etc.) est
  entièrement de votre responsabilité ; rien ici n'est certifié ni conforme HDS.
- **Sécurité non assurée.** L'API déployée est ouverte, sans authentification (voir « Ce que le
  chart déploie ») : usage local uniquement, jamais exposée sur un réseau non maîtrisé.
- **Code assisté par IA.** Il a été produit avec l'aide d'agents Claude et peut contenir des
  erreurs. Relisez-le, testez-le et validez-le avant tout emploi.
- **Aucun support, aucun engagement** de maintenance, de compatibilité ou de calendrier.
- **Marques et projets tiers.** Blaze, HL7 et FHIR, SQL on FHIR, Kubernetes, nginx et les autres
  noms cités appartiennent à leurs propriétaires ; ce dépôt n'est ni affilié à eux ni approuvé
  par eux. Leur usage reste soumis à leurs propres licences.

## Présentation

Le dépôt assemble un serveur FHIR R4 **Blaze** (`samply/blaze`, stockage standalone) déployé sur
**Kubernetes** derrière un reverse proxy **nginx**, augmenté d'un **module
complémentaire** (`modules/sql-on-fhir/`) qui évalue des ViewDefinitions SQL on FHIR.
Le module ne modifie pas Blaze : il se pose devant le jar de l'image Blaze publiée,
épinglée par empreinte (`build/Dockerfile`, ADR 0003).

**Pour essayer sans cluster, Docker suffit** : l'image du module se construit et se lance avec
`docker` (voir [Démarrage rapide](#démarrage-rapide)), ou toute la pile avec la démonstration
docker compose de [`demo/README.md`](demo/README.md). Kubernetes reste la seule livraison.

| Où | Quoi |
|---|---|
| `modules/sql-on-fhir/` | le module : sources Clojure, overlay `resources/blaze.edn`, tests, suite de conformité |
| `build/construire-jar.sh`, `build/Dockerfile` | jar du module ; image du module, unique `FROM` l'image Blaze publiée épinglée (`docs/construction.md`) |
| `kubernetes/chart/` | chart Helm de la livraison ; `nginx/` : configuration du proxy |
| `versions.env` | seule source des versions et empreintes |
| `docs/architecture.md`, `docs/configuration.md`, `docs/manuel.md` | ce qui existe, où se règle quoi (chaque paramètre), comment le vérifier |
| `docs/methode-module.md`, `docs/couverture.md`, `docs/impacts-blaze.md` | développement du module, couverture, points d'extension de Blaze touchés |

## Démarrage rapide

Du dossier publié au jar du module, puis à l'image qui l'emploie. Prérequis du poste :
`bash`, `make`, `git`, `python3`, `docker`, `curl`, `jq`, `unzip`, `tar`, `sha256sum`.
Aucun JDK n'est requis pour le jar ni pour l'image (le jar n'est pas compilé). Depuis la
racine du dossier :

```bash
git init -q
git add -A
git -c user.name=tiers -c user.email=tiers@example.org commit -qm "copie initiale"
build/construire-jar.sh
scripts/outils.sh jar
make check
make image
docker run -d --name blaze-module -p 8080:8080 -e VIEWDEFINITION_EXPORT_DIR=/app/data/export -e VIEWDEFINITION_EXPORT_MAX_BYTES=1073741824 -e VIEWDEFINITION_EXPORT_RETENTION_SECONDS=86400 -e VIEWDEFINITION_EXPORT_CLEANUP_PERIOD_SECONDS=300 blaze-platform-module:dev
for i in $(seq 60); do curl -sf http://localhost:8080/health >/dev/null && break; sleep 5; done
curl -s http://localhost:8080/fhir/metadata | jq '[.rest[0].operation[]?.name | select(test("^viewdefinition-(run|export)$"))] | length'
docker rm -f blaze-module
```

1. `git init` et un commit : le jar archive les fichiers **suivis par git** (sans dépôt git,
   `build/construire-jar.sh` rend 3).
2. `build/construire-jar.sh` : jar du module, `build/blaze-addon.jar` ; ses quatre étapes et
   l'empreinte imprimée sont expliquées dans `docs/construction.md`.
3. `scripts/outils.sh jar` : jar de l'image Blaze publiée, `.work/blaze-standalone.jar`,
   vérifié par empreinte ; `make check` le compare au jar du module (seule entrée commune
   admise : `blaze.edn`).
4. `make check` : reconstruit le jar, puis contrôle versions, règle « module complémentaire »
   (`SUCCES` sur M1 à M6) et plafond `_include`.
5. `make image` : image `blaze-platform-module:dev`, unique `FROM` l'image Blaze publiée
   épinglée par empreinte, jar du module copié en `/app/addon/blaze-addon.jar`, **devant** le
   jar de Blaze dans le classpath, et jar du moteur SQL `duckdb_jdbc` téléchargé de Maven Central
   (réseau requis), empreinte de `versions.env` vérifiée (`docs/construction.md`).
6. `docker run` : l'image ne démarre qu'avec les quatre variables `VIEWDEFINITION_EXPORT_*`
   (ou `DISABLE_VIEWDEFINITION_EXPORT=true`) ; toutes les variables, et les clés du chart :
   `docs/configuration.md`. La dernière `curl` rend `2` : les opérations
   `$viewdefinition-run` et `$viewdefinition-export` sont déclarées. `docker rm -f` retire le
   conteneur.

Développer le module (tests, conformité) : `make outils`, `eval "$(scripts/outils.sh env)"`,
`make -C modules/sql-on-fhir test` ; procédure : `docs/methode-module.md`.

Voir tourner l'ensemble sur ce poste, sans cluster : [`demo/README.md`](demo/README.md) — démonstration docker compose, outil de poste **hors livraison** (Kubernetes reste la seule livraison).

## Exemples d'utilisation

Sur un déploiement du chart (voir « Déployer »). Les blocs `bash` s'enchaînent dans un
même shell. **Données synthétiques uniquement** (aucune donnée patient réelle).

### 1. Variables

```bash
export FHIR=http://localhost:8088/fhir          # base de l'API de votre déploiement
export CT='Content-Type: application/fhir+json'
```

### 2. Vérifier le serveur

```bash
curl -s $FHIR/metadata | jq '{serveur: .software.name, version: .software.version, fhir: .fhirVersion}'
```

### 3. Créer, lire, modifier un Patient

```bash
# Création : l'identifiant est attribué par le serveur (en-tête Location)
PID=$(curl -s -X POST $FHIR/Patient -H "$CT" -d '{
  "resourceType": "Patient",
  "name": [{"family": "Exemple", "given": ["Alice"]}],
  "gender": "female",
  "birthDate": "1980-04-12"
}' | jq -r .id)
echo "Patient créé : $PID"

# Lecture
curl -s $FHIR/Patient/$PID | jq '{id, name, birthDate, version: .meta.versionId}'

# Modification (remplace la ressource ; nouvelle version)
curl -s -X PUT $FHIR/Patient/$PID -H "$CT" -d "{
  \"resourceType\": \"Patient\", \"id\": \"$PID\",
  \"name\": [{\"family\": \"Exemple\", \"given\": [\"Alice\", \"Marie\"]}],
  \"gender\": \"female\", \"birthDate\": \"1980-04-12\"
}" | jq '{id, given: .name[0].given, version: .meta.versionId}'

# Historique des versions
curl -s $FHIR/Patient/$PID/_history | jq '[.entry[] | {version: .resource.meta.versionId, methode: .request.method}]'
```

### 4. Ajouter une Observation et chercher

```bash
OID=$(curl -s -X POST $FHIR/Observation -H "$CT" -d "{
  \"resourceType\": \"Observation\", \"status\": \"final\",
  \"code\": {\"coding\": [{\"system\": \"http://loinc.org\", \"code\": \"29463-7\", \"display\": \"Body weight\"}]},
  \"subject\": {\"reference\": \"Patient/$PID\"},
  \"valueQuantity\": {\"value\": 68.5, \"unit\": \"kg\", \"system\": \"http://unitsofmeasure.org\", \"code\": \"kg\"}
}" | jq -r .id)

# Recherches (paramètres R4 standard)
curl -s "$FHIR/Patient?family=Exemple&birthdate=1980-04-12" | jq '{total, ids: [.entry[].resource.id]}'
curl -s "$FHIR/Observation?patient=$PID&code=http://loinc.org|29463-7" | jq '{total}'
curl -s "$FHIR/Patient?_summary=count" | jq .total

# _include : l'Observation et son Patient en une requête (plafond : 5 par requête)
curl -s "$FHIR/Observation?_id=$OID&_include=Observation:subject" | jq '[.entry[].resource.resourceType]'

# SQL on FHIR : chercher la patiente par une ViewDefinition ($viewdefinition-run), sortie CSV
curl -s -X POST "$FHIR/\$viewdefinition-run?_format=csv" -H "$CT" -d '{
  "resourceType": "Parameters",
  "parameter": [{"name": "viewResource", "resource": {
    "resourceType": "ViewDefinition", "name": "recherche_patient", "resource": "Patient", "status": "active",
    "select": [{"column": [
      {"name": "id", "path": "id"},
      {"name": "nom", "path": "name.first().family"},
      {"name": "prenom", "path": "name.first().given.first()"},
      {"name": "naissance", "path": "birthDate"}]}],
    "where": [{"path": "name.family = '"'"'Exemple'"'"'"}]}}]}'
# équivalent en une commande : scripts/patient_sql.sh Exemple   (ou id:<id>)
```

### 5. Transaction (plusieurs ressources en une fois)

```bash
curl -s -X POST $FHIR -H "$CT" -d '{
  "resourceType": "Bundle", "type": "transaction",
  "entry": [
    {"fullUrl": "urn:uuid:p1", "resource": {"resourceType": "Patient", "name": [{"family": "Lot"}]},
     "request": {"method": "POST", "url": "Patient"}},
    {"resource": {"resourceType": "Observation", "status": "final",
       "code": {"coding": [{"system": "http://loinc.org", "code": "8867-4"}]},
       "subject": {"reference": "urn:uuid:p1"}, "valueQuantity": {"value": 72, "unit": "/min"}},
     "request": {"method": "POST", "url": "Observation"}}
  ]
}' | jq '[.entry[].response.status]'
```

### 6. Supprimer

Blaze garde l'intégrité référentielle : un Patient encore référencé ne se
supprime pas (409). On supprime d'abord l'Observation.

```bash
curl -s -o /dev/null -w 'DELETE Observation -> %{http_code}\n' -X DELETE $FHIR/Observation/$OID
curl -s -o /dev/null -w 'DELETE Patient -> %{http_code}\n' -X DELETE $FHIR/Patient/$PID
curl -s -o /dev/null -w 'GET après suppression -> %{http_code}\n' $FHIR/Patient/$PID   # 410 Gone
```

### Limites posées par le proxy

Plus de 5 `_include`/`_revinclude` dans une requête, ou toute forme `:iterate`,
sont refusées en **400** par nginx (`nginx/nginx.conf`) :

```bash
curl -s -o /dev/null -w 'HTTP %{http_code}\n' "$FHIR/Observation?_include=Observation:subject&_include=Observation:patient&_include=Observation:encounter&_include=Observation:device&_include=Observation:performer&_include=Observation:specimen"
```

## Requêtes SQL sur les vues (`$sqlquery-run`)

`POST /fhir/$sqlquery-run` exécute une requête SQL sur des vues (ViewDefinition) rangées sur le
serveur, **sur ses données courantes, sans export préalable**. À chaque appel, le serveur ouvre
une base DuckDB en mémoire, y charge en tables les vues que nomme la requête, exécute la requête,
puis ferme la base : rien n'est conservé (ADR 0014). L'opération est **allumée par défaut et
servie sans authentification** (voir « Couper la fonction »).

### Utiliser

Sur un déploiement chargé une fois du jeu de démonstration, données synthétiques
(`bash demo/charger-jeu.sh $FHIR`). Les blocs s'enchaînent dans un même shell ; chaque ligne
`# …` est la sortie attendue.

1. **Ranger les vues.** Une vue rangée est une `Library` qui porte la ViewDefinition en base64 et
   se désigne par sa canonique `url|version` (ADR 0009). Au second passage, `201` devient `200`.

```bash
export FHIR=http://localhost:8088/fhir
export CT='Content-Type: application/fhir+json'
store_view() {
  jq -n --arg id "$1" --arg url "$2" --arg data "$(base64 -w0)" '{resourceType: "Library", id: $id,
    url: $url, version: "1", status: "active",
    type: {coding: [{system: "http://terminology.hl7.org/CodeSystem/library-type", code: "asset-collection"}]},
    content: [{contentType: "application/json", data: $data}]}' |
  curl -s -o /dev/null -w "$1 %{http_code}\n" -X PUT "$FHIR/Library/$1" -H "$CT" -d @-
}
store_view readme-patient http://example.org/ViewDefinition/readme-patient <<'EOF'
{"resourceType": "ViewDefinition", "name": "patient", "resource": "Patient", "status": "active",
 "select": [{"column": [
   {"name": "key", "path": "getResourceKey()"},
   {"name": "synthea_id", "path": "identifier.where(system = 'https://github.com/synthetichealth/synthea').value.first()"}]}]}
EOF
store_view readme-allergy http://example.org/ViewDefinition/readme-allergy <<'EOF'
{"resourceType": "ViewDefinition", "name": "allergy", "resource": "AllergyIntolerance", "status": "active",
 "select": [{"column": [
   {"name": "patient_key", "path": "patient.getReferenceKey(Patient)"},
   {"name": "category", "path": "category.first()"}]}]}
EOF
# readme-patient 201
# readme-allergy 201
```

2. **Interroger.** La requête est une `Library` de `type` `sql-query` : chaque `relatedArtifact`
   nomme une table (`label`) et la vue qui la remplit (`resource`, canonique) ; le paramètre
   `:synthea_id` est déclaré (`parameter`) et sa valeur passée dans `parameters` : elle est
   **liée**, jamais recopiée dans le SQL. Ici, les allergies d'une patiente du jeu, comptées par
   catégorie. Formats servis : NDJSON sans `_format`, `_format=json`, `_format=csv` ; `_limit`
   (entrée du corps) réduit le nombre de lignes. Une `Library` déjà rangée sur le serveur se
   désigne par `queryReference` au lieu de `queryResource`.

```bash
SQL=$(cat <<'EOF'
SELECT a.category, count(*) AS n
FROM patient p JOIN allergy a ON a.patient_key = p.key
WHERE p.synthea_id = :synthea_id
GROUP BY a.category ORDER BY a.category
EOF
)
QUERY=$(jq -n --arg sql "$(printf '%s' "$SQL" | base64 -w0)" '{resourceType: "Parameters", parameter: [
  {name: "queryResource", resource: {resourceType: "Library", status: "active",
    type: {coding: [{system: "https://sql-on-fhir.org/ig/CodeSystem/LibraryTypesCodes", code: "sql-query"}]},
    relatedArtifact: [
      {type: "depends-on", label: "patient", resource: "http://example.org/ViewDefinition/readme-patient|1"},
      {type: "depends-on", label: "allergy", resource: "http://example.org/ViewDefinition/readme-allergy|1"}],
    parameter: [{name: "synthea_id", use: "in", type: "string"}],
    content: [{contentType: "application/sql;dialect=duckdb", data: $sql}]}},
  {name: "parameters", resource: {resourceType: "Parameters", parameter: [
    {name: "synthea_id", valueString: "2d950093-7ccd-c8c0-c61e-bb6865439601"}]}}]}')
curl -s -X POST "$FHIR/\$sqlquery-run" -H "$CT" -d "$QUERY"
# {"category":"environment","n":6}
# {"category":"food","n":4}
curl -s -X POST "$FHIR/\$sqlquery-run?_format=json" -H "$CT" -d "$QUERY"; echo
# [{"category":"environment","n":6},{"category":"food","n":4}]
curl -s -X POST "$FHIR/\$sqlquery-run?_format=csv" -H "$CT" -d "$QUERY" | tr -d '\r'
# category,n
# environment,6
# food,4
```

3. **Refus.** Corps `OperationOutcome` ; une ligne par code, dans l'ordre du bloc :

   | Code | Cas montré | `issue.code` |
   |---|---|---|
   | 400 | paramètre déclaré sans valeur (aussi : paramètre inconnu, mal typé, entrée inconnue) | `invalid` (`required` sans `queryResource` ni `queryReference`) |
   | 404 | vue introuvable (aussi : `Library` de `queryReference` introuvable) | `not-found` |
   | 422 | instruction autre que `SELECT` (aussi : SQL fausse) ; plafond de durée dépassé : `timeout` ; de mémoire ou de lignes : `too-costly` | `invalid` |
   | 503 | plafond de concurrence atteint : deux requêtes longues en cours, défaut 2 | `throttled` |

```bash
refusal() {
  curl -s -w '\n%{http_code}' -X POST "$FHIR/\$sqlquery-run" -H "$CT" -d "$1" |
  { read -r body; read -r code; echo "$code $(jq -r '.issue[0].code' <<<"$body")"; }
}
with_sql() { jq --arg sql "$(printf '%s' "$1" | base64 -w0)" '.parameter[0].resource.content[0].data = $sql'; }
refusal "$(jq 'del(.parameter[1])' <<<"$QUERY")"
refusal "$(jq '.parameter[0].resource.relatedArtifact[1].resource = "http://example.org/ViewDefinition/missing|1"' <<<"$QUERY")"
refusal "$(with_sql 'DELETE FROM patient' <<<"$QUERY")"
LONG=$(with_sql 'SELECT sum(i * j) AS s FROM range(100000) t(i), range(40000) u(j)' <<<"$QUERY" |
  jq 'del(.parameter[1], .parameter[0].resource.relatedArtifact, .parameter[0].resource.parameter)')
for i in 1 2; do curl -s -o /dev/null -X POST "$FHIR/\$sqlquery-run" -H "$CT" -d "$LONG" & done
sleep 2; refusal "$LONG"; wait
# 400 invalid
# 404 not-found
# 422 invalid
# 503 throttled
```

4. **Nettoyer.**

```bash
for id in readme-patient readme-allergy; do curl -s -o /dev/null -w "$id %{http_code}\n" -X DELETE "$FHIR/Library/$id"; done
# readme-patient 204
# readme-allergy 204
```

### Limites

Tirées de l'ADR 0014, « Limites d'utilisation », qui porte l'état et la mesure de chacune.

| # | Limite | Réglage |
|---|---|---|
| L1 | `SELECT` seul : toute autre instruction (création, modification, suppression, `COPY`, `ATTACH`, `INSTALL`, `SET`…) est refusée, 422 ; ni fichier, ni réseau, ni extension | — |
| L2 | Dialecte DuckDB (`application/sql;dialect=duckdb`) : une requête écrite pour un autre moteur peut échouer, 422 | — |
| L3 | Durée plafonnée, chargement des vues compris, puis 422 `timeout` ; plafond **de protection, non de précision** : un dépassement jusqu'à 5 s, servi en 200, est possible ; à ajuster au volume de données et à la machine | `delaiSecondes` |
| L4 | Au-delà de 600 s, nginx coupe la requête avant Blaze (`proxy_read_timeout`) ; non mesuré | `nginx/nginx.conf` |
| L5 | Mémoire du moteur plafonnée, sans débordement sur disque ; au-delà, 422 `too-costly` | `plafondMemoire` |
| L6 | Cette mémoire est prise sous la limite du pod, partagée avec le tas et RocksDB ; la limite ne couvre pas la somme des bornes (risque résiduel, « Paramétrage ») | `ressources.blaze.limits.memory` |
| L7 | Au plus N requêtes simultanées ; la suivante est refusée sur-le-champ, 503 `throttled`, sans file d'attente | `plafondSimultanees` |
| L8 | **Aucune authentification** : tout client qui joint le serveur lit, par SQL, toutes les données que les vues atteignent | `actif` ; `NetworkPolicy` du chart |
| L9 | Vues recalculées à chaque appel : ni persistance, ni vue matérialisée, ni index ; mesuré (T-118) : vue de 6 001 lignes 1,9 à 2,9 s, vue de 168 981 lignes 121 à 137 s sans plafond | — |
| L10 | Les tables sont les vues nommées par les `relatedArtifact` de la requête, et elles seules ; vue introuvable → 404 | — |
| L11 | Résultat plafonné par le plafond de lignes de `$viewdefinition-run`, partagé ; au-delà, 422 `too-costly` | `plafondLignes` |
| L12 | Image livrée et mesurée en amd64 seule ; arm64 non mesuré | — |
| L13 | Un plantage du moteur natif arrête le processus Blaze, donc tout le serveur ; non mesuré | — |
| L14 | Métadonnées du moteur lisibles par toute requête : `duckdb_settings()`, `current_setting('home_directory')`, `information_schema.tables` | — |
| L15 | Bibliothèque native extraite dans `/tmp` à chaque démarrage : `/tmp` doit être inscriptible et exécutable (`emptyDir` du chart ; sous Docker, `/tmp` du conteneur ou `--tmpfs /tmp:exec`) | — |
| L16 | Décimal FHIR rendu en `DOUBLE` : précision perdue au-delà de 15 à 17 chiffres significatifs | — |
| L17 | Lignes des vues chargées **en tas** avant le moteur : mémoire hors `plafondMemoire`, bornée seulement par L3 et L7, aucun plafond de chargement ; mesuré (T-118) : +201 Mio pour la vue de 168 981 lignes | — |
| L18 | Non servis : `_format=fhir`, `_format=parquet`, entrée `source` → 400 `not-supported` ; appel sur `Library` → 405 (type), 404 (instance) ; écarts à l'IG, `docs/manuel.md` §4 | — |

### Paramétrage

Options de mise en route, chacune changée seule, **sans reconstruire l'image**. Défauts : ceux de
`kubernetes/chart/values.yaml`, qui fait foi ; noms et rôles complets : `docs/configuration.md`.

| Paramètre | Variable | Clé de chart | Défaut | Effet | Quand le réviser |
|---|---|---|---|---|---|
| Interrupteur | `DISABLE_SQLQUERY_RUN` | `blaze.sqlqueryRun.actif` | `true` | `false` pose `DISABLE_SQLQUERY_RUN=true` : opération retirée de `/fhir/metadata`, appel → 404 | serveur joignable par des clients qui ne doivent pas lire toutes les données des vues (L8) |
| Plafond de durée | `SQLQUERY_RUN_TIMEOUT_SECONDS` | `blaze.sqlqueryRun.delaiSecondes` | `30` | durée maximale de toute la requête, chargement des vues compris, puis 422 `timeout` ; plafond de protection, non de précision : dépassement jusqu'à 5 s possible (L3) | à ajuster au volume des vues et à la machine : la vue de 168 981 lignes dépasse le défaut (L9) ; au-delà de 600 s, régler aussi nginx (L4) |
| Plafond de mémoire | `SQLQUERY_RUN_MAX_MEMORY` | `blaze.sqlqueryRun.plafondMemoire` | `256MiB` | mémoire du moteur hors tas, sans disque ; au-delà, 422 `too-costly` (L5) | tri ou agrégat légitime refusé en `too-costly` ; chaque requête simultanée l'ajoute sous la limite du pod (L6) |
| Plafond de concurrence | `SQLQUERY_RUN_MAX_CONCURRENT` | `blaze.sqlqueryRun.plafondSimultanees` | `2` | au plus N requêtes simultanées, la suivante refusée en 503 `throttled` (L7) | monter N seulement après avoir baissé `plafondMemoire` ou monté la limite du pod ; N > 2 non mesuré ; chaque requête ajoute aussi le chargement de ses vues en tas (L17) |
| Plafond de lignes, partagé | `VIEWDEFINITION_RUN_MAX_ROWS` | `blaze.viewdefinitionRun.plafondLignes` | `10000` | lignes rendues au plus par `$sqlquery-run` et `$viewdefinition-run`, au-delà 422 `too-costly` ; ne borne pas le chargement des vues (L11, L17) | résultat légitime plus grand ; vaut pour les deux opérations |

**Risque résiduel, documenté** (décision humaine du 2026-10-28) : la limite mémoire du pod
(`ressources.blaze.limits.memory` de `kubernetes/chart/values.yaml`) ne couvre pas la somme des
bornes — tas, cache et memtables RocksDB, réserve JVM, N × `plafondMemoire`, 3688 Mio au total
(ADR 0014, point 5) ; elle est fixée sur le pic mesuré, 1 823 Mio à N = 2 (T-118). Une écriture
massive pendant des requêtes saturantes **n'est pas mesurée** ; aucune tâche n'est ouverte.
Avant de monter `plafondSimultanees` ou `plafondMemoire`, montez cette limite.

**Sous Helm**, ajoutez à la commande `helm upgrade --install` de « Déployer » une option
`--set blaze.sqlqueryRun.<clé>=<valeur>` par paramètre (`--set blaze.viewdefinitionRun.plafondLignes=…`
pour les lignes), ou `-f <fichier>` :

```yaml
blaze:
  sqlqueryRun:
    delaiSecondes: 120
    plafondSimultanees: 1
```

Le chart refuse une valeur invalide (`fail`, code 1) : `actif` non booléen, durée ou concurrence
autre qu'un entier ≥ 1, mémoire sans unité (`KB`…`TiB`).

**Sous Docker**, posez la variable : `-e SQLQUERY_RUN_TIMEOUT_SECONDS=120` dans le `docker run`
de « Démarrage rapide » ; dans la démonstration docker compose (`demo/compose.yaml`), sous
`environment:` du service `blaze` :

```yaml
    environment:
      SQLQUERY_RUN_TIMEOUT_SECONDS: "120"
```

La démonstration docker compose ne pose pas le jar du moteur SQL dans le classpath : l'opération
y est déclarée mais rend 500 `No suitable driver found for jdbc:duckdb:` (relevé du 2026-10-28) ;
pour l'essayer sous Docker, employez l'image du module (`make image`).

### Couper la fonction

Helm : `--set blaze.sqlqueryRun.actif=false` ajouté à la commande `helm upgrade --install` de
« Déployer ». Docker : `-e DISABLE_SQLQUERY_RUN=true`, ou `DISABLE_SQLQUERY_RUN: "true"` sous
`environment:`. Contrôle : la commande ci-dessous rend `null` quand l'opération est coupée, un
entier (son rang dans la liste) quand elle est allumée ; coupée, l'appel rend 404 `not-found`.

```bash
curl -s $FHIR/metadata | jq '[.rest[0].operation[]?.name] | index("sqlquery-run")'
```

### Retrait

Si la fonction ne convainc pas, la procédure de retrait, le registre de tout ce qu'elle a posé et
les vérifications sont dans l'[ADR 0014, « Empreinte et retrait »](docs/adr/0014-moteur-sql-embarque-sqlquery-run.md#empreinte-et-retrait).

## Le module sql-on-fhir sans serveur

Le serveur expose le module par HTTP (`$viewdefinition-run`, `$viewdefinition-export`,
`$sqlquery-run` : `docs/manuel.md`). Le moteur s'exécute aussi **sans serveur**, en mémoire :

```bash
eval "$(scripts/outils.sh env)"       # après `make outils`, une fois

# Une vue sur des ressources NDJSON (sortie de `blazectl download`) → une ligne JSON par ligne de vue
cat > /tmp/vue.json <<'EOF'
{"resourceType": "ViewDefinition", "resource": "Patient", "status": "active",
 "select": [{"column": [{"name": "id", "path": "id"},
                        {"name": "nom", "path": "name.first().family"},
                        {"name": "naissance", "path": "birthDate"}]}]}
EOF
cat > /tmp/patients.ndjson <<'EOF'
{"resourceType": "Patient", "id": "p1", "name": [{"family": "Exemple"}], "birthDate": "1980-04-12"}
{"resourceType": "Patient", "id": "p2", "name": [{"family": "Lot"}]}
EOF
make -s -C modules/sql-on-fhir vue-ndjson VUE=/tmp/vue.json RESSOURCES=/tmp/patients.ndjson 2>/dev/null
# {"id":"p1","nom":"Exemple","naissance":"1980-04-12"}
# {"id":"p2","nom":"Lot","naissance":null}
```

| Commande (`make -C modules/sql-on-fhir …`) | Effet |
|---|---|
| `test` | tests unitaires du module (kaocha) |
| `conformance` | suite de conformité SQL on FHIR, attendu `REUSSIS 144/144` |
| `independance` | aucun namespace `blaze.db` / `blaze.interaction` chargé |
| `vue-ndjson VUE=… RESSOURCES=…` | exécute une vue (ci-dessus) ; rc 3 fichier absent, 8 anomalie du moteur |

## Ce que le chart déploie (et ne déploie pas)

- **Déployé** : Blaze (une réplique, RocksDB sur volume persistant) dans l'image du module,
  nginx, politique de sécurité de pods `restricted`, `NetworkPolicy` (Blaze n'est joignable
  que par nginx).
- **Pas encore** : authentification OpenID, frontend Blaze, validation de profils par
  validateur externe, Ingress et TLS (`docs/architecture.md`, « Architecture cible »).
  L'API est donc **ouverte, sans authentification** : usage local uniquement.

## Déployer

Les versions viennent de `versions.env` (jamais de `latest`) ; le chart est
`kubernetes/chart/`, paramétré par `kubernetes/chart/values.yaml` (la base
publique de l'API se règle par `blaze.baseUrl` ; réglages : `docs/configuration.md`).

Le chart déploie l'image **du module** (`make image`) et refuse de se rendre sans
`image.blaze.tag`. Un cluster distant suppose cette image **publiée dans un registre**
qu'il sait lire : poussez-la sous le dépôt et l'étiquette ci-dessous
(`<BLAZE_VERSION>-<12 premiers hexadécimaux de l'identifiant de l'image>`), puis ajoutez
`--set image.blaze.registry=<hôte[:port]>` et `--set image.blaze.digest=sha256:<…>`
(empreinte que le registre a rendue au `docker push`) : avec un registre, le chart exige
l'empreinte ; il refuse toujours `latest` (`docs/configuration.md`). Le
chart porte son propre `Namespace` (politique `restricted`) : le bloc crée le namespace
avec les métadonnées d'adoption de Helm, sans `--create-namespace` (qui entrerait en
conflit) :

```sh
. ./versions.env
IMAGE=blaze-platform-module
TAG="${BLAZE_VERSION}-$(docker image inspect blaze-platform-module:dev --format '{{.Id}}' | cut -c8-19)"
kubectl create ns blaze
kubectl label ns blaze app.kubernetes.io/managed-by=Helm
kubectl annotate ns blaze meta.helm.sh/release-name=blaze meta.helm.sh/release-namespace=blaze
kubernetes/chart/deriver.sh
helm upgrade --install blaze kubernetes/chart -n blaze \
  -f kubernetes/chart/values-versions.yaml --set image.blaze.repository="$IMAGE" \
  --set image.blaze.tag="$TAG" --set blaze.baseUrl=https://fhir.exemple.test
```

## Contribuer

Organisation et procédure du module : `docs/methode-module.md` ; décisions : `docs/adr/`.

## Licence

Apache-2.0 : texte intégral dans [LICENSE](LICENSE). Blaze, dont l'image publiée sert de
base, est distribué sous la même licence. Seule exception : la suite de conformité copiée
d'un tiers, `modules/sql-on-fhir/conformance/sql-on-fhir-js/`, reste sous sa licence MIT
(son `LICENSE.md`, ADR 0006).

---


---

## English

This repository provides a **SQL on FHIR module** for **Blaze** (built on version 1.11.0),
following release v2.0.0 of the specification. Functional coverage is not complete yet
(progress: [avancement.html](avancement.html), in French). Development is carried out with the
help of Claude agents (Opus and Sonnet); implementation details are in the documentation. The
current version is largely preliminary: it aims to validate the concept and will be completed as
work progresses.

### Disclaimer and limitation of liability

This software is **preliminary work for experimental and educational purposes**. It is provided
**"as is", without warranty of any kind**, express or implied (merchantability, fitness for a
particular purpose, absence of defects, accuracy of results), in accordance with sections 7 and
8 of the Apache-2.0 license ([LICENSE](LICENSE)). In no event shall the author or the
contributors be liable for any direct or indirect damage arising from the use of, the inability
to use, or the malfunction of the software, including loss of data, service interruption, or any
decision taken on the strength of its results.

- **Not a medical device, no clinical use.** No care logic and no decision support: do not use
  it for care, diagnosis, or any decision affecting people.
- **Synthetic data only.** Do not put any real patient data in it. If you do so anyway,
  compliance (GDPR, health-data hosting, medical confidentiality, etc.) is entirely your
  responsibility; nothing here is certified or HDS-compliant.
- **Security is not provided.** The deployed API is open, without authentication (see "What the
  chart deploys"): local use only, never exposed on an uncontrolled network.
- **AI-assisted code.** It was produced with the help of Claude agents and may contain errors.
  Review it, test it and validate it before any use.
- **No support, no commitment** on maintenance, compatibility or schedule.
- **Third-party trademarks and projects.** Blaze, HL7 and FHIR, SQL on FHIR, Kubernetes, nginx
  and the other names cited belong to their owners; this repository is neither affiliated with
  nor endorsed by them. Their use remains subject to their own licenses.

### Overview

The repository assembles a **Blaze** FHIR R4 server (`samply/blaze`, standalone storage)
deployed on **Kubernetes** behind an **nginx** reverse proxy, extended by an **add-on module**
(`modules/sql-on-fhir/`) that evaluates SQL on FHIR ViewDefinitions. The module does not
modify Blaze: it is placed in front of the jar of the published Blaze image, pinned by
digest (`build/Dockerfile`, ADR 0003). `docs/construction.md` (building the jar and the
image) and `docs/configuration.md` (every parameter) are bilingual; the rest of `docs/` is in
French.

**To try it without a cluster, Docker is enough**: the module image is built and run with
`docker` (see [Quick start](#quick-start)), or the whole stack with the docker compose
demonstration in [`demo/README.md`](demo/README.md). Kubernetes remains the only delivery.

| Where | What |
|---|---|
| `modules/sql-on-fhir/` | the module: Clojure sources, `resources/blaze.edn` overlay, tests, conformance suite |
| `build/construire-jar.sh`, `build/Dockerfile` | module jar; module image, single `FROM` the pinned published Blaze image (`docs/construction.md`) |
| `kubernetes/chart/` | Helm chart of the delivery; `nginx/`: proxy configuration |
| `versions.env` | single source of versions and digests |
| `docs/architecture.md`, `docs/configuration.md`, `docs/manuel.md` | what exists, where each setting lives (every parameter), how to verify it |
| `docs/methode-module.md`, `docs/couverture.md`, `docs/impacts-blaze.md` | module development, coverage, Blaze extension points touched |

### Quick start

From the published folder to the module jar, then to the image that uses it. Workstation
prerequisites: `bash`, `make`, `git`, `python3`, `docker`, `curl`, `jq`, `unzip`, `tar`,
`sha256sum`. No JDK is needed for the jar nor the image (the jar is not compiled). From the
root of the folder:

```bash
git init -q
git add -A
git -c user.name=tiers -c user.email=tiers@example.org commit -qm "initial copy"
build/construire-jar.sh
scripts/outils.sh jar
make check
make image
docker run -d --name blaze-module -p 8080:8080 -e VIEWDEFINITION_EXPORT_DIR=/app/data/export -e VIEWDEFINITION_EXPORT_MAX_BYTES=1073741824 -e VIEWDEFINITION_EXPORT_RETENTION_SECONDS=86400 -e VIEWDEFINITION_EXPORT_CLEANUP_PERIOD_SECONDS=300 blaze-platform-module:dev
for i in $(seq 60); do curl -sf http://localhost:8080/health >/dev/null && break; sleep 5; done
curl -s http://localhost:8080/fhir/metadata | jq '[.rest[0].operation[]?.name | select(test("^viewdefinition-(run|export)$"))] | length'
docker rm -f blaze-module
```

1. `git init` and one commit: the jar archives the **git-tracked** files (without a git
   repository, `build/construire-jar.sh` exits 3).
2. `build/construire-jar.sh`: module jar, `build/blaze-addon.jar`; its four steps and the
   printed digest are explained in `docs/construction.md`.
3. `scripts/outils.sh jar`: jar of the published Blaze image, `.work/blaze-standalone.jar`,
   checked by digest; `make check` compares it with the module jar (only shared entry
   allowed: `blaze.edn`).
4. `make check`: rebuilds the jar, then checks versions, the "add-on module" rule (`SUCCES`
   on M1 to M6) and the `_include` cap.
5. `make image`: image `blaze-platform-module:dev`, single `FROM` the published Blaze image
   pinned by digest, module jar copied to `/app/addon/blaze-addon.jar`, **in front of** the
   Blaze jar on the classpath, and the `duckdb_jdbc` SQL engine jar downloaded from Maven Central
   (network required), digest from `versions.env` checked (`docs/construction.md`).
6. `docker run`: the image only starts with the four `VIEWDEFINITION_EXPORT_*` variables (or
   `DISABLE_VIEWDEFINITION_EXPORT=true`); every variable, and the chart keys:
   `docs/configuration.md`. The last `curl` prints `2`: the `$viewdefinition-run` and
   `$viewdefinition-export` operations are declared. `docker rm -f` removes the container.

Developing the module (tests, conformance): `make outils`, `eval "$(scripts/outils.sh env)"`,
`make -C modules/sql-on-fhir test`; procedure: `docs/methode-module.md`.

See the whole stack run on this workstation, without a cluster: [`demo/README.md`](demo/README.md) — docker compose demonstration, a workstation tool **not part of the delivery** (Kubernetes remains the only delivery).

### Usage examples

On a deployment of the chart (see "Deploying"). The `bash` blocks run in the same shell.
**Synthetic data only** (no real patient data).

#### 1. Variables

```bash
export FHIR=http://localhost:8088/fhir          # API base of your deployment
export CT='Content-Type: application/fhir+json'
```

#### 2. Check the server

```bash
curl -s $FHIR/metadata | jq '{server: .software.name, version: .software.version, fhir: .fhirVersion}'
```

#### 3. Create, read, update a Patient

```bash
# Create: the id is assigned by the server (Location header)
PID=$(curl -s -X POST $FHIR/Patient -H "$CT" -d '{
  "resourceType": "Patient",
  "name": [{"family": "Example", "given": ["Alice"]}],
  "gender": "female",
  "birthDate": "1980-04-12"
}' | jq -r .id)
echo "Patient created: $PID"

# Read
curl -s $FHIR/Patient/$PID | jq '{id, name, birthDate, version: .meta.versionId}'

# Update (replaces the resource; new version)
curl -s -X PUT $FHIR/Patient/$PID -H "$CT" -d "{
  \"resourceType\": \"Patient\", \"id\": \"$PID\",
  \"name\": [{\"family\": \"Example\", \"given\": [\"Alice\", \"Marie\"]}],
  \"gender\": \"female\", \"birthDate\": \"1980-04-12\"
}" | jq '{id, given: .name[0].given, version: .meta.versionId}'

# Version history
curl -s $FHIR/Patient/$PID/_history | jq '[.entry[] | {version: .resource.meta.versionId, method: .request.method}]'
```

#### 4. Add an Observation and search

```bash
OID=$(curl -s -X POST $FHIR/Observation -H "$CT" -d "{
  \"resourceType\": \"Observation\", \"status\": \"final\",
  \"code\": {\"coding\": [{\"system\": \"http://loinc.org\", \"code\": \"29463-7\", \"display\": \"Body weight\"}]},
  \"subject\": {\"reference\": \"Patient/$PID\"},
  \"valueQuantity\": {\"value\": 68.5, \"unit\": \"kg\", \"system\": \"http://unitsofmeasure.org\", \"code\": \"kg\"}
}" | jq -r .id)

# Searches (standard R4 parameters)
curl -s "$FHIR/Patient?family=Example&birthdate=1980-04-12" | jq '{total, ids: [.entry[].resource.id]}'
curl -s "$FHIR/Observation?patient=$PID&code=http://loinc.org|29463-7" | jq '{total}'
curl -s "$FHIR/Patient?_summary=count" | jq .total

# _include: the Observation and its Patient in one request (cap: 5 per request)
curl -s "$FHIR/Observation?_id=$OID&_include=Observation:subject" | jq '[.entry[].resource.resourceType]'

# SQL on FHIR: find the patient through a ViewDefinition ($viewdefinition-run), CSV output
curl -s -X POST "$FHIR/\$viewdefinition-run?_format=csv" -H "$CT" -d '{
  "resourceType": "Parameters",
  "parameter": [{"name": "viewResource", "resource": {
    "resourceType": "ViewDefinition", "name": "patient_search", "resource": "Patient", "status": "active",
    "select": [{"column": [
      {"name": "id", "path": "id"},
      {"name": "family", "path": "name.first().family"},
      {"name": "given", "path": "name.first().given.first()"},
      {"name": "birth_date", "path": "birthDate"}]}],
    "where": [{"path": "name.family = '"'"'Example'"'"'"}]}}]}'
# one-command equivalent: scripts/patient_sql.sh Example   (or id:<id>)
```

#### 5. Transaction (several resources at once)

```bash
curl -s -X POST $FHIR -H "$CT" -d '{
  "resourceType": "Bundle", "type": "transaction",
  "entry": [
    {"fullUrl": "urn:uuid:p1", "resource": {"resourceType": "Patient", "name": [{"family": "Batch"}]},
     "request": {"method": "POST", "url": "Patient"}},
    {"resource": {"resourceType": "Observation", "status": "final",
       "code": {"coding": [{"system": "http://loinc.org", "code": "8867-4"}]},
       "subject": {"reference": "urn:uuid:p1"}, "valueQuantity": {"value": 72, "unit": "/min"}},
     "request": {"method": "POST", "url": "Observation"}}
  ]
}' | jq '[.entry[].response.status]'
```

#### 6. Delete

Blaze enforces referential integrity: a Patient that is still referenced cannot be deleted
(409). Delete the Observation first.

```bash
curl -s -o /dev/null -w 'DELETE Observation -> %{http_code}\n' -X DELETE $FHIR/Observation/$OID
curl -s -o /dev/null -w 'DELETE Patient -> %{http_code}\n' -X DELETE $FHIR/Patient/$PID
curl -s -o /dev/null -w 'GET after delete -> %{http_code}\n' $FHIR/Patient/$PID   # 410 Gone
```

#### Limits set by the proxy

More than 5 `_include`/`_revinclude` in a request, or any `:iterate` form, is refused with
**400** by nginx (`nginx/nginx.conf`):

```bash
curl -s -o /dev/null -w 'HTTP %{http_code}\n' "$FHIR/Observation?_include=Observation:subject&_include=Observation:patient&_include=Observation:encounter&_include=Observation:device&_include=Observation:performer&_include=Observation:specimen"
```

### SQL queries on views (`$sqlquery-run`)

`POST /fhir/$sqlquery-run` runs a SQL query over views (ViewDefinition) stored on the server,
**on its current data, with no prior export**. On each call the server opens an in-memory DuckDB
database, loads the views named by the query into tables, runs the query, then closes the
database: nothing is kept (ADR 0014). The operation is **on by default and served without
authentication** (see "Turning it off").

#### Usage

On a deployment loaded once with the demonstration set, synthetic data
(`bash demo/charger-jeu.sh $FHIR`). The blocks chain in one shell; each `# …` line is the
expected output.

1. **Store the views.** A stored view is a `Library` carrying the ViewDefinition in base64,
   designated by its canonical `url|version` (ADR 0009). On a second run, `201` becomes `200`.

```bash
export FHIR=http://localhost:8088/fhir
export CT='Content-Type: application/fhir+json'
store_view() {
  jq -n --arg id "$1" --arg url "$2" --arg data "$(base64 -w0)" '{resourceType: "Library", id: $id,
    url: $url, version: "1", status: "active",
    type: {coding: [{system: "http://terminology.hl7.org/CodeSystem/library-type", code: "asset-collection"}]},
    content: [{contentType: "application/json", data: $data}]}' |
  curl -s -o /dev/null -w "$1 %{http_code}\n" -X PUT "$FHIR/Library/$1" -H "$CT" -d @-
}
store_view readme-patient http://example.org/ViewDefinition/readme-patient <<'EOF'
{"resourceType": "ViewDefinition", "name": "patient", "resource": "Patient", "status": "active",
 "select": [{"column": [
   {"name": "key", "path": "getResourceKey()"},
   {"name": "synthea_id", "path": "identifier.where(system = 'https://github.com/synthetichealth/synthea').value.first()"}]}]}
EOF
store_view readme-allergy http://example.org/ViewDefinition/readme-allergy <<'EOF'
{"resourceType": "ViewDefinition", "name": "allergy", "resource": "AllergyIntolerance", "status": "active",
 "select": [{"column": [
   {"name": "patient_key", "path": "patient.getReferenceKey(Patient)"},
   {"name": "category", "path": "category.first()"}]}]}
EOF
# readme-patient 201
# readme-allergy 201
```

2. **Query.** The query is a `Library` of `type` `sql-query`: each `relatedArtifact` names a
   table (`label`) and the view that fills it (`resource`, canonical); the `:synthea_id`
   parameter is declared (`parameter`) and its value passed in `parameters`: it is **bound**,
   never copied into the SQL. Here, the allergies of one patient of the set, counted by
   category. Served formats: NDJSON without `_format`, `_format=json`, `_format=csv`; `_limit`
   (body input) reduces the number of rows. A `Library` already stored on the server is
   designated by `queryReference` instead of `queryResource`.

```bash
SQL=$(cat <<'EOF'
SELECT a.category, count(*) AS n
FROM patient p JOIN allergy a ON a.patient_key = p.key
WHERE p.synthea_id = :synthea_id
GROUP BY a.category ORDER BY a.category
EOF
)
QUERY=$(jq -n --arg sql "$(printf '%s' "$SQL" | base64 -w0)" '{resourceType: "Parameters", parameter: [
  {name: "queryResource", resource: {resourceType: "Library", status: "active",
    type: {coding: [{system: "https://sql-on-fhir.org/ig/CodeSystem/LibraryTypesCodes", code: "sql-query"}]},
    relatedArtifact: [
      {type: "depends-on", label: "patient", resource: "http://example.org/ViewDefinition/readme-patient|1"},
      {type: "depends-on", label: "allergy", resource: "http://example.org/ViewDefinition/readme-allergy|1"}],
    parameter: [{name: "synthea_id", use: "in", type: "string"}],
    content: [{contentType: "application/sql;dialect=duckdb", data: $sql}]}},
  {name: "parameters", resource: {resourceType: "Parameters", parameter: [
    {name: "synthea_id", valueString: "2d950093-7ccd-c8c0-c61e-bb6865439601"}]}}]}')
curl -s -X POST "$FHIR/\$sqlquery-run" -H "$CT" -d "$QUERY"
# {"category":"environment","n":6}
# {"category":"food","n":4}
curl -s -X POST "$FHIR/\$sqlquery-run?_format=json" -H "$CT" -d "$QUERY"; echo
# [{"category":"environment","n":6},{"category":"food","n":4}]
curl -s -X POST "$FHIR/\$sqlquery-run?_format=csv" -H "$CT" -d "$QUERY" | tr -d '\r'
# category,n
# environment,6
# food,4
```

3. **Refusals.** `OperationOutcome` body; one line per code, in the order of the block:

   | Code | Case shown | `issue.code` |
   |---|---|---|
   | 400 | declared parameter without a value (also: unknown or mistyped parameter, unknown input) | `invalid` (`required` with neither `queryResource` nor `queryReference`) |
   | 404 | view not found (also: `Library` of `queryReference` not found) | `not-found` |
   | 422 | statement other than `SELECT` (also: wrong SQL); duration cap exceeded: `timeout`; memory or row cap: `too-costly` | `invalid` |
   | 503 | concurrency cap reached: two long queries running, default 2 | `throttled` |

```bash
refusal() {
  curl -s -w '\n%{http_code}' -X POST "$FHIR/\$sqlquery-run" -H "$CT" -d "$1" |
  { read -r body; read -r code; echo "$code $(jq -r '.issue[0].code' <<<"$body")"; }
}
with_sql() { jq --arg sql "$(printf '%s' "$1" | base64 -w0)" '.parameter[0].resource.content[0].data = $sql'; }
refusal "$(jq 'del(.parameter[1])' <<<"$QUERY")"
refusal "$(jq '.parameter[0].resource.relatedArtifact[1].resource = "http://example.org/ViewDefinition/missing|1"' <<<"$QUERY")"
refusal "$(with_sql 'DELETE FROM patient' <<<"$QUERY")"
LONG=$(with_sql 'SELECT sum(i * j) AS s FROM range(100000) t(i), range(40000) u(j)' <<<"$QUERY" |
  jq 'del(.parameter[1], .parameter[0].resource.relatedArtifact, .parameter[0].resource.parameter)')
for i in 1 2; do curl -s -o /dev/null -X POST "$FHIR/\$sqlquery-run" -H "$CT" -d "$LONG" & done
sleep 2; refusal "$LONG"; wait
# 400 invalid
# 404 not-found
# 422 invalid
# 503 throttled
```

4. **Clean up.**

```bash
for id in readme-patient readme-allergy; do curl -s -o /dev/null -w "$id %{http_code}\n" -X DELETE "$FHIR/Library/$id"; done
# readme-patient 204
# readme-allergy 204
```

#### Limits

Taken from ADR 0014, "Limites d'utilisation", which records the state and measurement of each.

| # | Limit | Setting |
|---|---|---|
| L1 | `SELECT` only: any other statement (create, update, delete, `COPY`, `ATTACH`, `INSTALL`, `SET`…) is refused, 422; no file, no network, no extension | — |
| L2 | DuckDB dialect (`application/sql;dialect=duckdb`): a query written for another engine may fail, 422 | — |
| L3 | Duration capped, view loading included, then 422 `timeout`; a **protection cap, not a precision cap**: an overrun of up to 5 s, served 200, is possible; adjust it to the data volume and the machine | `delaiSecondes` |
| L4 | Beyond 600 s, nginx cuts the request before Blaze (`proxy_read_timeout`); not measured | `nginx/nginx.conf` |
| L5 | Engine memory capped, no spill to disk; beyond, 422 `too-costly` | `plafondMemoire` |
| L6 | That memory is taken under the pod limit, shared with the heap and RocksDB; the limit does not cover the sum of the bounds (residual risk, "Settings") | `ressources.blaze.limits.memory` |
| L7 | At most N simultaneous queries; the next one is refused at once, 503 `throttled`, no queue | `plafondSimultanees` |
| L8 | **No authentication**: any client that reaches the server reads, through SQL, all the data the views reach | `actif`; chart `NetworkPolicy` |
| L9 | Views recomputed on every call: no persistence, no materialised view, no index; measured (T-118): 6,001-row view 1.9 to 2.9 s, 168,981-row view 121 to 137 s uncapped | — |
| L10 | Tables are the views named by the query's `relatedArtifact`, and only those; view not found → 404 | — |
| L11 | Result capped by the row cap of `$viewdefinition-run`, shared; beyond, 422 `too-costly` | `plafondLignes` |
| L12 | Image delivered and measured on amd64 only; arm64 not measured | — |
| L13 | A crash of the native engine stops the Blaze process, hence the whole server; not measured | — |
| L14 | Engine metadata readable by any query: `duckdb_settings()`, `current_setting('home_directory')`, `information_schema.tables` | — |
| L15 | Native library extracted into `/tmp` at each start: `/tmp` must be writable and executable (chart `emptyDir`; under Docker, the container's `/tmp` or `--tmpfs /tmp:exec`) | — |
| L16 | FHIR decimal rendered as `DOUBLE`: precision lost beyond 15 to 17 significant digits | — |
| L17 | View rows loaded **on the heap** before the engine: memory outside `plafondMemoire`, bounded only by L3 and L7, no loading cap; measured (T-118): +201 MiB for the 168,981-row view | — |
| L18 | Not served: `_format=fhir`, `_format=parquet`, `source` input → 400 `not-supported`; call on `Library` → 405 (type), 404 (instance); deviations from the IG, `docs/manuel.md` §4 | — |

#### Settings

Start-up options, each changed on its own, **without rebuilding the image**. Defaults: those of
`kubernetes/chart/values.yaml`, which is authoritative; full names and roles:
`docs/configuration.md`.

| Setting | Variable | Chart key | Default | Effect | When to revise |
|---|---|---|---|---|---|
| Switch | `DISABLE_SQLQUERY_RUN` | `blaze.sqlqueryRun.actif` | `true` | `false` sets `DISABLE_SQLQUERY_RUN=true`: operation removed from `/fhir/metadata`, call → 404 | server reachable by clients that must not read all the data of the views (L8) |
| Duration cap | `SQLQUERY_RUN_TIMEOUT_SECONDS` | `blaze.sqlqueryRun.delaiSecondes` | `30` | maximum duration of the whole query, view loading included, then 422 `timeout`; protection cap, not a precision cap: an overrun of up to 5 s is possible (L3) | adjust it to the volume of the views and the machine: the 168,981-row view exceeds the default (L9); beyond 600 s, tune nginx too (L4) |
| Memory cap | `SQLQUERY_RUN_MAX_MEMORY` | `blaze.sqlqueryRun.plafondMemoire` | `256MiB` | off-heap engine memory, no disk; beyond, 422 `too-costly` (L5) | a legitimate sort or aggregate refused with `too-costly`; each simultaneous query adds it under the pod limit (L6) |
| Concurrency cap | `SQLQUERY_RUN_MAX_CONCURRENT` | `blaze.sqlqueryRun.plafondSimultanees` | `2` | at most N simultaneous queries, the next refused with 503 `throttled` (L7) | raise N only after lowering `plafondMemoire` or raising the pod limit; N > 2 not measured; each query also adds the heap loading of its views (L17) |
| Row cap, shared | `VIEWDEFINITION_RUN_MAX_ROWS` | `blaze.viewdefinitionRun.plafondLignes` | `10000` | rows returned at most by `$sqlquery-run` and `$viewdefinition-run`, beyond 422 `too-costly`; does not bound view loading (L11, L17) | a larger legitimate result; applies to both operations |

**Residual risk, documented** (human decision of 2026-10-28): the pod memory limit
(`ressources.blaze.limits.memory` of `kubernetes/chart/values.yaml`) does not cover the sum of
the bounds — heap, RocksDB cache and memtables, JVM reserve, N × `plafondMemoire`, 3688 MiB in
total (ADR 0014, point 5); it is set on the measured peak, 1,823 MiB at N = 2 (T-118). A massive
write during saturating queries **is not measured**; no task is open. Before raising
`plafondSimultanees` or `plafondMemoire`, raise that limit.

**Under Helm**, add to the `helm upgrade --install` command of "Deploying" one
`--set blaze.sqlqueryRun.<key>=<value>` option per setting (`--set blaze.viewdefinitionRun.plafondLignes=…`
for rows), or `-f <file>`:

```yaml
blaze:
  sqlqueryRun:
    delaiSecondes: 120
    plafondSimultanees: 1
```

The chart refuses an invalid value (`fail`, exit 1): `actif` not a boolean, duration or
concurrency other than an integer ≥ 1, memory without a unit (`KB`…`TiB`).

**Under Docker**, set the variable: `-e SQLQUERY_RUN_TIMEOUT_SECONDS=120` in the `docker run` of
"Quick start"; in the docker compose demonstration (`demo/compose.yaml`), under `environment:` of
the `blaze` service:

```yaml
    environment:
      SQLQUERY_RUN_TIMEOUT_SECONDS: "120"
```

The docker compose demonstration does not put the SQL engine jar on the classpath: the operation
is declared there but returns 500 `No suitable driver found for jdbc:duckdb:` (reading of
2026-10-28); to try it under Docker, use the module image (`make image`).

#### Turning it off

Helm: `--set blaze.sqlqueryRun.actif=false` added to the `helm upgrade --install` command of
"Deploying". Docker: `-e DISABLE_SQLQUERY_RUN=true`, or `DISABLE_SQLQUERY_RUN: "true"` under
`environment:`. Check: the command below returns `null` when the operation is off, an integer
(its rank in the list) when it is on; off, the call returns 404 `not-found`.

```bash
curl -s $FHIR/metadata | jq '[.rest[0].operation[]?.name] | index("sqlquery-run")'
```

#### Removal

If the feature does not convince, the removal procedure, the register of everything it put in
place and the checks are in [ADR 0014, "Empreinte et retrait"](docs/adr/0014-moteur-sql-embarque-sqlquery-run.md#empreinte-et-retrait).

### The sql-on-fhir module without a server

The server exposes the module over HTTP (`$viewdefinition-run`, `$viewdefinition-export`,
`$sqlquery-run`: `docs/manuel.md`). The engine also runs **without a server**, in memory:

```bash
eval "$(scripts/outils.sh env)"       # after `make outils`, once

# A view over NDJSON resources (output of `blazectl download`) → one JSON line per view row
cat > /tmp/view.json <<'JSON'
{"resourceType": "ViewDefinition", "resource": "Patient", "status": "active",
 "select": [{"column": [{"name": "id", "path": "id"},
                        {"name": "family", "path": "name.first().family"},
                        {"name": "birth_date", "path": "birthDate"}]}]}
JSON
cat > /tmp/patients.ndjson <<'JSON'
{"resourceType": "Patient", "id": "p1", "name": [{"family": "Example"}], "birthDate": "1980-04-12"}
{"resourceType": "Patient", "id": "p2", "name": [{"family": "Batch"}]}
JSON
make -s -C modules/sql-on-fhir vue-ndjson VUE=/tmp/view.json RESSOURCES=/tmp/patients.ndjson 2>/dev/null
# {"id":"p1","family":"Example","birth_date":"1980-04-12"}
# {"id":"p2","family":"Batch","birth_date":null}
```

| Command (`make -C modules/sql-on-fhir …`) | Effect |
|---|---|
| `test` | module unit tests (kaocha) |
| `conformance` | SQL on FHIR conformance suite, expected `REUSSIS 144/144` |
| `independance` | no `blaze.db` / `blaze.interaction` namespace loaded |
| `vue-ndjson VUE=… RESSOURCES=…` | runs a view (above); rc 3 missing file, 8 engine anomaly |

### What the chart deploys (and does not)

- **Deployed**: Blaze (one replica, RocksDB on a persistent volume) in the module image,
  nginx, `restricted` pod security, `NetworkPolicy` (Blaze reachable from nginx only).
- **Not yet**: OpenID authentication, Blaze frontend, profile validation by an external
  validator, Ingress and TLS (`docs/architecture.md`, "Architecture cible"). The API is
  therefore **open, without authentication**: local use only.

### Deploying

Versions come from `versions.env` (never `latest`); the chart is `kubernetes/chart/`,
configured by `kubernetes/chart/values.yaml` (the public API base is set with `blaze.baseUrl`;
settings: `docs/configuration.md`).

The chart deploys the **module** image (`make image`) and refuses to render without
`image.blaze.tag`. A remote cluster requires that image to be **pushed to a registry** it can
read: push it under the repository and tag below
(`<BLAZE_VERSION>-<first 12 hex digits of the image id>`), then add
`--set image.blaze.registry=<host[:port]>` and `--set image.blaze.digest=sha256:<…>` (the digest
the registry returned on `docker push`): with a registry the chart requires the digest, and it
always refuses `latest` (`docs/configuration.md`). The chart carries its own `Namespace`
(`restricted` policy): the block creates the namespace with Helm's adoption metadata, without
`--create-namespace` (which would conflict):

```sh
. ./versions.env
IMAGE=blaze-platform-module
TAG="${BLAZE_VERSION}-$(docker image inspect blaze-platform-module:dev --format '{{.Id}}' | cut -c8-19)"
kubectl create ns blaze
kubectl label ns blaze app.kubernetes.io/managed-by=Helm
kubectl annotate ns blaze meta.helm.sh/release-name=blaze meta.helm.sh/release-namespace=blaze
kubernetes/chart/deriver.sh
helm upgrade --install blaze kubernetes/chart -n blaze \
  -f kubernetes/chart/values-versions.yaml --set image.blaze.repository="$IMAGE" \
  --set image.blaze.tag="$TAG" --set blaze.baseUrl=https://fhir.example.test
```

### Contributing

Module organization and procedure: `docs/methode-module.md`; decisions: `docs/adr/`.

### License

Apache-2.0: full text in [LICENSE](LICENSE). Blaze, whose published image is the base, is
distributed under the same license. Only exception: the conformance suite copied from a third
party, `modules/sql-on-fhir/conformance/sql-on-fhir-js/`, stays under its MIT license (its
`LICENSE.md`, ADR 0006).
