# ADR 0003 — Le code écrit ici est un module complémentaire d'une image Blaze publiée

- **Statut** : acceptée
- **Date** : 2026-10-08
- **Élément de backlog** : F-003
- **Décideur** : humain (consigne du 2026-10-08), essai de faisabilité par le fil principal

## Contexte

Le document `docs/Architecture moteur SQL on FHIR pour Blaze.md` prévoit un module
`modules/<nom>` **dans le dépôt Blaze** (déclaré dans son `deps.edn`, sa matrice de CI), des
modifications de `modules/fhir-path` et une contribution amont. Cela suppose de construire
Blaze depuis ses sources. L'humain l'écarte : le travail prend la forme d'un **module
complémentaire** d'une **image publiée**, et si aucune voie n'existe, on **s'arrête**.

**Faisabilité — mesurée le 2026-10-08** sur `samply/blaze:1.11.0`
(`sha256:7b85c55df66122061abade67c715959b38508bb74bc28f62f262771e00f9a66c`), essai jetable hors dépôt :

| # | Fait | Commande | Obtenu |
|---|---|---|---|
| A1 | L'image lance `java -jar blaze-1.11.0-standalone.jar` ; `CMD` remplaçable | `docker image inspect samply/blaze:1.11.0 --format '{{json .Config.Cmd}}'` | `["sh","-c","… exec java -jar blaze-1.11.0-standalone.jar"]` |
| A2 | Avec `-cp <dossier>:<jar> blaze.core`, Blaze démarre et lit le `blaze.edn` du dossier **avant** celui du jar | `docker run … java -cp /addon:/app/blaze-1.11.0-standalone.jar blaze.core` | log `Feature Addon spike enabled`, puis `Successfully started … Blaze version 1.11.0` |
| A3 | Un composant Integrant de notre namespace est chargé et initialisé | idem | log `SPIKE : composant du module complementaire initialise` |
| A4 | Une opération système ajoutée par ce `blaze.edn` répond et se déclare | `curl localhost:18080/fhir/$spike` ; `GET /fhir/metadata` | `{"resourceType":"Parameters",…}` ; `spike` listée dans `rest[0].operation` |
| A5 | Témoin négatif : sans le module | `curl /fhir/$spike` sur l'image nue | HTTP **404** |
| A6 | `blaze.fhir-path/function-expression` est une multiméthode **ouverte** : `defmethod "empty"` depuis notre namespace | `(fp/eval nil (fp/compile "name.empty()") {…})` | `[true]` (avant : anomalie `unknown function`) |
| A7 | `blaze.fhir-path/FPCompiler` est un protocole **ouvert** : `or` et `%nom` s'ajoutent par `extend-protocol` sur les contextes ANTLR ; `-eval` accepte un contexte avec `:constants` | `java -cp … clojure.main ext.clj` | avant : anomalie `No implementation of method: :-compile … ExternalConstantTermContext` ; après : `[true]` et `[5]` ; arglists de `eval` inchangés `([resolver expr value])` |
| A8 | **Limite** : les namespaces de Blaze sont précompilés (AOT, `direct-linking`) : `unzip -l blaze-standalone.jar` liste `blaze/fhir_path$singleton.class` | `unzip -l .work/blaze-standalone.jar \| grep fhir_path` | redéfinir un fichier du jar par un fichier de même chemin est **inopérant et interdit** (contrôle M4) |

Conclusion : **une voie existe**, par quatre points d'extension — classpath ordonné, overlay de
`blaze.edn` (fonctionnalité à interrupteur), multiméthodes, protocoles. Elle a des limites
(A8, conséquences) ; une exigence qu'elle n'atteint pas **arrête le travail**.

## Décision

1. **Tout code que ce dépôt écrit pour Blaze est un module complémentaire**, sous
   `modules/<nom>/`, livré par `build/Dockerfile` dont l'**unique** `FROM` est l'image
   publiée `samply/blaze:${BLAZE_VERSION}@${BLAZE_IMAGE_DIGEST}` (`versions.env`). Aucun
   `latest`, aucune étiquette sans empreinte. *Deux formes, jar et image : amendement du 2026-10-17.*
