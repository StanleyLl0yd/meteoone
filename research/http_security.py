from __future__ import annotations

import urllib.request
from collections.abc import Callable
from typing import Any


class ValidatingRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Validate a redirect target before urllib opens the redirected URL."""

    def __init__(self, validate_redirect: Callable[[str], object]) -> None:
        super().__init__()
        self._validate_redirect = validate_redirect

    def redirect_request(
        self,
        req: urllib.request.Request,
        fp: Any,
        code: int,
        msg: str,
        headers: Any,
        newurl: str,
    ) -> urllib.request.Request | None:
        self._validate_redirect(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def open_with_validated_redirects(
    request: urllib.request.Request,
    *,
    timeout_seconds: float,
    validate_redirect: Callable[[str], object],
) -> Any:
    """Open a request while validating every redirect before it is followed."""

    if timeout_seconds <= 0:
        raise ValueError("timeout_seconds must be positive")
    opener = urllib.request.build_opener(ValidatingRedirectHandler(validate_redirect))
    return opener.open(request, timeout=timeout_seconds)


def read_bounded(response: Any, *, max_bytes: int, url: str) -> bytes:
    """Read at most max_bytes and reject oversized declared or actual bodies."""

    if max_bytes <= 0:
        raise ValueError("max_bytes must be positive")

    headers = getattr(response, "headers", None)
    declared = headers.get("Content-Length") if headers is not None else None
    if declared is not None:
        try:
            declared_length = int(declared)
        except (TypeError, ValueError) as error:
            raise RuntimeError(
                f"Response from {url} has an invalid Content-Length"
            ) from error
        if declared_length < 0:
            raise RuntimeError(f"Response from {url} has a negative Content-Length")
        if declared_length > max_bytes:
            raise RuntimeError(
                f"Response from {url} declares {declared_length} bytes; "
                f"limit is {max_bytes}"
            )

    payload = response.read(max_bytes + 1)
    if len(payload) > max_bytes:
        raise RuntimeError(f"Response from {url} exceeded {max_bytes} bytes")
    return payload
