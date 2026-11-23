# SPDX-License-Identifier: Apache-2.0
"""Offline packaging/extension assertions, independent of SWT runtime."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
CORE = ROOT / 'plugins/io.github.niwantha33.zew.core'
UI = ROOT / 'plugins/io.github.niwantha33.zew.ui'


class QuickHelpIntegrationTests(unittest.TestCase):
    def test_expected_eclipse_contributions(self):
        root = ET.parse(UI / 'plugin.xml').getroot()
        all_tags = list(root.iter())
        command = 'io.github.niwantha33.zew.quickHelp'
        view = 'io.github.niwantha33.zew.ui.quickHelp'
        self.assertEqual(1, sum(e.get('id') == view for e in all_tags))
        self.assertEqual(1, sum(e.tag == 'command' and e.get('id') == command for e in all_tags))
        self.assertEqual(1, sum(e.tag == 'handler' and e.get('commandId') == command for e in all_tags))
        self.assertTrue(any(e.tag == 'menuContribution' and e.get('locationURI', '').startswith('popup:#TextEditorContext')
                            and any(c.get('commandId') == command for c in e.iter()) for e in all_tags))

    def test_catalogue_is_packaged(self):
        build = (CORE / 'build.properties').read_text(encoding='utf-8')
        self.assertIn('quick-help/', build)
        index = (CORE / 'quick-help/v1/index.txt').read_text(encoding='utf-8')
        self.assertTrue(index.startswith('format: 1\n'))
        for entry in [line for line in index.splitlines()[1:] if line and not line.startswith('#')]:
            doc = (CORE / 'quick-help/v1' / entry)
            self.assertTrue(doc.is_file(), str(doc))
            content = doc.read_text(encoding='utf-8')
            self.assertIn('version: 4.4.0', content)
            self.assertIn('source: https://github.com/zephyrproject-rtos/zephyr/blob/v4.4.0/', content)
            self.assertIn('## Example', content)


if __name__ == '__main__':
    unittest.main()
