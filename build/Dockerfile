# Cible du module complémentaire (ADR 0003) : l'image PUBLIÉE de Blaze, épinglée par
# empreinte, plus le jar du module (build/construire-jar.sh) posé AVANT le jar de Blaze
# dans le classpath. Aucune étape de compilation de Blaze, aucun builder : un seul FROM.
# Les arguments viennent de versions.env (make image) — jamais saisis ici.
ARG BLAZE_VERSION
ARG BLAZE_IMAGE_DIGEST
FROM samply/blaze:${BLAZE_VERSION}@${BLAZE_IMAGE_DIGEST}

# Le jar du module, tel que construit : même empreinte dans l'image (chemin ci-dessous).
COPY blaze-addon.jar /app/addon/blaze-addon.jar

# Le moteur SQL de $sqlquery-run (F-039, ADR 0014) : jar Maven publie, tel quel, telecharge puis verifie
# par son empreinte sha256 (versions.env) — une empreinte qui differe fait echouer la construction.
# --chmod : sans lui le fichier est en 0600, illisible de l'utilisateur non-root de l'image.
ARG DUCKDB_JDBC_VERSION
ARG DUCKDB_JDBC_SHA256
ADD --chmod=0644 --checksum=sha256:${DUCKDB_JDBC_SHA256} \
    https://repo1.maven.org/maven2/org/duckdb/duckdb_jdbc/${DUCKDB_JDBC_VERSION}/duckdb_jdbc-${DUCKDB_JDBC_VERSION}.jar \
    /app/addon/duckdb_jdbc.jar

# CMD de l'image amont, avec `-cp` au lieu de `-jar` : même JVM, mêmes options, même
# classe principale (blaze.core). `/app/*` = le jar de Blaze, quel que soit son numéro ;
# le jar du module le précède, donc son `blaze.edn` masque celui de Blaze ; le jar du moteur suit.
CMD ["sh", "-c", "JAVA_TOOL_OPTIONS=\"${BASE_JAVA_TOOL_OPTIONS} ${JAVA_TOOL_OPTIONS}\" exec java -cp \"/app/addon/blaze-addon.jar:/app/addon/duckdb_jdbc.jar:/app/*\" blaze.core"]
