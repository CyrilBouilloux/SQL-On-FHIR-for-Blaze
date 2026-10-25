#!/usr/bin/env python3
"""Garde-fou 2 (CLAUDE.md §4) : le plafond _include de nginx.conf est celui que
values.yaml déclare. Rend 0 si égaux, 1 si divergents, 2 si non établi.
"""
import re
import sys
from pathlib import Path

racine = Path(__file__).resolve().parent.parent
try:
    valeurs = (racine / "kubernetes/chart/values.yaml").read_text()
    conf = (racine / "nginx/nginx.conf").read_text()
    declare = int(re.search(r"^proxy:\n\s+plafondInclude:\s*(\d+)", valeurs, re.M).group(1))
    # {N,} répété après la première occurrence : plafond = N (N+1 occurrences refusées).
    applique = int(re.search(r"\(_include\|_revinclude\)\)\{(\d+),\}", conf).group(1))
except (OSError, AttributeError) as e:
    print(f"check_gardefou7 : NON ETABLI — {e!r}")
    sys.exit(2)

if declare != applique:
    print(f"check_gardefou7 : ECHEC — values.yaml declare {declare}, nginx.conf applique {applique}")
    sys.exit(1)
print(f"check_gardefou7 : plafond _include = {declare} (values.yaml = nginx.conf)")
