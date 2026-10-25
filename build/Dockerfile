# Cible du module complémentaire (ADR 0003) : l'image PUBLIÉE de Blaze, épinglée par
# empreinte, plus le jar du module (build/construire-jar.sh) posé AVANT le jar de Blaze
# dans le classpath. Aucune étape de compilation de Blaze, aucun builder : un seul FROM.
# Les deux arguments viennent de versions.env (make image) — jamais saisis ici.
ARG BLAZE_VERSION
ARG BLAZE_IMAGE_DIGEST
FROM samply/blaze:${BLAZE_VERSION}@${BLAZE_IMAGE_DIGEST}

# Le jar du module, tel que construit : même empreinte dans l'image (chemin ci-dessous).
COPY blaze-addon.jar /app/addon/blaze-addon.jar

# CMD de l'image amont, avec `-cp` au lieu de `-jar` : même JVM, mêmes options, même
# classe principale (blaze.core). `/app/*` = le jar de Blaze, quel que soit son numéro ;
# le jar du module le précède, donc son `blaze.edn` masque celui de Blaze.
CMD ["sh", "-c", "JAVA_TOOL_OPTIONS=\"${BASE_JAVA_TOOL_OPTIONS} ${JAVA_TOOL_OPTIONS}\" exec java -cp \"/app/addon/blaze-addon.jar:/app/*\" blaze.core"]
