#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Mesure que la navigation tient : depuis un fichier du module, la définition d'un symbole
de Blaze (`fp/function-expression`) se résout vers amont/blaze/ (requête LSP
`textDocument/definition` à clojure-lsp, le même serveur que Calva).

Sortie 0 : définition trouvée sous amont/blaze. 8 : résolue ailleurs, ou introuvable.
3 : clojure-lsp ou amont/ absent (NON-BRANCHE : scripts/outils.sh installer, make outils).
"""
import json
import pathlib
import subprocess
import sys
import time

RACINE = pathlib.Path(__file__).resolve().parent.parent
LSP = RACINE / ".outils/bin/clojure-lsp"
FICHIER = RACINE / "modules/sql-on-fhir/test/blaze/addon/canari_test.clj"
SYMBOLE = "fp/function-expression"
DELAI = 180


def main():
    if not LSP.is_file() or not (RACINE / "amont/blaze/deps.edn").is_file():
        print("NON-BRANCHE navigation — make outils")
        return 3
    env = {"PATH": f"{RACINE}/.outils/bin:{RACINE}/.outils/jdk/bin:/usr/bin:/bin", "JAVA_HOME": f"{RACINE}/.outils/jdk", "HOME": str(pathlib.Path.home())}
    p = subprocess.Popen([str(LSP)], cwd=RACINE, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)

    def envoyer(m):
        b = json.dumps(m).encode()
        p.stdin.write(b"Content-Length: %d\r\n\r\n" % len(b) + b)
        p.stdin.flush()

    def recevoir():
        h = b""
        while not h.endswith(b"\r\n\r\n"):
            c = p.stdout.read(1)
            if not c:
                raise EOFError("clojure-lsp s'est arrêté")
            h += c
        n = int([l for l in h.decode().split("\r\n") if l.lower().startswith("content-length")][0].split(":")[1])
        return json.loads(p.stdout.read(n))

    try:
        envoyer({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"processId": None, "rootUri": RACINE.as_uri(), "capabilities": {}}})
        while recevoir().get("id") != 1:
            pass
        envoyer({"jsonrpc": "2.0", "method": "initialized", "params": {}})
        texte = FICHIER.read_text(encoding="utf-8")
        envoyer({"jsonrpc": "2.0", "method": "textDocument/didOpen", "params": {"textDocument": {"uri": FICHIER.as_uri(), "languageId": "clojure", "version": 1, "text": texte}}})
        ligne = next(i for i, l in enumerate(texte.split("\n")) if SYMBOLE in l)
        col = texte.split("\n")[ligne].index(SYMBOLE) + 4
        envoyer({"jsonrpc": "2.0", "id": 2, "method": "textDocument/definition", "params": {"textDocument": {"uri": FICHIER.as_uri()}, "position": {"line": ligne, "character": col}}})
        fin = time.time() + DELAI
        while time.time() < fin:
            m = recevoir()
            if m.get("id") == 2:
                r = m.get("result")
                uri = (r[0] if isinstance(r, list) and r else r or {}).get("uri", "")
                if (RACINE / "amont/blaze").as_uri() in uri:
                    print(f"SUCCES navigation — {SYMBOLE} -> {uri.removeprefix(RACINE.as_uri() + '/')}")
                    return 0
                print(f"ECHEC navigation — {SYMBOLE} résolu vers « {uri or 'rien'} », attendu amont/blaze")
                return 8
        print("ECHEC navigation — pas de réponse de clojure-lsp")
        return 8
    finally:
        p.terminate()


if __name__ == "__main__":
    sys.exit(main())
