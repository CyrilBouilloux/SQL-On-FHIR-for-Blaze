# Architecture — ce qui existe

<!-- Tenu par `architect`. Décrit l'existant, renvoie aux ADR sans les recopier ; aucune
     version, aucun secret. État au 2026-10-17. -->

## Architecture cible

Les décisions qui fixent la forme de la plateforme. Une décision n'est remise en cause que
par une ADR.

| # | Décision |
|---|---|
| 1 | **Blaze, variante de stockage `standalone`** : journal de transactions et magasin de ressources embarqués (RocksDB) dans un seul nœud Blaze, sur un volume persistant ; ni serveur FHIR écrit ici, ni base relationnelle, parce que recherche, indexation, historique, CQL et terminologie sont des briques de Blaze |
| 2 | **Le corps de la ressource reste du JSON canonique**, stocké par Blaze : ni normalisation, ni ORM, ni stockage parallèle du contenu |
| 3 | **Search parameters : ceux de Blaze pour R4, plus une liste de paramètres ajoutés** dans `blaze/search-parameters.json` (bundle chargé par `DB_SEARCH_PARAM_BUNDLE`), seule liste qui fasse foi pour les ajouts (absente : aucun paramètre ajouté). *Décidé, non mesuré* : aucun moyen documenté de désactiver un paramètre R4 ; un paramètre ne s'ajoute que sur besoin démontré, tracé dans une ADR |
| 4 | **Validation de profils par un validateur externe**, appelé par Blaze avant persistance (`EXTERN_VALIDATOR_URL`) ; le validateur a son propre serveur de terminologie ; Blaze ne valide pas les profils lui-même |
| 5 | **Reverse proxy nginx devant Blaze**, couche de politique (plafonds, sonde) distincte de l'Ingress ; le plafond `_include`/`_revinclude` s'y applique, pas dans Blaze |
| 6 | **Authentification OpenID et frontend Blaze** déployés avec le backend (déploiement « full standalone » de la documentation Blaze) |
| 7 | **Kubernetes est la seule livraison** : chart Helm de ce dépôt, politique `restricted`, `NetworkPolicy` appliquée ; une image de conteneur n'est qu'un artefact |

| Composant | Rôle cible | État dans le chart (`kubernetes/chart/`) |
|---|---|---|
| Blaze | serveur FHIR R4, image du module | **déployé** (`StatefulSet`, `templates/blaze.yaml`) |
| Reverse proxy nginx | plafonds, sonde `/healthz` | **déployé** (`Deployment`, `templates/nginx.yaml`) |
| Ingress | exposition HTTPS hors du cluster | non déployé : exposition par `Service` `ClusterIP` ou `NodePort` (`exposition.type`) |
| Frontend Blaze | interface web | non déployé |
| Fournisseur OpenID | authentification | non déployé |
| Validateur externe | validation de profils avant persistance | non déployé |
| Serveur de terminologie | terminologie du validateur | non déployé |

Relevé du 2026-10-17 : `helm template blaze kubernetes/chart -n blaze -f kubernetes/chart/values-versions.yaml --set image.blaze.tag=essai | /usr/bin/grep -E '^kind:' | sort | uniq -c`
→ `ConfigMap 1, Deployment 1, Namespace 1, NetworkPolicy 3, PersistentVolumeClaim 1, Service 2, StatefulSet 1`, aucun `Ingress` ;
la même sortie filtrée par `/usr/bin/grep -ciE 'keycloak|frontend|validator|openid|terminolog'` → `0`.

## Livraison Kubernetes (ADR 0003)

```
client ──> Service nginx ──> nginx ──> Service blaze ──> StatefulSet blaze-0
                                                           ├─ PVC data (RocksDB)
                                                           └─ PVC <release>-export (fichiers d'export)
```

| Composant | Fichier qui fait foi | Rôle |
|---|---|---|
| Namespace `blaze`, politique `restricted` | `kubernetes/chart/templates/namespace.yaml` | isolement, sécurité des pods |
| Volume d'export, PVC séparé `<release>-export` | `kubernetes/chart/templates/export-pvc.yaml` | fichiers de `$viewdefinition-export`, distinct de `data` ; conservé à `helm uninstall` ; réglages : `docs/configuration.md` |
| Blaze, variante `standalone`, un nœud | `kubernetes/chart/templates/blaze.yaml` | serveur FHIR R4 ; image du module (`build/Dockerfile`), dont l'unique `FROM` est l'image **publiée** `samply/blaze` |
| Reverse proxy nginx | `kubernetes/chart/templates/nginx.yaml`, `nginx/nginx.conf` | plafond `_include`/`_revinclude`, sonde `/healthz` |
| `NetworkPolicy` (3) | `kubernetes/chart/templates/networkpolicy.yaml` | Blaze joignable par nginx seul |

