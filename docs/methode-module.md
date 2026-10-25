# Méthode de développement du module complémentaire

<!-- FICHIER VIVANT. Tenu par `architect`. Sources : ADR 0003 (cadre), ADR 0004 (poste),
     `docs/Architecture moteur SQL on FHIR pour Blaze.md` (stratégies, plan, 2026-10-08). -->

Ce fichier ne répète pas le document d'architecture : il dit **ce qui s'applique ici** et
**ce qui change** du fait de l'ADR 0003. Les stratégies `S-xx`, les jalons `J0…J5` et les 88
opérations (`FP-01`, `VE-07`…) y restent définis une seule fois.

## 1. La procédure, en cinq règles

1. **Test en échec d'abord, puis le code** — montré en échec. Une tâche dont le compte rendu
   n'a pas la sortie rouge est `à reprendre`.
2. **La stratégie `S-xx` de l'opération fixe l'ordre des étapes** (document d'architecture,
   « Stratégies de développement »). La tâche cite sa stratégie ; l'exécutant ne la réécrit pas.
3. **Style de code** : `amont/blaze/AGENTS.md` (au tag) — fonctions pures, anomalies et non
   exceptions, spécifications avec `:ret` dans des fichiers `spec`/`-spec`, avertissements
   de réflexion, fixture `tu/fixture`, macro `given`.
4. **Qualité avant de rendre** : `make -C modules/<nom> fmt lint test`, puis
   `make -C modules/<nom> test-coverage` (seuil 95 %, `tests.edn`), puis `make check`.
5. **Un commit par tâche** : `F-xxx T-xxx <code> <intitulé>` (le garde-fou 5 exige le `F-xxx`).

## 2. Écarts au document d'architecture (ADR 0003)

