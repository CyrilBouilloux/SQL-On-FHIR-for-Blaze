# Ajouter N patients synthétiques à la base courante

Procédure pour porter la base du banc (`http://localhost:8088/fhir`) à un volume choisi,
sans toucher au jeu de référence. Briques : Synthea (génération), `blazectl upload`
(chargement) ; ce dépôt n'écrit que la colle (`bench/datasets/`, ADR 0007).
**Garde-fou §4.4 : données synthétiques exclusivement.**

## Principe

- Un jeu additionnel est un jeu **nommé** : `archives/<nom>.tar`, empreinte dans
  `empreintes.sha256`. Le jeu `reference` (100 patients, figé par `reference.conf`) n'est
  ni régénéré ni remplacé.
- Le jeu est généré **une fois**, archivé, puis chargé **depuis l'archive** (Synthea n'est
  pas déterministe : régénérer ne redonne pas les mêmes octets).
- Ce que l'on ajoute **s'additionne** : la base ne se vide pas, les patients existants
  restent. Une même archive chargée deux fois crée deux fois les patients.

## Prérequis

| Besoin | Vérification |
|---|---|
| Docker (conteneur Synthea) | `docker version` |
| `blazectl` épinglé | `ls .outils/bin/blazectl` — sinon `make outils` |
| Blaze joignable | `curl -sS -o /dev/null -w '%{http_code}\n' http://localhost:8088/fhir/metadata` → `200` |
| Place disque pour le volume RocksDB de Blaze | à mesurer (`df -h` sur le volume du pod Blaze) ; aucun ordre de grandeur relevé ici |

## Étapes

### 1. Relever l'état de départ

```bash
scripts/patient_count_sql.sh                     # nombre de patients (SQL on FHIR)
curl -sS 'http://localhost:8088/fhir/Patient?_summary=count' | jq .total
```

Les deux doivent donner le même nombre ; ce sera la valeur « avant ».

### 2. Générer et archiver le jeu

```bash
bash bench/datasets/generate.sh <nom> <population>
# exemple : bash bench/datasets/generate.sh lot1000 1000
```

- `<nom>` : `a-z`, `0-9`, `_`, `-`. `reference` est refusé (code 4).
- `<population>` : entier ≥ 1.
- La graine des patients est dérivée du nom (`cksum`) : deux noms différents donnent des
  patients différents. La graine des praticiens et la date de référence sont celles de
  `reference.conf`.
- Refus **avant toute écriture** si `archives/<nom>.tar` existe (code 6) : choisir un autre
  nom, ou supprimer l'archive **et** sa ligne de `empreintes.sha256`.
- Sortie : `bench/datasets/out/<nom>/fhir/` (bundles Synthea, un par patient, plus un bundle
  d'organisations et un de praticiens), `bench/datasets/archives/<nom>.tar` (un `.json.bz2`
  par bundle) et une ligne `sha256` ajoutée à `bench/datasets/empreintes.sha256`.
- `out/` et `archives/` sont ignorés par git ; **`empreintes.sha256` est versionné** : la
  ligne ajoutée se commite avec un `F-xxx`.

### 3. Contrôler ce qui a été généré

```bash
python3 -I bench/datasets/compte.py bench/datasets/out/<nom>/fhir
( cd bench/datasets && sha256sum -c empreintes.sha256 )
```

Le compte de `Patient` est à comparer à `<population>` ; tout écart est à noter dans la
tâche (à mesurer : non vérifié ici), pas à corriger à la main.

### 4. Charger

```bash
bash bench/datasets/charger.sh <nom>
# autre serveur : BANC_FHIR_URL=http://hote:8088/fhir bash bench/datasets/charger.sh <nom>
```

- Le script contrôle l'empreinte avant toute écriture (absente : code 3, inconnue : code 4,
  différente : code 8 — rien n'est écrit).
