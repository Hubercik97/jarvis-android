"""Fetch public training inputs and verify their SHA256 before extracting.

Usage: python fetch.py CACHE_DIRECTORY
No cloud AI services or credentials. This downloads about 65 MB once.
"""
from pathlib import Path
import hashlib
import json
import sys
import tarfile
import urllib.request

cache = Path(sys.argv[1])
cache.mkdir(parents=True, exist_ok=True)
sources = json.loads((Path(__file__).parent / 'sources.json').read_text())
for entry in sources['downloads']:
    destination = cache / entry['destination']
    destination.parent.mkdir(parents=True, exist_ok=True)
    if not destination.exists():
        print('Downloading', entry['url'], flush=True)
        with urllib.request.urlopen(entry['url']) as response:
            destination.write_bytes(response.read())
    digest = hashlib.sha256(destination.read_bytes()).hexdigest()
    if digest != entry['sha256']:
        raise SystemExit(f'Hash mismatch: {destination}; remove it and retry.')
    if entry.get('archive'):
        with tarfile.open(destination, 'r:bz2') as archive:
            archive.extractall(cache, filter='data')
print('All training inputs verified.')
