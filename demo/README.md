# Démonstration — Blaze, module et reverse proxy sur un poste

*Français ci-dessous — [English below](#english).*

## Français

### À quoi elle sert

Voir tourner l'ensemble sur son seul poste, d'une commande, sans cluster Kubernetes :
Blaze (image publiée épinglée par empreinte), le jar du module posé **devant** le jar de
Blaze dans le classpath, et nginx avec la configuration du chart
(`nginx/nginx.conf`, identique à celle du chart, plafond `_include`). Un jeu de 100 patients
synthétiques est chargé au démarrage.

C'est un **outil de poste, hors livraison** : Kubernetes (`kubernetes/chart/`) reste la seule
livraison. La démonstration n'est ni livrée, ni supportée en exploitation, et elle n'éprouve
pas le chart (ni politique `restricted`, ni `NetworkPolicy`, ni Ingress).

### Prérequis

- `bash`, `docker` avec `docker compose`, `curl`, `jq`, `ss`, `sha256sum`, `python3`, `git`
  (`demo/demo.sh` refuse, code 3, si l'un manque).
- Le dossier est un dépôt git dont les fichiers sont suivis : le jar du module archive les
  fichiers suivis (sinon code 3) ; depuis une copie publiée, faire d'abord les trois lignes
  `git` du « Démarrage rapide » de [`README.md`](../README.md).
- Pour régénérer le jeu seulement : `java`, et un accès réseau au dépôt de Synthea sur GitHub.

### Démarrer

Depuis la racine du dossier :

```bash
bash demo/demo.sh monter
```

`monter` contrôle d'abord (rien n'est créé en cas de refus) : prérequis, paramètres, port
libre, objets `demo-blaze-*` absents, empreinte du jeu, présence de `nginx/nginx.conf`
(la configuration que monte la démonstration, suivie par git). Puis il construit le jar du module
(`build/construire-jar.sh`, expliqué dans [`docs/construction.md`](../docs/construction.md)),
démarre les deux conteneurs, attend `200` sur `/fhir/metadata` et charge le jeu. Il finit par
`Prêt : http://127.0.0.1:18090/fhir`.

Durée : `metadata` rend `200` en 120 s au plus, **images en cache local** (relevé du
2026-10-17 : 26 s au pire, 3 essais, commande entière). Le premier tirage des images (ordre de
grandeur ~760 Mo) n'est **pas mesuré** et s'ajoute.

### Interroger

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18090/fhir/metadata
curl -s http://127.0.0.1:18090/fhir/metadata | jq '[.rest[0].operation[]?.name | select(test("^viewdefinition-(run|export)$"))] | length'
curl -s 'http://127.0.0.1:18090/fhir/Patient?_summary=count' | jq .total
curl -s -o /dev/null -w '%{http_code}\n' 'http://127.0.0.1:18090/fhir/Observation?_count=1&_include=Observation:patient&_include=Observation:patient&_include=Observation:patient&_include=Observation:patient&_include=Observation:patient&_include=Observation:patient'
```

Réponses attendues : `200` ; `2` (les opérations `$viewdefinition-run` et
`$viewdefinition-export` du module) ; `100` ; `400` (plus de 5 `_include` : refus de nginx).
Les exemples de [`README.md`](../README.md) s'y jouent avec `FHIR=http://127.0.0.1:18090/fhir`.

### Arrêter

```bash
bash demo/demo.sh arreter
```

Retire les conteneurs `demo-blaze-blaze` et `demo-blaze-nginx`, le réseau
`demo-blaze-reseau` et le volume `demo-blaze-donnees` — eux seuls. Tout ce qui a été chargé ou
écrit est perdu. Après un `monter` en échec (codes 5 ou 8 une fois les conteneurs démarrés),
`arreter` avant de relancer.

### Paramètres

Chaque variable se règle à l'appel de `monter` (`NOM=valeur bash demo/demo.sh monter`). La
liste de ce que lit la démonstration :

```bash
bash demo/demo.sh parametres
```

| Variable | Rôle | Défaut |
|---|---|---|
| `DEMO_PORT` | port de l'hôte, sur `127.0.0.1` seulement, où répond nginx ; fixe aussi `BASE_URL` de Blaze | `18090` |
| `DEMO_ATTENTE_SECONDES` | délai maximal d'attente de `200` sur `/fhir/metadata` (au-delà : code 5) | `180` |
| `DEMO_BLAZE_MEMOIRE` | plafond mémoire du conteneur Blaze, entier suivi de `m` ou `g` (sinon code 2) | `1536m` |
| `DEMO_BLAZE_XMX` | tas Java de Blaze (`-Xmx`) | `768m` |
| `DEMO_JAR` | jar du module monté devant le jar de Blaze ; fichier absent : code 3 | `build/blaze-addon.jar` (construit par `monter`) |
| `DEMO_EXPORT_MIO` | taille en Mio de la tmpfs `/app/export` (exports du module) ; entier > 8 et ≤ `DEMO_BLAZE_MEMOIRE`, sinon code 2 ; compte dans la mémoire du conteneur | `128` |
| `DEMO_EXPORT_MAX_BYTES` | **dérivé, ne pas régler** (toute valeur donnée est remplacée) : plafond des exports du module, `(DEMO_EXPORT_MIO − 8) × 1048576` | `125829120` |
| `BLAZE_VERSION` | étiquette de l'image Blaze ; **lue de `versions.env`, ne pas régler** (une variable du shell l'emporterait) | valeur de `versions.env` |
| `BLAZE_IMAGE_DIGEST` | empreinte de l'image Blaze ; **lue de `versions.env`, ne pas régler** | valeur de `versions.env` |
| `NGINX_VERSION` | étiquette de l'image nginx ; **lue de `versions.env`, ne pas régler** | valeur de `versions.env` |
| `NGINX_IMAGE_DIGEST` | empreinte de l'image nginx ; **lue de `versions.env`, ne pas régler** | valeur de `versions.env` |

