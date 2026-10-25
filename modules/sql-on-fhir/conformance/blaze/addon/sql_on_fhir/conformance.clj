(ns blaze.addon.sql-on-fhir.conformance
  "Banc de conformité (INF-02, VE-20) : joue les cas de la suite copiée `S`
  (`conformance/sql-on-fhir-js/tests/`, ADR 0006) contre le moteur de vue du module.

  Règle de passage du runner de référence (`sof-js/tests/compliance.test.js`, commit 0821b673) :
  lignes comparées sans ordre, mêmes clés, valeurs égales, nombres par valeur (`5` = `5.0`) ;
  `expectError` : la vue est refusée ; `expectColumns` : colonnes dans l'ordre attendu.

  Un cas dont l'API est absente est NON-BRANCHE, un cas faux ECHEC (CLAUDE.md §3.6 r5).
  Cœur pur : `juger`, `identiques?` ; la périphérie lit les fichiers et imprime."
  (:require
   [blaze.anomaly :as ba]
   [blaze.fhir.parsing-context]
   [blaze.fhir.spec :as fhir-spec]
   [blaze.fhir.structure-definition-repo]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [integrant.core :as ig]
   [jsonista.core :as j]))

(set! *warn-on-reflection* true)

(def ^:private mapper
  "Décision T-022 : clés en mots-clés, nombres décimaux conservés."
  (j/object-mapper {:decode-key-fn true :bigdecimals true}))

(defn- norm
  "Forme canonique pour la comparaison : nombres par valeur, clés par nom."
  [x]
  (cond
    (number? x) (.stripTrailingZeros (bigdec x))
    (map? x) (into {} (map (fn [[k v]] [(name k) (norm v)])) x)
    (sequential? x) (mapv norm x)
    :else x))

(defn identiques?
  "Vrai si les deux ensembles de lignes sont égaux sans tenir compte de l'ordre des lignes."
  [attendu obtenu]
  (= (frequencies (map norm attendu)) (frequencies (map norm obtenu))))

(defn- colonnes [rows]
  (mapv name (keys (first rows))))

(defn- abrege [x]
  (let [s (pr-str x)]
    (if (< 300 (count s)) (str (subs s 0 300) "…") s)))

(defn- verdict-lignes [{:keys [expect expectColumns]} rows]
  (cond
    (not (sequential? rows))
    [:echec (str "lignes attendues, obtenu " (abrege rows))]

    (not (identiques? expect rows))
    [:echec (str "lignes différentes : attendu " (abrege expect) ", obtenu " (abrege rows))]

    (and expectColumns (not= (mapv name expectColumns) (colonnes rows)))
    [:echec (str "colonnes : attendu " (abrege expectColumns) ", obtenu " (abrege (colonnes rows)))]

    :else [:reussi]))

