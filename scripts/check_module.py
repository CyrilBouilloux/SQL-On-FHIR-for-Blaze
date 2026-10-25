#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Contrôle N0 de l'ADR 0003 : le code écrit ici est un module complémentaire d'une
image Blaze publiée, jamais un build des sources de Blaze.

Six contrôles, trois verdicts (SUCCES, ECHEC, NON-BRANCHE) :

  M1  chaque FROM de build/Dockerfile* est l'image publiée, épinglée par empreinte
      (versions.env : BLAZE_VERSION, BLAZE_IMAGE_DIGEST), et il n'y en a qu'un
  M2  aucun fichier de build ne désigne amont/ ni ne clone/compile Blaze
  M3  aucun deps.edn de module ne porte amont/ hors alias :test ; aucun module ne déclare Blaze en :deps
  M4  aucun namespace de modules/*/src n'existe dans le jar publié, et tous sont sous blaze.addon.* ;
      le jar du module (build/blaze-addon.jar) n'a, hors META-INF/, que blaze.edn en commun avec lui
      (NON-BRANCHE si l'un des jars est absent : scripts/outils.sh jar, make jar ; ECHEC si le jar
      publié diffère de BLAZE_JAR_SHA256)

  M5  toute source et tout test de modules/*/ figure dans docs/couverture.md ; une ligne
      `réalisé` ne cite que des fichiers qui existent (ADR 0004). Un test peut être
      exempté de la citation par une ligne de scripts/exemptions-couverture.txt (chemin
      depuis la racine) : fichier facultatif, absent d'une copie publiée dont le test
      est lui aussi absent ; une exemption sans fichier est un ECHEC

  M6  chaque defmethod, extend, extend-protocol de modules/*/src visant une multiméthode ou un
      protocole d'un namespace du jar (ig/init-key : la clé) a son nom dans un segment de code
      d'une ligne du tableau « Registre » de docs/impacts-blaze.md (NON-BRANCHE si jar ou registre absent)

Sortie 0 sans ECHEC, 8 sinon (lib/codes.sh). `--test` : un témoin rouge par contrôle.
"""
from __future__ import annotations

import hashlib
import pathlib
import re
import sys
import tempfile
import zipfile

RACINE = pathlib.Path(__file__).resolve().parent.parent
EX_ECHEC = 8
JAR_MODULE = "build/blaze-addon.jar"  # produit par build/construire-jar.sh (make jar)


def versions(racine: pathlib.Path) -> dict[str, str]:
    cles = {}
    for ligne in (racine / "versions.env").read_text(encoding="utf-8").splitlines():
        if ligne.strip() and not ligne.lstrip().startswith("#") and "=" in ligne:
            k, _, v = ligne.partition("=")
            cles[k.strip()] = v.strip()
    return cles


def dockerfiles(racine):
    return sorted((racine / "build").glob("Dockerfile*"))


def m1(racine, v):
    attendu = f"samply/blaze:${{BLAZE_VERSION}}@${{BLAZE_IMAGE_DIGEST}}"
    erreurs = []
    for cle in ("BLAZE_VERSION", "BLAZE_IMAGE_DIGEST"):
        if not re.fullmatch(r"\d+\.\d+\.\d+" if cle == "BLAZE_VERSION" else r"sha256:[0-9a-f]{64}", v.get(cle, "")):
            erreurs.append(f"versions.env : {cle} absent ou mal formé")
    fichiers = dockerfiles(racine)
    if not fichiers:
        erreurs.append("build/Dockerfile absent")
    for f in fichiers:
        froms = [l.strip() for l in f.read_text(encoding="utf-8").splitlines() if re.match(r"\s*FROM\b", l, re.I)]
        if len(froms) != 1:
            erreurs.append(f"{f.name} : {len(froms)} instruction(s) FROM, une seule attendue (pas de builder)")
        for l in froms:
            if l.split()[1] != attendu:
                erreurs.append(f"{f.name} : « {l} » au lieu de « FROM {attendu} »")
    return erreurs


MOTIFS_M2 = [r"amont", r"git\s+clone", r"clojure\s+-T:build", r"\buberjar\b", r"samply/blaze\.git"]


def m2(racine, v):
    erreurs = []
    for f in dockerfiles(racine) + sorted((racine / "build").glob("*.sh")):
        for n, l in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
            if l.lstrip().startswith("#"):
                continue
            for m in MOTIFS_M2:
                if re.search(m, l):
                    erreurs.append(f"{f.relative_to(racine)}:{n}: motif interdit /{m}/ : {l.strip()}")
    return erreurs


def alias_portant(texte, mot):
    """Noms des alias (clés du map `:aliases`) dont le corps contient `mot`."""
    profondeur, courant, trouves = 0, None, []
    for m in re.finditer(r"[{}]|:[\w./-]+|[^\s{}:]+", texte):
        t = m.group(0)
        if t == "{":
            profondeur += 1
        elif t == "}":
            profondeur -= 1
        elif t.startswith(":") and profondeur == 1:
            courant = t[1:]
        elif mot in t and courant and courant not in trouves:
            trouves.append(courant)
    return trouves


def m3(racine, v):
    erreurs = []
    for d in sorted((racine / "modules").glob("*/deps.edn")):
        t = d.read_text(encoding="utf-8")
        sans_commentaires = "\n".join(l for l in t.splitlines() if not l.lstrip().startswith(";"))
        # Le texte avant `:aliases` est le :paths/:deps de premier niveau.
        haut = sans_commentaires.split(":aliases")[0]
        if "amont" in haut:
            erreurs.append(f"{d.relative_to(racine)} : amont/ dans :paths/:deps de premier niveau")
        if re.search(r"\bblaze/[\w.-]+\s*\{[^}]*:(local/root|git/url|mvn/version)", haut):
            erreurs.append(f"{d.relative_to(racine)} : Blaze déclaré en :deps (il vient du jar publié)")
        for alias in alias_portant(sans_commentaires.split(":aliases", 1)[-1], "amont"):
            if alias != "test":
                erreurs.append(f"{d.relative_to(racine)} : amont/ hors alias :test (alias :{alias})")
    return erreurs


