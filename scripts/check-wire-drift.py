#!/usr/bin/env python3
"""Compile schema mutations against the real supported client, in disposable directories."""
import argparse
import copy
import importlib.util
from pathlib import Path
import shutil
import subprocess
import tempfile

import yaml

ROOT = Path(__file__).resolve().parents[1]
module = importlib.util.spec_from_file_location('wire_generator', ROOT / 'scripts/generate-wire.py')
generator = importlib.util.module_from_spec(module)
module.loader.exec_module(generator)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--docker', action='store_true')
    args = parser.parse_args()
    original = yaml.safe_load((ROOT / 'resources/shieldlabs-api.yaml').read_text())
    cases = [('baseline', None, None, None)]
    for model, field in [('HistoryRow', 'score'), ('HistoryRow', 'request_id'),
                         ('DomainProfile', 'Weight'), ('IdentificationScoredData', 'risk_score'),
                         ('IdentificationScoredEvent', 'event_type'), ('DetectionFlags', 'vpn'),
                         ('TrafficSource', 'channel'), ('HistoryPage', 'total')]:
        cases.append((model + '.' + field + ' rename', model, field, 'rename'))
        cases.append((model + '.' + field + ' type', model, field, 'type'))
    cases.extend([('query rename', None, None, 'parameter'),
                  ('query type', None, None, 'parameter-type'),
                  ('search type enum removed', None, None, 'search-type'),
                  ('profile header renamed', None, None, 'header'),
                  ('profile header type', None, None, 'header-type'),
                  ('optional additive', 'HistoryRow', None, 'add')])
    for label, model, field, change in cases:
        doc = copy.deepcopy(original)
        if model:
            props = doc['components']['schemas'][model]['properties']
            if change == 'rename':
                props[field + '_changed'] = props.pop(field)
            elif change == 'type':
                props[field] = {'type': 'object'}
            else:
                props['future_optional'] = {'type': 'string'}
        elif change == 'parameter':
            doc['components']['parameters']['HistoryLimit']['name'] = 'changed_limit'
        elif change == 'parameter-type':
            doc['components']['parameters']['HistoryLimit']['schema'] = {'type': 'string'}
        elif change == 'search-type':
            doc['components']['parameters']['HistorySearchType']['schema']['enum'].remove('request_id')
        elif change == 'header':
            doc['components']['parameters']['ShieldDomain']['name'] = 'X-Changed-Domain'
        elif change == 'header-type':
            doc['components']['parameters']['ShieldDomain']['schema'] = {'type': 'integer'}
        expected_success = change is None or change == 'add'
        try:
            output = generator.generate(doc)
        except (KeyError, ValueError) as exc:
            if expected_success:
                raise
            print('PASS rejected schema: ' + label + ' (' + str(exc) + ')', flush=True)
            continue
        with tempfile.TemporaryDirectory(prefix='shieldlabs-java-contract-') as tmp:
            work = Path(tmp)
            shutil.copy2(ROOT / 'pom.xml', work / 'pom.xml')
            shutil.copytree(ROOT / 'src/main', work / 'src/main')
            (work / generator.OUTPUT).write_text(output)
            command = ['mvn', '-B', '-ntp', 'compile', '-DskipTests']
            if args.docker:
                command = ['docker', 'run', '--rm', '-v', str(work) + ':/src',
                           '-v', 'shieldlabs-sdk-m2:/root/.m2', '-w', '/src',
                           'maven:3.9-eclipse-temurin-17'] + command
            run = subprocess.run(command, cwd=work, capture_output=True, text=True, timeout=180)
            if (run.returncode == 0) != expected_success:
                raise RuntimeError(label + '\n' + run.stdout + run.stderr)
            if not expected_success and '[ERROR] COMPILATION ERROR' not in run.stdout:
                raise RuntimeError('Not a compiler rejection: ' + label + '\n' + run.stdout + run.stderr)
            print('PASS ' + ('compiled: ' if expected_success else 'compiler rejected: ') + label, flush=True)


if __name__ == '__main__':
    main()