(defn juger
  "Verdict d'un cas : `[:reussi]`, `[:echec message]` ou `[:non-branche message]`.
  `moteur` : `{:compile f :run g}` (fonctions ou nil si l'API est absente) ; `ressources` :
  ressources du cas, déjà lues."
  [{:keys [compile run erreur]} {:keys [view expectError] :as cas} ressources]
  (cond
    erreur [:echec (str "moteur présent mais non chargeable : " erreur)]
    (not (and compile run)) [:non-branche "API view.compile/compile ou view.run/run absente"]
    :else
    (try
      (let [compiled (compile view)
            rows (when-not (ba/anomaly? compiled) (run compiled ressources))]
        (cond
          expectError
          (if (or (ba/anomaly? compiled) (ba/anomaly? rows))
            [:reussi]
            [:echec (str "vue non refusée, lignes obtenues " (abrege rows))])

          (ba/anomaly? compiled) [:echec (str "compilation refusée : " (abrege compiled))]
          (ba/anomaly? rows) [:echec (str "exécution refusée : " (abrege rows))]
          :else (verdict-lignes cas rows)))
      (catch Throwable e
        [:echec (str "exception " (.getName (class e)) " : " (ex-message e))]))))

;; --- périphérie : contexte, fichiers, impression ------------------------------------

(defn- contexte []
  (ig/init-key :blaze.fhir/parsing-context
               {:structure-definition-repo (ig/init-key :blaze.fhir/structure-definition-repo {})}))

(defn- lire-ressource [ctx ressource]
  (let [r (fhir-spec/parse-json ctx (j/write-value-as-string ressource))]
    (if (ba/anomaly? r)
      (throw (ex-info (str "ressource illisible : " (abrege r)) {}))
      r)))

(defn api
  "La var `sym`, ou nil si son namespace est absent du classpath. Un namespace présent mais
  non chargeable lève (il n'est pas « absent »)."
  [sym]
  (when (io/resource (-> (namespace sym) (str/replace "-" "_") (str/replace "." "/") (str ".clj")))
    (requiring-resolve sym)))

(defn charger
  "Le moteur `{:compile var :run var}` des symboles donnés (nil si absent) ; s'il est présent mais
  non chargeable : `{:erreur message}`, que `juger` rend en ECHEC."
  [compile-sym run-sym]
  (try
    {:compile (api compile-sym) :run (api run-sym)}
    (catch Throwable e
      {:erreur (str (.getName (class e)) " : " (ex-message e))})))

(defn moteur-du-module []
  (charger 'blaze.addon.sql-on-fhir.view.compile/compile 'blaze.addon.sql-on-fhir.view.run/run))

(defn- fichiers [dir]
  (->> (.listFiles (io/file dir))
       (filter #(str/ends-with? (str %) ".json"))
       (sort-by str)))

(defn jouer-fichier
  "Joue les cas d'un fichier : vecteur de `[titre verdict]`. `moteur-de` : cas → moteur."
  [moteur-de ctx fichier]
  (let [{:keys [resources tests]} (j/read-value fichier mapper)
        ressources (try (mapv (partial lire-ressource ctx) resources)
                        (catch Exception e e))]
    (mapv (fn [cas]
            [(:title cas)
             (if (instance? Exception ressources)
               [:echec (ex-message ressources)]
               (juger (moteur-de cas) cas ressources))])
          tests)))

(defn- ligne [nom resultats]
  (let [n (fn [k] (count (filter #(= k (first (second %))) resultats)))]
    (format "%-24s REUSSIS %d/%d  ECHEC %d  NON-BRANCHE %d"
            nom (n :reussi) (count resultats) (n :echec) (n :non-branche))))

(defn jouer
  "Joue tous les fichiers de `dir` ; imprime une ligne par fichier, les ECHEC, puis la synthèse
  `REUSSIS n/total`. Rend le code de sortie : 0 tout passe, 8 au moins un ECHEC, 3 sinon (NON-BRANCHE)."
  [moteur-de dir]
  (let [ctx (contexte)
        par-fichier (mapv (fn [f] [(.getName ^java.io.File f) (jouer-fichier moteur-de ctx f)])
                          (fichiers dir))
        tous (mapcat (fn [[nom rs]] (map #(vector nom %) rs)) par-fichier)
        compte (fn [k] (count (filter #(= k (first (second (second %)))) tous)))]
    (doseq [[nom resultats] par-fichier]
      (println (ligne nom resultats)))
    (doseq [[nom [titre [statut message]]] tous
            :when (= :echec statut)]
      (println (str "ECHEC " nom " « " titre " » : " message)))
    (println (format "REUSSIS %d/%d  ECHEC %d  NON-BRANCHE %d"
                     (compte :reussi) (count tous) (compte :echec) (compte :non-branche)))
    (cond (= (count tous) (compte :reussi)) 0
          (pos? (compte :echec)) 8
          :else 3)))

;; --- témoin : moteur factice qui rend l'attendu, intact puis altéré -------------------

(defn- reformer
  "Attendu rendu sous une autre forme que la source : lignes inversées, entiers en décimaux
  (`5` rendu `5.0`), colonnes dans l'ordre `colonnes` si donné : la règle doit l'accepter."
  [rows colonnes]
  (->> rows
       (mapv (fn [row]
               (apply array-map
                      (mapcat (fn [k] [k (let [v (get row k)] (if (integer? v) (bigdec v) v))])
                              (or (some->> colonnes (map keyword)) (keys row))))))
       reverse
       vec))

(defn- alterer
  "Altère en mémoire une valeur de la première ligne."
  [rows]
  (update rows 0 (fn [row] (update row (key (first row)) #(str % "-ALTERE")))))

(defn- moteur-factice
  "Moteur qui ne calcule rien : rend l'attendu du cas, réformé, et altéré si `(altere? cas)`."
  [altere?]
  (fn [{:keys [expect expectError expectColumns] :as cas}]
    {:compile (fn [_] (if expectError (ba/incorrect "vue refusée") {:rows expect}))
     :run (fn [{:keys [rows]} _]
            (cond-> (reformer rows expectColumns) (altere? cas) alterer))}))

(defn temoin
  "Vérifie la règle de comparaison par un moteur factice. Vert : tous les cas passent. Altéré :
  une valeur de `basic.json` « basic attribute » modifiée en mémoire, ce cas seul passe en ECHEC.
  Rend 0 si les deux tiennent, 8 sinon."
  [dir]
  (println "--- témoin vert (attendu : REUSSIS 144/144)")
  (let [vert (jouer (moteur-factice (constantly false)) dir)]
    (println "--- témoin rouge (attendu : un ECHEC, « basic attribute »)")
    (let [rouge (jouer (moteur-factice #(= "basic attribute" (:title %))) dir)]
      (if (and (zero? vert) (= 8 rouge)) 0 8))))

(defn -main [& args]
  (let [dir "conformance/sql-on-fhir-js/tests"]
    (System/exit (if (= ["temoin"] (vec args))
                   (temoin dir)
                   (jouer (constantly (moteur-du-module)) dir)))))
