# ADR 0015 — Contrôles de la norme sur la ViewDefinition reçue, tenus par le module

- **Statut** : acceptée le 2026-11-04 par l'humain (décision (A) du 2026-11-04, fil principal, sur le constat B1 de T-126 itération 2/2)
- **Date** : 2026-11-04
- **Élément de backlog** : F-041, F-042, F-043, F-044
- **Décideur** : humain

## Contexte

La revue T-126 (itération 2/2, `reviewer`) relève en bloquant B1 que
`modules/sql-on-fhir/src/blaze/addon/sql_on_fhir/view/validate.clj` recopie en Clojure
quatre contraintes de `StructureDefinition-ViewDefinition.json` (SQL on FHIR 2.0.0) :
`sql-expressions` (`forEach` et `forEachOrNull` exclusifs, l. 67-68), `sql-name` (nom de
vue et de constante, l. 13, 85, 95), binding `required` de `status` (l. 88, 97-99, et
présence par `require-status`, l. 112-118), types déclarés des branches d'`unionAll`
(l. 43-62). Au regard du principe directeur, c'est de la colle qui réimplémente une part
de validateur.

Aucune brique ne valide aujourd'hui une ViewDefinition **reçue par opération** :

- le validateur externe du §1.4 (`EXTERN_VALIDATOR_URL`) est appelé par Blaze avant
  **persistance** ; une vue passée en `viewResource` ou désignée par une `Library` n'est pas
  une écriture ;
- il n'est pas déployé : `/usr/bin/grep -rn EXTERN_VALIDATOR kubernetes/ demo/` → aucune
  ligne, code 1 (2026-11-04) ;
- le corps de la vue est lu brut par le module (ADR 0005, amendement du 2026-10-10) ; la
  forme de la vue est déjà jugée par `validate` (`VE-01`, ADR 0005, essai S5).

## Décision

1. Les quatre contrôles restent dans `view/validate.clj`, sur le chemin unique du moteur en
   mémoire et des opérations ; la présence de `status` est exigée aux opérations seules
   (`require-status`, décision humaine du 2026-11-03, F-043).
2. Cette décision se rouvre (amendement de cette ADR) dès qu'un validateur capable de juger
   une ViewDefinition reçue par opération est déployé par le chart.

## Conséquences

- **Coût** : quatre contraintes de la norme sont recopiées ; une évolution de la norme
  (2.0.0 vers `2.1.0-pre`, ADR 0005) n'est pas suivie d'elle-même. Le maintien repose sur
  les tests U (`validate_test.clj`), la suite de conformité (`REUSSIS 144/144`, relevé
  `bench/baselines/T-126-n2/reprise/qualite.txt`) et IT-062…IT-065.
- Le message de refus est celui du module, pas celui d'un validateur : une vue refusée ici
  et par un serveur conforme ne rend pas le même `diagnostics`.
- `reviewer` ne relève plus B1 tant que la condition du point 2 n'est pas remplie ; toute
  contrainte de la StructureDefinition **ajoutée** au module passe par un `F-xxx` et un
  amendement de cette ADR.
- À surveiller : le déploiement d'un validateur (chart, `EXTERN_VALIDATOR_URL`), qui rouvre
  la décision.

## Alternatives écartées

- **Retirer les contrôles** : F-041…F-044 échouent (vues refusées par un serveur conforme
  acceptées ici), défaut que ces éléments corrigent.
- **Déléguer au validateur externe du §1.4** : non déployé, et appelé à la persistance, non
  sur une entrée d'opération ; le brancher sur l'opération exigerait le validateur, son
  serveur de terminologie et le paquet de l'IG — hors du minimum de la norme demandé
  (consigne humaine du 2026-11-03). *Décidé, non mesuré* : coût de déploiement non relevé.
- **Embarquer une bibliothèque de validation de profils dans le module** : nouvelle brique
  lourde dans l'image pour quatre contraintes, sans mesure ; et un validateur générique
  écrit ici relève de « Ce que ce projet n'est pas ».

## Amendements
