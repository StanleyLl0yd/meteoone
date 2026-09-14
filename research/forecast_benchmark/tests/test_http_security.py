from __future__ import annotations

import io
import unittest
import urllib.request
from unittest.mock import Mock, patch

from research.http_security import (
    ValidatingRedirectHandler,
    open_with_validated_redirects,
    read_bounded,
)


class _Response:
    def __init__(self, body: bytes, *, content_length: str | None = None) -> None:
        self._body = io.BytesIO(body)
        self.headers = {}
        if content_length is not None:
            self.headers["Content-Length"] = content_length

    def read(self, size: int = -1) -> bytes:
        return self._body.read(size)


class ResearchHttpSecurityTest(unittest.TestCase):
    def test_redirect_target_is_validated_before_follow_request_is_created(self) -> None:
        seen: list[str] = []

        def reject(url: str) -> None:
            seen.append(url)
            raise ValueError("blocked redirect")

        handler = ValidatingRedirectHandler(reject)
        request = urllib.request.Request("https://origin.test/data")
        with self.assertRaisesRegex(ValueError, "blocked redirect"):
            handler.redirect_request(
                request,
                None,
                302,
                "Found",
                {},
                "https://other.test/data",
            )

        self.assertEqual(seen, ["https://other.test/data"])

    def test_open_uses_validating_redirect_handler(self) -> None:
        response = Mock()
        opener = Mock()
        opener.open.return_value = response
        validator = Mock()
        request = urllib.request.Request("https://origin.test/data")

        with patch(
            "research.http_security.urllib.request.build_opener",
            return_value=opener,
        ) as build_opener:
            actual = open_with_validated_redirects(
                request,
                timeout_seconds=5.0,
                validate_redirect=validator,
            )

        self.assertIs(actual, response)
        handler = build_opener.call_args.args[0]
        self.assertIsInstance(handler, ValidatingRedirectHandler)
        opener.open.assert_called_once_with(request, timeout=5.0)

    def test_bounded_read_rejects_declared_and_actual_oversize(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "declares 5 bytes"):
            read_bounded(
                _Response(b"12345", content_length="5"),
                max_bytes=4,
                url="https://example.test/data",
            )

        with self.assertRaisesRegex(RuntimeError, "exceeded 4 bytes"):
            read_bounded(
                _Response(b"12345"),
                max_bytes=4,
                url="https://example.test/data",
            )

    def test_bounded_read_rejects_invalid_content_length(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "invalid Content-Length"):
            read_bounded(
                _Response(b"ok", content_length="not-a-number"),
                max_bytes=4,
                url="https://example.test/data",
            )


if __name__ == "__main__":
    unittest.main()
