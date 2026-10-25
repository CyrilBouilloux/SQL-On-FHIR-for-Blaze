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
   jar de Blaze dans le classpath.
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

## Le module sql-on-fhir sans serveur

Le serveur expose le module par HTTP (`$viewdefinition-run`, `$viewdefinition-export` :
`docs/manuel.md`). Le moteur s'exécute aussi **sans serveur**, en mémoire :

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
   Blaze jar on the classpath.
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

### The sql-on-fhir module without a server

The server exposes the module over HTTP (`$viewdefinition-run`, `$viewdefinition-export`:
`docs/manuel.md`). The engine also runs **without a server**, in memory:

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
