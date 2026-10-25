(ns blaze.addon.sql-on-fhir.view.fichier-spec
  (:require
   [blaze.addon.sql-on-fhir.view.fichier :as fichier]
   [blaze.anomaly :as ba]
   [clojure.spec.alpha :as s]))

(s/fdef fichier/contexte
  :args (s/cat)
  :ret some?)

(s/fdef fichier/executer
  :args (s/cat :ctx some? :texte-vue string? :lignes (s/coll-of string?))
  :ret (s/or :lignes (s/coll-of string? :kind vector?) :anomaly ba/anomaly?))

(s/fdef fichier/verdict
  :args (s/cat :ctx some? :vue string? :ressources string?)
  :ret (s/keys :req-un [::code] :opt-un [::lignes ::erreur]))

(s/def ::code #{0 3 8})
(s/def ::lignes (s/coll-of string?))
(s/def ::erreur string?)

(s/fdef fichier/lancer
  :args (s/cat :vue string? :ressources string?)
  :ret ::code)
