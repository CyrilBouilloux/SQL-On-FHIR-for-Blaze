#!/usr/bin/env python3
"""Tableau de bord d'avancement : compteurs réalisé / en cours / reste, lus dans
docs/backlog, docs/plan et leurs archives. Usage : python3 scripts/avancement.py
-> .work/avancement.html (à rouvrir ou rafraîchir après chaque relance)."""
import html, re, sys, textwrap
from datetime import datetime
from pathlib import Path

R = Path(__file__).resolve().parent.parent
OUT = R / ".work" / "avancement.html"
DONE, WIP, TODO = "fait", "cours", "reste"


def lire(p):
    try:
        return p.read_text(encoding="utf-8")
    except OSError as e:
        sys.exit(f"ECHEC illisible : {p} ({e})")


def criteres(texte):
    """(cochés, non cochés) des cases `- [ ]` / `- [x]`."""
    c = re.findall(r"^\s*- \[([ xX])\]", texte, re.M)
    return sum(x != " " for x in c), sum(x == " " for x in c)


# --- tâches : archive (closes) + tableau (ouvertes)
taches = {}  # id -> (F, état)
for m in re.finditer(r"^- \*\*(T-\d+)\*\* \((F-\d+),[^)]*\) · `([^`]+)`", lire(R / "docs/plan-archive.md"), re.M):
    taches[m[1]] = (m[2], DONE)
for ligne in lire(R / "docs/plan/README.md").splitlines():
    m = re.match(r"\| \[(T-\d+)\]\([^)]*\) \| (F-\d+) \| (.*?) \|.*\| (\d+)/\d+ \|[^|]*\|\s*$", ligne)
    if m:
        livree = "livrée" in m[3] or int(m[4]) > 0
        taches[m[1]] = (m[2], WIP if livree else TODO)
if not taches:
    sys.exit("ECHEC aucune tâche lue : format de docs/plan/README.md changé ?")

# --- éléments : statut courant + critères
ETAT_F = {"validé": DONE, "rejeté": DONE, "parqué": DONE, "à valider": WIP,
          "en cours": WIP, "planifié": WIP, "spécifié": TODO, "proposé": TODO}
elements = []
for f in sorted((R / "docs/backlog").glob("F-*.md")):
    t = lire(f)
    titre = re.search(r"^### (F-\d+) — (.*)$", t, re.M)
    statut = re.search(r"^- \*\*Statut\*\* : ([^—(\n]*)", t, re.M)
    if not (titre and statut):
        sys.exit(f"ECHEC {f.name} : titre ou statut introuvable")
    s = next((k for k in sorted(ETAT_F, key=len, reverse=True) if statut[1].strip().startswith(k)), "")
    if not s:
        sys.exit(f"ECHEC {f.name} : statut inconnu « {statut[1].strip()} »")
    ok, ko = criteres(t)
    elements.append(dict(id=titre[1], titre=titre[2], statut=s, etat=ETAT_F[s], ok=ok, ko=ko))
for m in re.finditer(r"^- \*\*(F-\d+)\*\*", lire(R / "docs/backlog-archive.md"), re.M):
    if not any(e["id"] == m[1] for e in elements):  # fichier déjà retiré : seule la synthèse reste
        elements.append(dict(id=m[1], titre="(archivé)", statut="validé", etat=DONE, ok=0, ko=0))


# --- composants : inventaire du document d'architecture x opérations de docs/couverture.md
CODES = {  # composant -> opérations du plan (docs/couverture.md) ; absent = existant
    "Requêtes asynchrones": ["AS-06", "AS-07"],
    "`$viewdefinition-run`": ["OP-01", "OP-02", "OP-03", "OP-04", "OP-06", "OP-09"],
    "`$viewdefinition-export`": ["AS-01", "AS-02"],
    "Déclaration des opérations": ["OP-07", "OP-08"],
    "Modèle ViewDefinition": ["VE-01", "VE-02", "VE-15"],
    "Compilateur de vue": [f"VE-{i:02d}" for i in (3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 16)],
    "Évaluateur FHIRPath": [f"FP-{i:02d}" for i in range(1, 16)],
    "Sérialiseurs de lignes": ["SR-01", "SR-02", "SR-03", "SR-04"],
    "Gestion des exports": ["AS-03", "AS-04", "AS-05", "AS-08", "AS-09", "SR-05"],
    "Planificateur de lecture": ["DS-08"],
    "Source de données": [f"DS-{i:02d}" for i in range(1, 8)],
    "Moteur SQL embarqué": ["SQ-01", "SQ-02", "SQ-03", "SQ-04"],
}
ARCHI = R / "docs" / "Architecture moteur SQL on FHIR pour Blaze.md"


