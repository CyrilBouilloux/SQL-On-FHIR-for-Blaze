#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Verifie qu'un fichier de versions ne contient plus aucune entree TODO-VERSION.

Regle du projet : aucune reference a `latest`, et aucun numero invente. Une
entree vide est un blocage assume, pas un defaut a contourner.

Prend le chemin du fichier en argument (defaut : le gabarit des versions,
a la racine du depot).
Depuis F-058/T-127, ce script n'est PAS appele sur le .env d'une instance :
celui-ci porte des cles vides legitimes (BENCH_PATIENT_ID, par exemple) que ce
controle rejetterait a tort. Les messages nomment le fichier RECU, jamais un
nom en dur.
"""

from __future__ import annotations

import pathlib
import sys


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
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
