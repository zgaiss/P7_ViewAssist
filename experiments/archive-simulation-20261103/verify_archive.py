#!/usr/bin/env python3
"""Read-only validation of the minimal source archive; does not run candidate code."""
from pathlib import Path
import ast, hashlib, json, re
root=Path(__file__).resolve().parent
manifest=json.loads((root/'SOURCE-MANIFEST.json').read_text())
allowed=set(manifest)|{'README.md','SOURCE-MANIFEST.json','verify_archive.py'}
actual={p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file() and '__pycache__' not in p.parts}
assert actual==allowed,(actual-allowed,allowed-actual)
patterns=[r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',r'gh[pousr]_[A-Za-z0-9]{30,}',r'sk-(?:proj-)?[A-Za-z0-9_-]{30,}',r'AKIA[A-Z0-9]{16}',r'(?i)(?:password|secret|token|api_key)\s*=\s*[\"\'][A-Za-z0-9/+_-]{24,}[\"\']']
for name in sorted(allowed):
 p=root/name;assert not p.is_symlink(),name
 raw=p.read_bytes();text=raw.decode('utf-8');assert b'\0' not in raw,name
 if name in manifest:assert hashlib.sha256(raw).hexdigest()==manifest[name]['sha256'],name
 for pattern in patterns:assert not re.search(pattern,text),('credential pattern',name)
 if name.endswith('.py'):ast.parse(text,filename=name)
print(f'PASS: {len(manifest)} exact source hashes; {len(allowed)} text-only allowlisted files; Python syntax and credential-pattern checks')
