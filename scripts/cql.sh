#!/usr/bin/env bash
# scripts/cql.sh — evaluer du CQL sur le serveur d'API, Docker ou Kubernetes (F-115).
#
# Colle seulement : tout le travail est fait par le module Clinical Reasoning de
# HAPI (`$cql`, `Library/$evaluate`, `Measure/$evaluate-measure`), active par
# HAPI_CR_ENABLED=true. Mode d'emploi et exemples : docs/cql.md.
set -euo pipefail

RACINE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=../lib/codes.sh
source "${RACINE}/lib/codes.sh"

usage() {
    cat <<'EOF'
Usage : scripts/cql.sh CIBLE COMMANDE [options]

CIBLE (une seule)
  -e NOM          environnement Docker environments/NOM (adresse lue dans son .env)
  -k [-n NS]      cluster Kubernetes courant, service `proxy` du namespace NS (defaut fhir)
  -u URL          adresse de base FHIR explicite (ex. http://localhost:8101/fhir/DEFAULT)

COMMANDE
  expr "EXPRESSION"   [-s SUJET]       evalue une expression CQL ($cql)
  run FICHIER.cql     [-s SUJET]       evalue une bibliotheque CQL sans la stocker ($cql)
  charger FICHIER.json                 ecrit une ressource (PUT par id) ou un Bundle transaction
  publier FICHIER.cql                  stocke la bibliotheque (Library/<nom>, PUT idempotent)
  library NOM -s SUJET [-x EXPR]       evalue une Library stockee (Library/$evaluate)
  mesure ID -s SUJET --debut AAAA-MM-JJ --fin AAAA-MM-JJ [--rapport subject|population]
                                       evalue une Measure ($evaluate-measure)

  -s SUJET   Patient/<id> ou Group/<id> ; OBLIGATOIRE pour `mesure` (sans sujet,
             le serveur evaluerait toute la population)
  --json     reponse FHIR brute au lieu du resume
EOF
}

refus() { echo "cql : $2" >&2; exit "$1"; }

# --- Arguments ---------------------------------------------------------------
ENV_NOM="" KUBE=0 NS="fhir" BASE="" SUJET="" EXPR="" DEBUT="" FIN="" RAPPORT="" JSON=0
POS=()
while [ $# -gt 0 ]; do
    case "$1" in
        -e) ENV_NOM="${2:?}"; shift 2 ;;
        -k) KUBE=1; shift ;;
        -n) NS="${2:?}"; shift 2 ;;
        -u) BASE="${2:?}"; shift 2 ;;
        -s) SUJET="${2:?}"; shift 2 ;;
        -x) EXPR="${2:?}"; shift 2 ;;
        --debut) DEBUT="${2:?}"; shift 2 ;;
        --fin) FIN="${2:?}"; shift 2 ;;
        --rapport) RAPPORT="${2:?}"; shift 2 ;;
        --json) JSON=1; shift ;;
        -h|--help) usage; exit "$EX_SUCCES" ;;
        -*) usage >&2; refus "$EX_USAGE" "option inconnue : $1" ;;
        *) POS+=("$1"); shift ;;
    esac
