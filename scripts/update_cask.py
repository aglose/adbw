#!/usr/bin/env python3
"""Point Casks/adbw.rb at a release: python3 scripts/update_cask.py 0.3.1 dist/adbw"""
import hashlib
import re
import sys
from pathlib import Path

version, artifact = sys.argv[1], Path(sys.argv[2])
sha = hashlib.sha256(artifact.read_bytes()).hexdigest()
path = Path(__file__).resolve().parent.parent / "Casks/adbw.rb"
text, n1 = re.subn(r'version "[^"]+"', f'version "{version}"', path.read_text(), count=1)
text, n2 = re.subn(r'sha256 "[0-9a-f]{64}"', f'sha256 "{sha}"', text, count=1)
if n1 != 1 or n2 != 1:
    sys.exit("Casks/adbw.rb doesn't look as expected; not updating it")
path.write_text(text)
print(f"Casks/adbw.rb -> {version} {sha}")
