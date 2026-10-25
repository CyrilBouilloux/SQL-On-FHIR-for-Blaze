# Construction du jar et de l'image — Building the jar and the image

<!-- Tenu par `architect`. Décrit `build/construire-jar.sh`, `Makefile` (`jar`, `image`) et
     `build/Dockerfile` tels qu'ils sont ; aucune version recopiée (`versions.env` fait foi).
     État au 2026-10-17. -->

*Français ci-dessous — [English below](#english).*

## Français

Le module se livre de deux façons, construites depuis ce dossier, devenu dépôt git
(`git init`, un commit) : le **jar du module**, à poser **devant** le jar de l'image Blaze
publiée dans le classpath, et l'**image** qui l'emploie. Paramètres : `docs/configuration.md`.

### Le jar du module : `build/construire-jar.sh` (ou `make jar`)

Le jar est l'archive des fichiers **suivis par git** de `modules/*/src` et
`modules/*/resources`, chemins relatifs à ces racines, **sans compilation** : Blaze charge
les sources Clojure telles quelles. Il ne contient ni Blaze ni dépendance, et aucune entrée
`META-INF/`. Seule entrée commune avec le jar de Blaze : `blaze.edn`, l'overlay de
configuration, qui masque celui de Blaze parce que le jar du module le précède. Le script se
lance de n'importe quel répertoire ; il annonce quatre étapes :

1. `étape 1/4 : lire versions.env (clés BLAZE_VERSION, BLAZE_IMAGE_DIGEST)` — **lit**
   `versions.env` ; **produit** la ligne `image publiée visée : samply/blaze:<version>@<empreinte>`,
   l'image devant le jar de laquelle poser celui du module. Clé absente → code 3.
2. `étape 2/4 : lister les fichiers suivis de modules/*/src et modules/*/resources` — **lit**
   l'index git (`git ls-files`) ; **produit** une liste temporaire et la ligne `<n> fichiers`.
   Hors dépôt git à la racine, les fichiers sont lus sur le disque ; liste vide → code 3, aucun jar écrit.
3. `étape 3/4 : archiver (entrées triées, horodatage 1980-01-01, sans compilation)` — **lit**
   les fichiers listés ; **produit** `build/blaze-addon.jar` (entrées triées, date fixe, droits
   0644 : deux constructions du même commit donnent la même empreinte). L'archive est écrite à
   côté puis renommée : un échec ne laisse aucun jar partiel. Brique : module `zipfile` de
   Python 3, ni `zip` ni `jar` requis.
4. `étape 4/4 : imprimer l'empreinte et le chemin du jar` — **lit** `build/blaze-addon.jar` ;
   **produit** les lignes `jar : <chemin absolu>` et `sha256 : <empreinte>`, égale à
   `sha256sum build/blaze-addon.jar`.

Codes de sortie (`lib/codes.sh`) : 0 succès, 3 prérequis absent ou état non établi.

### Le jar de l'image publiée : `scripts/outils.sh jar`

Extrait le jar de Blaze de l'image publiée (`docker run --rm --entrypoint cat`) vers
`.work/blaze-standalone.jar` et refuse s'il diffère de `BLAZE_JAR_SHA256` (`versions.env`).
Il n'entre pas dans l'image du module ; `make check` le lit pour vérifier que le jar du
module ne redéfinit aucune entrée de Blaze hors `blaze.edn` (contrôle M4 de
`scripts/check_module.py` ; sans lui, M4 rend `NON-BRANCHE`).

### L'image : `make image`

`make image` reconstruit le jar (`make jar`), puis `docker build -f build/Dockerfile build`
avec `BLAZE_VERSION` et `BLAZE_IMAGE_DIGEST` lus dans `versions.env`. Étiquette :
`blaze-platform-module:dev` (variable `IMAGE` du `Makefile`, jamais `latest`).

- **lit** : `build/Dockerfile`, `build/blaze-addon.jar`, l'image `samply/blaze` épinglée par
  empreinte (unique `FROM`) ;
- **produit** : une image dont `/app/addon/blaze-addon.jar` a la même empreinte que le jar
  construit, et dont la commande est celle de l'image publiée, `-cp` au lieu de `-jar` :
  `java -cp "/app/addon/blaze-addon.jar:/app/*" blaze.core` (jar du module devant celui de
  Blaze).

L'image ne démarre qu'avec les quatre variables de `$viewdefinition-export` (ou
`DISABLE_VIEWDEFINITION_EXPORT=true`) : `docs/configuration.md`, « Variables lues par
l'image ».

---

## English

The module ships in two forms, built from this folder once it is a git repository
(`git init`, one commit): the **module jar**, placed **in front of** the jar of the published
Blaze image on the classpath, and the **image** that uses it. Parameters:
`docs/configuration.md` (English section).

### The module jar: `build/construire-jar.sh` (or `make jar`)

The jar is the archive of the **git-tracked** files of `modules/*/src` and
`modules/*/resources`, paths relative to those roots, **not compiled**: Blaze loads the
Clojure sources as they are. It contains neither Blaze nor any dependency, and no `META-INF/`
entry. The only entry shared with the Blaze jar is `blaze.edn`, the configuration overlay,
which hides Blaze's own because the module jar comes first. The script runs from any
directory; it announces four steps (printed in French, quoted verbatim):

1. `étape 1/4 : lire versions.env (clés BLAZE_VERSION, BLAZE_IMAGE_DIGEST)` (read
   `versions.env`) — **reads** `versions.env`; **produces** the line
   `image publiée visée : samply/blaze:<version>@<digest>`, the image whose jar the module jar
   goes in front of. Missing key → exit code 3.
2. `étape 2/4 : lister les fichiers suivis de modules/*/src et modules/*/resources` (list the
   tracked files) — **reads** the git index (`git ls-files`); **produces** a temporary list and
   the line `<n> fichiers`. Outside a git repository root, files are read from disk; empty list → exit code 3, no jar written.
3. `étape 3/4 : archiver (entrées triées, horodatage 1980-01-01, sans compilation)` (archive) —
   **reads** the listed files; **produces** `build/blaze-addon.jar` (sorted entries, fixed
   date, mode 0644: two builds of the same commit give the same digest). The archive is
   written alongside and then renamed: a failure leaves no partial jar. Building block:
   Python 3 `zipfile`; neither `zip` nor `jar` is needed.
4. `étape 4/4 : imprimer l'empreinte et le chemin du jar` (print digest and path) — **reads**
   `build/blaze-addon.jar`; **produces** the lines `jar : <absolute path>` and
   `sha256 : <digest>`, equal to `sha256sum build/blaze-addon.jar`.

Exit codes (`lib/codes.sh`): 0 success, 3 missing prerequisite or state not established.

### The published image's jar: `scripts/outils.sh jar`

Extracts the Blaze jar from the published image (`docker run --rm --entrypoint cat`) into
`.work/blaze-standalone.jar` and refuses it if it differs from `BLAZE_JAR_SHA256`
(`versions.env`). It does not go into the module image; `make check` reads it to verify that
the module jar redefines no Blaze entry other than `blaze.edn` (check M4 of
`scripts/check_module.py`; without it, M4 reports `NON-BRANCHE`, i.e. not connected).

### The image: `make image`

`make image` rebuilds the jar (`make jar`), then runs
`docker build -f build/Dockerfile build` with `BLAZE_VERSION` and `BLAZE_IMAGE_DIGEST` read
from `versions.env`. Tag: `blaze-platform-module:dev` (`IMAGE` variable of the `Makefile`,
never `latest`).

- **reads**: `build/Dockerfile`, `build/blaze-addon.jar`, the `samply/blaze` image pinned by
  digest (single `FROM`);
- **produces**: an image whose `/app/addon/blaze-addon.jar` has the same digest as the built
  jar, and whose command is the published image's one with `-cp` instead of `-jar`:
  `java -cp "/app/addon/blaze-addon.jar:/app/*" blaze.core` (module jar in front of Blaze's).

The image only starts with the four `$viewdefinition-export` variables (or
`DISABLE_VIEWDEFINITION_EXPORT=true`): `docs/configuration.md`, "Variables read by the image".