2. **Interdit, pour tous les agents** : construire Blaze depuis ses sources ; modifier, forker
   ou patcher Blaze ; livrer un fichier de même chemin qu'un fichier du jar publié ;
   déclarer `amont/` ou un module Blaze dans le `:deps` d'un module ; nommer un namespace
   hors `blaze.addon.*`.
3. **Les sources de Blaze (`amont/blaze`, au tag de `BLAZE_VERSION`) sont une référence en
   lecture seule** : on y navigue, on y lit les signatures. Elles ne sont ni modifiées, ni
   livrées, ni exécutées (sauf la seconde exception de l'amendement du 2026-10-16). Ce qui s'exécute et se teste est **le jar de l'image publiée**.
4. **On étend par les points d'extension publics** : overlay de `blaze.edn` (fonctionnalité
   à interrupteur par variable d'environnement, comme les opérations amont), `ig/init-key`,
   `defmethod` sur les multiméthodes ouvertes, `extend-protocol` sur les protocoles ouverts.
5. **Si une exigence n'est atteignable par aucun point d'extension, l'agent s'arrête et
   l'escalade** (cinquième cas d'escalade du §3.3 de `CLAUDE.md`, ici : mise en cause du §1).
   Il ne contourne pas par un patch, une copie ou un fork. Repli à instruire alors, par
   l'humain : service séparé parlant à l'API FHIR (perd la lecture à `t` fixe sans copie).
6. **Contrôle** : `scripts/check_module.py` (N0, dans `make check`, 8 témoins rouges
   `--test`) rend ECHEC si M1–M4 ne tiennent pas ; `reviewer` l'applique (garde-fou 6).
7. **Montée de version de Blaze** : un geste explicite — nouvelle étiquette, nouvelle
   empreinte d'image et de jar dans `versions.env`, `make outils`, `make test` puis
   `make image` ; rien n'est repris sans cette séquence.

## Conséquences

- **Le document d'architecture est amendé** (table des écarts : `docs/methode-module.md` §2) : plus de module dans le
  dépôt Blaze, plus de ligne dans sa matrice de CI ni de `Closes: #n` amont ; `SPK-03`
  (avis des mainteneurs) devient facultatif ; `INF-01` crée un module **ici**.
- **Les extensions sont globales à la JVM.** `defmethod`/`extend-protocol` sur
  `blaze.fhir-path` s'appliquent aussi à l'indexation des search parameters. Garde : ne
  jamais redéfinir un cas existant, n'ajouter que des cas absents (A6, A7) — deux exceptions
  admises : `AdditiveExpressionContext` (ci-dessous, IB-03) et `EqualityExpressionContext`
  (amendement du 2026-10-14, IB-15). Qu'aucun test
  d'indexation ne régresse se mesure par les suites amont jouées contre jar + module
  (`make -C modules/sql-on-fhir test-amont`, harnais T-005) *(amendé le 2026-10-09)*. `db` ne
  passe pas entier **contre le jar seul** : relevé complet `336 tests, 8675 assertions, 17 errors,
  58 failures.`, identique avec le module vide (`docs/comptes-rendus/T-005.md`) ; jar compilé en
  `:direct-linking true` (`amont/blaze/build.clj:99`), cause *présumée*, non éprouvée test par
  test. D'où le critère F-004 « Indexation inchangée » (a)–(d) : `fhir-path` à 0, `db` privé
  d'une liste d'exclusion contrôlée à 0, relevés complets égaux — liste et contrôles : T-015 ;
  rejoué T-008…T-014. Mêmes conditions par module pour le dépôt enveloppé (F-006, IB-07) —
  sans objet depuis le 2026-10-10 : aucune enveloppe (ADR 0005, amendement « corps brut »).
- **`AdditiveExpressionContext` est déjà implémenté** pour `+` seul : y ajouter `-` revient à
  **remplacer** l'implémentation globale. À traiter comme cas limite de `FP-09` : reprendre
  `+` à l'identique, ou escalader si l'identité ne se prouve pas.
