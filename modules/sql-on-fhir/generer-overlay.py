#!/usr/bin/env python3
"""Genere resources/blaze.edn : le blaze.edn du jar publie + les fonctionnalites `viewdefinition-run`,
`viewdefinition-export` et `sqlquery-run` (ADR 0003 : l'overlay masque le fichier amont en entier). L'empreinte du fichier amont est en
tete du fichier genere : `--verifier` rend ECHEC (8) si le jar ou les ajouts ont change.
Sortie : 0 ok, 3 jar absent (NON-BRANCHE), 8 ECHEC."""
import difflib, hashlib, pathlib, sys, zipfile

ICI = pathlib.Path(__file__).resolve().parent
JAR = ICI.parent.parent / ".work" / "blaze-standalone.jar"
SORTIE = ICI / "resources" / "blaze.edn"
ANCRE = "\n :features\n [;;\n"

AJOUT = """
  ;;
  ;; $viewdefinition-run (F-006, IB-08, IB-12). Allumee par defaut ;
  ;; DISABLE_VIEWDEFINITION_RUN=true l'eteint (interrupteur inverse).
  ;;
  {:key :viewdefinition-run
   :name "ViewDefinition run"
   :toggle "DISABLE_VIEWDEFINITION_RUN"
   :inverse? true
   :config
   {:blaze.rest-api/operations
    [#:blaze.rest-api.operation
      {:code "viewdefinition-run"
       :def-uri "http://sql-on-fhir.org/OperationDefinition/$viewdefinition-run"
       :affects-state false
       :resource-types ["Resource"]
       :post-middleware #blaze/ref :blaze.addon.sql-on-fhir.operation.run/raw-body
       :system-handler #blaze/ref :blaze.addon.sql-on-fhir.operation.run/handler
       :documentation "Runs a SQL on FHIR ViewDefinition against the data of this server and returns its rows. The only supported `viewReference` format is the canonical (`url` or `url|version`) of a Library stored on this server that holds the view; it is looked up locally and never downloaded."}]

    :blaze.addon.sql-on-fhir.operation.run/raw-body {}

    :blaze.addon.sql-on-fhir.operation.run/handler
    {:max-rows #blaze/cfg ["VIEWDEFINITION_RUN_MAX_ROWS" pos-int? 10000]}

    :blaze.addon.sql-on-fhir.operation.sortie/app
    {:app #blaze/ref :blaze.handler/app}

    :blaze/server
    {:handler #blaze/ref :blaze.addon.sql-on-fhir.operation.sortie/app}}}

  ;;
  ;; $viewdefinition-export (F-007, IB-08, IB-11, IB-13). Allumee par defaut ;
  ;; DISABLE_VIEWDEFINITION_EXPORT=true l'eteint (interrupteur inverse). Allumee, elle exige
  ;; VIEWDEFINITION_EXPORT_DIR (chemin absolu), ..._MAX_BYTES, ..._RETENTION_SECONDS et
  ;; ..._CLEANUP_PERIOD_SECONDS (entiers >= 1), sans valeur par defaut : absente ou mal formee,
  ;; le demarrage est refuse (les specs sont verifiees par m/pre-init-spec de chaque composant).
  ;;
  {:key :viewdefinition-export
   :name "ViewDefinition export"
   :toggle "DISABLE_VIEWDEFINITION_EXPORT"
   :inverse? true
   :config
   {:blaze.rest-api/operations
    [#:blaze.rest-api.operation
      {:code "viewdefinition-export"
       :def-uri "http://sql-on-fhir.org/OperationDefinition/$viewdefinition-export"
       :affects-state false
       :resource-types ["Resource"]
       :post-middleware #blaze/ref :blaze.addon.sql-on-fhir.operation.run/raw-body
       :system-handler #blaze/ref :blaze.addon.sql-on-fhir.operation.export/handler
       :documentation "Starts the export of SQL on FHIR ViewDefinitions to one file per view, run in the background. The only supported `viewReference` format is the canonical (`url` or `url|version`) of a Library stored on this server that holds the view; it is looked up locally and never downloaded."}]

    :blaze.addon.sql-on-fhir.operation.run/raw-body {}

    :blaze.addon.sql-on-fhir.operation.export/handler
    {:base-url #blaze/var base-url
     :context-path #blaze/cfg ["CONTEXT_PATH" string? "/fhir"]
     :clock #blaze/ref :blaze/clock}

    :blaze.job/viewdefinition-export
    {:main-node #blaze/ref :blaze.db.main/node
     :admin-node #blaze/ref :blaze.db.admin/node
     :db-sync-timeout #blaze/cfg ["DB_SYNC_TIMEOUT" pos-int? 10000]
     :dir #blaze/cfg ["VIEWDEFINITION_EXPORT_DIR" string?]
     :max-bytes #blaze/cfg ["VIEWDEFINITION_EXPORT_MAX_BYTES" pos-int?]
     :executor #blaze/ref :blaze.addon.sql-on-fhir.export.job/executor}

    :blaze.addon.sql-on-fhir.export.job/executor {}

    :blaze.addon.sql-on-fhir.export.routes/handler
    {:admin-node #blaze/ref :blaze.db.admin/node
     :job-scheduler #blaze/ref :blaze/job-scheduler
     :dir #blaze/cfg ["VIEWDEFINITION_EXPORT_DIR" string?]
     :writing-context #blaze/ref :blaze.fhir/writing-context
     :base-url #blaze/var base-url
     :context-path #blaze/cfg ["CONTEXT_PATH" string? "/fhir"]}

    :blaze.addon.sql-on-fhir.export.lifecycle/manager
    {:job-scheduler #blaze/ref :blaze/job-scheduler
     :admin-node #blaze/ref :blaze.db.admin/node
     :dir #blaze/cfg ["VIEWDEFINITION_EXPORT_DIR" string?]
     :retention-seconds #blaze/cfg ["VIEWDEFINITION_EXPORT_RETENTION_SECONDS" pos-int?]
     :period-seconds #blaze/cfg ["VIEWDEFINITION_EXPORT_CLEANUP_PERIOD_SECONDS" pos-int?]
     :scheduler #blaze/ref :blaze/scheduler
     :clock #blaze/ref :blaze/clock}

    :blaze.addon.sql-on-fhir.operation.sortie/app
    {:app #blaze/ref :blaze.handler/app
     :export-routes #blaze/ref :blaze.addon.sql-on-fhir.export.routes/handler}

    :blaze/server
    {:handler #blaze/ref :blaze.addon.sql-on-fhir.operation.sortie/app}}}

  ;;
  ;; $sqlquery-run (F-039, SQ-03, ADR 0014). Allumee par defaut ; DISABLE_SQLQUERY_RUN=true l'eteint
  ;; (interrupteur inverse). Plafonds (ADR 0014, point 5) : duree, memoire du moteur hors tas,
  ;; requetes simultanees ; le plafond de lignes est VIEWDEFINITION_RUN_MAX_ROWS, partage avec
  ;; $viewdefinition-run (L11). Valeurs par defaut : duree, memoire (64 MiB/256 MiB) et concurrence
  ;; (N=1, N=2) mesurees (T-118, bench/baselines/T-118/) ; la reserve JVM de 384 Mio reste decidee, non mesuree.
  ;;
  {:key :sqlquery-run
   :name "SQL query run"
   :toggle "DISABLE_SQLQUERY_RUN"
   :inverse? true
   :config
   {:blaze.rest-api/operations
    [#:blaze.rest-api.operation
      {:code "sqlquery-run"
       :def-uri "http://sql-on-fhir.org/OperationDefinition/$sqlquery-run"
       :affects-state false
       :resource-types ["Resource"]
       :post-middleware #blaze/ref :blaze.addon.sql-on-fhir.operation.run/raw-body
       :system-handler #blaze/ref :blaze.addon.sql-on-fhir.operation.sqlquery/handler
       :documentation "Runs a SQL on FHIR SQLQuery Library (`queryResource`, or `queryReference` to a Library stored on this server) against the views it depends on, on the current data of this server, and returns the rows. Only a SELECT query is accepted; it runs on an in-memory database without file, network or extension access. Parameters are bound by name from `parameters`, never interpolated. The size of the result is capped by the row limit of $viewdefinition-run."}]

    :blaze.addon.sql-on-fhir.operation.run/raw-body {}

    :blaze.addon.sql-on-fhir.operation.sqlquery/handler
    {:max-rows #blaze/cfg ["VIEWDEFINITION_RUN_MAX_ROWS" pos-int? 10000]
     :timeout-seconds #blaze/cfg ["SQLQUERY_RUN_TIMEOUT_SECONDS" pos-int? 30]
     :max-memory #blaze/cfg ["SQLQUERY_RUN_MAX_MEMORY" string? "256MiB"]
     :max-concurrent #blaze/cfg ["SQLQUERY_RUN_MAX_CONCURRENT" pos-int? 2]}

    :blaze.addon.sql-on-fhir.operation.sortie/app
    {:app #blaze/ref :blaze.handler/app}

    :blaze/server
    {:handler #blaze/ref :blaze.addon.sql-on-fhir.operation.sortie/app}}}

"""