def codes_de(cellule):
    jetons = re.findall(r"`([A-Z]+-\d+)`(\s*…)?", cellule)
    out, i = [], 0
    while i < len(jetons):
        c, ell = jetons[i]
        if ell and i + 1 < len(jetons):
            pre, a = c.split("-"); b = jetons[i + 1][0].split("-")[1]
            out += [f"{pre}-{n:02d}" for n in range(int(a), int(b) + 1)]
            i += 2
        else:
            out.append(c); i += 1
    return out


RANG = {TODO: 0, WIP: 1, DONE: 2}
op_etat = {}  # code -> fait|cours|reste
for ligne in lire(R / "docs/couverture.md").splitlines():
    cel = [c.strip() for c in ligne.strip().strip("|").split("|")]
    if not ligne.startswith("|") or len(cel) < 5:
        continue
    mot = re.sub(r"[`*]", "", cel[-1]).split(" ")[0]
    etat = {"réalisé": DONE, "en": WIP}.get(mot, TODO)
    for c in codes_de(cel[1]):  # une ligne de décision (§ 6) ne fait pas reculer une opération déjà réalisée
        if RANG[etat] > RANG[op_etat.get(c, TODO)]:
            op_etat[c] = etat
        else:
            op_etat.setdefault(c, etat)

composants = []
for ligne in lire(ARCHI).splitlines():
    cel = [c.strip() for c in ligne.strip().strip("|").split("|")]
    if ligne.startswith("| ") and len(cel) == 5 and cel[2] in ("Existant", "À étendre", "À développer", "Développé"):
        composants.append(dict(couche=cel[0], nom=cel[1].replace("`", ""), doc=cel[2], cle=cel[1]))
if len(composants) < 19:
    sys.exit(f"ECHEC inventaire : {len(composants)} composants lus, 19 attendus")
for c in composants:
    ops = CODES.get(c["cle"], [])
    if c["doc"] != "Existant" and not ops:
        sys.exit(f"ECHEC composant sans opérations associées : {c['nom']}")
    connues = [o for o in ops if o in op_etat]  # une opération écartée du plan (« sans objet ») ne compte plus
    if ops and not connues and not ops[0].startswith("SQ-"):
        sys.exit(f"ECHEC {c['nom']} : aucune de ses opérations n'est dans docs/couverture.md : {ops}")
    ops = connues or ops
    etats = [op_etat.get(o, TODO) for o in ops]
    c["ops"], c["faites"] = len(ops), etats.count(DONE)
    if c["doc"] == "Existant":
        c["etat"] = "exist"
    elif etats and all(e == DONE for e in etats):
        c["etat"] = DONE
    elif any(e != TODO for e in etats):
        c["etat"] = WIP
    else:
        c["etat"] = TODO


def cpt(etats):
    return {e: sum(1 for x in etats if x == e) for e in (DONE, WIP, TODO)}


def barre(c, unite=""):
    tot = sum(c.values()) or 1
    seg = "".join(
        f'<span class="seg {k}" style="width:{c[k] * 100 / tot:.2f}%" title="{c[k]}"></span>'
        for k in (DONE, WIP, TODO) if c[k])
    return f'<div class="bar">{seg}</div>'


def carte(titre, c):
    tot = sum(c.values())
    pct = round(c[DONE] * 100 / tot) if tot else 0
    chiffres = "".join(
        f'<div class="n {k}"><b>{c[k]}</b><i>{lib}</i></div>'
        for k, lib in ((DONE, "réalisé"), (WIP, "en cours"), (TODO, "reste")))
    return f'<section class="card"><h2>{titre}<em>{pct} %</em></h2><div class="nums">{chiffres}</div>{barre(c)}</section>'