def m4(racine, v):
    jar = racine / ".work" / "blaze-standalone.jar"
    if not jar.is_file():
        return None  # NON-BRANCHE
    h = hashlib.sha256(jar.read_bytes()).hexdigest()
    if h != v.get("BLAZE_JAR_SHA256"):
        return [f"{jar.name} : empreinte {h[:12]}… ≠ BLAZE_JAR_SHA256 (scripts/outils.sh jar)"]
    with zipfile.ZipFile(jar) as z:
        noms = set(z.namelist())
    erreurs = []
    for src in sorted((racine / "modules").glob("*/src")):
        for f in sorted(p for p in src.rglob("*") if p.suffix in (".clj", ".cljc")):
            rel = f.relative_to(src).as_posix()
            ns = re.search(r"^\(ns\s+(?:\^\S+\s+)?([^\s()]+)", f.read_text(encoding="utf-8"), re.M)
            if rel in noms:
                erreurs.append(f"{f.relative_to(racine)} : existe déjà dans le jar publié (redéfinition interdite)")
            if ns and not ns.group(1).startswith("blaze.addon."):
                erreurs.append(f"{f.relative_to(racine)} : namespace {ns.group(1)} hors blaze.addon.*")
    jar_module = racine / JAR_MODULE
    if not jar_module.is_file():
        return erreurs or f"jar du module absent — {JAR_MODULE} : make jar"
    with zipfile.ZipFile(jar_module) as z:
        propres = {n for n in z.namelist() if not n.endswith("/") and not n.startswith("META-INF/")}
    communes = propres & {n for n in noms if not n.endswith("/") and not n.startswith("META-INF/")}
    for n in sorted(communes - {"blaze.edn"}):
        erreurs.append(f"{JAR_MODULE} : {n} existe déjà dans le jar publié (redéfinition interdite)")
    if "blaze.edn" not in communes:
        erreurs.append(f"{JAR_MODULE} : blaze.edn absente du jar du module (l'overlay doit masquer celui de Blaze)")
    return erreurs