## Module `sql-on-fhir` (ADR 0003, 0004, 0006)

Code Clojure greffé sur le jar de l'image publiée, jamais sur les sources de Blaze. Points
d'extension touchés : `docs/impacts-blaze.md` ; fichier par fonctionnalité : `docs/couverture.md`.

| Couche | Dossier (`modules/sql-on-fhir/`) | Ce qu'elle fait |
|---|---|---|
| Extensions FHIRPath | `src/blaze/addon/sql_on_fhir/fhir_path/` | ajoute à l'évaluateur de Blaze fonctions, opérateurs, variables, constantes ; refuse en `unsupported` les 7 constructions restantes |
| Moteur de vues | `src/blaze/addon/sql_on_fhir/view/` | `validate` → `compile` (chaque expression compilée une fois) → `run` (lignes en mémoire) ; `schema` (colonnes) ; erreurs rendues en anomalies |
| Source de données | `src/blaze/addon/sql_on_fhir/data_source.clj` | lit un type à une seule valeur de base (`blaze.db.api`, instant fixe), filtres `patient`, `group`, `_since` (= recherches R4 équivalentes ; `patient` et `group` ensemble : intersection), `limit` ; patient ou groupe absent → anomalie `not-found` (IB-10) ; appelée par le handler de l'opération |
| Opération `$viewdefinition-run` | `src/blaze/addon/sql_on_fhir/operation/`, `src/blaze/addon/sql_on_fhir/serializer.clj` | `params` lit le `Parameters` brut (refus 400) ; `run` compile la vue une fois, lit les lignes à la valeur de base de la requête jusqu'au plafond + 1 (au-delà : 422 `too-costly`), rend les refus de vue en 422 ; `serializer` écrit NDJSON (défaut), JSON, CSV ; `sortie` : voie de sortie brute (IB-12), lit l'`Accept` d'origine avant de le fixer (format de repli après `_format`) |
| Opération `$viewdefinition-export` | `src/blaze/addon/sql_on_fhir/operation/export.clj`, `src/blaze/addon/sql_on_fhir/export/`, `src/blaze/addon/sql_on_fhir/serializer/file.clj` | refuse avant toute écriture (400, 422) ; crée une `Task` de l'ordonnanceur de Blaze à l'instant `t` de la requête, rend 202 ; la tâche de fond (`export/job.clj`, pool de 2 fils) écrit un fichier par vue en flux (`<jeton>.<ext>.part` puis renommage) sur le volume d'export ; routes `__export/status|result|file/<jeton>` servies par l'enrobage de sortie, jetons `SecureRandom` (`export/token.clj`) ; `docs/impacts-blaze.md` IB-11, IB-13 |
| Vue rangée, `viewReference` | `src/blaze/addon/sql_on_fhir/operation/reference.clj` | résout la canonique (`url` ou `url\|version`) en une `Library` cherchée par `type-query` dans la base de la requête, jamais téléchargée ; la vue est la pièce `application/json` de `content` ; refus 400 `not-supported` (sans `scheme:`), `not-found`, `multiple-matches`, 422 (contenu inutilisable) ; l'export résout au lancement, la `Task` porte la vue ; `docs/impacts-blaze.md` IB-14 |
| Suite de conformité | `conformance/blaze/`, suite copiée `conformance/sql-on-fhir-js/` | 144 cas, règle du runner de référence |

Le moteur ne charge aucun namespace `blaze.db` ni `blaze.interaction` (`make -C
modules/sql-on-fhir independance`). L'opération `$viewdefinition-run` l'expose par un overlay
`resources/blaze.edn` **généré** depuis celui du jar (`generer-overlay.py`, contrôle `make -C
modules/sql-on-fhir overlay-controle`), interrupteur et plafond compris, et par l'enrobage de
sortie `operation/sortie.clj` : `docs/impacts-blaze.md` IB-08, IB-10, IB-12.

Export, cycle de vie : annulation par `DELETE` du lieu de suivi, plafond d'espace, expiration et
nettoyage périodique (composant `lifecycle/manager` de l'overlay), refus de `/__async-status`
pour les tâches d'export, reprise au démarrage ; une annulation reçue pendant la fin de l'export
n'est jamais écrasée en `completed`.

Image : `build/Dockerfile`, unique `FROM` l'image publiée épinglée par empreinte, jar du module
(`build/blaze-addon.jar`, `make jar`) copié en `/app/addon/blaze-addon.jar`, avant le jar de
Blaze dans le classpath (`make image`). Le chart la déploie sous l'étiquette
`image.blaze.tag` (`docs/configuration.md`) ; aucun registre n'est fourni.

## Prévu, non commencé

Parquet : `docs/couverture.md` §5.
