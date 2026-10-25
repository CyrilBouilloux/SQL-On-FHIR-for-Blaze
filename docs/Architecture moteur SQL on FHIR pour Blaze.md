# Architecture : moteur SQL on FHIR pour Blaze

Oct 8, 2026 · @marcelo

Un moteur de ViewDefinitions s'ajoute à Blaze sous la forme d'un nouveau module qui lit la base existante à un instant `t` fixe, sans dupliquer les données.

## Synthèse

Blaze ne propose pas SQL on FHIR aujourd'hui : sa liste d'opérations (v1.11) ne contient ni `$viewdefinition-run` ni `$viewdefinition-export`. Le mainteneur a indiqué en 2024 que cette fonction devrait être construite sur le stockage existant, sans duplication des données.

**Principe.** Un noyau pur transforme une ressource en lignes selon une ViewDefinition ; un adaptateur lui fournit les ressources lues dans la base de Blaze à un instant `t` fixe ; une couche API expose les opérations de la spécification.

**Quatre décisions structurantes :**

1. Réécrire le moteur en Clojure plutôt que reprendre Pathling ou Aidbox, dont le traitement est lié à Spark ou à la génération de SQL PostgreSQL.
2. Réutiliser la suite de tests de conformité partagée de la spécification, indépendante du langage.
3. Traiter dès le départ le point de blocage principal : ViewDefinition n'est pas une ressource FHIR R4, et Blaze est en R4.
4. Étendre le module `fhir-path` existant plutôt que d'en créer un second, car il sert déjà à l'indexation des paramètres de recherche.

## Vue d'ensemble

&#91;embedded content: architecture des composants · existant et à développer\]

La légende sous le schéma distingue l'existant, le développement à faire et ce qui reste à vérifier. Le module n'accède à la base que par la source de données.

## Inventaire des composants

Au 2026-10-08, sur 19 composants, 7 existaient déjà, 3 étaient à étendre et 9 à développer. La colonne Statut garde cette nature (`scripts/avancement.py` la lit) ; ce que F-006 (`validé` le 2026-10-12) et F-007 (`validé` le 2026-10-13) ont livré est dans la colonne Remarque.

| Couche | Composant | Statut | Rôle | Remarque |
| --- | --- | --- | --- | --- |
| API | API FHIR de Blaze | Existant | Reçoit les requêtes, publie le CapabilityStatement | Opérations actuelles : `$cql`, `$evaluate-measure`, `$everything`… |
| API | Requêtes asynchrones | À étendre | Répond 202 puis permet d'interroger l'avancement (`__async-status`) | L'export a ses routes `__export/{status,result,file}/<jeton>` (202, 303, 200) ; `__async-status` d'une tâche d'export rend 404 (ADR 0008 R2) ; Blaze non étendu (F-007) |
| API | `$viewdefinition-run` | À développer | Exécute une vue et renvoie les lignes | Au niveau système en mode R4 ; fait (F-006) |
| API | `$viewdefinition-export` | À développer | Exporte les vues en NDJSON, CSV ou Parquet | Asynchrone ; fait (F-007) en NDJSON, CSV, JSON ; Parquet : F-008 |
| API | Déclaration des opérations | À étendre | Annonce les nouvelles opérations dans `/fhir/metadata` | Les clients découvrent les capacités par ce point d'entrée ; fait (F-006, F-007) par l'overlay `blaze.edn` |
| Moteur | Modèle ViewDefinition | À développer | Lit et valide la vue : colonnes, types, constantes | ViewDefinition n'est pas une ressource R4 |
| Moteur | Compilateur de vue | À développer | Transforme `select`, `forEach`, `forEachOrNull`, `unionAll` et `where` en une fonction ressource vers lignes | Noyau pur, sans accès à la base |
| Moteur | Évaluateur FHIRPath | À étendre | Évalue les expressions des colonnes et du `where` | Existe dans modules/fhir-path mais ne couvre que 5 des 12 fonctions de la suite de conformité : voir Travaux 1 |
| Moteur | Types FHIR internes | Existant | Ressources en maps Clojure, primitifs accessibles comme types système FHIRPath | Sert à typer les colonnes |
| Moteur | Sérialiseurs de lignes | À développer | NDJSON, CSV, JSON, puis Parquet | Fait (F-006) pour NDJSON, CSV, JSON ; Parquet : `parquet-hadoop` choisi (ADR 0005), F-008 |
| Moteur | Gestion des exports | À développer | Suit les tâches et conserve les fichiers de résultat | Fait (F-007) : `Task` de l'ordonnanceur de Blaze ; fichiers sur le PVC `<release>-export` (ADR 0008) |
| Moteur | Planificateur de lecture | À développer | Traduit patient, `_since` et filtres simples en accès aux index | Optionnel dans la première version |
| Stockage | Source de données | À développer | Lit les ressources d'un type à un `t` fixe, en flux | Seul composant qui s'adresse à la base ; fait (F-006) |
| Stockage | Valeur de base à `t` | Existant | Instantané cohérent de la base | Déjà utilisé pour la pagination des recherches |
| Stockage | Index des versions et du temps | Existant | Liste par type (`ResourceAsOf`, `TypeAsOf`), conversion d'un instant en `t` (`TByInstant`) |  |
| Stockage | Index de recherche et compartiments | Existant | Filtres par paramètre, ressources d'un Patient |  |
| Stockage | Document store | Existant | Contenu des ressources, en CBOR |  |
| Tests | Suite de conformité SQL on FHIR | Existant | Cas JSON partagés : vue, ressources, lignes attendues | Dépôt `FHIR/sql-on-fhir.js` |
| Plus tard | Moteur SQL embarqué | À développer | Exécute `$sqlquery-run` sur les tables de vues | Phase ultérieure |

## Parcours d'une requête

&#91;embedded content: traitement de $viewdefinition-run · 6 étapes, 2 sorties\]

Le `t` est fixé une seule fois au départ : toutes les lignes viennent du même instantané de la base, que le résultat soit renvoyé en flux ou écrit dans un fichier.

## Points d'appui dans le stockage de Blaze

Chaque besoin du moteur trouve un mécanisme déjà présent dans Blaze ; seule la lecture des ressources en flux reste à écrire.

| Besoin du moteur | Mécanisme existant | Adaptation à prévoir |
| --- | --- | --- |
| Lire toutes les ressources d'un type | Index `ResourceAsOf` et `TypeAsOf`, parcourus par type | Fait (F-006) : `d/type-list` puis `d/pull-many` par paquets de 100, lus à la demande |
| Obtenir un export cohérent | Valeur de base immuable identifiée par `t` | Fait (F-007) : `t` de la requête rangé dans la `Task`, base de la tâche synchronisée à ce `t` |
| Appliquer un `where` simple | Requête par type (`type-query`) : scan d'un index, puis vérification des autres clauses | Reconnaître quelques motifs (égalité sur un code) ; le reste est filtré en FHIRPath sur les ressources lues |
| Restreindre à un patient | Index de compartiment (`CompartmentResourceType`) | Fait (F-006) : `d/compartment-type-list`, `d/compartment-query` |
| Filtrer avec `_since` | `TByInstant` convertit un instant en `t` | Fait (F-006) : requête `_lastUpdated=gt<instant>`, sans `TByInstant` ; créées ou modifiées strictement après |
| Ignorer les ressources supprimées | Une valeur de base ne contient pas les ressources supprimées avant son `t` | Aucune |
| Obtenir le contenu des ressources | Document store en CBOR, puis types internes | Aucune |

## Travaux 1 : étendre l'évaluateur FHIRPath