def m5(racine, v):
    doc = racine / "docs" / "couverture.md"
    if not doc.is_file():
        return ["docs/couverture.md absent"]
    modules = [d for d in sorted((racine / "modules").glob("*")) if d.is_dir()]
    cites, erreurs = set(), []
    for n, ligne in enumerate(doc.read_text(encoding="utf-8").splitlines(), 1):
        if not ligne.startswith("|"):
            continue
        cols = [c.strip() for c in ligne.strip().strip("|").split("|")]
        chemins = [c for col in cols for c in re.findall(r"`([^`\s]+\.(?:clj|cljc|edn|md))`", col)]
        cites.update(chemins)
        if len(cols) >= 7 and cols[-1] == "réalisé":
            for c in chemins:
                if "…" in c or c.startswith("docs/") or c.startswith("build/") or c.startswith("tests/"):
                    continue
                if not any((m / c).is_file() for m in modules):
                    erreurs.append(f"docs/couverture.md:{n}: ligne réalisée, fichier absent : {c}")
    liste = racine / "scripts" / "exemptions-couverture.txt"
    exemptes = {l.strip() for l in liste.read_text(encoding="utf-8").splitlines() if l.strip()} if liste.is_file() else set()
    for e in sorted(exemptes):
        if not (racine / e).is_file():
            erreurs.append(f"scripts/exemptions-couverture.txt : exemption sans fichier : {e}")
    for m in modules:
        for sous in ("src", "test"):
            for f in sorted(p for p in (m / sous).rglob("*") if p.suffix in (".clj", ".cljc")):
                if f.relative_to(racine).as_posix() in exemptes:
                    continue
                if f.relative_to(m).as_posix() not in cites:
                    erreurs.append(f"{f.relative_to(racine)} : absent de docs/couverture.md")
    return erreurs


# --- M6 : les extensions de Blaze sont inscrites au registre des impacts ---

def _nettoyer(texte):
    """Chaînes vidées, commentaires retirés : seules restent les formes."""
    return re.sub(r";[^\n]*", "", re.sub(r'"(?:\\.|[^"\\])*"', '""', texte))


def _formes(t, i):
    """Sous-formes de premier niveau de la liste ou du vecteur ouvert en t[i]."""
    profondeur, courant, enfants = 0, "", []
    for j in range(i, len(t)):
        c = t[j]
        if c in "([{":
            profondeur += 1
            if profondeur == 1:
                continue
        elif c in ")]}":
            profondeur -= 1
            if profondeur == 0:
                break
        if profondeur == 1 and (c.isspace() or c == ","):
            if courant:
                enfants.append(courant); courant = ""
        else:
            courant += c
    if courant:
        enfants.append(courant)
    return enfants


def _requis(texte):
    """(alias → namespace, symbole référé → namespace) d'après le :require du ns."""
    alias, refer = {}, {}
    m = re.search(r"\(\s*ns\s", texte)
    for e in (_formes(texte, m.start()) if m else []):
        if not e.startswith("(:require"):
            continue
        for s in _formes(e, 0)[1:]:
            toks = re.findall(r"[^\s\[\],]+", s)
            alias[toks[0]] = toks[0]
            for k, tok in enumerate(toks):
                if tok == ":as" and k + 1 < len(toks):
                    alias[toks[k + 1]] = toks[0]
                if tok == ":refer":
                    for n in toks[k + 1:]:
                        if n.startswith(":"):
                            break
                        refer[n] = toks[0]
    return alias, refer


def _resoudre(sym, alias, refer):
    """(namespace, nom nu) d'un symbole, ou None s'il n'est ni qualifié ni référé."""
    if "/" in sym and not sym.startswith((":", "/")):
        a, _, nu = sym.partition("/")
        return alias.get(a, a), nu
    return (refer[sym], sym) if sym in refer else None


def extensions(texte, noms_jar):
    """[(forme, nom à inscrire)] des extensions de namespaces du jar ; nom None si non établissable."""
    t = _nettoyer(texte)
    alias, refer = _requis(t)
    trouvees = []
    for m in re.finditer(r"\(\s*(defmethod|extend-protocol|extend)\s", t):
        forme, enfants = m.group(1), _formes(t, m.start())[1:]
        if forme == "defmethod":
            cibles = enfants[:1]
        elif forme == "extend-protocol":
            cibles = enfants[:1]
        else:  # (extend Classe Protocole {…} Protocole {…} …)
            cibles = enfants[1::2]
        for sym in cibles:
            r = _resoudre(sym, alias, refer)
            if not r:
                continue
            ns, nu = r
            fichier = ns.replace(".", "/").replace("-", "_")
            if f"{fichier}.clj" not in noms_jar and f"{fichier}.cljc" not in noms_jar:
                continue
            if forme == "defmethod" and ns == "integrant.core":
                cle = enfants[1] if len(enfants) > 1 else ""
                trouvees.append((f"{forme} {sym}", cle if re.fullmatch(r":[^\s:]\S*", cle) else None))
            else:
                trouvees.append((f"{forme} {sym}", nu))
    return trouvees


