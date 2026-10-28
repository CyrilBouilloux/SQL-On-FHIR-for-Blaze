#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Verifie qu'un fichier de versions ne contient plus aucune entree TODO-VERSION.

Regle du projet : aucune reference a `latest`, et aucun numero invente. Une
entree vide est un blocage assume, pas un defaut a contourner.

Prend le chemin du fichier en argument (defaut : le gabarit des versions,
a la racine du depot).

Sans argument, controle aussi que DUCKDB_JDBC_VERSION (versions.env) est la version
de org.duckdb/duckdb_jdbc dans modules/sql-on-fhir/deps.edn, qui ne peut pas lire
versions.env (F-039, T-116) ; un second argument designe un autre deps.edn. Sorties :
0 egales, 1 differentes (les deux fichiers et les deux valeurs sont nommes), 2 impossible
a etablir (fichier, cle ou dependance absents) — jamais 0.
Depuis F-058/T-127, ce script n'est PAS appele sur le .env d'une instance :
celui-ci porte des cles vides legitimes (BENCH_PATIENT_ID, par exemple) que ce
controle rejetterait a tort. Les messages nomment le fichier RECU, jamais un
nom en dur.
"""

from __future__ import annotations

import pathlib
import re
import sys

DEPS_PAR_DEFAUT = "modules/sql-on-fhir/deps.edn"
CLE_MOTEUR = "DUCKDB_JDBC_VERSION"
DEPENDANCE = "org.duckdb/duckdb_jdbc"


def controle_moteur(chemin: pathlib.Path, deps: pathlib.Path) -> int:
    """0 si la version du moteur est la meme dans les deux fichiers, 1 si elle differe, 2 si
    elle ne peut pas etre etablie."""
    if not deps.is_file():
        print(f"ERREUR : {deps} introuvable, version de {DEPENDANCE} non etablie", file=sys.stderr)
        return 2
    attendu = None
    for numero, ligne in enumerate(chemin.read_text(encoding="utf-8").splitlines(), 1):
        cle, _, valeur = ligne.strip().partition("=")
        if cle.strip() == CLE_MOTEUR and not ligne.lstrip().startswith("#"):
            attendu = (numero, valeur.strip())
    if attendu is None or not attendu[1]:
        print(f"ERREUR : {CLE_MOTEUR} absente de {chemin}, version non etablie", file=sys.stderr)
        return 2
    texte = re.sub(r";[^\n]*", "", deps.read_text(encoding="utf-8"))   # sans commentaires
    trouve = re.search(re.escape(DEPENDANCE) + r'\s*\{[^}]*:mvn/version\s*"([^"]+)"', texte)
    if trouve is None:
        print(f"ERREUR : {DEPENDANCE} absente de {deps}, version non etablie", file=sys.stderr)
        return 2
    if trouve.group(1) != attendu[1]:
        print(f"{chemin}:{attendu[0]}: {CLE_MOTEUR}={attendu[1]} differe de {deps} : "
              f"{DEPENDANCE} {trouve.group(1)}", file=sys.stderr)
        return 1
    return 0


def main() -> int:
    chemin = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "versions.env")
    if not chemin.is_file():
        print(f"ERREUR : {chemin} introuvable", file=sys.stderr)
        return 2

    manquantes, latest = [], []
    for numero, ligne in enumerate(chemin.read_text(encoding="utf-8").splitlines(), 1):
        nue = ligne.strip()
        if not nue or nue.startswith("#"):
            continue
        cle, _, valeur = nue.partition("=")
        valeur = valeur.strip()
        if not valeur:
            manquantes.append((numero, cle.strip()))
        elif valeur.lower() in ("latest", "master", "main", "edge"):
            latest.append((numero, cle.strip(), valeur))

    for numero, cle, valeur in latest:
        print(f"{chemin}:{numero}: {cle}={valeur} — reference flottante interdite",
              file=sys.stderr)

    if manquantes:
        print(f"{len(manquantes)} version(s) non renseignee(s) dans {chemin} :",
              file=sys.stderr)
        for numero, cle in manquantes:
            print(f"  {chemin}:{numero}: {cle}", file=sys.stderr)
        print("\nRenseigner ces valeurs a la main. scaffold.py n'invente aucun"
              " numero de version.", file=sys.stderr)

    if manquantes or latest:
        return 1

    print(f"{chemin} : toutes les versions sont renseignees et epinglees")
    if len(sys.argv) <= 1 or len(sys.argv) > 2:
        deps = pathlib.Path(sys.argv[2] if len(sys.argv) > 2 else DEPS_PAR_DEFAUT)
        code = controle_moteur(chemin, deps)
        if code == 0:
            print(f"{CLE_MOTEUR} = {DEPENDANCE} de {deps}")
        return code
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
