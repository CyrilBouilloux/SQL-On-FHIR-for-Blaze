# CQL sur fhir-platform

Le serveur d'API évalue du CQL (Clinical Quality Language) grâce au module
**Clinical Reasoning de HAPI**. Ce dépôt n'y ajoute que l'option qui l'active et
un script d'appel, `scripts/cql.sh` (F-115).

| | Docker (poste) | Kubernetes (cluster) |
|---|---|---|
| Option | `HAPI_CR_ENABLED`, **`false`** par défaut | `runtime.HAPI_CR_ENABLED`, **`true`** |
| Cible du script | `-e <environnement>` | `-k [-n <namespace>]`, via `svc/proxy` |

## Activer

**Docker.** Ajoutez la clé au `.env` de l'environnement, puis redémarrez-le :

```bash
echo "HAPI_CR_ENABLED=true" >> environments/<nom>/.env
cd environments/<nom> && ./arreter && ./demarrer
```

**Kubernetes.** Le chart l'active déjà. Pour l'éteindre, passez
`--set runtime.HAPI_CR_ENABLED=false` au `helm upgrade` : les pods HAPI
redémarrent d'eux-mêmes.

Pour vérifier :

```bash
scripts/cql.sh -e <nom> expr "1"     # return = 1 ; rc 8 si le module est éteint
```

## Le script

```
scripts/cql.sh CIBLE COMMANDE [options]      # CIBLE : -e NOM | -k [-n NS] | -u URL
  expr "EXPRESSION" [-s SUJET]                # $cql
  run FICHIER.cql [-s SUJET]                  # $cql, bibliothèque non stockée
  publier FICHIER.cql                         # PUT Library/<nom de l'en-tête>
  charger FICHIER.json                        # PUT ressource, ou POST Bundle transaction
  library NOM -s SUJET [-x EXPRESSION]        # Library/$evaluate
  mesure ID -s SUJET --debut D --fin D [--rapport subject|population]
```

Le script affiche un résumé (`nom = valeur`), ou la réponse FHIR brute avec
`--json`. Ses codes de sortie sont ceux de `lib/codes.sh` : **0** succès,
**2** usage, **4** environnement inconnu, **5** serveur injoignable,
**8** erreur CQL ou HTTP (le diagnostic s'affiche sur la sortie d'erreur).

## Exemples

Les exemples suivants ont été joués le 2026-10-06 sur Docker (`-e cql`) et sur
le banc Kubernetes (`-k`), avec des résultats identiques. Ils utilisent les
fichiers de [`docs/cql/`](cql/).

```bash
C="scripts/cql.sh -e <nom>"        # ou : C="scripts/cql.sh -k"

# 0. Données de démonstration : Patient/cql-demo (femme née en 1960, diabète de
#    type 2, HbA1c à 9,2 %) et Group/cql-demo
$C charger docs/cql/demo-patient.json

# 1. Une expression seule
$C expr "days between @2026-01-01 and @2026-10-06"         # return = 278
$C expr "AgeInYears()" -s Patient/cql-demo                 # return = 66

# 2. Une bibliothèque entière, sans la stocker
$C run docs/cql/DiabeteNonEquilibre.cql -s Patient/cql-demo
#   Diabetique = true
#   Valeur HbA1c = {"value": 9.2, "unit": "%", ...}
#   Non equilibre = true

# 3. La stocker comme Library, puis l'évaluer par son nom
$C publier docs/cql/DiabeteNonEquilibre.cql                # Library/DiabeteNonEquilibre
$C library DiabeteNonEquilibre -s Patient/cql-demo -x "Non equilibre"

# 4. Une mesure de qualité (proportion), sur un patient puis sur un groupe
$C charger docs/cql/Measure-DiabeteNonEquilibre.json
$C mesure DiabeteNonEquilibre -s Patient/cql-demo --debut 2026-01-01 --fin 2026-12-31
$C mesure DiabeteNonEquilibre -s Group/cql-demo  --debut 2026-01-01 --fin 2026-12-31
#   summary · complete · ...
#     g1 : initial-population=1, denominator=1, numerator=1 ; score=1.0
```

**Écrire une bibliothèque.** Le modèle à suivre est
[`DiabeteNonEquilibre.cql`](cql/DiabeteNonEquilibre.cql). Son en-tête
`library <Nom> version '<x.y.z>'` donne l'id de la `Library`.
`FHIRHelpers 4.0.1` est fourni par le serveur. Une `Measure` désigne la
bibliothèque par son URL canonique, `http://fhir-platform.local/Library/<Nom>`,
et chaque `group` et chaque `population` de la `Measure` doit porter un `id`.

## Limites

- **`mesure` exige `-s`.** Sans sujet, le serveur évaluerait toute la base, soit
  4,8 M de `Patient` sur le banc. Pour une population, on évalue un `Group`.
  L'analytique de masse relève du lac (SQL-on-FHIR), pas de l'étage OLTP
  (garde-fou 4).
- **Terminologie.** Une déclaration `valueset` exige que le `ValueSet` soit
  présent sur le serveur, en le chargeant ou par un IG (`FHIR_IG`). Les `code`
  directs, comme dans l'exemple, n'ont pas besoin de `ValueSet`.
- **`Measure/$care-gaps`** est publiée, mais elle répond
  `Organization/default is not known` : il lui faut un rapporteur configuré
  (`hapi.fhir.cr.caregaps.reporter`), que ce dépôt ne pose pas.
- **Opérations publiées sans exemple ici** : `PlanDefinition/$apply`,
  `ActivityDefinition/$apply`, `Questionnaire/$populate`,
  `QuestionnaireResponse/$extract`, `Measure/$data-requirements`,
  `$collect-data`, `$submit-data`, `Library/$data-requirements`. Elles se
  liront dans le `CapabilityStatement` (`GET <base>/metadata`).

Documentation de la brique : <https://hapifhir.io/hapi-fhir/docs/clinical_reasoning/overview.html>.