def generer():
    with zipfile.ZipFile(JAR) as z:
        amont = z.read("blaze.edn")
    texte = amont.decode("utf-8")
    if texte.count(ANCRE) != 1:
        raise SystemExit(f"ECHEC : ancre :features absente ou multiple dans le blaze.edn du jar")
    entete = (f";; GENERE par generer-overlay.py : ne pas editer. blaze.edn du jar, plus les fonctionnalites\n"
              f";; viewdefinition-run, viewdefinition-export et sqlquery-run. sha256 du blaze.edn amont : {hashlib.sha256(amont).hexdigest()}\n\n")
    return entete + texte.replace(ANCRE, ANCRE + AJOUT, 1)


def main(argv):
    if not JAR.is_file():
        print(f"NON-BRANCHE : {JAR} absent (make outils)", file=sys.stderr)
        return 3
    voulu = generer()
    if argv[1:] == ["--verifier"]:
        if not SORTIE.is_file() or SORTIE.read_text(encoding="utf-8") != voulu:
            ecart = difflib.unified_diff(SORTIE.read_text(encoding="utf-8").splitlines() if SORTIE.is_file() else [],
                                         voulu.splitlines(), "resources/blaze.edn", "genere", n=0, lineterm="")
            print("ECHEC : resources/blaze.edn n'est pas celui que genere le jar courant :", file=sys.stderr)
            print("\n".join(list(ecart)[:12]), file=sys.stderr)
            return 8
        print("SUCCES : resources/blaze.edn = jar publie + ajouts")
        return 0
    SORTIE.write_text(voulu, encoding="utf-8")
    print(f"ecrit {SORTIE.relative_to(ICI)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