# --- rendu composants + diagramme UML
NOMS_COURTS = {c["nom"]: c for c in composants}
BH = 66
POS = {  # composant -> (x, y, largeur) ; hauteur BH
    "$viewdefinition-run": (40, 70, 200), "Moteur SQL embarqué": (260, 70, 200), "$viewdefinition-export": (480, 70, 200),
    "Compilateur de vue": (40, 160, 200), "Sérialiseurs de lignes": (260, 160, 200), "Gestion des exports": (480, 160, 200),
    "Modèle ViewDefinition": (40, 250, 200), "Source de données": (260, 250, 200), "Planificateur de lecture": (480, 250, 200),
    "Suite de conformité SQL on FHIR": (750, 70, 210),
    "API FHIR de Blaze": (30, 400, 130), "Déclaration des opérations": (30, 485, 130),
    "Évaluateur FHIRPath": (175, 400, 150), "Types FHIR internes": (175, 485, 150),
    "Valeur de base à t": (357, 410, 155), "Index des versions et du temps": (522, 410, 155),
    "Document store": (357, 490, 155), "Index de recherche et compartiments": (522, 490, 155),
    "Requêtes asynchrones": (740, 440, 170),
}
CIBLE = {"Stockage": (345, 392, 345, 170)}  # sous-paquetage Blaze : (x, y, l, h), cible de flèches
LIENS = [  # dépendances UML (source -> cible), flèches droites
    ("$viewdefinition-run", "Compilateur de vue"), ("$viewdefinition-run", "Source de données"),
    ("$viewdefinition-run", "Sérialiseurs de lignes"), ("$viewdefinition-export", "Gestion des exports"),
    ("$viewdefinition-export", "Sérialiseurs de lignes"), ("Gestion des exports", "Sérialiseurs de lignes"),
    ("Gestion des exports", "Source de données"), ("Compilateur de vue", "Modèle ViewDefinition"),
    ("Évaluateur FHIRPath", "Types FHIR internes"), ("Déclaration des opérations", "API FHIR de Blaze"),
    ("Moteur SQL embarqué", "Compilateur de vue"), ("Source de données", "Stockage"),
    ("Planificateur de lecture", "Stockage"),
]
CHEMINS = [  # dépendances routées par une gouttière : points successifs
    [(240, 193), (250, 193), (250, 400)],                 # Compilateur -> Évaluateur FHIRPath
    [(680, 103), (722, 103), (722, 473), (740, 473)],     # $viewdefinition-export -> Requêtes asynchrones
]


def rect(n):
    if n in CIBLE:
        x, y, w, h = CIBLE[n]; return x, y, w, h
    x, y, w = POS[n]; return x, y, w, BH


def bord(a, b):
    """Point où le segment centre(a)->centre(b) sort du rectangle a."""
    ax, ay, aw, ah = rect(a); bx, by, bw, bh = rect(b)
    cx, cy = ax + aw / 2, ay + ah / 2
    dx, dy = bx + bw / 2 - cx, by + bh / 2 - cy
    k = min((aw / 2) / abs(dx) if dx else 9e9, (ah / 2) / abs(dy) if dy else 9e9)
    return cx + dx * k, cy + dy * k


def boite(nom):
    c = NOMS_COURTS[nom]; x, y, w = POS[nom]
    lignes = textwrap.wrap(nom, int((w - 24) / 7.5))
    prog = "existant" if c["etat"] == "exist" else f'{c["faites"]}/{c["ops"]} opérations' + (" · étendu" if c["doc"] == "À étendre" else "")
    stereo = "«component»"
    t = f'<text class="st" x="{x + 8}" y="{y + 13}">{stereo}</text>'
    t += "".join(f'<text class="nm" x="{x + 8}" y="{y + 29 + 13 * i}">{html.escape(l)}</text>' for i, l in enumerate(lignes))
    t += f'<text class="pg" x="{x + 8}" y="{y + BH - 6}">{prog}</text>'
    ic = f'<rect class="ic" x="{x + w - 22}" y="{y + 6}" width="14" height="10"/><rect class="ic" x="{x + w - 25}" y="{y + 8}" width="6" height="3"/><rect class="ic" x="{x + w - 25}" y="{y + 12}" width="6" height="3"/>'
    return f'<g><rect class="bx {c["etat"]}{" etd" if c["doc"] == "À étendre" else ""}" x="{x}" y="{y}" width="{w}" height="{BH}" rx="6"/>{t}{ic}</g>'


for n in POS:
    if n not in NOMS_COURTS:
        sys.exit(f"ECHEC diagramme : composant inconnu du document d'architecture : {n}")
if set(NOMS_COURTS) - set(POS):
    sys.exit(f"ECHEC diagramme : composants sans position : {set(NOMS_COURTS) - set(POS)}")
