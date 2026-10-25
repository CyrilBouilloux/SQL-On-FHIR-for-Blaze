;; Mesure du perimetre FHIRPath de F-004 : combien des 18 expressions de la table
;; « Travaux 1 » du document d'architecture s'evaluent-elles sur le jar publie ?
;; Rejeu (commande R de F-004, registre de types initialise — IB-06) :
;;   cd modules/sql-on-fhir && eval "$(../../scripts/outils.sh env)" &&
;;   clojure -Sdeps '{:deps {blaze/publie {:local/root "../../.work/blaze-standalone.jar"}}}' -M -e "(require 'blaze.fhir.structure-definition-repo 'blaze.fhir-path)(integrant.core/init-key :blaze.fhir/structure-definition-repo {})" ../../scripts/mesure_fhirpath.clj
;; Attendu aujourd'hui : « evaluables: 0/18 ».
(require '[blaze.fhir-path :as fp] '[blaze.anomaly :as ba] '[blaze.fhir.spec.type :as t])
(def p {:fhir/type :fhir/Patient :id "x"
        :name [{:fhir/type :fhir/HumanName :family "Doe" :given ["A" "B"]}]
        :active true})
(def exprs
  [["empty()" "name.empty()"] ["not()" "active.not()"] ["join()" "name.given.join(' ')"]
   ["getResourceKey()" "getResourceKey()"] ["getReferenceKey()" "subject.getReferenceKey(Patient)"]
   ["lowBoundary()" "birthDate.lowBoundary()"] ["highBoundary()" "birthDate.highBoundary()"]
   ["or" "active or false"] ["<" "1 < 2"] ["<=" "1 <= 2"] [">" "2 > 1"] [">=" "2 >= 1"]
   ["+" "1 + 2"] ["-" "3 - 1"] ["*" "2 * 3"] ["/" "6 / 2"] ["$this" "name.where($this.family = 'Doe')"] ["%nom" "%seuil"]])
(def ok (atom 0))
(doseq [[k e] exprs]
  (let [c (fp/compile e)
        r (if (ba/anomaly? c) c (fp/eval nil c p))]
    (when-not (ba/anomaly? r) (swap! ok inc))
    (println (if (ba/anomaly? r) "NON " "OUI ") k "   " e)))
(println (str "evaluables: " @ok "/" (count exprs)))