done
[ ${#POS[@]} -ge 2 ] || { usage >&2; exit "$EX_USAGE"; }
CMD="${POS[0]}" ARG="${POS[1]}"
[ -n "$ENV_NOM" ] && [ "$KUBE" -eq 1 ] && refus "$EX_USAGE" "-e et -k s'excluent"
command -v curl >/dev/null && command -v python3 >/dev/null \
    || refus "$EX_PREREQUIS" "curl et python3 sont requis"

# --- Resolution de la cible ----------------------------------------------------
PF=""
trap '[ -n "$PF" ] && kill "$PF" 2>/dev/null || true' EXIT
if [ -n "$ENV_NOM" ]; then
    f="${RACINE}/environments/${ENV_NOM}/.env"
    [ -f "$f" ] || refus "$EX_CONFIG" "environnement inconnu : $f"
    BASE="$(sed -n 's/^HAPI_BASE_URL=//p' "$f" | tr -d '"' | tail -1)"
    [ -n "$BASE" ] || refus "$EX_CONFIG" "HAPI_BASE_URL absente de $f"
elif [ "$KUBE" -eq 1 ]; then
    command -v kubectl >/dev/null || refus "$EX_PREREQUIS" "kubectl est requis"
    port="$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1])')"
    kubectl -n "$NS" port-forward svc/proxy "${port}:8090" >/dev/null 2>&1 & PF=$!
    BASE="http://127.0.0.1:${port}/fhir/DEFAULT"
    for _ in $(seq 30); do
        curl -fs -o /dev/null "http://127.0.0.1:${port}/healthz" && break
        kill -0 "$PF" 2>/dev/null || refus "$EX_DEMARRAGE" "port-forward impossible vers svc/proxy (namespace $NS)"
        sleep 0.5
    done
fi
[ -n "$BASE" ] || { usage >&2; refus "$EX_USAGE" "cible absente : -e, -k ou -u"; }
BASE="${BASE%/}"

# --- Appel et resume ----------------------------------------------------------
# Corps JSON construit par python3 (echappement sur), jamais par concatenation.
parametres() {  # paires nom=valeur -> Parameters FHIR (valueString)
    python3 - "$@" <<'PY'
import json, sys
p = [{"name": k, "valueString": v} for k, v in (a.split("=", 1) for a in sys.argv[1:]) if v]
print(json.dumps({"resourceType": "Parameters", "parameter": p}))
PY
}

appel() {  # METHODE CHEMIN [CORPS]
    local sortie code
    sortie="$(mktemp)"
    code="$(curl -sS -o "$sortie" -w '%{http_code}' -X "$1" "${BASE}/$2" \
        -H 'Accept: application/fhir+json' -H 'Content-Type: application/fhir+json' \
        ${3:+--data-binary "$3"})" || { rm -f "$sortie"; refus "$EX_DEMARRAGE" "serveur injoignable : $BASE"; }
    local rc=0
    python3 - "$code" "$JSON" "$sortie" <<'PY' || rc=$?
import json, sys
code, brut, chemin = int(sys.argv[1]), sys.argv[2] == "1", sys.argv[3]
texte = open(chemin, encoding="utf-8").read()
try:
    d = json.loads(texte)
except ValueError:
    print(f"HTTP {code} : {texte[:300]}", file=sys.stderr); sys.exit(8)
if brut:
    print(json.dumps(d, indent=2, ensure_ascii=False))
    sys.exit(0 if code < 400 else 8)
t = d.get("resourceType")
if t == "OperationOutcome":
    for i in d.get("issue", []):
        print(f"{i.get('severity')} : {i.get('diagnostics', i.get('code'))}",
              file=sys.stderr if code >= 400 else sys.stdout)
elif t == "Parameters":
    for p in d.get("parameter", []):
        if p["name"] == "evaluation error":
            for i in p.get("resource", {}).get("issue", []):
                print(f"erreur CQL : {i.get('diagnostics')}", file=sys.stderr)
            code = 422
            continue
        if "resource" in p:
            r = p["resource"]; v = f"{r['resourceType']}/{r.get('id')}"
        else:
            v = next((json.dumps(x, ensure_ascii=False) for k, x in p.items() if k.startswith("value")), "null")
        print(f"{p['name']} = {v}")
elif t == "MeasureReport":
    print(f"{d.get('type')} · {d.get('status')} · {d.get('period', {}).get('start')} → {d.get('period', {}).get('end')}")
    for g in d.get("group", []):
        pops = ", ".join(f"{p['code']['coding'][0]['code']}={p.get('count')}" for p in g.get("population", []))
        print(f"  {g.get('id', 'groupe')} : {pops} ; score={g.get('measureScore', {}).get('value')}")
else:
    print(f"{t}/{d.get('id')} (HTTP {code})")
sys.exit(0 if code < 400 else 8)
PY
    rm -f "$sortie"
    return "$rc"
}

lire() { [ -f "$1" ] || refus "$EX_USAGE" "fichier introuvable : $1"; cat "$1"; }

case "$CMD" in
    expr)    appel POST '$cql' "$(parametres "expression=$ARG" "subject=$SUJET")" ;;
    run)     appel POST '$cql' "$(parametres "content=$(lire "$ARG")" "subject=$SUJET")" ;;
    charger)
        corps="$(lire "$ARG")"
        chemin="$(python3 -c '
import json, sys
d = json.load(sys.stdin)
if d["resourceType"] == "Bundle": print("")
elif d.get("id"): print(d["resourceType"] + "/" + d["id"])
else: sys.exit("cql : ressource sans id")' <<<"$corps")" || exit "$EX_USAGE"
        if [ -z "$chemin" ]; then
            appel POST "" "$corps" >/dev/null && echo "Bundle charge : $ARG"
        else
            appel PUT "$chemin" "$corps"
        fi ;;
    publier)
        corps="$(lire "$ARG" | python3 -c '
import base64, json, re, sys
cql = sys.stdin.read()
m = re.search(r"^\s*library\s+\"?([A-Za-z][\w]*)\"?(?:\s+version\s+\x27([^\x27]+)\x27)?", cql, re.M)
if not m: sys.exit("cql : en-tete `library <Nom> version ...` absent")
nom, ver = m.group(1), m.group(2) or "0.0.0"
print(json.dumps({"resourceType": "Library", "id": nom, "name": nom, "version": ver,
  "url": f"http://fhir-platform.local/Library/{nom}", "status": "active",
  "type": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/library-type", "code": "logic-library"}]},
  "content": [{"contentType": "text/cql", "data": base64.b64encode(cql.encode()).decode()}]}))')" \
            || exit "$EX_USAGE"
        nom="$(python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])' <<<"$corps")"
        appel PUT "Library/${nom}" "$corps" ;;
    library)
        [ -n "$SUJET" ] || refus "$EX_USAGE" "library : -s SUJET requis"
        appel POST "Library/${ARG}/\$evaluate" "$(parametres "subject=$SUJET" "expression=$EXPR")" ;;
    mesure)
        [ -n "$SUJET" ] && [ -n "$DEBUT" ] && [ -n "$FIN" ] \
            || refus "$EX_USAGE" "mesure : -s, --debut et --fin sont requis"
        case "$SUJET" in Group/*) RAPPORT="${RAPPORT:-population}" ;; *) RAPPORT="${RAPPORT:-subject}" ;; esac
        appel GET "Measure/${ARG}/\$evaluate-measure?periodStart=${DEBUT}&periodEnd=${FIN}&subject=${SUJET}&reportType=${RAPPORT}" ;;
    *) usage >&2; refus "$EX_USAGE" "commande inconnue : $CMD" ;;
esac
