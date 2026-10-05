#!/usr/bin/env python3
"""Regression tests for operation and shared webhook contract boundaries."""
import copy
import importlib.util
from pathlib import Path
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[1]
module = importlib.util.spec_from_file_location('wire_generator', ROOT / 'scripts/generate-wire.py')
generator = importlib.util.module_from_spec(module)
module.loader.exec_module(generator)


class GenerationBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.original = yaml.safe_load((ROOT / 'resources/shieldlabs-api.yaml').read_text())

    def operation(self, doc, name):
        return next((item, op) for item in doc['paths'].values()
                    for method, op in item.items()
                    if method == 'get' and op['operationId'] == name)

    def test_unknown_required_parameters_are_rejected_on_both_operations(self):
        for operation in ['searchHistory', 'getDomainProfile']:
            for location in ['query', 'header', 'path']:
                for level in ['path-item', 'operation']:
                    with self.subTest(operation=operation, location=location, level=level):
                        doc = copy.deepcopy(self.original)
                        item, op = self.operation(doc, operation)
                        target = item if level == 'path-item' else op
                        target.setdefault('parameters', []).append({
                            'name': 'tenant', 'in': location, 'required': True,
                            'schema': {'type': 'string'}})
                        with self.assertRaisesRegex(ValueError, 'Unsupported required parameter'):
                            generator.generate(doc)

    def test_optional_query_and_header_parameters_remain_compatible(self):
        baseline = generator.generate(self.original)
        for operation in ['searchHistory', 'getDomainProfile']:
            for location in ['query', 'header']:
                with self.subTest(operation=operation, location=location):
                    doc = copy.deepcopy(self.original)
                    _, op = self.operation(doc, operation)
                    op.setdefault('parameters', []).append({
                        'name': 'future', 'in': location, 'required': False,
                        'schema': {'type': 'string'}})
                    self.assertEqual(baseline, generator.generate(doc))

    def test_profile_route_changes_require_adapter_update(self):
        doc = copy.deepcopy(self.original)
        item, _ = self.operation(doc, 'getDomainProfile')
        old = next(path for path, value in doc['paths'].items() if value is item)
        doc['paths']['/v2/profile'] = doc['paths'].pop(old)
        with self.assertRaisesRegex(ValueError, 'Unsupported profile route'):
            generator.generate(doc)

    def test_ping_common_field_rename_and_retype_require_adapter_update(self):
        for field in ['event_type', 'schema_version', 'created_at']:
            for change in ['rename', 'retype']:
                with self.subTest(field=field, change=change):
                    doc = copy.deepcopy(self.original)
                    props = doc['components']['schemas']['WebhookPingEvent']['properties']
                    if change == 'rename':
                        props[field + '_changed'] = props.pop(field)
                    else:
                        props[field] = {'type': 'integer'}
                    with self.assertRaisesRegex(ValueError, 'Incompatible shared webhook field'):
                        generator.generate(doc)


if __name__ == '__main__':
    unittest.main()
