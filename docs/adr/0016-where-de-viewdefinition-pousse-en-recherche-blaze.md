# ADR 0016 — Clauses `where` simples d'une ViewDefinition poussées en recherche Blaze (différée)

- **Statut** : proposée — **différée** (décision humaine (2) du 2026-11-04 : « prévoir une ADR pour le futur » ; aucune implémentation engagée)
- **Date** : 2026-11-04
- **Élément de backlog** : — (aucun besoin ouvert ; constat issu de F-046, T-133 ; F-048 la cite en ADR liée)
- **Décideur** : humain, sur proposition d'`architect`

## Contexte

`$viewdefinition-run` et `$sqlquery-run` lisent les ressources par
`modules/sql-on-fhir/src/blaze/addon/sql_on_fhir/data_source.clj` (`handles`, l. 50-64). Seuls
`patient`, `group` et `since` y deviennent des clauses de recherche Blaze (`d/type-query`,
`d/compartment-query` de `blaze.db.api`) ; sans eux, le type entier est lu (`d/type-list`) et le
`where` de la vue est évalué en mémoire sur chaque ressource.

Mesure (T-133, `bench/baselines/T-133/mesures-run.txt`, banc kind, 2026-11-04, banc libre) :
`POST $viewdefinition-run` d'une vue `Observation` dont le `where` retient 5 340 lignes sur
3 547 403 `Observation` dure **53,7 s, 54,3 s, 56,1 s** ; la vue `Patient` (6 001) 0,20 s. Le
coût suit le volume du type, non celui du résultat : borner une vue par `where` réduit les lignes,
pas la durée. Conséquence directe : `$sqlquery-run` (délai par défaut 30 s) refuse en `422` une
vue `Observation` de 5 340 lignes (`bench/baselines/T-133/it-049-borne.txt`).

Extrapolation linéaire, **décidé, non mesuré** : 54 s / 3,5 × 10⁶ ressources ≈ 15 µs par
ressource, soit ≈ 4 h pour 10⁹ ressources d'un type.

## Décision

Différée. Si elle est déclenchée (critères ci-dessous), la voie retenue sera :

1. **Pré-filtre, jamais substitut.** Une clause du `where` qui se traduit **sans perte** en
   paramètre de recherche R4 de Blaze (par exemple `code.coding.code = '<c>'` → `code=<c>`) est
   ajoutée aux clauses de `handles` ; le `where` complet reste évalué en mémoire sur les
   ressources rendues. La recherche ne fait que réduire l'ensemble balayé : elle doit en être un
   **sur-ensemble** des ressources que retient le `where`, sinon la clause n'est pas poussée.
2. **Dans le module, par l'API publique** `blaze.db.api` (`d/type-query`,
   `d/compartment-query`), déjà employée par `handles` : aucun patch, fork ni fichier de même
   chemin que Blaze (`CLAUDE.md` §1.10, ADR 0003).
3. **Liste fermée de formes traduisibles**, chacune portant son test d'équivalence ; toute autre
   forme (`or`, fonctions, chemins non indexés) reste évaluée en mémoire seule.

## Conséquences

- **Résultat inchangé, à prouver.** La sémantique FHIRPath (`=` sur une collection de plusieurs
  éléments rend vide) diffère de celle d'un paramètre `token` (une correspondance suffit) : le
  pré-filtre est sur-ensemble, le `where` en mémoire tranche. Exigé avant livraison : suite de
  conformité SQL on FHIR inchangée (144/144, ADR 0006), et cas N2 comparant, sur le même état,
  les lignes avec et sans pré-filtre (0 ligne d'écart) avec témoin rouge (clause poussée plus
  stricte que le `where`).
- **Coût de code** : un traducteur `where` → clauses, colle neuve à tenir face à chaque montée
  de version de Blaze (paramètres R4, indexation) ; entrée au registre `docs/impacts-blaze.md`.
- **Gain non mesuré** : la durée d'une `type-query` sur `code` à 3,5 × 10⁶ ressources est
  *à mesurer* ; sans cette mesure, aucune promesse de délai.
- **Dépendance aux index** : seuls les paramètres R4 indexés par Blaze servent ; un paramètre
  ajouté pour l'occasion passe par le garde-fou §4.1 et ne réindexe que les écritures suivantes.
- **Ne règle pas** les vues sans `where` (`observation-tous`) : leur coût reste celui du type.

## Critères de déclenchement

L'ADR sort de l'état différé, par décision humaine, quand l'un de ces faits est établi :

1. un élément de backlog exige qu'une vue filtrée d'un type de *N* ressources soit servie sous
   une durée *D*, et la mesure de T-133 rejouée à *N* (`POST $viewdefinition-run`, durée
   relevée, 3 essais) dépasse *D* ;
2. la limite de temps de `$viewdefinition-run` (F-048, `VIEWDEFINITION_RUN_TIMEOUT_SECONDS`, défaut
   30 s) doit être relevée au-delà de sa valeur par défaut pour servir
   une vue filtrée du jeu de référence ou de la démonstration.

## Alternatives écartées

- **Statu quo** (balayage du type, `where` en mémoire) : retenu **tant que** aucun critère de
  déclenchement n'est atteint ; écarté au-delà, la durée croissant avec le type et non avec le
  résultat.
- **Filtres existants `patient`, `group`, `since`** comme seule réponse : ils restreignent par
  compartiment ou par date, non par contenu ; utiles à l'appelant qui les connaît, sans effet sur
  une vue filtrée par `code`. Gardés, non suffisants. `$sqlquery-run` ne les expose pas
  (`source` refusé `400`, T-133).
- **Pushdown complet** (le `where` remplacé par la recherche) : écarté, les sémantiques FHIRPath et
  recherche R4 divergent ; le résultat pourrait changer, contre la norme SQL on FHIR.
- **Index ou stockage parallèle propre au module** : écarté (`CLAUDE.md` §1.2, « Ce que ce projet
  n'est pas » : pas de stockage parallèle).

## Amendements
