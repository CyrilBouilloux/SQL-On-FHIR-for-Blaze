(ns blaze.addon.sql-on-fhir.export.token
  "AS-09 (URLs non devinables) : jetons des URL de suivi, de résultat et de fichier. Un jeton est
  tiré de `java.security.SecureRandom` : 24 octets (192 bits), en base64url sans bourrage, soit
  32 caractères de l'alphabet `A-Za-z0-9_-` (64 symboles, 6 bits chacun)."
  (:import
   [java.security SecureRandom]
   [java.util Base64]))

(set! *warn-on-reflection* true)

(def ^:private ^SecureRandom secure-random (SecureRandom.))

(defn new-token
  "Un jeton de 192 bits aléatoires cryptographiques (32 caractères)."
  []
  (let [bytes (byte-array 24)]
    (.nextBytes secure-random bytes)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))

(defn token?
  "Vrai si `x` a la forme d'un jeton : 32 caractères de `A-Za-z0-9_-`."
  [x]
  (boolean (and (string? x) (re-matches #"[A-Za-z0-9_-]{32}" x))))
