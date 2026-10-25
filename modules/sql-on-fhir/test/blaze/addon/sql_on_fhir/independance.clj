(ns blaze.addon.sql-on-fhir.independance
  "T-021 (F-005, critère « Indépendance de la base ») : JVM neuve, évaluation des 144 cas de la
  suite copiée, puis 0 namespace chargé dont le nom commence par `blaze.db` ou `blaze.interaction`.
  Hors kaocha (pas de suffixe `-test`) : la JVM d'un test partagé a déjà chargé d'autres namespaces.
  `make independance` : 0 SUCCES seulement si les 144 cas sont REUSSIS puis 0 namespace fautif ;
  8 ECHEC si le moteur est présent mais non chargeable, si un des 144 cas n'est pas REUSSIS
  (message : décompte du banc) ou si un namespace fautif est chargé ; 3 NON-BRANCHE si le moteur
  est absent (API non livrée).
  `make independance-temoin` : `(require 'blaze.db.api)` avant le contrôle doit le rendre rouge ;
  rend 0 si le contrôle est rouge, 8 sinon."
  (:require
   [blaze.addon.sql-on-fhir.conformance :as conformance]
   [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn fautifs
  "Noms des namespaces chargés qui commencent par `blaze.db` ou `blaze.interaction`."
  []
  (->> (all-ns) (map str) (filter #(re-find #"^blaze\.(?:db|interaction)" %)) sort vec))

(defn verdict
  "`[code message]` du contrôle. `jouer` : () → code du banc (imprime sa synthèse) ; `fautifs-fn` : ()
  → namespaces fautifs. Trois réponses : moteur absent → 3 ; moteur non chargeable, banc non
  entièrement REUSSI ou namespace fautif → 8 ; sinon 0."
  [moteur jouer fautifs-fn]
  (cond
    (:erreur moteur)
    [8 (str "ECHEC : moteur présent mais non chargeable : " (:erreur moteur))]

    (not (and (:compile moteur) (:run moteur)))
    [3 "NON-BRANCHE : API absente, brancher blaze.addon.sql-on-fhir.view.compile/compile et view.run/run (T-022, T-023 ; docs/couverture.md §3)"]

    :else
    (let [sortie (java.io.StringWriter.)
          code (binding [*out* sortie] (jouer))
          banc (or (last (re-seq #"(?m)^REUSSIS .*$" (str sortie))) "synthèse du banc absente")]
      (if-not (zero? code)
        [8 (str "ECHEC : les 144 cas ne sont pas tous REUSSIS (code du banc " code ") : " banc)]
        (let [f (fautifs-fn)]
          (if (empty? f)
            [0 (str "SUCCES : " banc " ; 0 namespace blaze.db / blaze.interaction chargé")]
            [8 (str "ECHEC : " (count f) " namespace(s) chargé(s) : " (str/join " " f))]))))))

(defn -main [& args]
  (let [temoin? (= ["temoin"] (vec args))]
    (System/exit
     (if temoin?
       (do (require 'blaze.db.api)
           (let [f (fautifs)]
             (if (seq f)
               (do (println "témoin rouge tenu :" (count f) "namespace(s) blaze.db/blaze.interaction chargé(s), dont" (first f)) 0)
               (do (println "témoin NON rouge : aucun namespace chargé après (require 'blaze.db.api)") 8))))
       (let [moteur (conformance/moteur-du-module)
             [code message] (verdict moteur
                                     #(conformance/jouer (constantly moteur) "conformance/sql-on-fhir-js/tests")
                                     fautifs)]
         (println message)
         code)))))
