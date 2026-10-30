"""Text encoding detection, decoding, and binary sniffing utilities."""

from __future__ import annotations

UTF8_BOM = b"\xef\xbb\xbf"
UTF16_LE_BOM = b"\xff\xfe"
UTF16_BE_BOM = b"\xfe\xff"

SUPPORTED_ENCODINGS: tuple[str, ...] = (
    "utf-8",
    "utf-16-le",
    "utf-16-be",
    "iso-8859-1",
    "cp1252",
    "gbk",
    "shift_jis",
)


def is_probably_text(data: bytes, sniff_bytes: int = 8192) -> bool:
    """Return True if the initial bytes look like text (no NUL bytes, or valid UTF-16 BOM)."""
    head = data[:sniff_bytes]
    if not head:
        return True
    if head.startswith((UTF16_LE_BOM, UTF16_BE_BOM)):
        return True
    return b"\x00" not in head


def detect_eol(text: str) -> str:
    """Detect dominant line ending ('\\r\\n' or '\\n')."""
    if "\r\n" in text:
        return "\r\n"
    return "\n"


def detect_encoding(data: bytes) -> tuple[str, bytes]:
    """Sniff BOM first, then validate UTF-8, falling back to ISO-8859-1."""
    if data.startswith(UTF8_BOM):
        return "utf-8", UTF8_BOM
    if data.startswith(UTF16_LE_BOM):
        return "utf-16-le", UTF16_LE_BOM
    if data.startswith(UTF16_BE_BOM):
        return "utf-16-be", UTF16_BE_BOM
    try:
        data.decode("utf-8")
        return "utf-8", b""
    except UnicodeDecodeError:
        return "iso-8859-1", b""


def decode_text(data: bytes, encoding: str | None = None) -> tuple[str, str]:
    """Decode bytes into (text, canonical_encoding)."""
    if encoding:
        norm = encoding.strip().lower()
        if norm in ("utf-8", "utf8") and data.startswith(UTF8_BOM):
            return data[len(UTF8_BOM) :].decode("utf-8"), "utf-8"
        if norm in ("utf-16", "utf16"):
            return data.decode("utf-16"), "utf-16"
        if norm == "utf-16-le" and data.startswith(UTF16_LE_BOM):
            return data[len(UTF16_LE_BOM) :].decode("utf-16-le"), "utf-16-le"
        if norm == "utf-16-be" and data.startswith(UTF16_BE_BOM):
            return data[len(UTF16_BE_BOM) :].decode("utf-16-be"), "utf-16-be"
        return data.decode(encoding), norm

    detected, bom = detect_encoding(data)
    payload = data[len(bom) :] if bom else data
    return payload.decode(detected), detected


def encode_text(text: str, encoding: str = "utf-8", include_bom: bool = False) -> bytes:
    """Encode text to bytes with optional BOM."""
    norm = encoding.strip().lower()
    raw = text.encode(encoding)
    if include_bom:
        if norm in ("utf-8", "utf8") and not raw.startswith(UTF8_BOM):
            return UTF8_BOM + raw
        if norm == "utf-16-le" and not raw.startswith(UTF16_LE_BOM):
            return UTF16_LE_BOM + raw
        if norm == "utf-16-be" and not raw.startswith(UTF16_BE_BOM):
            return UTF16_BE_BOM + raw
    return raw
