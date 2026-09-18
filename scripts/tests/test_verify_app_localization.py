from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from scripts.verify_app_localization import verify_localization


class AppLocalizationVerifierTest(unittest.TestCase):
    def _root(
        self,
        base_body: str,
        ru_body: str,
    ) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        base = root / "app/src/main/res/values/strings.xml"
        ru = root / "app/src/main/res/values-ru/strings.xml"
        base.parent.mkdir(parents=True)
        ru.parent.mkdir(parents=True)
        base.write_text(
            f'<?xml version="1.0" encoding="utf-8"?><resources>{base_body}</resources>',
            encoding="utf-8",
        )
        ru.write_text(
            f'<?xml version="1.0" encoding="utf-8"?><resources>{ru_body}</resources>',
            encoding="utf-8",
        )
        return temporary, root

    def test_accepts_matching_strings_and_locale_specific_plural_quantities(self) -> None:
        temporary, root = self._root(
            '<string name="title">Count %1$d</string>'
            '<plurals name="hours">'
            '<item quantity="one">%1$d hour</item>'
            '<item quantity="other">%1$d hours</item>'
            '</plurals>',
            '<string name="title">Количество: %1$d</string>'
            '<plurals name="hours">'
            '<item quantity="one">%1$d час</item>'
            '<item quantity="few">%1$d часа</item>'
            '<item quantity="many">%1$d часов</item>'
            '<item quantity="other">%1$d часа</item>'
            '</plurals>',
        )
        with temporary:
            verify_localization(root)

    def test_rejects_missing_russian_resource(self) -> None:
        temporary, root = self._root(
            '<string name="title">Title</string><string name="body">Body</string>',
            '<string name="title">Заголовок</string>',
        )
        with temporary:
            with self.assertRaisesRegex(ValueError, "missing in RU: body"):
                verify_localization(root)

    def test_rejects_string_plural_type_drift(self) -> None:
        temporary, root = self._root(
            '<string name="hours">%1$d hours</string>',
            '<plurals name="hours"><item quantity="other">%1$d часов</item></plurals>',
        )
        with temporary:
            with self.assertRaisesRegex(ValueError, "resource type string != plurals"):
                verify_localization(root)

    def test_rejects_placeholder_drift(self) -> None:
        temporary, root = self._root(
            '<string name="coordinate">%1$.1f, %2$.1f</string>',
            '<string name="coordinate">%1$.1f</string>',
        )
        with temporary:
            with self.assertRaisesRegex(ValueError, "format placeholders"):
                verify_localization(root)

    def test_rejects_inconsistent_plural_placeholders(self) -> None:
        temporary, root = self._root(
            '<plurals name="hours">'
            '<item quantity="one">%1$d hour</item>'
            '<item quantity="other">%1$d hours</item>'
            '</plurals>',
            '<plurals name="hours">'
            '<item quantity="one">%1$d час</item>'
            '<item quantity="other">часы</item>'
            '</plurals>',
        )
        with temporary:
            with self.assertRaisesRegex(ValueError, "inconsistent format placeholders"):
                verify_localization(root)


if __name__ == "__main__":
    unittest.main()
