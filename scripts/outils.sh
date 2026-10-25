#!/usr/bin/env bash
# scripts/outils.sh — outillage Clojure du module complémentaire (ADR 0003, 0004).
#
#   scripts/outils.sh installer   télécharge JDK, clojure, clj-kondo, clojure-lsp, cljfmt
#   scripts/outils.sh jar         extrait le jar de Blaze de l'image publiée (.work/)
#   scripts/outils.sh amont       clone les sources de Blaze, au tag publié, en lecture seule, et les prépare
#   scripts/outils.sh etat        versions effectivement présentes
#   scripts/outils.sh env         imprime les `export` (eval "$(scripts/outils.sh env)")
#
# N'installe rien sur le poste : tout va dans .outils/ (ignoré par git).
# Les versions viennent de versions.env, jamais d'ici. Chaque archive est
# vérifiée par l'empreinte SHA-256 que son éditeur publie ; sans empreinte, refus.
set -euo pipefail

RACINE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source "${RACINE}/lib/codes.sh"

OUT="${RACINE}/.outils"
BIN="${OUT}/bin"

version() {
    local v
    v="$(sed -n "s/^${1}=\(.*\)$/\1/p" "${RACINE}/versions.env" | tail -1)"
    [ -n "$v" ] || { echo "versions.env ne porte pas $1" >&2; exit "$EX_CONFIG"; }
    printf '%s' "$v"
}

prerequis() {
    local o
    for o in curl unzip tar sha256sum docker git; do
        command -v "$o" >/dev/null || { echo "prérequis absent : $o" >&2; exit "$EX_PREREQUIS"; }
    done
}

# telecharger <url> <fichier> <url-empreinte> — refuse si l'empreinte diffère ou manque.
telecharger() {
    local url="$1" fichier="$2" url_sha="$3" attendu obtenu
    attendu="$(curl -fsSL "$url_sha" | awk '{print $1; exit}')"
    [[ "$attendu" =~ ^[0-9a-f]{64}$ ]] || { echo "empreinte illisible : $url_sha" >&2; exit "$EX_ECHEC"; }
    curl -fsSLo "$fichier" "$url"
    obtenu="$(sha256sum "$fichier" | awk '{print $1}')"
    [ "$obtenu" = "$attendu" ] || {
        echo "ECHEC empreinte ${url##*/} : attendu ${attendu}, obtenu ${obtenu}" >&2
        rm -f "$fichier"; exit "$EX_ECHEC"
    }
}

installer() {
    prerequis
    mkdir -p "$BIN" "${OUT}/dl"
    local dl="${OUT}/dl" v

    # JDK : le MÊME que celui de l'image publiée (jdk-25.0.3+9), pas celui du poste.
    v="$(version JDK_VERSION)"
    if [ ! -x "${OUT}/jdk/bin/java" ]; then
        local f="OpenJDK${v%%.*}U-jdk_x64_linux_hotspot_${v//+/_}.tar.gz" tag="jdk-${v}"
        telecharger "https://github.com/adoptium/temurin${v%%.*}-binaries/releases/download/${tag//+/%2B}/${f}" \
            "${dl}/${f}" "https://github.com/adoptium/temurin${v%%.*}-binaries/releases/download/${tag//+/%2B}/${f}.sha256.txt"
        rm -rf "${OUT}/jdk"; mkdir -p "${OUT}/jdk"
        tar -xzf "${dl}/${f}" -C "${OUT}/jdk" --strip-components=1
    fi
    export JAVA_HOME="${OUT}/jdk" PATH="${OUT}/jdk/bin:${PATH}"

    v="$(version CLOJURE_CLI_VERSION)"
    if ! [ -x "${BIN}/clojure" ] || ! grep -q "$v" "${OUT}/clojure/VERSION" 2>/dev/null; then
        curl -fsSLo "${dl}/linux-install.sh" \
            "https://github.com/clojure/brew-install/releases/download/${v}/linux-install.sh"
        rm -rf "${OUT}/clojure"
        # LC_ALL=C : l'installateur amont cherche « OK » dans la sortie localisée de sha256sum.
        # Il télécharge dans le répertoire courant : on se place dans .outils/dl.
        (cd "$dl" && LC_ALL=C bash linux-install.sh --prefix "${OUT}/clojure" >/dev/null)
        ln -sf "${OUT}/clojure/bin/clojure" "${BIN}/clojure"
        ln -sf "${OUT}/clojure/bin/clj" "${BIN}/clj"
        printf '%s\n' "$v" > "${OUT}/clojure/VERSION"
    fi

    v="$(version CLJ_KONDO_VERSION)"
    if ! "${BIN}/clj-kondo" --version 2>/dev/null | grep -q "$v"; then
        local f="clj-kondo-${v}-linux-static-amd64.zip" b="https://github.com/clj-kondo/clj-kondo/releases/download/v${v}"
        telecharger "${b}/${f}" "${dl}/${f}" "${b}/${f}.sha256"
        unzip -qo "${dl}/${f}" -d "$BIN"
    fi

    v="$(version CLOJURE_LSP_VERSION)"
    if ! "${BIN}/clojure-lsp" --version 2>/dev/null | grep -q "${v%%-*}"; then
        local f="clojure-lsp-native-static-linux-amd64.zip" b="https://github.com/clojure-lsp/clojure-lsp/releases/download/${v}"
        telecharger "${b}/${f}" "${dl}/${f}" "${b}/${f}.sha256"
        unzip -qo "${dl}/${f}" -d "$BIN"
    fi

    v="$(version CLJFMT_VERSION)"
    if ! "${BIN}/cljfmt" --version 2>/dev/null | grep -q "$v"; then
        local f="cljfmt-${v}-linux-amd64-static.tar.gz" b="https://github.com/weavejester/cljfmt/releases/download/${v}"
        telecharger "${b}/${f}" "${dl}/${f}" "${b}/${f}.sha256"
        tar -xzf "${dl}/${f}" -C "$BIN"
    fi
    # blazectl : chargement du jeu de reference (ADR 0007). L'empreinte attendue
    # est celle du BINAIRE, publiée dans le SBOM de la release (versions.env).
    v="$(version BLAZECTL_VERSION)"
    if ! "${BIN}/blazectl" --version 2>/dev/null | grep -q "$v"; then
        local f="blazectl-${v}-linux-amd64.tar.gz" b="https://github.com/samply/blazectl/releases/download/v${v}"
        local attendu obtenu
        attendu="$(version BLAZECTL_LINUX_AMD64_SHA256)"
        curl -fsSLo "${dl}/${f}" "${b}/${f}"
        rm -rf "${dl}/blazectl-${v}"; mkdir -p "${dl}/blazectl-${v}"
        tar -xzf "${dl}/${f}" -C "${dl}/blazectl-${v}" blazectl
        obtenu="$(sha256sum "${dl}/blazectl-${v}/blazectl" | awk '{print $1}')"
        [ "$obtenu" = "$attendu" ] || {
            echo "ECHEC empreinte blazectl : attendu ${attendu}, obtenu ${obtenu}" >&2; exit "$EX_ECHEC"
        }
        install -m 0755 "${dl}/blazectl-${v}/blazectl" "${BIN}/blazectl"
    fi

    chmod +x "${BIN}"/* 2>/dev/null || true
    etat
}

# Extrait le jar de l'image PUBLIÉE, épinglée par empreinte (versions.env), et
# refuse si l'empreinte du jar extrait diffère de BLAZE_JAR_SHA256.
jar() {
    prerequis
    local ver img jar attendu obtenu
    ver="$(version BLAZE_VERSION)"
    img="samply/blaze:${ver}"
    jar="${RACINE}/.work/blaze-standalone.jar"
    attendu="$(version BLAZE_JAR_SHA256)"
    mkdir -p "${RACINE}/.work"
    if [ ! -f "$jar" ] || [ "$(sha256sum "$jar" | awk '{print $1}')" != "$attendu" ]; then
        docker image inspect "$img" >/dev/null 2>&1 || docker pull -q "$img" >/dev/null
        docker run --rm --entrypoint cat "$img" "/app/blaze-${ver}-standalone.jar" > "$jar"
    fi
    obtenu="$(sha256sum "$jar" | awk '{print $1}')"
    [ "$obtenu" = "$attendu" ] || {
        echo "ECHEC jar ${jar##*/} : attendu ${attendu}, obtenu ${obtenu}" >&2; exit "$EX_ECHEC"
    }
    echo "jar de l'image publiée ${img} : ${jar#"$RACINE"/} (${obtenu:0:12}…)"
}