chem = "".join(
    '<polyline class="lk" fill="none" points="' + " ".join(f"{x},{y}" for x, y in pts) + '" marker-end="url(#fl)"/>'
    for pts in CHEMINS)
fl = chem + "".join(
    f'<line class="lk" x1="{bord(a, b)[0]:.0f}" y1="{bord(a, b)[1]:.0f}" x2="{bord(b, a)[0]:.0f}" y2="{bord(b, a)[1]:.0f}" marker-end="url(#fl)"/>'
    for a, b in LIENS)
svg = f"""<svg viewBox="0 0 1000 580" role="img" aria-label="Diagramme de composants UML">
<defs><marker id="fl" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto"><path d="M1 1L9 5L1 9" fill="none" stroke="var(--mut)" stroke-width="1.5"/></marker></defs>
<rect class="pk mod" x="20" y="20" width="690" height="310" rx="4"/><text class="pt" x="32" y="42">«package» Module sql-on-fhir — à construire (modules/sql-on-fhir)</text>
<rect class="pk tst" x="730" y="20" width="250" height="110" rx="4"/><text class="pt" x="742" y="42">«package» Tests</text>
<rect class="pk blz" x="20" y="350" width="960" height="220" rx="4"/><text class="pt" x="32" y="370">«package» Blaze — archive originale (image publiée) · existant</text>
<text class="pt sub" x="32" y="387">bordure pointillée : composant de Blaze étendu par le module</text>
<rect class="pk blz" x="345" y="392" width="345" height="170" rx="4"/><text class="pt sub" x="355" y="404">«subsystem» Stockage</text>
{fl}{"".join(boite(n) for n in POS)}</svg>"""

ORDRE = ("API", "Moteur", "Stockage", "Tests", "Plus tard")
comp_cartes = ""
for coche in ORDRE:
    for c in [x for x in composants if x["couche"] == coche]:
        if c["etat"] == "exist":
            prog = "<span class='tag exist'>existant</span>"; b = ""
        else:
            cc = {DONE: c["faites"], WIP: 0, TODO: c["ops"] - c["faites"]}
            prog = f'<span class="tag {c["etat"]}">{c["faites"]}/{c["ops"]}</span>'; b = barre(cc)
        comp_cartes += (f'<tr><td>{coche}</td><td class="t">{html.escape(c["nom"])}</td><td>{c["doc"]}</td>'
                        f'<td>{prog}</td><td class="b">{b}</td></tr>')
cc = cpt([c["etat"] if c["etat"] != "exist" else DONE for c in composants if c["doc"] != "Existant"])
nb_exist = sum(1 for c in composants if c["doc"] == "Existant")

crit = {DONE: 0, WIP: 0, TODO: 0}
for e in elements:
    crit[DONE] += e["ok"]
    crit[WIP if e["etat"] == WIP else TODO] += e["ko"]

lignes = ""
for e in elements:
    ts = [v[1] for v in taches.values() if v[0] == e["id"]]
    c = cpt(ts)
    lignes += (f'<tr><td>{e["id"]}</td><td class="t">{html.escape(e["titre"])}</td>'
               f'<td><span class="tag {e["etat"]}">{html.escape(e["statut"])}</span></td>'
               f'<td>{e["ok"]}/{e["ok"] + e["ko"]}</td>'
               f'<td class="b">{barre(c) if ts else ""}</td>'
               f'<td>{c[DONE]}·{c[WIP]}·{c[TODO]}</td></tr>')

