"""Controles locaux sans reseau ni valeur privee ; Python 3.11+ et Git."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import sys
import tomllib

ROOT = Path(__file__).resolve().parent.parent


def git(*args):
    return subprocess.run(["git", "-C", str(ROOT), *args], check=True,
                          capture_output=True).stdout


def check():
    origin = git("remote", "get-url", "origin").decode().strip().removesuffix(".git")
    if origin not in {"https://github.com/cv215/waka", "git@github.com:cv215/waka"}:
        raise ValueError("Depot WAKA inattendu")
    git("diff", "--check")
    paths = sorted(set(p.decode("utf-8") for p in git(
        "ls-files", "--cached", "--others", "--exclude-standard", "-z").split(b"\0") if p))
    forbidden_parts = {".git", "target", "build", ".dart_tool", ".gradle", "secrets", "private",
                       "prive", "confidentiel", "waka_confidentiel", "exports", "backups", "dumps"}
    secret_pattern = re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|"
                                r"gh[pousr]_[A-Za-z0-9]{30,}|sk-proj-[A-Za-z0-9_-]{32,}")
    links = 0
    manifest = {}
    for relative in paths:
        path = ROOT / relative
        if not path.resolve().is_relative_to(ROOT) or any(p.lower() in forbidden_parts for p in path.relative_to(ROOT).parts):
            raise ValueError("Fichier hors perimetre source")
        if path.suffix.lower() in {".kdbx", ".dpapi", ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".log", ".jar", ".apk"}:
            raise ValueError("Fichier prive ou genere dans les sources")
        raw = path.read_bytes()
        if len(raw) > 1_000_000:
            raise ValueError("Fichier source anormalement volumineux")
        manifest[relative] = hashlib.sha256(raw).hexdigest()
        if path.suffix == ".png":
            if not re.fullmatch(r"apps/mobile/android/app/src/main/res/mipmap-(?:mdpi|hdpi|xhdpi|xxhdpi|xxxhdpi)/ic_launcher.png"
                                r"|apps/mobile/web/favicon.png"
                                r"|apps/mobile/web/icons/(?:Icon-192|Icon-512|Icon-maskable-512|apple-touch-icon).png", relative):
                raise ValueError("Image non qualifiee dans les sources")
            continue  # Seules les cinq icones statiques du generateur Flutter sont admises.
        body = raw.decode("utf-8-sig")
        if "\ufffd" in body or secret_pattern.search(body):
            raise ValueError("Texte corrompu ou motif de secret detecte")
        if path.suffix == ".md":
            for target in re.findall(r"\]\(([^)]+)\)", body):
                if target.startswith(("http://", "https://", "#")):
                    continue
                target = target.split("#", 1)[0]
                if re.match(r"^[A-Za-z]:", target) or not (path.parent / target).resolve().is_file():
                    raise ValueError("Lien documentaire local invalide")
                links += 1
    agents = []
    for path in sorted((ROOT / ".codex/agents").glob("*.toml")):
        value = tomllib.loads(path.read_text(encoding="utf-8-sig"))
        required = {"name", "description", "developer_instructions"}
        if not required <= value.keys() or not value.keys() <= required | {"model", "model_reasoning_effort"}:
            raise ValueError("Champs de profil invalides")
        if value["name"] != path.stem or not all(value.get(k) for k in required):
            raise ValueError("Profil incomplet")
        agents.append(value["name"])
    if agents != ["atlas", "kora", "naya", "scribe", "sika", "vigie"]:
        raise ValueError("Equipe incomplete")
    for example in [".env", "backend/target/app.jar", "apps/mobile/build/app.apk",
                    "apps/mobile/android/local.properties", "Secrets/test.txt", "private/test.pdf"]:
        git("check-ignore", "--quiet", "--no-index", example)
    return dict(ok=True, correct_repository="cv215/waka", source_files=len(paths),
                local_links_checked=links, agent_definitions=agents, files=manifest)


if __name__ == "__main__":
    try:
        result = check()
        if len(sys.argv) == 3 and sys.argv[1] == "--manifest":
            # Destination choisie explicitement, reservee aux preuves locales hors Git.
            Path(sys.argv[2]).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({k: v for k, v in result.items() if k != "files"}, ensure_ascii=False))
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print("SOURCE_CHECK_FAILED: " + type(error).__name__, file=sys.stderr)
        sys.exit(1)