# Sources de Blaze au tag publié : RÉFÉRENCE seule (navigation, lecture), jamais livrée.
amont() {
    prerequis
    local ver tag dest="${RACINE}/amont/blaze"
    ver="$(version BLAZE_VERSION)"; tag="v${ver}"
    if [ -d "${dest}/.git" ]; then
        [ "$(git -C "$dest" describe --tags --exact-match 2>/dev/null)" = "$tag" ] || {
            echo "amont/blaze n'est pas au tag ${tag} : supprimer le dossier puis relancer" >&2; exit "$EX_CONFIG"
        }
    else
        git clone --quiet --depth 1 --branch "$tag" https://github.com/samply/blaze.git "$dest"
    fi
    # Préparation exigée par `clojure -A:nav` (IG générée, classes Java) : une fois par version.
    if [ ! -f "${dest}/.prepare-${ver}" ]; then
        command -v node >/dev/null || { echo "prérequis absent : node (génération de l'IG)" >&2; exit "$EX_PREREQUIS"; }
        (cd "$dest" && JAVA_HOME="${OUT}/jdk" PATH="${BIN}:${OUT}/jdk/bin:${PATH}" \
            make build-ig >/dev/null && clojure -X:deps prep >/dev/null)
        touch "${dest}/.prepare-${ver}"
    fi
    echo "amont/blaze au tag ${tag} ($(git -C "$dest" rev-parse --short HEAD)), préparé pour la navigation"
}

etat() {
    local o
    for o in clojure clj-kondo clojure-lsp cljfmt blazectl; do
        printf '%-13s' "$o"
        if [ -x "${BIN}/$o" ]; then
            JAVA_HOME="${OUT}/jdk" PATH="${OUT}/jdk/bin:${PATH}" "${BIN}/$o" --version 2>&1 | head -1
        else echo "ABSENT — scripts/outils.sh installer"; fi
    done
    printf '%-13s' "jdk"
    if [ -x "${OUT}/jdk/bin/java" ]; then "${OUT}/jdk/bin/java" -version 2>&1 | head -1; else echo "ABSENT — scripts/outils.sh installer"; fi
}

case "${1:-}" in
    installer) installer ;;
    jar)       jar ;;
    amont)     amont ;;
    etat)      etat ;;
    env)       echo "export JAVA_HOME=${OUT}/jdk PATH=${BIN}:${OUT}/jdk/bin:\$PATH" ;;
    *)         sed -n '2,10p' "${BASH_SOURCE[0]}"; exit "$EX_USAGE" ;;
esac
