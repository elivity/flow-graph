#!/usr/bin/env python3
"""Cheap source checks for regressions introduced by file extraction.

Does not replace Kotlin compilation.
"""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
src = root / 'src' / 'main' / 'kotlin'
problems = []
for file in src.rglob('*.kt'):
    source = file.read_text()
    if len(source.splitlines()) > 1000 and '/runtime/' not in str(file):
        problems.append(f'{file}: exceeds 1000 lines')
    if 'FlowGraphPanel.ClusterNavigationEntry' in source:
        problems.append(f'{file}: imports top-level ClusterNavigationEntry as nested')
    if 'FlowGraphPanel.CurrentUiRuntimeCandidate' in source:
        problems.append(f'{file}: imports top-level CurrentUiRuntimeCandidate as nested')
    if 'this@FlowGraphPanel' in source and file.name == 'LiveControls.kt':
        problems.append(f'{file}: invalid receiver label in extension function')

assert not problems, '\n'.join(problems)
print('Source boundary checks passed')
