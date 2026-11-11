#!/usr/bin/env python3
"""Point Formula/adbw.rb at a release: python3 scripts/update_formula.py 0.2.0 dist/adbw"""
import hashlib
import re
import sys
from pathlib import Path

version, artifact = sys.argv[1], Path(sys.argv[2])
sha = hashlib.sha256(artifact.read_bytes()).hexdigest()
path = Path(__file__).resolve().parent.parent / "Formula/adbw.rb"
text = path.read_text()
text = re.sub(r'releases/download/v[^/]+/adbw"', f'releases/download/v{version}/adbw"', text)
text = re.sub(r'version "[^"]+"', f'version "{version}"', text)
text = re.sub(r'sha256 "[0-9a-f]+"', f'sha256 "{sha}"', text)
path.write_text(text)
print(f"Formula/adbw.rb -> {version} {sha}")
