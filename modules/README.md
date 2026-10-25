# modules/ — le code écrit ici

Tout code que ce dépôt écrit pour Blaze est un **module complémentaire** de l'image Blaze
**publiée** (ADR 0003). Un dossier = un module = un projet Clojure.

| Module | Rôle | Plan |
|---|---|---|
| [sql-on-fhir/](sql-on-fhir/) | `$viewdefinition-run` / `$viewdefinition-export` (SQL on FHIR) | `docs/Architecture moteur SQL on FHIR pour Blaze.md` |

Avant d'écrire : `docs/methode-module.md` (procédure, organisation des sources) et
`docs/couverture.md` (fonctionnalité → tâche → source → test). Interdits : modifier ou
copier un fichier de Blaze, déclarer `amont/` hors alias `:test`, nommer un namespace hors
`blaze.addon.*` — `make check` les refuse. Si une exigence n'est atteignable par aucun point
d'extension, on **s'arrête et on escalade** (ADR 0003, décision 5).