- **L'overlay de `blaze.edn` masque le fichier amont en entier** (A2). Le copier à la main
  dérive à chaque montée de version : il est **généré** depuis le jar (amont + ajouts), avec
  l'empreinte du `blaze.edn` amont comme témoin. Réalisé le 2026-10-12 (T-032) : `modules/sql-on-fhir/generer-overlay.py`, `make -C modules/sql-on-fhir overlay-controle`.
  Alternative instruite plus bas.
- **Dépendances Maven du module** (Parquet) : elles s'ajoutent au classpath derrière le jar ;
  un conflit de version avec le jar se résout en faveur du jar. *À mesurer* au premier ajout.
- **Pas de contribution amont** : le travail ne rejoint pas Blaze ; la maintenance suit ses
  versions (point 7).
- **Démarrage** : sources `.clj` chargées au lancement, non compilées. *À mesurer* ; la
  compilation AOT du module est une option ultérieure.
- **Parité JVM** : l'image tourne en JDK 25.0.3+9 ; le poste de développement installe le
  même (`JDK_VERSION`).

## Alternatives écartées

- **Module dans le dépôt Blaze + contribution amont** (plan d'origine) : exige de construire
  Blaze depuis ses sources, contraire à la consigne.
- **Construire un uberjar Blaze personnalisé** (`clojure -T:build uber` avec le module) :
  même raison ; l'artefact n'est plus celui qui est publié.
- **Patcher `blaze/fhir_path.clj` dans le jar** : inopérant (A8, classes AOT) et fragile.
- **Fusion de `blaze.edn` à l'exécution** (classloader enveloppant) : sans fichier à
  versionner, mais magie à l'exécution, plus difficile à tester. Retenue en repli si la
  génération à la construction échoue.
- **Service séparé via l'API FHIR** : toujours possible, mais ne lit pas la base à `t` fixe
  ni sans copie — c'est le repli du point 5, pas le chemin nominal.

## Amendements

### 2026-10-09 — fusion admise ; registre des impacts (décision humaine)

1. **Ajouter un cas absent à un protocole ouvert par fusion avec l'implémentation existante
   n'est pas « redéfinir un cas existant »** (conséquences, « Les extensions sont globales ») :
   c'est admis. Forme : `(extend cls proto (assoc (get-in proto [:impls cls]) :-m f))`, chargée
   après le namespace de Blaze. `extend` seul **remplace** la map de la classe : mesuré sur
   le jar publié le 2026-10-09, `-compile` de `InvocationExpressionContext` perdu (anomalie
   `fault` `No implementation of method: :-compile`), conservé `identical?` par fusion
   (`docs/impacts-blaze.md`, M-d, M-e). Chaque fusion se livre avec un **test de
   non-régression** : toute méthode présente avant chargement est `identical?` après.
   Remplacer une méthode existante (`AdditiveExpressionContext`) reste hors de cette règle.
2. **Tout impact sur l'architecture de Blaze et toute utilisation particulière d'un point
   d'extension figure dans `docs/impacts-blaze.md`** (fichier vivant), **avant** sa livraison :
   point touché, source au tag, ajout, piège, test de non-régression, `F-xxx`/ADR, statut.
   Un diff qui ajoute un `defmethod`, `extend`, `extend-protocol` ou `ig/init-key` sur Blaze
   sans ligne dans ce registre est refusé par `reviewer`. Contrôle automatique dans
   `scripts/check_module.py` : réalisé (M6).

### 2026-10-09 — non-régression de l'indexation : sous-ensemble contrôlé (décision humaine)

Le jar publié ne passe pas la suite amont `db` entière (conséquences, « Les extensions sont
globales ») ; la reconstruire sans direct-linking est exclu (point 3, §1.10). La
non-régression se juge sur `fhir-path` entier, sur `db` privé d'une liste d'exclusion écrite
dans le dépôt et contrôlée (chaque test exclu échoue seul sur le jar seul), et sur l'égalité
des relevés complets jar seul / jar + module : F-004 « Indexation inchangée » (a)–(d),
F-006 « Suites amont rejouées » — critère retiré le 2026-10-10 (aucune enveloppe du dépôt de
StructureDefinition, ADR 0005, amendement « corps brut »).

### 2026-10-14 — `~` et `!~` : seconde exception de remplacement, sur `EqualityExpressionContext` (décision humaine Q1, F-017, T-077)

1. **Constat.** Le jar implémente `-compile` de `fhirpathParser$EqualityExpressionContext` par
   un `case` sur `=` et `!=` seuls (`amont/blaze/modules/fhir-path/src/blaze/fhir_path.clj:472-482`).
   Commande (forme M-b de `docs/impacts-blaze.md`, classe `org.cqframework.cql.gen.fhirpathParser$EqualityExpressionContext`) :
   clés de l'implémentation → attendu `(:-compile)`, obtenu `(:-compile)` ;
   `(fp/compile "name.family ~ 'x'")` → anomalie `fault` `No matching clause: ~`, 2026-10-14.
   Aucune `defmethod` ni classe sans implémentation n'atteint `~` : ajouter un cas, c'est
   **remplacer** la méthode existante.
2. **Décision.** Le remplacement de ce `-compile` est **admis**, seconde exception après
   `AdditiveExpressionContext` (IB-03, conséquences ci-dessus), aux mêmes conditions :
   forme par fusion (`extend` sur `(get-in … [:impls cls])`, amendement du 2026-10-09) ;
   `-compile` d'origine capturé par `defonce` avant remplacement ; `=` et `!=` **délégués à
   l'original**, à l'identique ; seuls `~` et `!~` sont ajoutés. Toute autre classe dont
   `-compile` existe reste hors de la règle : arrêt et escalade (point 5).
3. **Conditions de livraison** (T-079), **mesurées le 2026-10-14** (relevé ci-dessous) : test U montrant `=` et `!=` compilés par
   la fonction d'origine (`identical?`) ; `make -C modules/sql-on-fhir test-amont SORTIE=<s>`
   puis `test-amont-controle SORTIE=<s>` → `SUCCES` (critère F-004 « Indexation inchangée »
   (a)–(d), rejoué) ; ligne IB-15 de `docs/impacts-blaze.md` passée `livré`. Si l'identité de
   `=`/`!=` ne se prouve pas : arrêt et escalade.
   Relevé (`architect`, 2026-10-14, après T-079) : `egalite-deleguee-test`, `egalite-fusion-test`
   verts dans `make -C modules/sql-on-fhir test` → `207 tests, 1618 assertions, 0 failures.` ;
   `test-amont SORTIE=t079arch` → (a) 25/0 jar et module, (b) 280/0, (d) jar 58 échecs, module 59,
   diff exactement `composite-test/create-test` (liste `A`), code 0 ; `test-amont-controle
   SORTIE=t079arch` → `SUCCES : 29 sur 29`, `I` 2/2, `A` 3/3, code 0 ; IB-15 `livré`. Témoin rouge
   (T-079) : `=` recompilé sans l'original → `identical?` rouge (`docs/comptes-rendus/T-079.md`).

### 2026-10-16 — batterie `.github/scripts` exécutée en place contre une image (F-030) — **accepté par l'humain le 2026-10-16**

1. **Constat.** Le point 3 et `CLAUDE.md` §1.10 disent d'`amont/` : « ni exécutées ». F-030
   exige d'exécuter la batterie du job `integration-test:` (`amont/blaze/.github/scripts`,
   165 fichiers `*.sh`, `/usr/bin/find amont/blaze/.github/scripts -name '*.sh' | wc -l` → 165,
   2026-10-16) contre l'image publiée et contre l'image du module. Précédent : les suites de
   test amont tournent déjà sous alias `:test` contre le jar (amendements du 2026-10-09, M3).