- Chargement `--concurrency 1` : les écritures de Blaze sont sérielles (ADR 0001, B3). Il faut
  compter une durée proportionnelle à la population ; ne pas lancer d'autre écriture ni de
  mesure de performance pendant ce temps.

### 5. Vérifier

```bash
scripts/patient_count_sql.sh
```

Attendu : `avant + <population>`. Un autre résultat est un défaut de chargement : relever le
code de sortie de `charger.sh` et la sortie de `blazectl`, ne pas relancer à l'aveugle (un
second chargement ajouterait un second lot).

## Conséquences à connaître

- **Les mesures de performance changent de conditions.** Un relevé porte sa volumétrie ;
  les latences de `bench/baselines/reference.json` ont été prises à 100 patients. Après un
  gros chargement, `python3 bench/run.py` compare des conditions différentes : lire le
  résultat comme une nouvelle mesure, pas comme une régression, tant que la référence n'est
  pas rejouée (règle et commandes : `bench/baselines/README.md`, tenue par `perf-engineer`).
- **Les cas N2 supposent un banc connu.** Si des cas comptent des ressources, vérifier
  `docs/tests.md` avant de charger sur le banc partagé.
- **Pas de retour arrière prévu par ces scripts** (aucun script de suppression en masse) ;
  pour revenir à l'état précédent, recréer le banc (`scripts/banc.sh detruire` puis
  `monter`), puis recharger `reference`.
- **Search parameters.** Les patients chargés sont indexés à l'écriture avec les paramètres
  actifs au moment du chargement (garde-fou §4.1).

## Revenir à l'état de référence : recréer le banc et recharger `reference`

**Destructif** : `detruire` supprime le cluster kind **et le volume de Blaze** — tous les
patients, ceux de `reference` comme ceux des jeux additionnels. À ne faire qu'en
connaissance de cause (relever d'abord `scripts/patient_count_sql.sh`).

```bash
scripts/banc.sh detruire                         # supprime le cluster (et ses données)
make image                                       # image du module (si absente : monter la refuse)
scripts/banc.sh monter                           # cluster + chart ; attend Blaze
scripts/banc.sh smoke                            # N1 : SYNTHESE succes=7 echec=0 non-branche=0 total=7
bash bench/datasets/charger.sh                   # recharge archives/reference.tar (empreinte contrôlée)
scripts/patient_count_sql.sh                     # attendu : 100 patients du jeu
```

- `BANC_PORT` (défaut 8088) et `BANC_NOM` (défaut `blaze-banc`) valent pour chaque commande
  de `banc.sh` et pour `charger.sh` (`BANC_FHIR_URL`) ; les garder identiques d'une étape à l'autre.
- **Garder `archives/reference.tar`** : c'est le seul exemplaire du jeu (non régénérable à
  l'octet, `bench/datasets/README.md`). `detruire` n'y touche pas ; un `generate.sh` sans
  argument le refuse. Si l'archive manque, `charger.sh` s'arrête (code 3) sans rien écrire.
- Un banc recréé et rechargé n'a plus les mêmes volumes que celui qui a servi aux relevés
  existants : le nombre de patients ne dit pas tout (voir ci-dessus). Pour un banc de
  performance comparable, rejouer la référence selon `bench/baselines/README.md`.
- Sans détruire : `scripts/banc.sh redeployer` redéploie le chart **en conservant les données**.

## Fichiers

| Fichier | Rôle |
|---|---|
| `bench/datasets/generate.sh` | génère et archive (`[<nom> <population>]`) |
| `bench/datasets/reference.conf` | paramètres figés du jeu `reference` |
| `bench/datasets/charger.sh` | contrôle d'empreinte puis `blazectl upload` |
| `bench/datasets/compte.py` | compte les ressources d'un répertoire de bundles |
| `bench/datasets/empreintes.sha256` | empreintes des archives (versionné) |
| `scripts/patient_count_sql.sh` | compte les patients en base |