| Port publié | Rôle | Défaut |
|---|---|---|
| `127.0.0.1:${DEMO_PORT}:8090` | nginx (port 8090 du conteneur) publié sur l'interface locale de l'hôte | `127.0.0.1:18090` |

### Données

`demo/donnees/` : 100 patients **synthétiques** générés par Synthea (aucune donnée réelle),
un `Bundle` FHIR R4 JSON par fichier, plus deux bundles d'organisations et de praticiens.
`demo/donnees/provenance.txt` consigne l'empreinte du jeu, `SYNTHEA_VERSION` et
`SYNTHEA_JAR_SHA256` (ceux de `versions.env`), la configuration (`demo/synthea.properties`) et
les paramètres (population, graines, date de référence). Le jeu est chargé tel quel ; une
empreinte différente de la provenance est refusée avant tout envoi (code 8).

Régénérer le jeu (remplace `demo/donnees/` et sa provenance, seulement si les contrôles du jeu
passent ; le jar de Synthea d'une autre empreinte est refusé, code 8) :

```bash
bash demo/generer-jeu.sh
```

La régénération n'est pas garantie identique à l'octet : l'empreinte consignée, non la
graine, fait foi.

### Refus et codes de sortie de `demo/demo.sh`

| Code | Cause |
|---|---|
| `0` | succès |
| `2` | usage : sous-commande inconnue, `DEMO_PORT` non entier, `DEMO_BLAZE_MEMOIRE` mal formé, `DEMO_EXPORT_MIO` non entier, ≤ 8 ou supérieur à `DEMO_BLAZE_MEMOIRE` |
| `3` | prérequis absent, `nginx/nginx.conf` absent, `DEMO_JAR` absent, dossier hors git, état de Docker non établi |
| `5` | `/fhir/metadata` ne rend pas `200` dans `DEMO_ATTENTE_SECONDES` |
| `6` | port `DEMO_PORT` déjà à l'écoute, ou objet `demo-blaze-*` déjà présent |
| `8` | empreinte du jeu différente de la provenance, ou bundle refusé au chargement |

Les codes 2, 3, 6 et le refus d'empreinte (8) tombent avant toute création de conteneur, de
réseau ou de volume.

### Limites

- API **ouverte, sans authentification**, publiée sur `127.0.0.1` seulement. Ni frontend, ni
  fournisseur OpenID, ni validateur externe : la composition est celle du chart.
- Les exports du module vivent dans la tmpfs `/app/export` : perdus à l'arrêt. Au-delà de
  `DEMO_EXPORT_MAX_BYTES`, le module refuse l'export (message `The export files would exceed
  the space limit`). La tmpfs compte dans `DEMO_BLAZE_MEMOIRE` : `128` Mio tiennent avec
  `1536m` (mesuré une fois) ; relever `DEMO_EXPORT_MIO` exige de relever `DEMO_BLAZE_MEMOIRE`
  (marge à mesurer).
- Une seule démonstration à la fois par poste : les noms `demo-blaze-*` sont fixes.

---

## English

### What it is for

See the whole stack run on a single workstation, with one command, without a Kubernetes
cluster: Blaze (published image pinned by digest), the module jar placed **in front of** the
Blaze jar on the classpath, and nginx with the chart configuration
(`nginx/nginx.conf`, identical to the chart one, `_include` cap). A set of 100 synthetic patients is
loaded at start-up.

It is a **workstation tool, not a deliverable**: Kubernetes (`kubernetes/chart/`) remains the
only delivery. The demonstration is neither delivered nor supported in operation, and it does
not test the chart (no `restricted` policy, no `NetworkPolicy`, no Ingress).

### Prerequisites

- `bash`, `docker` with `docker compose`, `curl`, `jq`, `ss`, `sha256sum`, `python3`, `git`
  (`demo/demo.sh` refuses, exit code 3, if one is missing).
- The folder is a git repository whose files are tracked: the module jar archives the tracked
  files (otherwise exit code 3); from a published copy, first run the three `git` lines of the
  "Quick start" in [`README.md`](../README.md).
- To regenerate the data set only: `java`, and network access to the Synthea repository on
  GitHub.

### Starting

From the root of the folder:

```bash
bash demo/demo.sh monter
```

`monter` checks first (nothing is created on refusal): prerequisites, parameters, free port,
no `demo-blaze-*` object, data set digest, presence of `nginx/nginx.conf`
(the configuration the demonstration mounts, tracked by git). Then it builds the module jar
(`build/construire-jar.sh`, explained in [`docs/construction.md`](../docs/construction.md)),
starts both containers, waits for `200` on `/fhir/metadata` and loads the data set. It ends
with `Prêt : http://127.0.0.1:18090/fhir`.

Duration: `metadata` returns `200` within 120 s, **images in the local cache** (measured on
2026-10-17: 26 s at worst, 3 runs, whole command). The first pull of the images (about
~760 MB) is **not measured** and comes on top.

### Querying

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18090/fhir/metadata
curl -s http://127.0.0.1:18090/fhir/metadata | jq '[.rest[0].operation[]?.name | select(test("^viewdefinition-(run|export)$"))] | length'
curl -s 'http://127.0.0.1:18090/fhir/Patient?_summary=count' | jq .total
curl -s -o /dev/null -w '%{http_code}\n' 'http://127.0.0.1:18090/fhir/Observation?_count=1&_include=Observation:patient&_include=Observation:patient&_include=Observation:patient&_include=Observation:patient&_include=Observation:patient&_include=Observation:patient'
```

Expected answers: `200`; `2` (the module operations `$viewdefinition-run` and
`$viewdefinition-export`); `100`; `400` (more than 5 `_include`: refused by nginx). The
examples of [`README.md`](../README.md) run against it with `FHIR=http://127.0.0.1:18090/fhir`.

### Stopping

```bash
bash demo/demo.sh arreter
```

Removes the containers `demo-blaze-blaze` and `demo-blaze-nginx`, the network
`demo-blaze-reseau` and the volume `demo-blaze-donnees` — and nothing else. Everything loaded
or written is lost. After a failed `monter` (exit codes 5 or 8 once the containers have
started), run `arreter` before trying again.

### Parameters

Each variable is set when calling `monter` (`NAME=value bash demo/demo.sh monter`). The list
of what the demonstration reads:

```bash
bash demo/demo.sh parametres
```

| Variable | Role | Default |
|---|---|---|
| `DEMO_PORT` | host port, on `127.0.0.1` only, where nginx answers; also sets Blaze's `BASE_URL` | `18090` |
| `DEMO_ATTENTE_SECONDES` | maximum wait for `200` on `/fhir/metadata` (beyond: exit code 5) | `180` |
| `DEMO_BLAZE_MEMOIRE` | memory limit of the Blaze container, integer followed by `m` or `g` (otherwise exit code 2) | `1536m` |
| `DEMO_BLAZE_XMX` | Blaze Java heap (`-Xmx`) | `768m` |
| `DEMO_JAR` | module jar mounted in front of the Blaze jar; missing file: exit code 3 | `build/blaze-addon.jar` (built by `monter`) |
| `DEMO_EXPORT_MIO` | size in MiB of the `/app/export` tmpfs (module exports); integer > 8 and ≤ `DEMO_BLAZE_MEMOIRE`, otherwise exit code 2; counted in the container memory | `128` |
| `DEMO_EXPORT_MAX_BYTES` | **derived, do not set** (any given value is replaced): cap of the module exports, `(DEMO_EXPORT_MIO − 8) × 1048576` | `125829120` |
| `BLAZE_VERSION` | Blaze image tag; **read from `versions.env`, do not set** (a shell variable would take precedence) | value in `versions.env` |
| `BLAZE_IMAGE_DIGEST` | Blaze image digest; **read from `versions.env`, do not set** | value in `versions.env` |
| `NGINX_VERSION` | nginx image tag; **read from `versions.env`, do not set** | value in `versions.env` |
| `NGINX_IMAGE_DIGEST` | nginx image digest; **read from `versions.env`, do not set** | value in `versions.env` |

| Published port | Role | Default |
|---|---|---|
| `127.0.0.1:${DEMO_PORT}:8090` | nginx (container port 8090) published on the host loopback interface | `127.0.0.1:18090` |

### Data

`demo/donnees/`: 100 **synthetic** patients generated by Synthea (no real data), one FHIR R4
JSON `Bundle` per file, plus two bundles of organizations and practitioners.
`demo/donnees/provenance.txt` records the data set digest, `SYNTHEA_VERSION` and
`SYNTHEA_JAR_SHA256` (those of `versions.env`), the configuration (`demo/synthea.properties`)
and the parameters (population, seeds, reference date). The set is loaded as is; a digest
that differs from the provenance is refused before anything is sent (exit code 8).

Regenerating the data set (replaces `demo/donnees/` and its provenance, only if the data set
checks pass; a Synthea jar with another digest is refused, exit code 8):

```bash
bash demo/generer-jeu.sh
```

Regeneration is not guaranteed byte-identical: the recorded digest, not the seed, is
authoritative.

### Refusals and exit codes of `demo/demo.sh`

| Code | Cause |
|---|---|
| `0` | success |
| `2` | usage: unknown sub-command, non-integer `DEMO_PORT`, malformed `DEMO_BLAZE_MEMOIRE`, `DEMO_EXPORT_MIO` not an integer, ≤ 8 or above `DEMO_BLAZE_MEMOIRE` |
| `3` | missing prerequisite, missing `nginx/nginx.conf`, missing `DEMO_JAR`, folder outside git, Docker state not established |
| `5` | `/fhir/metadata` does not return `200` within `DEMO_ATTENTE_SECONDES` |
| `6` | port `DEMO_PORT` already listening, or a `demo-blaze-*` object already present |
| `8` | data set digest differs from the provenance, or a bundle refused while loading |

Codes 2, 3, 6 and the digest refusal (8) occur before any container, network or volume is
created.

### Limits

- **Open API, without authentication**, published on `127.0.0.1` only. No frontend, no
  OpenID provider, no external validator: the composition is that of the chart.
- Module exports live in the `/app/export` tmpfs: lost on stop. Beyond
  `DEMO_EXPORT_MAX_BYTES`, the module refuses the export (message `The export files would
  exceed the space limit`). The tmpfs counts against `DEMO_BLAZE_MEMOIRE`: `128` MiB fit with
  `1536m` (measured once); raising `DEMO_EXPORT_MIO` requires raising `DEMO_BLAZE_MEMOIRE`
  (margin to be measured).
- One demonstration at a time per workstation: the `demo-blaze-*` names are fixed.
