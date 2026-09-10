from __future__ import annotations

import unittest

from research.grib_capabilities.grib2 import inspect_grib2


def section(number: int, body: bytes) -> bytes:
    length = 5 + len(body)
    return length.to_bytes(4, "big") + bytes([number]) + body


def grib2_message(
    *,
    discipline: int = 0,
    grid_template: int = 0,
    product_template: int = 8,
    data_template: int = 42,
    parameter_category: int = 1,
    parameter_number: int = 8,
    include_local_use: bool = False,
) -> bytes:
    section1 = section(1, b"\x00")
    section2 = section(2, b"local") if include_local_use else b""
    section3 = section(
        3,
        b"\x00"  # grid-definition source, octet 6
        + (1).to_bytes(4, "big")  # number of points, octets 7-10
        + b"\x00\x00"  # optional-list metadata, octets 11-12
        + grid_template.to_bytes(2, "big"),  # octets 13-14
    )
    section4 = section(
        4,
        b"\x00\x00"  # number of coordinate values, octets 6-7
        + product_template.to_bytes(2, "big")  # octets 8-9
        + bytes([parameter_category, parameter_number]),  # octets 10-11
    )
    section5 = section(
        5,
        (1).to_bytes(4, "big")  # number of represented points, octets 6-9
        + data_template.to_bytes(2, "big"),  # octets 10-11
    )
    section6 = section(6, b"\xff")
    section7 = section(7, b"\x00")
    body = section1 + section2 + section3 + section4 + section5 + section6 + section7
    total_length = 16 + len(body) + 4
    section0 = (
        b"GRIB"
        + b"\x00\x00"
        + bytes([discipline, 2])
        + total_length.to_bytes(8, "big")
    )
    return section0 + body + b"7777"


class Grib2InspectorTest(unittest.TestCase):
    def test_extracts_grid_product_and_data_templates(self) -> None:
        payload = grib2_message(
            discipline=0,
            grid_template=101,
            product_template=8,
            data_template=42,
            parameter_category=2,
            parameter_number=22,
            include_local_use=True,
        )

        messages = inspect_grib2(payload)

        self.assertEqual(len(messages), 1)
        message = messages[0]
        self.assertEqual(message.offset, 0)
        self.assertEqual(message.length, len(payload))
        self.assertEqual(message.discipline, 0)
        self.assertEqual(message.edition, 2)
        self.assertEqual(message.section_numbers, (1, 2, 3, 4, 5, 6, 7))
        self.assertEqual(message.grid_definition_template, 101)
        self.assertEqual(message.product_definition_template, 8)
        self.assertEqual(message.data_representation_template, 42)
        self.assertEqual(message.parameter_category, 2)
        self.assertEqual(message.parameter_number, 22)

    def test_inspects_concatenated_messages_at_exact_boundaries(self) -> None:
        first = grib2_message(grid_template=0, product_template=0, data_template=3)
        second = grib2_message(grid_template=101, product_template=8, data_template=42)

        messages = inspect_grib2(first + second)

        self.assertEqual(len(messages), 2)
        self.assertEqual(messages[0].offset, 0)
        self.assertEqual(messages[1].offset, len(first))
        self.assertEqual(messages[1].length, len(second))

    def test_rejects_empty_non_grib_wrong_edition_and_truncation(self) -> None:
        with self.assertRaises(ValueError):
            inspect_grib2(b"")
        with self.assertRaises(ValueError):
            inspect_grib2(b"not grib")

        wrong_edition = bytearray(grib2_message())
        wrong_edition[7] = 1
        with self.assertRaises(ValueError):
            inspect_grib2(bytes(wrong_edition))

        payload = grib2_message()
        with self.assertRaises(ValueError):
            inspect_grib2(payload[:-1])

    def test_rejects_bad_message_length_and_missing_end_marker(self) -> None:
        payload = bytearray(grib2_message())
        payload[8:16] = (19).to_bytes(8, "big")
        with self.assertRaises(ValueError):
            inspect_grib2(bytes(payload))

        payload = bytearray(grib2_message())
        payload[-4:] = b"0000"
        with self.assertRaises(ValueError):
            inspect_grib2(bytes(payload))

    def test_rejects_duplicate_out_of_order_or_missing_sections(self) -> None:
        valid = grib2_message()
        section1_start = 16
        section1_length = int.from_bytes(valid[section1_start : section1_start + 4], "big")
        section3_start = section1_start + section1_length
        section3_length = int.from_bytes(valid[section3_start : section3_start + 4], "big")
        section3 = valid[section3_start : section3_start + section3_length]

        insertion = section3_start + section3_length
        body = valid[:insertion] + section3 + valid[insertion:-4]
        total_length = len(body) + 4
        duplicate = bytearray(body + b"7777")
        duplicate[8:16] = total_length.to_bytes(8, "big")
        with self.assertRaises(ValueError):
            inspect_grib2(bytes(duplicate))

        section4_start = section3_start + section3_length
        section4_length = int.from_bytes(valid[section4_start : section4_start + 4], "big")
        missing3 = valid[:section3_start] + valid[section4_start:-4]
        total_length = len(missing3) + 4
        missing3_payload = bytearray(missing3 + b"7777")
        missing3_payload[8:16] = total_length.to_bytes(8, "big")
        with self.assertRaises(ValueError):
            inspect_grib2(bytes(missing3_payload))

        self.assertGreater(section4_length, 0)


if __name__ == "__main__":
    unittest.main()
