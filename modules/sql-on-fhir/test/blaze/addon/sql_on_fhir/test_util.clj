(ns blaze.addon.sql-on-fhir.test-util
  "Aides de test du module : registre de types hors serveur (IB-06) et instantané des cas
  existants de `blaze.fhir-path` (IB-01…IB-04, ADR 0003 amendement du 2026-10-09)."
  (:require
   [blaze.anomaly :as ba]
   [blaze.fhir-path :as fp]
   [blaze.fhir.parsing-context]
   [blaze.fhir.spec :as fhir-spec]
   [blaze.fhir.spec.type.system :as system]
   [blaze.fhir.structure-definition-repo]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [integrant.core :as ig]
   [jsonista.core :as j])
  (:import
   [java.io File]))

(set! *warn-on-reflection* true)

(defn registre-types
  "Fixture `:once` : initialise le registre des types FHIR, que seul le serveur initialise
  (IB-06). Sans lui, `ofType(HumanName)` rend `unknown FHIR type` hors serveur."
  [f]
  (ig/init-key :blaze.fhir/structure-definition-repo {})
  (f))

;; --- instantané des cas existants ---------------------------------------------------

(def ^:private additive-context
  "Classe dont `-compile` est remplacé (ADR 0003, `-` de FP-09, IB-03)."
  "org.cqframework.cql.gen.fhirpathParser$AdditiveExpressionContext")

(def ^:private equality-context
  "Classe dont `-compile` est remplacé par fusion (ADR 0003, amendement du 2026-10-14, `~` et
  `!~` de F-017, IB-15)."
  "org.cqframework.cql.gen.fhirpathParser$EqualityExpressionContext")

(defn ret-conforme?
  "Vrai si `valeur` satisfait la spec `:ret` de la fonction `sym` (`st/instrument` ne vérifie que
  les `:args`)."
  [sym valeur]
  (s/valid? (:ret (s/get-spec sym)) valeur))

(defn snapshot
  "Instantané des points d'extension de `blaze.fhir-path` : méthodes de `function-expression`
  et fonctions de chaque classe de `extenders` de `FPCompiler`."
  []
  {:methods (into {} (methods fp/function-expression))
   :impls (into {}
                (map (fn [cls] [(.getName ^Class cls)
                                (into {} (get-in fp/FPCompiler [:impls cls]))]))
                (extenders fp/FPCompiler))})

