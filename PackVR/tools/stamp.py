#!/usr/bin/env python3
"""Stamp the page's module URLs with a hash of the code, so a browser can never mix old and new files.

GitHub Pages lets a browser reuse a file for 10 minutes, and PackVR is several modules: right after an
update, a reload could pair the new index.html with old cached modules and break. index.html's import map
points every module at `file.js?v=<hash of all the code>`, so any change gives all of them new addresses.
Run this before committing changes to PackVR/js or PackVR/gen (sync_from_app.py, then this).
"""
import hashlib
import pathlib
import re

root = pathlib.Path(__file__).resolve().parent.parent
files = sorted(list((root / "js").glob("*.js")) + list((root / "gen").glob("*.js")))
digest = hashlib.sha1()
for f in files:
    digest.update(f.name.encode())
    digest.update(f.read_bytes())
v = digest.hexdigest()[:8]

mods = [f"./{f.relative_to(root).as_posix()}" for f in files]
imap = '{ "imports": {\n    "three": "./vendor/three.module.js",\n' + ",\n".join(f'    "{m}": "{m}?v={v}"' for m in mods) + "\n  } }"
page = root / "index.html"
html = page.read_text()
html, n1 = re.subn(r'<script type="importmap">.*?</script>', lambda m: f'<script type="importmap">{imap}</script>', html, flags=re.S)
html, n2 = re.subn(r'<script type="module" src="[^"]*"></script>', f'<script type="module" src="./js/main.js?v={v}"></script>', html)
assert n1 == 1 and n2 == 1, "index.html no longer has the import map / module script this expects"
page.write_text(html)
print(f"stamped {len(mods)} modules with v={v}")
