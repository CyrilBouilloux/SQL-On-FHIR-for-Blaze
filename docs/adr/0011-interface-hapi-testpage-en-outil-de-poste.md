# ADR 0011 — Interface web du poste : la page de test de HAPI FHIR, hors livraison

- **Statut** : acceptée
- **Date** : 2026-10-13
- **Élément de backlog** : F-016
- **Décideur** : humain (2026-10-13), rédaction `claude`

## Contexte

Il faut une page web pour parcourir et modifier les données du banc, sans écrire de code (principe directeur) et sans toucher la livraison. Le frontend Blaze (F-015) exige Keycloak et reste différé (ADR 0010). FRED n'a que des profils DSTU2/STU3 et n'écrit pas sur le serveur ; SMART Records est hébergé chez un tiers, sans source ni image. La `testpage` de HAPI FHIR est en R4, appelle le serveur FHIR **depuis son propre serveur** (aucun CORS) et fait chercher, lire, créer, modifier, supprimer, historique. L'image `hapiproject/hapi:v8.10.0-3` est celle de `fhir-platform` (déjà présente sur le poste).

## Décision

1. **Outil de poste jetable**, lancé à la main par l'humain ou à sa demande par un LLM ; aucun fichier de la livraison (`kubernetes/`, `nginx/`, `build/`, `modules/`, `versions.env`) ne le mentionne, aucun script.
2. **Le serveur HAPI n'est qu'un hôte de la page** : base H2 en mémoire, perdue à l'arrêt ; les données restent dans Blaze.
3. **Lancement** (deux terminaux, depuis la racine du dépôt) :

```bash
# 1. Blaze joignable depuis un conteneur (reste ouvert). Le banc, sinon Blaze n'écoute que 127.0.0.1:8088.
KUBECONFIG=.banc/kubeconfig kubectl port-forward -n blaze svc/blaze --address 172.17.0.1 18080:8080

# 2. La page, sur http://localhost:8095/ (172.17.0.1 = passerelle Docker : `docker network inspect bridge`)
docker run --rm --name fhir-ui -p 127.0.0.1:8095:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:h2:mem:ui -e SPRING_DATASOURCE_USERNAME=sa -e SPRING_DATASOURCE_PASSWORD= \
  -e SPRING_DATASOURCE_DRIVERCLASSNAME=org.h2.Driver \
  -e SPRING_JPA_PROPERTIES_HIBERNATE_DIALECT=ca.uhn.fhir.jpa.model.dialect.HapiFhirH2Dialect \
  -e HAPI_FHIR_TESTER_HOME_NAME=Blaze \
  -e HAPI_FHIR_TESTER_HOME_SERVER_ADDRESS=http://172.17.0.1:18080/fhir \
  -e HAPI_FHIR_TESTER_HOME_REFUSE_TO_FETCH_THIRD_PARTY_URLS=false \
  -e HAPI_FHIR_TESTER_HOME_FHIR_VERSION=R4 \
  hapiproject/hapi:v8.10.0-3
```

   Arrêt : `Ctrl-C` dans les deux terminaux (`--rm`). Un LLM qui reçoit « ouvre l'interface web » donne ces deux commandes ; il ne les lance qu'avec l'accord de l'humain (le port-forward sur la passerelle Docker expose Blaze aux conteneurs du poste).
4. **Données synthétiques exclusivement** (garde-fou §4.4) ; l'outil écrit en direct dans Blaze, **sans** passer par nginx.

## Conséquences

- Le port-forward vise Blaze, pas nginx : les plafonds `_include`/`_revinclude` (garde-fou 2) ne s'appliquent pas à cet usage.
- Blaze fabrique ses liens de pagination avec `http://localhost:8088/fhir` : le bouton « suivant » appelle depuis le conteneur une adresse qui n'existe pas — *à mesurer* ; contournement : `_count=200`.
- Une image d'environ 1 Go et un second serveur Java sur le poste, pour une page.
- Écritures hors module : aucune validation de profil si l'humain contourne `EXTERN_VALIDATOR_URL` ; ici l'appel passe par Blaze, donc la validation de Blaze s'applique.
- Tout levier ci-dessus est *décidé, non mesuré* hors la connexion constatée par l'humain le 2026-10-13.

## Alternatives écartées

- **FRED (smart-fred)** : profils DSTU2/STU3 seulement, pas d'écriture vers le serveur.
- **SMART Records** : hébergé par un tiers, ni source ni image ; les données quitteraient le poste.
- **Frontend Blaze** : demande Keycloak (ADR 0010, F-015) — différé.
- **Écrire une interface** : réimplémente une brique, échec de conception (principe directeur).

## Amendements

Aucun.