2. **Décision proposée.** Le point 3 admet une seconde exception : les scripts de
   `amont/blaze/.github/scripts` et le jeu `amont/blaze/.github/test-data` **s'exécutent en
   place**, comme **clients HTTP** d'un serveur déjà lancé, par la seule commande de F-030, sur
   un banc jetable. Conditions, chacune mesurée par un critère de F-030 :
   (a) jamais copiés — aucun fichier suivi n'a l'empreinte sha256 d'un des 165 scripts ;
   (b) jamais modifiés — `git -C amont/blaze status --porcelain .github` vide après exécution ;
   (c) jamais construits ni livrés — rien d'`amont/` dans `build/` (M2 inchangé) ni dans
   `kubernetes/chart/` ; le `docker-compose.yml` de `.github/standalone-test` n'est **pas**
   exécuté (§1.7) : le serveur est déployé par le chart.
   Ce qui est éprouvé reste le jar de l'image publiée ; les scripts n'en sont que les clients.
   Toute autre exécution d'`amont/` reste interdite.
3. **Garde-fou §4.6 tenu.** M1–M6 inchangés ; la commande vit sous `scripts/`, hors du domaine
   de M2 (`build/Dockerfile*`, `build/*.sh`). Relevé (`architect`, 2026-10-16) :
   `python3 scripts/check_module.py` → M1–M6 `SUCCES`, code 0 ; `--test` → tous `ok`, code 0,
   dont le témoin « M2 : build depuis amont/ » (`COPY amont/blaze /src` ajouté → `ECHEC`).