def inscrits(registre):
    """Mots des segments de code des lignes du tableau de la section « Registre », ou None."""
    m = re.search(r"^## Registre\s*$(.*?)(?=^## |\Z)", registre, re.M | re.S)
    if not m:
        return None
    mots = set()
    for ligne in m.group(1).splitlines():
        if ligne.startswith("|"):
            for seg in re.findall(r"`([^`]+)`", ligne):
                for mot in re.split(r"[\s,()\[\]{}]+", seg):
                    mots.add(mot)
                    if mot and not mot.startswith(":") and "/" in mot:
                        mots.add(mot.rpartition("/")[2])
    return mots


def m6(racine, v):
    jar = racine / ".work" / "blaze-standalone.jar"
    registre = racine / "docs" / "impacts-blaze.md"
    if not jar.is_file():
        return "jar absent — scripts/outils.sh jar"
    if not registre.is_file():
        return "docs/impacts-blaze.md absent"
    mots = inscrits(registre.read_text(encoding="utf-8"))
    if mots is None:
        return ["docs/impacts-blaze.md : section « Registre » introuvable"]
    with zipfile.ZipFile(jar) as z:
        noms = set(z.namelist())
    erreurs = []
    for src in sorted((racine / "modules").glob("*/src")):
        for f in sorted(p for p in src.rglob("*") if p.suffix in (".clj", ".cljc")):
            for forme, nom in extensions(f.read_text(encoding="utf-8"), noms):
                if nom is None:
                    erreurs.append(f"{f.relative_to(racine)} : {forme} : clé non littérale, inscription non établissable")
                elif nom not in mots:
                    erreurs.append(f"{f.relative_to(racine)} : {forme} : « {nom} » absent du registre docs/impacts-blaze.md")
    return erreurs


CONTROLES = [("M1", "image publiée épinglée par empreinte", m1), ("M2", "aucun build des sources de Blaze", m2),
             ("M3", "deps.edn des modules sans Blaze ni amont/", m3), ("M4", "aucune redéfinition d'un fichier du jar", m4),
             ("M5", "sources et tests tous portés par docs/couverture.md", m5),
             ("M6", "extensions de Blaze inscrites au registre des impacts", m6)]


def executer(racine=RACINE, sortie=sys.stdout):
    v = versions(racine)
    echecs = 0
    for code, intitule, f in CONTROLES:
        r = f(racine, v)
        if r is None or isinstance(r, str):
            print(f"NON-BRANCHE {code} {intitule} — {r or 'scripts/outils.sh jar'}", file=sortie)
        elif r:
            echecs += 1
            print(f"ECHEC       {code} {intitule}", file=sortie)
            for e in r:
                print(f"            {e}", file=sortie)
        else:
            print(f"SUCCES      {code} {intitule}", file=sortie)
    return EX_ECHEC if echecs else 0


# --- témoins rouges : une altération minimale par contrôle, message d'échec littéral ---