Le module `modules/fhir-path` de Blaze existe et sait déjà analyser toute la grammaire FHIRPath (ANTLR), mais il n'évalue que 5 des 12 fonctions utilisées par la suite de conformité, et aucun des opérateurs `or`, de comparaison ou arithmétiques. Chaque ajout ci-dessous suit le même schéma : test en échec d'abord dans `test/blaze/fhir_path_test.clj`, puis un `defmethod function-expression` (fonctions) ou une méthode du protocole `FPCompiler` (opérateurs, variables).

| Élément | Exemple de la suite | Comportement attendu | Comment l'ajouter |
| --- | --- | --- | --- |
| `empty()` | `name.empty()` | Vrai si la collection est vide | `defmethod function-expression "empty"` renvoyant un booléen singleton |
| `not()` | `active.not()` | Négation, vide si l'entrée est vide | `defmethod` « not » avec `singleton` et `convert` en booléen |
| `join([sep])` | `name.given.join(' ')` | Concatène les chaînes avec le séparateur | `defmethod` « join », séparateur optionnel évalué une fois |
| `getResourceKey()` | `getResourceKey()` | Identifiant stable de la ressource | `defmethod` lisant `:id` de la ressource courante |
| `getReferenceKey([type])` | `subject.getReferenceKey(Patient)` | Clé de la ressource référencée, vide si le type diffère | Réutiliser `split-literal-ref` du module |
| `lowBoundary()` / `highBoundary()` | `birthDate.lowBoundary()` | Borne basse ou haute d'une date partielle | Calcul propre au module sur les types `java.time` lus par Blaze (`date-time-lower-bound`/`-upper-bound` rendent des secondes d'époque, non réutilisables) |
| `or` | `a or b` | Logique à trois valeurs | Calquer le motif du `and` existant |
| `<` `<=` `>` `>=` | `value > 5` | Comparaison, vide si les types sont incompatibles | Étendre `EqualityExpression` et la comparaison de types système |
| `+ - * /` numériques | `a * 2` | Arithmétique sur nombres et quantités | Compléter le `case` d'`AdditiveExpression` (limité aujourd'hui à `+`) et ajouter la multiplication |
| `$this` | `where($this > 3)` | Renvoie la collection courante | Méthode `FPCompiler` pour `ThisInvocationContext` qui retourne `coll` |
| `%nom` | `%threshold` | Valeur de la constante de la vue | Nouvelle clé `:constants` dans le contexte d'`eval` ; méthode pour `ExternalConstantTermContext` |
| `%rowIndex` | `%rowIndex` | Indice de ligne dans `forEach` | Constante réservée alimentée par le moteur de vue |
| Constructions non gérées | Syntaxe hors sous-ensemble | Erreur explicite | Renvoyer une anomalie `unsupported` au lieu de l'erreur générique actuelle |