4. **Escalade (§1, `CLAUDE.md` §3.3).** Acceptée par l'humain le 2026-10-16 ; `CLAUDE.md` §1.10
   réécrit en conséquence, phrase retenue : « Les sources de Blaze
   (`amont/`) se **lisent** et ne se livrent pas ; elles ne s'exécutent que comme instruments
   de test : suites sous alias `:test` (F-004), batterie `.github/scripts` en place (F-030,
   ADR 0003 amendement du 2026-10-16). »

### 2026-10-17 — le module est livré sous deux formes complémentaires (décision humaine, F-034, F-035, F-037)

1. **Constat.** La Décision 1 dit le module « livré par `build/Dockerfile` ». Aujourd'hui l'image
   copie le **dossier** des sources non compilées devant le jar de Blaze
   (`/usr/bin/grep -c 'COPY staging/addon' build/Dockerfile` → `1` ; `CMD … -cp "/app/addon:/app/*"
   blaze.core`, 2026-10-17). F-034 demande un jar, F-035 son dépôt en artefact de version, F-037
   son emploi devant l'image publiée **sans** image construite.
2. **Décision.** La Décision 1 se lit désormais : le module est livré sous **deux formes
   complémentaires**, issues des mêmes sources de `modules/<nom>/` —
   (a) **le jar du module** : archive de ses `src/` et `resources/`, **jamais fusionné** avec le
   jar de Blaze, posé **devant** le jar de l'image publiée épinglée
   (`samply/blaze:${BLAZE_VERSION}@${BLAZE_IMAGE_DIGEST}`) dans le classpath ; aucune entrée de
   même chemin qu'une entrée de ce jar, hors l'overlay `blaze.edn` déjà admis (A2) ;
   téléchargeable en artefact de version (F-035), jamais versionné dans git ;
   (b) **l'image** construite par `build/Dockerfile`, unique `FROM` l'image publiée épinglée par
   empreinte, qui **copie ce jar** devant le jar de Blaze — même empreinte (F-034, « Image depuis
   la seule copie »).
   Les interdits du point 2 et l'alternative écartée « uberjar personnalisé » sont inchangés.
3. **Mesure.** Entrées communes, sources du module contre le jar de l'image :
   `LC_ALL=C comm -12 <(git ls-files modules/sql-on-fhir/src modules/sql-on-fhir/resources | sed -E
   's#^modules/sql-on-fhir/(src|resources)/##' | LC_ALL=C sort -u) <(unzip -Z1
   .work/blaze-standalone.jar | /usr/bin/grep -v '^META-INF/' | LC_ALL=C sort -u)` → attendu
   `blaze.edn`, obtenu `blaze.edn` (2026-10-17). Classpath avec un **jar** devant au lieu d'un
   dossier (A2 mesuré avec un dossier) : *décidé, non mesuré* — critères F-034 « Jar devant, sans
   redéfinition », « Image depuis la seule copie », F-037 « Module chargé ».
4. **Garde-fou §4.6 tenu.** Aucun build de Blaze : le jar ne contient que le module.
   `python3 scripts/check_module.py` → M1–M6 `SUCCES`, code 0 (2026-10-17, avant tout jar). Que M4
   contrôle aussi le jar construit : *à décider* dans la tâche de F-034.