def _arbre(tmp, avec_jar=None):
    (tmp / "build").mkdir(); (tmp / "modules/m/src/blaze/addon").mkdir(parents=True)
    (tmp / "versions.env").write_text("BLAZE_VERSION=1.11.0\nBLAZE_IMAGE_DIGEST=sha256:" + "a" * 64 + "\n")
    (tmp / "build/Dockerfile").write_text("ARG BLAZE_VERSION\nARG BLAZE_IMAGE_DIGEST\nFROM samply/blaze:${BLAZE_VERSION}@${BLAZE_IMAGE_DIGEST}\n")
    (tmp / "modules/m/deps.edn").write_text("{:paths [\"src\"]\n :aliases {:test {:extra-paths [\"../../amont/x\"]}}}\n")
    (tmp / "modules/m/src/blaze/addon/ok.clj").write_text("(ns blaze.addon.ok)\n")
    (tmp / "docs").mkdir()
    (tmp / "docs/couverture.md").write_text("| F | Plan | F-xxx | Source | Tests | Niveau | État |\n"
                                            "| a | `X-01` | F-1 | `src/blaze/addon/ok.clj` | — | U | réalisé |\n")
    if avec_jar:
        (tmp / ".work").mkdir()
        j = tmp / ".work/blaze-standalone.jar"
        with zipfile.ZipFile(j, "w") as z:
            for n in (*avec_jar, "blaze.edn", "META-INF/MANIFEST.MF"):
                z.writestr(n, "")
        with zipfile.ZipFile(tmp / "build/blaze-addon.jar", "w") as z:  # jar du module : blaze.edn seule entrée commune
            for n in ("blaze.edn", "blaze/addon/ok.clj", "META-INF/MANIFEST.MF"):
                z.writestr(n, "")
        (tmp / "versions.env").write_text((tmp / "versions.env").read_text() + "BLAZE_JAR_SHA256=" + hashlib.sha256(j.read_bytes()).hexdigest() + "\n")


def _verdict(tmp):
    import io
    s = io.StringIO(); c = executer(tmp, s); return c, s.getvalue()


