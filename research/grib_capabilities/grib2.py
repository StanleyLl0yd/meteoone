from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any


GRIB_MAGIC = b"GRIB"
END_MARKER = b"7777"
MIN_MESSAGE_BYTES = 20
MAX_MESSAGE_BYTES = 64 * 1024 * 1024
MAX_MESSAGES = 256


@dataclass(frozen=True)
class Grib2MessageInfo:
    offset: int
    length: int
    discipline: int
    edition: int
    section_numbers: tuple[int, ...]
    grid_definition_template: int
    product_definition_template: int
    data_representation_template: int
    parameter_category: int | None
    parameter_number: int | None

    def to_dict(self) -> dict[str, Any]:
        payload = asdict(self)
        payload["section_numbers"] = list(self.section_numbers)
        return payload


def inspect_grib2(data: bytes) -> tuple[Grib2MessageInfo, ...]:
    """Inspect one or more concatenated GRIB2 messages without decoding values."""
    if not data:
        raise ValueError("GRIB2 payload is empty")

    messages: list[Grib2MessageInfo] = []
    cursor = 0
    while cursor < len(data):
        if len(messages) >= MAX_MESSAGES:
            raise ValueError("GRIB2 payload contains too many messages")
        message = _inspect_message(data, cursor)
        messages.append(message)
        cursor += message.length

    if cursor != len(data):
        raise ValueError("GRIB2 payload contains trailing bytes")
    return tuple(messages)


def _inspect_message(data: bytes, offset: int) -> Grib2MessageInfo:
    remaining = len(data) - offset
    if remaining < 16:
        raise ValueError(f"Truncated GRIB2 section 0 at byte {offset}")
    if data[offset : offset + 4] != GRIB_MAGIC:
        raise ValueError(f"Missing GRIB magic at byte {offset}")

    discipline = data[offset + 6]
    edition = data[offset + 7]
    if edition != 2:
        raise ValueError(f"Unsupported GRIB edition {edition} at byte {offset}")

    message_length = int.from_bytes(data[offset + 8 : offset + 16], "big")
    if message_length < MIN_MESSAGE_BYTES:
        raise ValueError(f"Invalid GRIB2 message length {message_length}")
    if message_length > MAX_MESSAGE_BYTES:
        raise ValueError(f"GRIB2 message exceeds {MAX_MESSAGE_BYTES} byte limit")
    end = offset + message_length
    if end > len(data):
        raise ValueError(
            f"GRIB2 message at byte {offset} declares {message_length} bytes but is truncated"
        )
    if data[end - 4 : end] != END_MARKER:
        raise ValueError(f"GRIB2 message at byte {offset} is missing section 8 marker")

    section_cursor = offset + 16
    section_end = end - 4
    section_numbers: list[int] = []
    section_payloads: dict[int, bytes] = {}
    previous_section = 0

    while section_cursor < section_end:
        if section_end - section_cursor < 5:
            raise ValueError(f"Truncated GRIB2 section header at byte {section_cursor}")

        section_length = int.from_bytes(
            data[section_cursor : section_cursor + 4], "big"
        )
        section_number = data[section_cursor + 4]
        if section_length < 5:
            raise ValueError(
                f"Invalid section {section_number} length {section_length} at byte {section_cursor}"
            )
        next_cursor = section_cursor + section_length
        if next_cursor > section_end:
            raise ValueError(
                f"Section {section_number} at byte {section_cursor} exceeds message boundary"
            )
        if section_number not in range(1, 8):
            raise ValueError(f"Invalid GRIB2 section number {section_number}")
        if section_number == 2:
            if previous_section != 1:
                raise ValueError("Optional GRIB2 section 2 must follow section 1")
        elif section_number <= previous_section:
            raise ValueError("GRIB2 sections are duplicated or out of order")

        section = data[section_cursor:next_cursor]
        section_numbers.append(section_number)
        section_payloads[section_number] = section
        previous_section = section_number
        section_cursor = next_cursor

    if section_cursor != section_end:
        raise ValueError("GRIB2 section parsing did not end at section 8")

    required_sections = {1, 3, 4, 5, 6, 7}
    missing = required_sections - section_payloads.keys()
    if missing:
        raise ValueError(f"GRIB2 message is missing required sections {sorted(missing)}")

    section3 = section_payloads[3]
    section4 = section_payloads[4]
    section5 = section_payloads[5]
    if len(section3) < 14:
        raise ValueError("GRIB2 section 3 is too short for a grid template number")
    if len(section4) < 9:
        raise ValueError("GRIB2 section 4 is too short for a product template number")
    if len(section5) < 11:
        raise ValueError("GRIB2 section 5 is too short for a data template number")

    parameter_category = section4[9] if len(section4) >= 11 else None
    parameter_number = section4[10] if len(section4) >= 11 else None

    return Grib2MessageInfo(
        offset=offset,
        length=message_length,
        discipline=discipline,
        edition=edition,
        section_numbers=tuple(section_numbers),
        grid_definition_template=int.from_bytes(section3[12:14], "big"),
        product_definition_template=int.from_bytes(section4[7:9], "big"),
        data_representation_template=int.from_bytes(section5[9:11], "big"),
        parameter_category=parameter_category,
        parameter_number=parameter_number,
    )