5. **`CLAUDE.md` §1.10** (humain) : « module complémentaire d'une image publiée » reste vrai ;
   « La cible est `build/Dockerfile` » devient incomplet (deux cibles) — reformulation signalée à
   l'humain, non faite ici.

### 2026-10-17 (suite) — mesures de T-098 à T-100 (F-034), en ajout aux passages devenus faux

Les passages ci-dessous restent écrits tels quels ; ils se lisent corrigés ainsi. Mesures
rejouées par `architect` le 2026-10-17 dans une copie neuve (`scripts/publier.sh`, `git init`,
un commit, sans `.outils/`, `.work/` ni `amont/`).

1. **Décision, point 6** (« 8 témoins rouges `--test` ») : `python3 scripts/check_module.py --test`
   → `21` lignes `ok`, `0` `ROUGE`, code 0.
2. **Amendement du 2026-10-17, point 1** (« l'image copie le **dossier** », `COPY staging/addon`
   → `1`) : l'image copie désormais le **jar** du module. `/usr/bin/grep -c 'COPY staging/addon'
   build/Dockerfile` → `0` ; `/usr/bin/grep -cE '^FROM ' build/Dockerfile` → `1` ;
   `docker image inspect -f '{{json .Config.Cmd}}'` → `… java -cp "/app/addon/blaze-addon.jar:/app/*"
   blaze.core`. Jar : `build/construire-jar.sh` (`make jar`) → `build/blaze-addon.jar`, 52 entrées,
   aucune `META-INF/`, deux constructions → même `sha256` ; `sha256sum` du jar lu dans l'image =
   celui du jar construit.
3. **Amendement du 2026-10-17, point 3** (classpath avec un jar devant : *décidé, non mesuré*) :
   **mesuré**. `LC_ALL=C comm -12` des entrées hors `META-INF/` du jar du module et du jar extrait
   de `samply/blaze:${BLAZE_VERSION}@${BLAZE_IMAGE_DIGEST}` → `blaze.edn` seule. Image démarrée,
   `GET /fhir/metadata | jq '[.rest[0].operation[]?.name | select(test("^viewdefinition-(run|export)$"))] | length'`
   → attendu `2`, obtenu `2` ; témoin, image publiée de base seule → `0`. L'image du module ne
   démarre qu'avec les quatre variables `VIEWDEFINITION_EXPORT_DIR`, `_MAX_BYTES`,
   `_RETENTION_SECONDS`, `_CLEANUP_PERIOD_SECONDS` (`modules/sql-on-fhir/resources/blaze.edn`).
4. **Amendement du 2026-10-17, point 4** (« Que M4 contrôle aussi le jar construit : *à décider* ») :
   **tranché, fait** (T-099). M4 lit `build/blaze-addon.jar` : seule entrée commune admise
   `blaze.edn` ; entrée `blaze/core.clj` ajoutée → `ECHEC M4` nommant l'entrée, code 8 ; jar absent
   → `NON-BRANCHE M4`, jamais `SUCCES`. Que `make check` dépende de `make jar`, pour que M4 juge le jar des
   sources courantes et jamais un jar périmé : décidé par `architect` le 2026-10-17, non mesuré
   (prérequis de T-102).
5. **Amendement du 2026-10-17, point 5** (fin du point 4 : « décidé, non mesuré (prérequis de
   T-102) ») : **mesuré**. `Makefile` : `check: jar $(CONTROLES_INTERNES)`, `jar` reconstruit à
   chaque `make check`. `make -n check | /usr/bin/grep -c construire-jar` → attendu `1`, obtenu `1`
   (2026-10-17, deux exécutions identiques, `architect`) ; témoin, avant la dépendance → `0`. Copie
   fraîche (`git init`, un commit) : `make check` code 0, `SUCCES M1`…`M6` ; jar supprimé puis
   `make check` → jar recréé, même empreinte, `SUCCES M4` (`docs/comptes-rendus/T-102-prerequis.md`).
6. **Amendement du 2026-10-17, point 6** (décision humaine) : l'exemption de M5
   (`scripts/exemptions-couverture.txt`, `memoire_test.clj`) est **maintenue**. Elle ne vaut que
   pour le dépôt de développement ; le test n'est pas publié, `docs/couverture.md` n'a pas à le
   citer dans la copie diffusée.