def temoins():
    cas = []
    def un(nom, alterer, attendu, jar=("blaze/core.clj",)):
        with tempfile.TemporaryDirectory() as t:
            t = pathlib.Path(t); _arbre(t, jar); alterer(t); c, s = _verdict(t)
            cas.append((nom, c == EX_ECHEC and attendu in s, attendu, s))
    with tempfile.TemporaryDirectory() as t:
        t = pathlib.Path(t); _arbre(t, ("blaze/core.clj",)); c, s = _verdict(t)
        cas.append(("arbre sain → vert", c == 0 and "ECHEC" not in s, "0 échec", s))
    un("M1 : un second FROM (builder)", lambda t: (t / "build/Dockerfile").write_text((t / "build/Dockerfile").read_text() + "FROM clojure:temurin-25\n"), "2 instruction(s) FROM")
    un("M1 : étiquette sans empreinte", lambda t: (t / "build/Dockerfile").write_text("FROM samply/blaze:${BLAZE_VERSION}\n"), "au lieu de")
    un("M2 : build depuis amont/", lambda t: (t / "build/Dockerfile").write_text((t / "build/Dockerfile").read_text() + "COPY amont/blaze /src\n"), "motif interdit /amont/")
    un("M3 : amont/ en :paths de premier niveau", lambda t: (t / "modules/m/deps.edn").write_text("{:paths [\"src\" \"../../amont/blaze/src\"]}\n"), "amont/ dans :paths")
    un("M3 : Blaze en :deps", lambda t: (t / "modules/m/deps.edn").write_text("{:deps {blaze/db {:local/root \"../x\"}}}\n"), "Blaze déclaré en :deps")
    un("M4 : fichier redéfini du jar", lambda t: (t / "modules/m/src/blaze/core.clj").write_text("(ns blaze.addon.core)\n"), "existe déjà dans le jar publié", jar=("blaze/core.clj",))
    un("M4 : namespace hors blaze.addon.*", lambda t: (t / "modules/m/src/blaze/addon/ok.clj").write_text("(ns blaze.autre)\n"), "hors blaze.addon.*")
    un("M5 : source absente de la couverture", lambda t: (t / "modules/m/src/blaze/addon/nouveau.clj").write_text("(ns blaze.addon.nouveau)\n"), "absent de docs/couverture.md")
    un("M5 : ligne réalisée citant un fichier absent", lambda t: (t / "modules/m/src/blaze/addon/ok.clj").unlink(), "fichier absent : src/blaze/addon/ok.clj")
    source = ('(ns blaze.addon.ok (:require [blaze.fhir-path :as fp] [integrant.core :as ig]))\n'
              '(defmethod fp/function-expression "empty" [_])\n'
              '(extend-protocol fp/FPCompiler String (-compile [_]))\n'
              '(defmethod ig/init-key :blaze.addon/mon-composant [_ _])\n')
    reg = "## Registre\n\n| # | Point |\n|---|---|\n| IB-1 | `%s` |\n"
    jar6 = ("blaze/fhir_path.clj", "integrant/core.clj")
    for nom, registre, attendu in (
            ("M6 : forme non inscrite → ECHEC", reg % "FPCompiler", ["ECHEC       M6", "ok.clj : defmethod fp/function-expression : « function-expression » absent du registre", "ok.clj : defmethod ig/init-key : « :blaze.addon/mon-composant » absent du registre"]),
            ("M6 : forme inscrite → SUCCES", reg % "function-expression` `FPCompiler` `ig/init-key :blaze.addon/mon-composant", ["SUCCES      M6"]),
            ("M6 : registre absent → NON-BRANCHE, jamais ECHEC", None, ["NON-BRANCHE M6"])):
        with tempfile.TemporaryDirectory() as t:
            t = pathlib.Path(t); _arbre(t, jar6); (t / "modules/m/src/blaze/addon/ok.clj").write_text(source)
            if registre:
                (t / "docs/impacts-blaze.md").write_text(registre)
            c, s = _verdict(t)
            cas.append((nom, (c == EX_ECHEC) == ("ECHEC       M6" in attendu) and all(a in s for a in attendu), " ; ".join(attendu), s))
    for nom, liste, ok_attendu, attendu in (
            ("M5 : test exempté par la liste → SUCCES", "modules/m/test/blaze/addon/hors_test.clj\n", True, "SUCCES      M5"),
            ("M5 : exemption sans fichier → ECHEC", "modules/m/test/blaze/addon/absent_test.clj\n", False, "exemption sans fichier : modules/m/test/blaze/addon/absent_test.clj"),
            ("M5 : test non listé → ECHEC", "", False, "modules/m/test/blaze/addon/hors_test.clj : absent de docs/couverture.md")):
        with tempfile.TemporaryDirectory() as t:
            t = pathlib.Path(t); _arbre(t, ("blaze/core.clj",))
            (t / "modules/m/test/blaze/addon").mkdir(parents=True)
            (t / "modules/m/test/blaze/addon/hors_test.clj").write_text("(ns blaze.addon.hors-test)\n")
            (t / "scripts").mkdir(); (t / "scripts/exemptions-couverture.txt").write_text(liste)
            c, s = _verdict(t)
            cas.append((nom, (c == 0) == ok_attendu and attendu in s, attendu, s))
    with tempfile.TemporaryDirectory() as t:
        t = pathlib.Path(t); _arbre(t); c, s = _verdict(t)
        cas.append(("M4 : jar absent → NON-BRANCHE, jamais ECHEC", c == 0 and "NON-BRANCHE M4" in s, "NON-BRANCHE M4", s))
    def _remplacer_jar(t, entrees):
        with zipfile.ZipFile(t / "build/blaze-addon.jar", "w") as z:
            for n in entrees:
                z.writestr(n, "")
    un("M4 jar du module : entrée blaze/core.clj de même chemin que le jar de Blaze", lambda t: _remplacer_jar(t, ("blaze.edn", "blaze/core.clj")), "build/blaze-addon.jar : blaze/core.clj existe déjà dans le jar publié")
    un("M4 jar du module : blaze.edn absente", lambda t: _remplacer_jar(t, ("blaze/addon/ok.clj",)), "blaze.edn absente du jar du module")
    with tempfile.TemporaryDirectory() as t:
        t = pathlib.Path(t); _arbre(t, ("blaze/core.clj",)); c, s = _verdict(t)
        cas.append(("M4 jar du module sain → SUCCES M4 (seule entrée commune : blaze.edn)", c == 0 and "SUCCES      M4" in s, "SUCCES      M4", s))
    with tempfile.TemporaryDirectory() as t:
        t = pathlib.Path(t); _arbre(t, ("blaze/core.clj",)); (t / "build/blaze-addon.jar").unlink(); c, s = _verdict(t)
        cas.append(("M4 jar du module absent → NON-BRANCHE, jamais SUCCES ni ECHEC", c == 0 and "NON-BRANCHE M4" in s and "SUCCES      M4" not in s, "NON-BRANCHE M4", s))
    ko = 0
    for nom, ok, attendu, s in cas:
        print(("ok   " if ok else "ROUGE"), nom)
        if not ok:
            ko += 1; print("      attendu :", attendu, "\n      obtenu  :", s)
    return 1 if ko else 0


if __name__ == "__main__":
    raise SystemExit(temoins() if "--test" in sys.argv else executer())