**Constantes typées.** La spécification admet 18 types de constantes en R4 (`ViewDefinition.constant.value[x]` de l'IG `2.1.0-pre`, 19 types dont `integer64`, absent de R4 ; chaîne, entier et booléen compris — F-004, ADR 0005). Il faut une conversion de la valeur JSON de la vue vers le type système FHIRPath correspondant, une fonction par type, testée séparément.

**Précautions.**

- Le même module sert à l'indexation des paramètres de recherche : garder l'arité 3 d'`eval` inchangée et ajouter une arité qui accepte les constantes.
- Lancer `make -C modules/fhir-path test` puis `make -C modules/db test` après chaque ajout.
- Les noms exacts des classes de contexte ANTLR (par exemple pour `$this`) sont à confirmer dans le jar généré avant d'écrire les méthodes.

## Travaux 2 : le moteur de vue

Le moteur est une fonction pure : elle prend une ViewDefinition et un flux de ressources, et renvoie des lignes. Il ne connaît ni Datomic ni RocksDB, ce qui le rend testable en mémoire. Il vit dans un nouveau module `modules/<nom>` (nom à valider avec les mainteneurs), à déclarer dans le `deps.edn` racine et dans la matrice `module` de `.github/workflows/build.yml`.

| Fonctionnalité | Cas de la suite | Comment la faire |
| --- | --- | --- |
| `where` | 8 | Filtrer chaque ressource avec les expressions FHIRPath compilées, toutes devant être vraies |
| `select` et `column` | 11 colonnes de base, 4 collections, 9 FHIRPath, 6 combinaisons | Une colonne = un chemin compilé ; une collection renvoie un tableau, sinon une valeur unique ou une erreur |
| `forEach` et `forEachOrNull` | 13 | Itérer sur le résultat du chemin ; `forEachOrNull` produit une ligne de nulls si le résultat est vide |
| `repeat` | 19 | Parcours récursif avec un ensemble de chemins, sans doublon |
| `unionAll` | 10 | Concaténer les lignes de chaque branche, colonnes identiques exigées |
| `constant` | 8 plus 14 au niveau fonction | Convertir les valeurs en constantes typées (Travaux 1) |
| `%rowIndex` | 9 | Passer l'indice de ligne dans le contexte d'évaluation |
| Validation de la vue | 5 | Rejeter les vues invalides avec une anomalie `incorrect` |
| Ressource de la vue | 3 | Vérifier le type de ressource ciblé |
| Nombres | 1 | Arrondi et représentation |

Au total : 120 cas au niveau du moteur et 24 au niveau des fonctions, soit 144.

**Comment.**

- Compiler chaque expression FHIRPath une seule fois avec `fhir-path/compile` au moment de l'analyse de la vue, puis réutiliser le résultat pour toutes les ressources.
- Exposer une fonction `run` pure, ou un transducteur, qui pourra être alimenté par n'importe quelle source de ressources.
- Signaler les erreurs (vue invalide, expression non supportée) par des anomalies, jamais par des exceptions.
- Écrire un banc de tests qui lit les fichiers JSON de la suite et compare les lignes obtenues aux lignes attendues, en suivant `tests.schema.json` pour l'ordre des lignes.
- Vérifier la licence de `FHIR/sql-on-fhir.js` avant de copier les fichiers de tests dans le dépôt.

## Travaux 3 : source de données et opérations

Cette partie relie le moteur à la couche de persistance de Blaze et expose les opérations HTTP.

| Élément | Comment le faire |
| --- | --- |
| Adaptateur de source | Utiliser `blaze.db.api` : `d/db` pour obtenir une vue stable, `d/type-list` pour parcourir un type, `d/type-query` avec `compile-type-query` et `execute-query` quand la vue a une clause `where` traduisible en recherche, `d/compartment-type-list` pour un patient, `d/new-batch-db` et `d/pull-many` pour charger les ressources. Lire les signatures exactes dans `modules/db/src/blaze/db/api.clj`. Fait (F-006) sans `d/new-batch-db` ni `compile-type-query` : le `where` reste évalué en FHIRPath (`DS-08` à faire). |
| Opération synchrone | Prendre pour modèle `modules/operation-cql` : composant Integrant avec `ig/init-key` et `m/pre-init-spec`, handler au niveau système, enregistrement dans `resources/blaze.edn` derrière une variable d'environnement de type `:toggle`. Fait (F-006) : overlay généré, `DISABLE_VIEWDEFINITION_RUN`. |
| Documentation | Documenter la variable dans `docs/deployment/environment-variables.md` et l'opération sous `docs/api/`. Ces pages n'existent pas dans ce dépôt : variables dans `docs/configuration.md`, usage et écarts à l'IG dans `docs/manuel.md`. |
| Type ViewDefinition en R4 | La ressource n'est pas dans le modèle R4. Décidé (ADR 0005, option A) : le modèle s'étend pour l'analyse seulement, par un dépôt de StructureDefinition enveloppé ; vues en ligne (`viewResource`), jamais stockées — amendé le 2026-10-13 : vue rangée en `Library`, désignée par canonique (ADR 0009 ; mise en œuvre F-011, `validé` le 2026-10-14). |
| Export asynchrone | Fait (F-007, ADR 0008) : `job-scheduler` de Blaze (`Task`, gestionnaire `:blaze.job/viewdefinition-export`), sans `job-async-interaction` ; fichiers sur un volume dédié ; 202 puis 303 servis par les routes propres du module. |
| Sérialiseurs | NDJSON et JSON avec jsonista, CSV ensuite ; Parquet plus tard. Fait (F-006) pour NDJSON, JSON, CSV ; Parquet : F-008. |

**Ordre conseillé.** Source de données et opération synchrone d'abord, export asynchrone ensuite, car il dépend d'un stockage qui n'existe pas encore.

## Méthode de réalisation

Les règles du fichier `AGENTS.md` de Blaze s'appliquent à chaque changement :

- Une issue GitHub par changement, créée avant d'écrire du code.
- Développement dirigé par les tests : le test en échec d'abord, puis le code.
- Chaque fonction publique a une spécification avec `:ret` ; les erreurs sont des anomalies.
- Les spécifications vont dans des fichiers `spec` et `-spec` dédiés, jamais dans l'espace de noms d'implémentation.
- Avertissements de réflexion activés dans tout espace de noms qui fait de l'interopérabilité Java.
- Les tests utilisent la fixture standard, `st/instrument` et la macro `given`.
- Avant de terminer : `make fmt`, `make lint`, `make -C modules/<module> test`, puis `make test-coverage` avec au moins 95 % des formes couvertes.
- Un nouveau module est ajouté à la matrice de la CI.
- Un seul commit par issue, intitulé comme l'issue, avec `Closes: #n` dans le corps.

## Plan d'exécution : codification et suivi

Cette partie transforme les travaux ci-dessus en un plan numéroté, pensé pour être exécuté étape par étape par plusieurs agents Claude Code. Chaque opération est un élément unique du plan, rattaché à une stratégie de développement nommée, décrite une seule fois dans la partie suivante.

**Codification.**

| Type de code | Forme | Exemple | Sens |
| --- | --- | --- | --- |
| Jalon | `J<n>` | `J1` | Lot d'opérations avec une porte de sortie |
| Opération | `<DOMAINE>-<nn>` | `FP-03` | Une unité de travail : une issue GitHub, un commit |
| Stratégie | `S-<NOM>` | `S-FN` | Mode opératoire réutilisé par plusieurs opérations |

**Domaines.**

| Domaine | Contenu |
| --- | --- |
| `SPK` | Décisions et vérifications préalables, sans code de production |
| `INF` | Module, CI, banc de conformité, issues |
| `FP` | Évaluateur FHIRPath (module `fhir-path`) |
| `VE` | Moteur de vue (nouveau module) |
| `DS` | Source de données sur `blaze.db.api` |
| `OP` | Opération `$viewdefinition-run` et son raccordement HTTP |
| `SR` | Sérialiseurs de lignes |
| `SEC` | Contrôles de sécurité |
| `AS` | Export asynchrone |
| `TS` | Tests d'intégration et de performance |
| `SQ` | Moteur SQL embarqué (plus tard) |

**Statuts.** Une opération est dans l'un de ces états : `À faire`, `En cours`, `À vérifier`, `Fait`, `Bloqué`. Elle passe à `En cours` quand un agent la prend, à `À vérifier` quand l'exécutant a fini, et à `Fait` seulement après la vérification par un second agent. Une opération `Bloqué` porte la raison dans la colonne Statut.

**Prêt à démarrer.** Une opération est prête quand toutes celles de sa colonne `Dépend de` sont `Fait`.

**Jalons.**

| Jalon | Objet | Porte de sortie |
| --- | --- | --- |
| `J0` | Préparation et décisions | Toutes les décisions écrites, module et banc de conformité en place |
| `J1` | Évaluateur FHIRPath | `FP-15` : fonctions de la suite évaluables, tests d'indexation toujours verts |
| `J2` | Moteur de vue | `VE-20` : 144 cas sur 144 de la suite de conformité |
| `J3` | Source de données et `$viewdefinition-run` | `TS-01` : requête de bout en bout sur un jeu de données |
| `J4` | Export asynchrone et sérialiseurs | `TS-02` : export complet récupéré par 303 |
| `J5` | Plus tard | SQL embarqué |

## Stratégies de développement

Chaque stratégie fixe l'ordre des étapes et les critères d'acceptation communs. Une opération n'y ajoute que son critère propre. Toutes respectent `AGENTS.md` : une issue par opération, test en échec avant le code, spécification avec `:ret`, anomalies pour les erreurs, un seul commit intitulé comme l'issue avec `Closes: #n`.

| Stratégie | Nom | Étapes dans l'ordre | Critères d'acceptation communs |
| --- | --- | --- | --- |
| `S-FN` | Fonction FHIRPath | 1. Issue. 2. Test en échec dans `test/blaze/fhir_path_test.clj` : cas nominal, collection vide, plusieurs éléments, type incompatible. 3. `defmethod function-expression` avec spécification. 4. Non-régression. 5. Qualité. 6. Commit. | `make -C modules/fhir-path test` et `make -C modules/db test` verts ; `make fmt`, `make lint` propres ; couverture ≥ 95 % ; cas équivalents de la suite de conformité passants |
| `S-OPR` | Opérateur ou variable FHIRPath | Mêmes étapes que `S-FN`, avec une méthode `FPCompiler` pour la classe de contexte ANTLR concernée et une table de vérité à trois valeurs dans les tests | Idem `S-FN`, plus : l'arité d'`eval` utilisée par l'indexation est inchangée |
| `S-PUR` | Fonction pure du moteur de vue | 1. Issue. 2. Repérer les cas de la suite concernés et les faire échouer via le banc `INF-02`. 3. Implémenter une fonction pure avec spécification. 4. Tests unitaires supplémentaires pour les erreurs. 5. Qualité. 6. Commit. | Tous les cas de la suite listés dans l'opération passent ; aucune dépendance à `blaze.db` dans le module ; erreurs en anomalies ; couverture ≥ 95 % |
| `S-ADP` | Adaptateur de persistance | 1. Issue. 2. Test d'intégration sur une base en mémoire (celle des tests de `modules/db`, à identifier) qui échoue. 3. Implémenter sur `blaze.db.api`. 4. Test de cohérence à `t` fixe. 5. Qualité. 6. Commit. | Résultat identique à une lecture directe par l'API ; une seule valeur de base par requête ; lecture en flux sans tout charger en mémoire |
| `S-OPS` | Opération HTTP | 1. Issue. 2. Test du handler avec une requête Ring (cas valide, paramètre manquant, vue invalide) qui échoue. 3. Composant Integrant avec `m/pre-init-spec` et test d'initialisation. 4. Enregistrement dans `resources/blaze.edn`. 5. Documentation. 6. Qualité. 7. Commit. | Codes HTTP et `OperationOutcome` conformes ; interrupteur par variable d'environnement documenté ; section « effet sur l'API » renseignée comme l'exige `AGENTS.md` ; `docs/api/` à jour |
| `S-JOB` | Traitement asynchrone | 1. Issue. 2. Test des transitions d'état de la tâche (prête, en cours, terminée, échouée, annulée) avec un planificateur de test. 3. Implémenter sur `job-scheduler`. 4. Test de reprise après redémarrage. 5. Qualité. 6. Commit. | Chaque état atteignable testé ; une tâche interrompue est reprise ou marquée échouée ; aucun fichier orphelin |
| `S-SER` | Sérialiseur | 1. Issue. 2. Tests de référence (valeurs nulles, collections, échappement, Unicode) qui échouent. 3. Implémenter en flux. 4. Test sur un grand nombre de lignes pour vérifier la mémoire. 5. Qualité. 6. Commit. | Sortie identique aux exemples de la spécification ; mémoire constante ; relecture de la sortie par un lecteur standard |
| `S-SPK` | Décision préalable | 1. Issue. 2. Bornes de temps. 3. Recherche ou petit essai jetable hors du dépôt. 4. Rédiger la décision (options, choix, raison) dans l'issue et dans ce document. | Décision écrite ; aucune ligne de code de production ; opérations dépendantes débloquées |
| `S-INF` | Infrastructure et outillage | 1. Issue. 2. Modifier la configuration ou l'outil. 3. Vérifier en exécutant la commande concernée. 4. Commit. | La commande de vérification donne le résultat attendu ; CI verte |
| `S-E2E` | Acceptation de bout en bout | 1. Issue. 2. Écrire le scénario (script sous `.github/scripts`, `blazectl` pour télécharger). 3. Le faire échouer. 4. Corriger l'opération en cause. 5. Commit. | Scénario reproductible en CI ; valeurs mesurées consignées dans l'issue |
| `S-DOC` | Documentation | 1. Issue. 2. Rédiger sous `docs/` (variables dans `docs/deployment/environment-variables.md`, API dans `docs/api/`). 3. Badges avec la version du jalon GitHub. 4. Commit. | Exemples de requête exécutés et corrects ; liens valides |

**Qualité** (étape commune) : `make fmt`, `make lint`, `make -C modules/<module> test`, puis `make test-coverage` avec au moins 95 % des formes couvertes.

## J0 : Préparation et décisions

| Code | Opération | Stratégie | Dépend de | Critère propre | Statut |
| --- | --- | --- | --- | --- | --- |
| `SPK-01` | Vérifier la licence de `FHIR/sql-on-fhir.js` et décider : copie des tests ou sous-module Git | `S-SPK` | aucune | Licence citée, choix noté | À faire |
| `SPK-02` | Choisir le traitement de ViewDefinition en R4 : donnée brute dans `viewResource` ou type ajouté au modèle. Écrire d'abord le test sur un `Parameters` contenant un type inconnu | `S-SPK` | aucune | Comportement actuel démontré par un test, choix écrit | Fait (F-006) : ADR 0005 option A, corps lu brut ; amendée par l'ADR 0009 (F-011) |
| `SPK-03` | Valider avec les mainteneurs le nom du module et la portée (issue Blaze #1543) | `S-SPK` | aucune | Réponse tracée dans l'issue | À faire |
| `SPK-04` | Retrouver le sous-ensemble FHIRPath exact de la spécification et le comparer aux opérations `FP-xx` | `S-SPK` | aucune | Écarts transformés en nouvelles opérations `FP-xx` | À faire |
| `SPK-05` | Figer la version de la spécification visée et les noms d'opérations (`$viewdefinition-run` ou `$run`, `$viewdefinition-export` ou `$export`) | `S-SPK` | aucune | Noms et version notés dans ce document | Fait (F-006) : `$viewdefinition-run`, `$viewdefinition-export`, IG `2.1.0-pre` (ADR 0005) |
| `SPK-06` | Choisir le stockage des fichiers d'export (disque local, RocksDB ou stockage objet) | `S-SPK` | `SPK-03` | Choix écrit, limites de taille notées | Fait (F-007) : PVC séparé `<release>-export`, emplacement et plafond d'octets réglables (ADR 0005, ADR 0008 R3) |
| `SPK-07` | Choisir la bibliothèque Parquet pour la JVM | `S-SPK` | `SPK-03` | Choix écrit, poids des dépendances mesuré | À faire |
| `INF-01` | Créer le module vide : `deps.edn` racine, `Makefile`, test runner, ligne dans la matrice de `.github/workflows/build.yml` | `S-INF` | `SPK-03` | `make -C modules/<nom> test` passe sur un test vide ; CI verte | À faire |
| `INF-02` | Banc de conformité : charger les 22 fichiers JSON, exécuter chaque cas, comparer selon `tests.schema.json`, afficher le taux par fichier | `S-INF` | `INF-01`, `SPK-01` | Rapport affichant 144 cas, tous échouant au départ | À faire |
| `INF-03` | Créer une issue GitHub par code du plan et un jalon GitHub par `J<n>` | `S-INF` | `SPK-03` | Chaque code a son numéro d'issue dans le suivi | À faire |
| `INF-04` | Vérifier l'environnement de développement : Clojure installé, `make build-ig`, `make -C modules/fhir-path test` | `S-INF` | aucune | Les commandes réussissent (ce n'était pas vérifiable dans l'environnement de ce document) | À faire |

## J1 : Évaluateur FHIRPath

Toutes ces opérations modifient `modules/fhir-path/src/blaze/fhir_path.clj` et son fichier de test. Elles doivent être exécutées **l'une après l'autre**, jamais en parallèle, pour éviter les conflits de fusion.

| Code | Opération | Stratégie | Dépend de | Critère propre | Statut |
| --- | --- | --- | --- | --- | --- |
| `FP-01` | Fonction `empty()` | `S-FN` | `INF-04` | Vrai pour une collection vide, faux sinon | À faire |
| `FP-02` | Fonction `not()` | `S-FN` | `INF-04` | Négation ; vide si l'entrée est vide | À faire |
| `FP-03` | Fonction `join([séparateur])` | `S-FN` | `INF-04` | Séparateur absent = chaîne vide ; élément non chaîne refusé | À faire |
| `FP-04` | Fonction `getResourceKey()` | `S-FN` | `INF-04` | Renvoie l'identifiant de la ressource courante | À faire |
| `FP-05` | Fonction `getReferenceKey([type])` | `S-FN` | `FP-04` | Vide si le type de la référence diffère ; réutilise `split-literal-ref` | À faire |
| `FP-06` | Fonctions `lowBoundary()` et `highBoundary()` | `S-FN` | `INF-04` | Dates partielles ; calcul propre au module sur les types `java.time` lus par Blaze (`date-time-lower-bound`/`-upper-bound` rendent des secondes d'époque, non réutilisables) | À faire |
| `FP-07` | Opérateur `or` | `S-OPR` | `INF-04` | Table de vérité à trois valeurs complète | À faire |
| `FP-08` | Opérateurs `<`, `<=`, `>`, `>=` | `S-OPR` | `INF-04` | Types compatibles comparés ; vide sinon | À faire |
| `FP-09` | Opérateurs arithmétiques `+`, `-`, `*`, `/` sur nombres | `S-OPR` | `INF-04` | `+` existant inchangé pour les chaînes ; division par zéro = vide | À faire |
| `FP-10` | Variable `$this` | `S-OPR` | `INF-04` | Utilisable dans `where($this ...)` | À faire |
| `FP-11` | Contexte de constantes `%nom` : nouvelle clé `:constants` et nouvelle arité d'`eval` | `S-OPR` | `INF-04` | L'arité utilisée par l'indexation est inchangée ; constante inconnue = anomalie | À faire |
| `FP-12` | Conversion des constantes typées : 18 types (hors `integer64`, F-004) | `S-OPR` | `FP-11` | Un test par type de constante | À faire |
| `FP-13` | Variable réservée `%rowIndex` | `S-OPR` | `FP-11` | Alimentable par le moteur ; absente hors `forEach` = vide | À faire |
| `FP-14` | Constructions non gérées : renvoyer une anomalie `unsupported` explicite au lieu de l'erreur générique | `S-OPR` | `INF-04` | Message nommant la construction ; test sur chaque catégorie de la grammaire | À faire |
| `FP-15` | **Porte J1** : lancer toute la suite de `modules/fhir-path` et `modules/db`, mesurer les expressions de la suite de conformité | `S-INF` | `FP-01` à `FP-14` | Tous les tests verts ; liste des expressions encore non évaluables | À faire |

## J2 : Moteur de vue

Le moteur est une fonction pure dans le nouveau module ; il n'utilise ni `blaze.db` ni HTTP. Les nombres de cas viennent de la suite de conformité (120 au niveau du moteur, 24 au niveau des fonctions). Les opérations de ce jalon sur des fichiers distincts peuvent s'exécuter en parallèle.

| Code | Opération | Stratégie | Dépend de | Critère propre | Statut |
| --- | --- | --- | --- | --- | --- |
| `VE-01` | Modèle de la vue et validation de structure : `resource`, `select`, `where`, `constant`, noms de colonnes | `S-PUR` | `INF-02`, `SPK-02` | Une vue mal formée donne une anomalie `incorrect` | À faire |
| `VE-02` | Validation sémantique de la vue (fichier `validate`, 5 cas) | `S-PUR` | `VE-01` | 5 cas sur 5 | À faire |
| `VE-03` | Compilation unique de chaque expression FHIRPath avec `fhir-path/compile` et réutilisation sur toutes les ressources | `S-PUR` | `VE-01`, `FP-15` | Aucune recompilation par ressource, vérifiée par un test | À faire |
| `VE-04` | Clause `where` (8 cas) | `S-PUR` | `VE-03`, `FP-02`, `FP-07`, `FP-08` | 8 cas sur 8 | À faire |
| `VE-05` | Colonnes simples et typage (fichiers `columns` et `basic`, 11 cas) | `S-PUR` | `VE-03` | 11 cas sur 11 | À faire |
| `VE-06` | Colonnes `collection: true` (4 cas) | `S-PUR` | `VE-05` | 4 cas sur 4 ; plusieurs valeurs sans `collection` = anomalie | À faire |
| `VE-07` | Expressions FHIRPath dans les colonnes (9 cas) | `S-PUR` | `VE-05`, `FP-01` à `FP-06` | 9 cas sur 9 | À faire |
| `VE-08` | Combinaison de plusieurs `select` (produit des lignes, 6 cas) | `S-PUR` | `VE-05` | 6 cas sur 6 | À faire |
| `VE-09` | `forEach` | `S-PUR` | `VE-08` | Cas `forEach` de la suite passés | À faire |
| `VE-10` | `forEachOrNull` | `S-PUR` | `VE-09` | Les 13 cas de `forEach` et `forEachOrNull` passés au total | À faire |
| `VE-11` | `repeat` (19 cas) | `S-PUR` | `VE-09` | 19 cas sur 19 ; arrêt sur cycle ou répétition | À faire |
| `VE-12` | `unionAll` (10 cas) | `S-PUR` | `VE-08` | 10 cas sur 10 ; colonnes différentes = anomalie | À faire |
| `VE-13` | Constantes de la vue (8 cas de vue plus 14 cas de fonctions) | `S-PUR` | `VE-03`, `FP-12` | 22 cas sur 22 | À faire |
| `VE-14` | `%rowIndex` (9 cas) | `S-PUR` | `VE-09`, `FP-13` | 9 cas sur 9 | À faire |
| `VE-15` | Contrôle du type de ressource ciblé (3 cas) | `S-PUR` | `VE-01` | 3 cas sur 3 | À faire |
| `VE-16` | Représentation des nombres (1 cas) | `S-PUR` | `VE-05` | 1 cas sur 1 | À faire |
| `VE-17` | Schéma de sortie : nom et type de chaque colonne, fourni aux sérialiseurs | `S-PUR` | `VE-05` | Schéma disponible avant la première ligne | À faire |
| `VE-18` | Interface publique : fonction `run` ou transducteur appliqué à un flux de ressources | `S-PUR` | `VE-04`, `VE-10` | Utilisable sans base de données ; spécification avec `:ret` | À faire |
| `VE-19` | Gestion des erreurs : toute erreur d'une ressource ou d'une vue devient une anomalie typée | `S-PUR` | `VE-18` | Aucune exception ne sort du module | À faire |
| `VE-20` | **Porte J2** : exécuter la suite complète | `S-INF` | `VE-02` à `VE-19` | 144 cas sur 144 | À faire |

## J3 : Source de données et opération `$viewdefinition-run`

Ce jalon relie le moteur à la base de Blaze et expose l'opération synchrone. Les signatures exactes de `blaze.db.api` sont à lire dans `modules/db/src/blaze/db/api.clj` avant chaque opération `DS`.

| Code | Opération | Stratégie | Dépend de | Critère propre | Statut |
| --- | --- | --- | --- | --- | --- |
| `DS-01` | Lire toutes les ressources d'un type à un `t` fixe, en flux (`d/db`, `d/type-list`, `d/new-batch-db`, `d/pull-many`) | `S-ADP` | `INF-01` | Nombre de ressources identique à `d/type-list` | Fait (F-006) |
| `DS-02` | Filtre `patient` (`d/compartment-type-list`) | `S-ADP` | `DS-01` | Seules les ressources du compartiment sont lues | Fait (F-006) |
| `DS-03` | Filtre `group` : membres du Group transformés en patients | `S-ADP` | `DS-02` | Groupe vide = aucune ligne ; membre non Patient ignoré | Fait (F-006) |
| `DS-04` | Filtre `_since` sur la date de dernière modification | `S-ADP` | `DS-01` | Bornes incluses ou exclues comme dans la spécification | Fait (F-006) : `_lastUpdated=gt<instant>`, borne exclue |
| `DS-05` | Source en mémoire : ressources fournies dans la requête (paramètre `resource`) | `S-ADP` | `VE-18` | **Écarté** (décision humaine du 2026-10-10, F-006 Q4) : `resource` refusé par 400 + `OperationOutcome` `not-supported` (`OP-02`) | Écarté |
| `DS-06` | Limite `_limit` appliquée sans lire plus que nécessaire | `S-ADP` | `DS-01` | Lecture interrompue dès la limite atteinte | Fait (F-006) : lecture par paquets de 100 ressources, arrêtée au paquet qui atteint la limite |
| `DS-07` | Capture d'une seule valeur de base par requête | `S-ADP` | `DS-01` | Une écriture pendant la lecture n'apparaît pas dans les lignes | Fait (F-006) |
| `DS-08` | Planificateur de lecture (optionnel) : traduire un `where` simple en requête de type (`compile-type-query`, `execute-query`) | `S-ADP` | `DS-01`, `VE-04` | Résultat identique à la lecture complète, plus rapide sur un grand jeu de données | À faire |
| `OP-01` | Entrée de la ViewDefinition en R4 selon la décision `SPK-02` | `S-OPS` | `SPK-02`, `VE-01` | Un `Parameters` avec `viewResource` est accepté | Fait (F-006) : corps lu brut, `ViewDefinition` non déclarée |
| `OP-02` | Lecture des paramètres : `viewReference`, `viewResource`, `_format`, `header`, `patient`, `group`, `_since`, `_limit`, `resource`, `source` | `S-OPS` | `OP-01` | Ni `viewResource` ni `viewReference` = 400 `required` ; `viewReference` sans schéma (`ViewDefinition/<id>`, `Library/<id>`), `source`, `resource` = 400 `not-supported` ; patient ou groupe absent = 400 `not-found` (F-006, « Requête refusée », « Filtres ») | Fait (F-006 ; `viewReference` : F-011) |
| `OP-03` | Handler système `$viewdefinition-run` synchrone | `S-OPS` | `OP-02`, `DS-01`, `VE-18` | Renvoie les lignes d'une vue simple | Fait (F-006) |
| `OP-04` | Négociation du format (`_format`, `Accept`) et en-tête `header` | `S-OPS` | `OP-03`, `SR-01` | Format absent = NDJSON ; `json`, `ndjson`, `csv` servis ; toute autre valeur = 400 `not-supported` (IG, « Invalid Format » ; F-006, « Format par défaut et format refusé ») | Fait (F-006 ; `Accept` : F-018) : `_format` prime ; sans lui, un `Accept` à type unique (`application/json`, `application/x-ndjson`, `text/csv`, paramètres ignorés sauf `q=0`) choisit le format ; `q=0` (RFC 9110 §12.4.2), liste, `*/*` ou autre type → NDJSON |
| `OP-05` | Résolution de `viewReference` vers une vue stockée | `S-OPS` | `OP-03`, `SPK-02` | Décidé par l'ADR 0009 (2026-10-13) : vue rangée en `Library`, canonique (`url` ou `url\|version`) résolue localement, jamais téléchargée ; introuvable = 400 `not-found`, plusieurs = 400 `multiple-matches` ; format documenté dans `metadata` | Fait (F-011, `validé` le 2026-10-14) |
| `OP-06` | Conversion des anomalies en `OperationOutcome` et codes HTTP : `incorrect` = 422 `invalid`, `unsupported` = 422 `not-supported`, `fault` = 500 `processing` (IG `2.1.0-pre` ; F-006, « Vue refusée ») | `S-OPS` | `OP-03` | Un test par catégorie d'anomalie ; le serveur répond encore après | Fait (F-006) : aucune vue ne produit `fault` |
| `OP-07` | Composant Integrant et enregistrement dans `resources/blaze.edn` avec interrupteur par variable d'environnement | `S-OPS` | `OP-03` | Opération absente quand l'interrupteur est coupé | Fait (F-006) : `DISABLE_VIEWDEFINITION_RUN`, allumée par défaut |
| `OP-08` | Déclaration de l'opération dans le CapabilityStatement | `S-OPS` | `OP-07` | Visible dans `/fhir/metadata` | Fait (F-006) |
| `OP-09` | Garde-fous : nombre maximal de lignes, réglable par une valeur du chart, défaut 10 000 | `S-OPS` | `OP-03` | Résultat au-delà du plafond = 422 `too-costly` et 0 ligne ; `_limit` ≤ plafond servi (F-006, « Plafond de lignes ») ; durée et mémoire : aucun critère, mémoire *à mesurer* | Fait (F-006) : `blaze.viewdefinitionRun.plafondLignes` → `VIEWDEFINITION_RUN_MAX_ROWS` |
| `OP-10` | Niveaux instance et type de l'opération si les vues sont stockées | `S-OPS` | `OP-05` | Sans objet tant que le type `ViewDefinition` n'est pas stocké : les vues se rangent en `Library` (ADR 0009) ; niveau système seul déclaré | À faire |
| `OP-11` | Documentation de l'API et des variables d'environnement | `S-DOC` | `OP-08` | Pages `docs/api/` et `docs/deployment/environment-variables.md` à jour | À faire : ni `docs/api/` ni `docs/deployment/` dans ce dépôt ; variables dans `docs/configuration.md`, écarts à l'IG dans `docs/manuel.md` |
| `SR-01` | Sérialiseur NDJSON | `S-SER` | `VE-17` | Une ligne JSON par ligne de vue | Fait (F-006) |
| `SR-02` | Sérialiseur JSON (tableau) avec jsonista | `S-SER` | `VE-17` | Tableau valide, flux | Fait (F-006) : en flux à l'export ; `$viewdefinition-run` compose le corps entier, borné par le plafond |
| `SR-03` | Sérialiseur CSV avec ligne d'en-tête optionnelle | `S-SER` | `VE-17` | Guillemets, virgules et sauts de ligne échappés | Fait (F-006) |
| `SEC-01` | Vérifier que l'opération respecte les mêmes contrôles d'accès que la recherche et ne contourne aucun filtre | `S-E2E` | `OP-03` | **Retiré de F-006** (critère « Accès » retiré, décision humaine du 2026-10-10, Q1 a) | Retiré |
| `TS-01` | **Porte J3** : scénario de bout en bout sur un jeu de données (script sous `.github/scripts`, téléchargement par `blazectl`) | `S-E2E` | `OP-03` à `OP-09`, `DS-02` à `DS-07`, `SR-01` à `SR-03` | Lignes obtenues identiques à l'exécution du moteur en mémoire | Fait (F-006) : cas N2 IT-010…IT-022, non `.github/scripts` ni `blazectl` |

## J4 : Export asynchrone et sérialiseurs

Le modèle asynchrone existant (`Prefer: respond-async`, `__async-status`) répond 202 puis permet de suivre l'avancement ; l'écart à combler est le 303 vers les fichiers.

| Code | Opération | Stratégie | Dépend de | Critère propre | Statut |
| --- | --- | --- | --- | --- | --- |
| `AS-01` | Handler `$viewdefinition-export` : validation puis réponse 202 avec `Content-Location` | `S-OPS` | `OP-03`, `SPK-05` | 202 et URL de suivi renvoyées | Fait (F-007) |
| `AS-02` | Paramètres d'export : liste de vues, `clientTrackingId`, `_format`, `header`, `patient`, `group`, `_since` ; `source` refusé | `S-OPS` | `AS-01` | Chaque paramètre transmis à la tâche ; `source` = 400 `not-supported`, aucune tâche créée (F-007, « Requête refusée au lancement ») | Fait (F-007) |
| `AS-03` | Type de tâche d'export enregistré dans `job-scheduler` | `S-JOB` | `AS-01` | Tâche créée avec ses paramètres et lisible après redémarrage | Fait (F-007) |
| `AS-04` | Exécuteur : lit la base à un `t` fixe, exécute chaque vue, écrit un fichier par vue | `S-JOB` | `AS-03`, `DS-07`, `SR-05` | Fichiers corrects pour deux vues dans une même tâche | Fait (F-007) |
| `AS-05` | Stockage des fichiers de résultat selon la décision `SPK-06` | `S-JOB` | `SPK-06` | Fichier relu à l'identique après redémarrage | Fait (F-007) : `<jeton>.<ext>.part` puis renommage ; fichier perdu → tâche déclarée échouée |
| `AS-06` | Statut et manifeste : réponse de suivi avec la liste des fichiers produits | `S-OPS` | `AS-04`, `AS-05` | Progression visible ; manifeste conforme à la spécification | Fait (F-007) : progression = statut `accepted` ou `in-progress`, sans avancement chiffré |
| `AS-07` | Redirection 303 en fin de tâche et téléchargement des fichiers | `S-OPS` | `AS-06` | 303 puis fichier téléchargeable par `blazectl` ou `curl` | Fait (F-007) : téléchargement par `curl` éprouvé, `blazectl` non essayé |
| `AS-08` | Annulation (`DELETE`) et gestion d'une tâche interrompue par un redémarrage | `S-JOB` | `AS-04` | Tâche annulée sans fichier orphelin | Fait (F-007) : tâche interrompue reprise (arrêt ordonné) ou déclarée échouée (ADR 0008 R1) |
| `AS-09` | Expiration et nettoyage des fichiers | `S-JOB` | `AS-05` | Fichiers supprimés après la durée configurée ; durée documentée | Fait (F-007) : conservation 24 h, nettoyage toutes les 300 s (défauts du chart) |
| `AS-10` | Documentation de l'export et des nouvelles variables d'environnement | `S-DOC` | `AS-07` | Pages `docs/api/` à jour | À faire : ni `docs/api/` dans ce dépôt ; variables dans `docs/configuration.md`, usage dans `docs/manuel.md` |
| `SR-04` | Sérialiseur Parquet | `S-SER` | `SPK-07`, `VE-17` | Fichier relu par un lecteur Parquet standard | À faire |
| `SR-05` | Écriture en flux vers fichier, avec découpage par taille maximale | `S-SER` | `SR-01`, `SR-03` | Mémoire constante sur un grand volume | À faire (partiel, F-007) : écriture en flux faite ; pas de découpage par taille (plafond d'espace → tâche échouée) ; mémoire sur grand volume : F-009 |
| `TS-02` | **Porte J4** : export complet de bout en bout, fichiers récupérés par 303 | `S-E2E` | `AS-01` à `AS-09`, `SR-04` | Lignes des fichiers identiques à celles de `$viewdefinition-run` | Fait hors `SR-04` (F-007, porte J4 T-051) : fichiers fidèles 6/6 face à `$viewdefinition-run` ; Parquet : F-008 |
| `TS-03` | Mesure de performance sur un grand jeu de données (objectifs à fixer avec les mainteneurs) | `S-E2E` | `TS-02` | Débit et mémoire consignés dans l'issue | À faire |

## J5 : Plus tard

Option, hors de la présente livraison (ADR 0005, amendement du 2026-10-14) : les quatre codes restent « À faire ».

| Code | Opération | Stratégie | Dépend de | Critère propre | Statut |
| --- | --- | --- | --- | --- | --- |
| `SQ-01` | Exposer les vues comme tables pour le moteur SQL | `S-PUR` | `VE-20` | Une vue apparaît comme une table avec son schéma | À faire |
| `SQ-02` | Moteur SQL embarqué pour `$sqlquery-run` (choix du moteur à décider d'abord) | `S-SPK` | `SQ-01` | Décision écrite, puis opérations dérivées créées | À faire |
| `SQ-03` | Opération `$sqlquery-run` | `S-OPS` | `SQ-02`, `OP-07` | Une requête sur une vue renvoie les lignes attendues | À faire |
| `SQ-04` | Utilisation des index de Blaze pour accélérer les filtres des vues | `S-ADP` | `DS-08`, `TS-03` | Gain mesuré par `TS-03` | À faire |

## Consignes pour l'environnement multi-agents

Ces consignes généralisent le plan : elles ne dépendent d'aucune opération en particulier, et un agent peut les appliquer à n'importe quel code du plan.

**Mise en place.**

- Exporter ce document en Markdown et le placer à la racine du dépôt sous le nom `PLAN.md`. C'est la seule source de vérité du plan et du suivi.
- Ajouter dans `CLAUDE.md` une ligne qui renvoie à `PLAN.md` et aux règles ci-dessous.
- Option : transformer chaque stratégie `S-xx` en sous-agent ou en compétence (par exemple `.claude/agents/s-fn.md`) pour que l'exécutant charge ses étapes sans les recopier.
- Dans un worktree neuf, lancer `make build-ig` avant tout test, comme l'exige `AGENTS.md`.

**Rôles.**

| Rôle | Responsabilité | Ce qu'il ne fait pas |
| --- | --- | --- |
| Orchestrateur | Lit `PLAN.md`, choisit les opérations prêtes, lance les exécutants, met à jour la colonne Statut, arbitre les blocages | Ne code pas |
| Exécutant | Réalise une seule opération en suivant sa stratégie, dans un worktree isolé, et rend un rapport | Ne modifie pas `PLAN.md`, ne prend pas d'autre opération |
| Vérificateur | Agent distinct qui revérifie le résultat à partir du diff et des commandes, sans avoir suivi le travail | Ne corrige pas : il accepte ou renvoie avec les écarts |

**Règles de l'orchestrateur.**

1. Une opération est lançable si toutes ses dépendances sont `Fait` et si elle est `À faire`.
2. Ne jamais lancer en parallèle deux opérations qui modifient le même fichier. Les opérations `FP-xx` partagent `fhir_path.clj` : une seule à la fois. Les opérations `VE-xx` sur des espaces de noms distincts peuvent être parallèles.
3. Limiter le nombre d'exécutants simultanés à ce que la machine supporte pour les tests (par défaut 3).
4. Chaque exécutant travaille dans son propre worktree ; l'orchestrateur fusionne dans l'ordre des codes et relance les tests après chaque fusion.
5. Après le rapport d'un exécutant, passer l'opération à `À vérifier` et lancer un vérificateur. `Fait` seulement après son accord.
6. Un échec renvoyé deux fois par le vérificateur passe l'opération à `Bloqué` avec la raison, et l'orchestrateur le signale à la personne.
7. À la fin d'un jalon, exécuter la porte de sortie avant de lancer le jalon suivant.
8. Une opération `S-SPK` qui demande un choix de la personne s'arrête et pose la question ; ne jamais décider à sa place.

**Prompt de l'orchestrateur.**

```
Tu es l'orchestrateur du développement décrit dans PLAN.md.
1. Lis PLAN.md en entier : stratégies, jalons, tableaux d'opérations.
2. Liste les opérations prêtes (Statut À faire, toutes les dépendances Fait).
3. Retire celles qui modifient les mêmes fichiers qu'une opération en cours.
4. Pour chaque opération retenue (3 au plus), passe le Statut à En cours puis lance un exécutant dans un worktree isolé avec le prompt exécutant, en remplaçant {CODE}.
5. À chaque rapport reçu, passe l'opération à À vérifier et lance un vérificateur avec le prompt vérificateur.
6. Si le vérificateur accepte : fusionne, relance les tests du module, passe le Statut à Fait. S'il refuse : renvoie les écarts à l'exécutant, deux fois au plus, puis Bloqué avec la raison.
7. Quand toutes les opérations d'un jalon sont Fait, exécute sa porte de sortie. Ne passe pas au jalon suivant tant qu'elle échoue.
8. Mets à jour le tableau de suivi et rends un point en cinq lignes : fait, en cours, bloqué, prochain lot, décision attendue de la personne.
Ne code jamais toi-même. Ne décide jamais à la place de la personne pour une opération S-SPK.
```

**Prompt de l'exécutant.**

```
Tu réalises l'opération {CODE} de PLAN.md et rien d'autre.
1. Lis dans PLAN.md : la ligne de {CODE}, la stratégie qu'elle cite (étapes et critères communs), AGENTS.md et CLAUDE.md.
2. Vérifie que les dépendances de {CODE} sont Fait. Sinon arrête-toi et dis laquelle manque.
3. Suis les étapes de la stratégie dans l'ordre. Le test en échec vient toujours avant le code ; montre-le en échec.
4. N'écris que dans les fichiers nécessaires à {CODE}. Ne modifie pas PLAN.md.
5. Exécute make fmt, make lint, les tests du module et make test-coverage. Colle la fin de chaque sortie dans le rapport. Si une commande est indisponible, dis-le : ne déclare jamais un test passé sans l'avoir lancé.
6. Fais un seul commit : titre de l'issue, corps Closes: #n, sans mention d'outil ni de coauteur IA.
7. Si tu es bloqué, arrête-toi, explique ce qui manque et propose une option ; n'invente pas de contournement.
Rapport final : code, fichiers modifiés, commandes lancées avec résultat, critères (commun et propre) un par un avec preuve, points d'attention.
```

**Prompt du vérificateur.**

```
Tu vérifies l'opération {CODE} de PLAN.md sans avoir participé à sa réalisation.
1. Lis la ligne de {CODE}, sa stratégie, AGENTS.md et le diff du commit.
2. Relance toi-même make fmt, make lint, les tests du module et make test-coverage.
3. Vérifie qu'un test couvre chaque comportement du critère propre, et que ce test aurait échoué avant le code.
4. Vérifie les conventions : spécifications avec :ret, anomalies sans exceptions, spécifications hors des espaces de noms d'implémentation, un seul commit, message conforme.
5. Vérifie qu'aucun fichier hors périmètre n'a changé.
Réponds ACCEPT ou REJECT. En cas de REJECT, liste chaque écart avec le fichier, la ligne et la correction attendue. Ne corrige rien.
```

**Format du rapport de l'orchestrateur à la personne.** Un point court après chaque lot : codes terminés, codes bloqués avec la raison, prochain lot, et questions nécessitant une décision.

## Suivi d'avancement

L'orchestrateur met ce tableau à jour après chaque lot. Le plan compte 88 opérations.

| Jalon | Opérations | Codes | Faites | En cours | Bloquées | Porte de sortie franchie |
| --- | --- | --- | --- | --- | --- | --- |
| `J0` | 11 | `SPK-01` à `SPK-07`, `INF-01` à `INF-04` | 0 | 0 | 0 | sans objet |
| `J1` | 15 | `FP-01` à `FP-15` | 0 | 0 | 0 | non |
| `J2` | 20 | `VE-01` à `VE-20` | 0 | 0 | 0 | non |
| `J3` | 24 | `DS-01` à `DS-08`, `OP-01` à `OP-11`, `SR-01` à `SR-03`, `SEC-01`, `TS-01` | 0 | 0 | 0 | non |
| `J4` | 14 | `AS-01` à `AS-10`, `SR-04`, `SR-05`, `TS-02`, `TS-03` | 0 | 0 | 0 | non |
| `J5` | 4 | `SQ-01` à `SQ-04` | 0 | 0 | 0 | non |

**Journal des décisions.** L'orchestrateur y note, avec la date, chaque décision `SPK-xx` prise par la personne et chaque opération `Bloqué`.

**Avant de démarrer J1.** `INF-04` doit être fait dans l'environnement qui hébergera les agents : Clojure et les dépendances de Blaze doivent y être installés, ce qui n'était pas le cas lors de la rédaction de ce plan.

## Décisions et risques

Sept points sont à trancher ou à vérifier avant de s'engager ; le premier peut bloquer la conception.

| Point | Risque | Piste |
| --- | --- | --- |
| ViewDefinition hors R4 | Blaze est en FHIR 4.0.1 et son modèle de données ne connaît pas ce type | La spécification prévoit un mode R4 : opération au niveau système, vue passée en ligne. Vérifier que le parseur accepte ce type dans un `Parameters`. Stocker des vues comme ressources demanderait d'ajouter un type au modèle |
| Version de la spécification | La spécification est en version 2.1.0-pre et les noms d'opérations varient selon les pages (`$run`, `$viewdefinition-run`) | Figer la version visée avant d'écrire l'API |
| Couverture FHIRPath | L'évaluateur de Blaze ne couvre que 5 des 12 fonctions de la suite de conformité et aucun opérateur de comparaison | Réaliser les Travaux 1 en premier, test par test, et mesurer le taux de réussite de la suite |
| Export asynchrone | Le motif existant ne produit pas de fichiers ; durée de vie, espace disque et droits d'accès sont à définir | Cadrer avec le mainteneur avant la phase d'export |
| Performance sans filtrage | Sans planificateur, une vue parcourt toutes les ressources du type | Mesurer sur un jeu de données réaliste avant d'investir dans le planificateur |
| Parquet | Dépendance supplémentaire sur la JVM | Commencer par NDJSON et CSV ; Parquet en phase 5 |
| Contribution amont | Le mainteneur préfère le CQL pour les analyses et rappelle que SQL on FHIR demandera du temps | Ouvrir la discussion dans l'issue #1543 avant d'écrire du code |

## Feuille de route

Sept phases mènent de l'accord de conception à l'export Parquet, chacune avec un critère de sortie vérifiable.

| Phase | Livrable | Critère de sortie | Composants touchés |
| --- | --- | --- | --- |
| 0. Cadrage | Version de la spécification, traitement de ViewDefinition en R4, périmètre, avis du mainteneur | Réponses écrites aux trois premiers risques | Aucun |
| 1. Noyau pur | Modèle de vue, compilateur, évaluateur FHIRPath étendu | Les 144 cas de la suite de conformité exécutés en mémoire, taux de réussite mesuré | Modèle, compilateur, évaluateur |
| 2. Lecture dans la base | Source de données à `t` fixe, filtres `patient` et `_since` | Mêmes résultats qu'en phase 1 sur un jeu chargé dans Blaze | Source de données, planificateur simple |
| 3. Exécution synchrone | `$viewdefinition-run` en NDJSON, CSV et JSON | Appel HTTP de bout en bout, CapabilityStatement à jour | API, sérialiseurs |
| 4. Export asynchrone | `$viewdefinition-export`, tâches et fichiers de résultat | Export d'un jeu volumineux sans saturer la mémoire, avancement consultable | API asynchrone, gestion des exports |
| 5. Parquet | Sérialiseur Parquet | Fichier relu par un outil d'analyse externe | Sérialiseurs |
| 6. SQL (option) | `$sqlquery-run` avec un moteur SQL embarqué | Hors de la présente livraison, version ultérieure ; critère de reprise : ADR 0005, amendement du 2026-10-14 | Moteur SQL embarqué |

## Sources

- [Blaze : API FHIR](https://blaze-server.org/api.html) (opérations, requêtes asynchrones, pagination)
- [Blaze : implémentation de la base](https://blaze-server.org/implementation/database.html) (index, valeur de base à `t`, requêtes par type)
- [Blaze : modèle de données FHIR](https://blaze-server.org/implementation/fhir-data-model.html) (représentation interne, CBOR)
- [Issue Blaze #1543](https://github.com/samply/blaze/issues/1543) (position du mainteneur sur SQL on FHIR)
- [HL7/sql-on-fhir](https://github.com/HL7/sql-on-fhir) (spécification, suite de tests dans `FHIR/sql-on-fhir.js`)
- [SQL on FHIR : opérations](https://build.fhir.org/ig/FHIR/sql-on-fhir-v2/operations.html) et [`$viewdefinition-run`](https://build.fhir.org/ig/FHIR/sql-on-fhir-v2/OperationDefinition-ViewDefinitionRun.html) (mode R4, paramètres, export asynchrone)
- [Pathling](https://pathling.csiro.au/docs) (implémentation Spark de SQL on FHIR)
- [Health Samurai : what is a ViewDefinition](https://health-samurai.io/articles/what-is-a-viewdefinition) (Aidbox transpile les vues en SQL PostgreSQL)
- [FHIR/sql-on-fhir.js](https://github.com/FHIR/sql-on-fhir.js) (suite de tests de conformité et implémentation de référence)
- [samply/blaze, module fhir-path](https://github.com/samply/blaze/tree/main/modules/fhir-path) (évaluateur FHIRPath de Blaze)
