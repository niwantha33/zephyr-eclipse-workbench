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

    def test_native_workbench_smoke_sources_registered(self):
        """Every smoke class invoked by WorkbenchSmoke must be compiled into its test bundle."""
        import re
        workbench = (ROOT / 'tests/eclipse/WorkbenchSmoke.java').read_text(encoding='utf-8')
        editor_smoke = (ROOT / 'scripts/editor_smoke.py').read_text(encoding='utf-8')
        invoked = set(re.findall(r'\b([A-Za-z][A-Za-z0-9]*Smoke)\.run\(\)', workbench))
        self.assertIn('QuickHelpSmoke', invoked)
        for name in sorted(invoked):
            with self.subTest(smoke=name):
                self.assertIn(f"tests/eclipse/{name}.java", editor_smoke)

    def test_learning_view_not_a_plain_text_dump(self):
        source = (UI / 'src/io/github/niwantha33/zew/ui/QuickHelpView.java').read_text(encoding='utf-8')
        for feature in ('new TabFolder(', 'new ScrolledComposite(', 'new StyledText(',
                        'void resizeText(', 'SWT.VERTICAL', 'SWT.HORIZONTAL',
                        'new StyleRange(', 'updateScrollSize('):
            with self.subTest(feature=feature):
                self.assertIn(feature, source)
        self.assertNotIn('COMPLETE EXAMPLE (main.c)\\n', source,
                         'Do not regress to the previous single giant text panel')

    def test_search_only_without_shortcut_buttons(self):
        source = (UI / 'src/io/github/niwantha33/zew/ui/QuickHelpView.java').read_text(encoding='utf-8')
        self.assertIn('SWT.SEARCH', source)
        self.assertIn('search.addModifyListener', source)
        self.assertNotIn('STARTERS', source)
        self.assertNotIn('starterGrid', source)
        self.assertIn('new TabFolder(', source)

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