| Le document dit | Ici |
|---|---|
| Nouveau module `modules/<nom>` **dans Blaze**, `deps.edn` racine amont, matrice `build.yml` | Module dans **ce** dépôt (`modules/sql-on-fhir`), livré par `build/Dockerfile` ; aucune CI amont |
| « Étendre `modules/fhir-path` » (décision 4), tests dans `fhir_path_test.clj` | Étendre **depuis le module**, par `defmethod function-expression` et `extend-protocol FPCompiler` (ou `extend` par fusion), registre `docs/impacts-blaze.md` ; tests dans `modules/sql-on-fhir/test/…/fhir_path/`. Jamais d'édition d'un fichier de Blaze |
| `eval` : « ajouter une arité qui accepte les constantes » | **Impossible sans éditer Blaze.** Le module appelle `-eval` (protocole) avec un contexte `{:resolver … :constants …}` ; l'arité 3 de `eval` n'est pas touchée (A7) |
| `make -C modules/fhir-path test` puis `modules/db test` après chaque ajout | Suites amont jouées **contre jar + module** : `make -C modules/sql-on-fhir test-amont` (T-005) — `fhir-path` à 0 échec ; `db` privé de la liste d'exclusion `L`, de la liste des instables `I` et de la liste des absences `A` à 0 échec, `L`, `I` et `A` contrôlées ; relevés complets jar seul / jar + module égaux, hors `A` (F-004 « Indexation inchangée » (a)–(d) ; `test-amont` joue (a), (b), (d) ; `test-amont-controle` joue (c) ; `L` = `modules/sql-on-fhir/exclusions/db.txt`, T-015 ; `I` = `exclusions/db-instables.txt`, preuves `db-instables.preuves`, `make test-amont-controle SORTIE=<préfixe du relevé>`, T-016 ; sorties des preuves 2 régénérées depuis un clone neuf par `make test-amont-instables` (T-026) ; `A` = `exclusions/db-absences.txt` (tests amont dont l'attendu est l'absence d'une construction ajoutée, sautés en (b) seul), preuves `db-absences.preuves`, `controle-absences.sh`, `exiger-diff-d.sh`, T-009 ; `docs/impacts-blaze.md`, limites) |
| `INF-01` : module vide + CI amont | Module vide **ici** : fait par F-003 (`make -C modules/sql-on-fhir test` vert sur le canari) |
| `INF-04` : Clojure installé, `make build-ig`, tests fhir-path | `make outils` ; `make build-ig` et les tests fhir-path restent des gestes de référence **amont** |
| `SPK-03` : valider nom et portée avec les mainteneurs (#1543) | Facultatif — pas de contribution ; le nom du module est décidé ici |
| Une issue GitHub par opération ; `Closes: #n` | Une tâche `T-xxx` par opération (champ **Code plan**) ; pas de `Closes:` |
| Orchestrateur / Exécutant / Vérificateur | `architect` (planifie, lance, juge) / exécutant de la tâche / `reviewer` + `testeur` (jamais l'exécutant) |
| Statuts `À faire … Fait` | Cycle du §3 de `CLAUDE.md` (`planifié → en cours → à valider → validé`) ; `Fait` = `accepté` au journal |
| Commit « sans mention d'outil ni de coauteur IA » | Les trailers de commit suivent la configuration de la session ; sans objet pour une contribution amont, qui n'existe plus |

## 3. Quel niveau éprouve quoi

| Niveau | Dans le module | Lancé par |
|---|---|---|
| **U** — le module fait-il ce que sa spécification dit, en mémoire ? | `modules/sql-on-fhir/test/`, kaocha ; toutes les stratégies `S-FN`, `S-OPR`, `S-PUR`, `S-SER` | `make test` |
| **N0** | `scripts/check_module.py` (ADR 0003), `check_versions.py` | `make check` (construit d'abord le jar du module, `make jar`, puis les contrôles ; dans le dépôt de travail, il ajoute les contrôles de `controles-internes.mk`, absent d'une copie publiée) |
| **N1** | l'image démarre avec le module (`make image`), l'opération est déclarée dans `/fhir/metadata` | démarrage de l'image (`make image`) puis `GET /fhir/metadata` |
| **N2** | `TS-01`, `TS-02`, `S-ADP` sur base chargée | `/test` (`architect`) |
| **N3** | suite de conformité SQL on FHIR, `modules/sql-on-fhir/conformance/` (144 cas) | `conformance` |
| **N4** | `TS-03` débit et mémoire | `perf-engineer` |

## 4. Organisation des sources

```
modules/sql-on-fhir/
  src/blaze/addon/sql_on_fhir/
    fhir_path/   function.clj  operator.clj  variable.clj  constant.clj  unsupported.clj   FP-*
    view/        validate.clj  compile.clj  run.clj  schema.clj (la vue reste une map)     VE-*
    data_source.clj                                                                          DS-*
    operation/   run.clj  export.clj  params.clj  outcome.clj                                OP-*, AS-*
    serializer/  ndjson.clj  json.clj  csv.clj  parquet.clj  file.clj                        SR-*
    export/      job.clj  storage.clj                                                        AS-*
  test/                       même arbre, suffixe _test.clj ; spécifications internes ici
  conformance/                sql-on-fhir-js/ : suite SQL on FHIR copiée, licence et provenance (ADR 0006) ; INF-02
  resources/blaze.edn         overlay généré (fonctionnalité à interrupteur)
```

Un fichier ne porte qu'une famille d'opérations : deux opérations qui modifient le même
fichier ne se lancent **pas** en parallèle (règle 2 de l'orchestrateur du document
d'origine) ; deux fichiers distincts, si. Le nom du fichier est le code du domaine, donc
`docs/couverture.md` se tient par domaine.

## 5. Pratiques retenues

- **REPL** : Calva, séquence « module (jar publié) » (`.vscode/settings.json`) ; évaluer, puis
  figer le comportement dans un test. Le REPL ne remplace pas le test en échec d'abord.
- **Une extension globale se prouve locale** : tout `defmethod`/`extend-protocol` sur Blaze
  est livré avec un test qui montre que le cas existant est inchangé, et inscrit **avant**
  livraison au registre `docs/impacts-blaze.md` (ADR 0003, amendement du 2026-10-09). Ajouter
  une méthode absente à une classe déjà implémentée se fait par `extend` **en fusion** avec
  `(get-in proto [:impls cls])` : `extend` seul remplace la map entière.
- **Un contrôle se mesure à sa capacité à rendre rouge** (§3.6 règle 2) : chaque contrôle
  ajouté à `check_module.py` ajoute son témoin à `--test`.
- **Jar d'abord** : en cas de doute sur un comportement de Blaze, on l'exécute contre le jar
  (`clojure -M:publie -e …`), on ne le déduit pas de la lecture de `amont/`.