page = f"""<!doctype html><html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Avancement</title>
<style>
:root{{--bg:#f6f7f9;--fg:#1c2330;--card:#fff;--mut:#6b7585;--line:#e3e6eb;--fait:#2e9e5b;--cours:#e6a117;--reste:#c5cbd5;--exist:#5b8def}}
@media(prefers-color-scheme:dark){{:root{{--bg:#12161d;--fg:#e8ebf0;--card:#1b212b;--mut:#8b95a6;--line:#2a3140;--fait:#3cb371;--cours:#f0b429;--reste:#3a4252;--exist:#4a78d4}}}}
body{{margin:0;padding:24px 16px;background:var(--bg);color:var(--fg);font:15px system-ui,sans-serif}}
main{{max-width:980px;margin:auto}}h1{{font-size:20px;margin:0 0 4px}}.maj{{color:var(--mut);font-size:13px;margin-bottom:16px}}
.grid{{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:12px}}
.card{{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:14px}}
.card h2{{font-size:14px;margin:0 0 10px;display:flex;justify-content:space-between}}.card em{{font-style:normal;color:var(--mut)}}
.nums{{display:flex;gap:16px;margin-bottom:10px}}.n b{{display:block;font-size:30px;line-height:1}}.n i{{font-style:normal;font-size:12px;color:var(--mut)}}
.n.fait b{{color:var(--fait)}}.n.cours b{{color:var(--cours)}}.n.reste b{{color:var(--mut)}}
.bar{{display:flex;height:10px;border-radius:5px;overflow:hidden;background:var(--line)}}.seg.fait{{background:var(--fait)}}.seg.cours{{background:var(--cours)}}.seg.reste{{background:var(--reste)}}
table{{width:100%;border-collapse:collapse;margin-top:16px;background:var(--card);border:1px solid var(--line);border-radius:10px}}
th,td{{padding:8px 10px;text-align:left;border-bottom:1px solid var(--line);font-size:13px;white-space:nowrap}}td.t{{white-space:normal}}td.b{{width:120px}}th{{color:var(--mut);font-weight:500}}
.tag{{padding:2px 8px;border-radius:9px;color:#fff;font-size:12px}}.tag.fait{{background:var(--fait)}}.tag.cours{{background:var(--cours)}}.tag.reste{{background:var(--mut)}}
.sec{{font-size:15px;margin:24px 0 0}}.tag.exist{{background:var(--exist)}}.diag{{margin-top:8px;overflow-x:auto}}.diag svg{{width:100%;min-width:760px;height:auto}}
.bx{{stroke:var(--fg);stroke-width:1}}.bx.fait{{fill:var(--fait)}}.bx.cours{{fill:var(--cours)}}.bx.reste{{fill:var(--reste)}}.bx.exist{{fill:var(--exist)}}
.nm{{font:600 12px system-ui;fill:#fff}}.st,.pg{{font:10px system-ui;fill:#fff;opacity:.9}}.bx.reste~text,.bx.cours~text{{fill:#1c2330}}.bx.cours~.ic,.bx.reste~.ic{{stroke:#1c2330}}.bx.etd{{stroke-dasharray:5 3;stroke-width:2}}.ic{{fill:none;stroke:#fff;stroke-width:1}}
.pk{{fill:none;stroke:var(--mut);stroke-width:1.2}}.pk.blz{{stroke:var(--exist)}}.pt{{font:600 12px system-ui;fill:var(--fg)}}.pt.sub{{font-weight:400;fill:var(--mut);font-size:11px}}
.lk{{stroke:var(--mut);stroke-width:1.2;stroke-dasharray:4 3}}.leg{{display:flex;flex-wrap:wrap;gap:16px;margin-top:8px;font-size:12px;color:var(--mut)}}.k{{display:inline-block;width:12px;height:12px;border-radius:3px;margin-right:5px;vertical-align:-2px}}.k.fait{{background:var(--fait)}}.k.cours{{background:var(--cours)}}.k.reste{{background:var(--reste)}}.k.exist{{background:var(--exist)}}
</style></head><body><main>
<h1>Avancement</h1><div class="maj">généré le {datetime.now():%Y-%m-%d %H:%M} · relancer <code>python3 scripts/avancement.py</code> puis F5</div>
<div class="grid">{carte("Éléments (F)", cpt([e["etat"] for e in elements]))}{carte("Tâches (T)", cpt([v[1] for v in taches.values()]))}{carte("Critères", crit)}{carte("Composants (hors existants)", cc)}</div>
<table><tr><th>F</th><th>Titre</th><th>Statut</th><th>Critères</th><th>Tâches</th><th>fait·cours·reste</th></tr>{lignes}</table>
<h2 class="sec">Composants</h2>
<table><tr><th>Couche</th><th>Composant</th><th>Inventaire</th><th>Opérations</th><th></th></tr>{comp_cartes}</table>
<h2 class="sec">Architecture (UML, diagramme de composants)</h2>
<div class="card diag">{svg}
<div class="leg"><span><i class="k fait"></i>réalisé</span><span><i class="k cours"></i>en cours</span><span><i class="k reste"></i>reste</span><span><i class="k exist"></i>existant (Blaze)</span><span>→ dépendance</span></div></div>
</main></body></html>"""
OUT.parent.mkdir(exist_ok=True)
OUT.write_text(page, encoding="utf-8")
print(f"SUCCES {OUT}")