(defn differences
  "Cas de `avant` qui ne sont plus `identical?` dans `apres`, hors `AdditiveExpressionContext` et
  `EqualityExpressionContext` (remplacements admis, IB-03 et IB-15)."
  [avant apres]
  (vec
   (concat
    (for [[k f] (:methods avant)
          :when (not (identical? f (get-in apres [:methods k])))]
      [:method k])
    (for [[cls fns] (:impls avant)
          :when (not (#{additive-context equality-context} cls))
          [k f] fns
          :when (not (identical? f (get-in apres [:impls cls k])))]
      [:impl cls k]))))

(defn module-namespaces
  "Les namespaces `blaze.addon.sql-on-fhir.*` de `src/` (hors test)."
  []
  (let [racine (io/file "src")]
    (->> (file-seq racine)
         (filter #(and (.isFile ^File %) (str/ends-with? (.getName ^File %) ".clj")))
         (map #(-> (.getPath ^File %)
                   (subs (inc (count (.getPath racine))))
                   (str/replace #"\.clj$" "")
                   (str/replace "/" ".")
                   (str/replace "_" "-")
                   symbol))
         (filter #(str/starts-with? (str %) "blaze.addon.sql-on-fhir."))
         sort
         vec)))

(defn- redefinir!
  "Redéfinit `-compile` de la classe nommée en remplaçant toute sa map (piège M-d)."
  [cls-name]
  (extend (Class/forName cls-name) fp/FPCompiler
          {:-compile (fn [& _] :remplace)}))

(defn child-main
  "Exécuté dans une JVM neuve (le chargement est global, l'« avant » doit précéder tout
  chargement du module). Écrit `RAPPORT <edn>` : comptes de l'instantané, puis les écarts après
  chargement du module, après remplacement d'`AdditiveExpressionContext` (admis), puis après
  remplacement de `InvocationExpressionContext` (témoin rouge)."
  [namespaces]
  (let [avant (snapshot)
        inv "org.cqframework.cql.gen.fhirpathParser$InvocationExpressionContext"]
    (run! require namespaces)
    (let [charge (differences avant (snapshot))
          egalite-avant (get-in avant [:impls equality-context])
          egalite-apres (get-in (snapshot) [:impls equality-context])
          egalite-originale (var-get (resolve 'blaze.addon.sql-on-fhir.fhir-path.operator/original-equality-compile))
          _ (redefinir! additive-context)
          additive (differences avant (snapshot))
          _ (redefinir! inv)
          invocation (differences avant (snapshot))]
      (println "RAPPORT"
               (pr-str {:methodes (sort (map str (keys (:methods avant))))
                        :classes (count (:impls avant))
                        :extenders-avant (sort (keys (:impls avant)))
                        :invocation-avant (vec (keys (get-in avant [:impls inv])))
                        :egalite-original-identique (identical? egalite-originale (:-compile egalite-avant))
                        :egalite-compile-remplace (not (identical? (:-compile egalite-avant) (:-compile egalite-apres)))
                        :egalite-autres-fonctions-identiques
                        (every? (fn [[k f]] (identical? f (get egalite-apres k)))
                                (dissoc egalite-avant :-compile))
                        :apres-chargement charge
                        :apres-additive additive
                        :apres-invocation invocation})))))

(defn rapport-existants
  "Lance `child-main` dans une JVM neuve, de même classpath, et rend son rapport (map)."
  []
  (let [java (str (System/getProperty "java.home") "/bin/java")
        code (str "(require 'blaze.addon.sql-on-fhir.test-util)"
                  "(blaze.addon.sql-on-fhir.test-util/child-main '"
                  (pr-str (module-namespaces)) ")")
        pb (doto (ProcessBuilder. ^"[Ljava.lang.String;"
                  (into-array String [java "-cp" (System/getProperty "java.class.path")
                                      "clojure.main" "-e" code]))
             (.redirectErrorStream true))
        process (.start pb)
        sortie (slurp (.getInputStream process))
        code-sortie (.waitFor process)
        ligne (->> (str/split-lines sortie)
                   (filter #(str/starts-with? % "RAPPORT "))
                   first)]
    (if (and (zero? code-sortie) ligne)
      (edn/read-string (subs ligne (count "RAPPORT ")))
      (throw (ex-info "JVM fille en échec" {:code code-sortie :sortie sortie})))))

;; --- attendus FHIRPath (attendus.edn, écrit par `testeur`, lecture seule) -------------------

(def attendus
  "Contenu de `attendus.edn` : `:sondes`, `:cas`, `:table-or`, `:constantes-typees`."
  (delay (-> "blaze/addon/sql_on_fhir/fhir_path/attendus.edn" io/resource slurp edn/read-string)))

(def ^:private contexte-analyse
  (delay (ig/init-key :blaze.fhir/parsing-context
                      {:structure-definition-repo
                       (ig/init-key :blaze.fhir/structure-definition-repo {})})))

(defn sonde
  "La ressource FHIR de la sonde `k` de `attendus.edn`."
  [k]
  (fhir-spec/parse-json @contexte-analyse (j/write-value-as-string (get-in @attendus [:sondes k]))))

(defn valeur-systeme
  "L'élément `x` d'une collection FHIRPath, sous la forme `{:type \"System.X\" :valeur v}` de
  `attendus.edn` ; les temporels s'écrivent en ISO 8601."
  [x]
  (let [x (cond-> x (fhir-spec/primitive-val? x) :value)
        type (system/type x)]
    {:type (->> (str/split (name type) #"-") (map str/capitalize) (apply str "System."))
     :valeur (cond-> x (#{:system/date :system/date-time :system/time} type) str)}))

(defn evaluer-avec
  "Comme `evaluer`, avec les clés `contexte` ajoutées au contexte passé à `-eval` (IB-05 : le
  module appelle `-eval` directement, l'arité de `eval` est inchangée)."
  [expression ressource contexte]
  (let [expr (fp/compile expression)]
    (if (ba/anomaly? expr)
      expr
      (let [coll (ba/try-anomaly (fp/-eval expr (merge {:resolver nil} contexte) [ressource]))]
        (if (ba/anomaly? coll) coll (mapv valeur-systeme coll))))))

(defn evaluer
  "Compile `expression` et l'évalue sur la ressource `ressource` ; rend la collection
  (`valeur-systeme` par élément) ou l'anomalie."
  [expression ressource]
  (let [expr (fp/compile expression)]
    (if (ba/anomaly? expr)
      expr
      (let [coll (fp/eval nil expr ressource)]
        (if (ba/anomaly? coll) coll (mapv valeur-systeme coll))))))
