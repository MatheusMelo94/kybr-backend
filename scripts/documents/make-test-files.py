#!/usr/bin/env python3
"""Writes the synthetic upload test files into a directory (none is a real document).

  python3 -I scripts/documents/make-test-files.py <out-dir>

  plain.pdf        a valid one-page PDF (expected: stored)
  marker.pdf       a valid PDF carrying the scanner self-test marker in an uncompressed content
                   stream (expected: refused by the scanner, 415)
  javascript.pdf   opens with a JavaScript action (expected: 415)
  launch.pdf       opens with a Launch action (expected: 415)
  encrypted.pdf    declares standard encryption (expected: 415)
  openaction.pdf   opens at a page (/OpenAction without any script) (expected: 415)
  aa.pdf           document additional actions (/AA) (expected: 415)
  objstm-js.pdf    a /JS action only inside a compressed object stream (no literal /JS or /JavaScript
                   in the file's bytes) (expected: 415)
  escaped-javascript.pdf  /J#61vaScript and /J#53 (hex-escaped names) (expected: 415)
  escaped-js.pdf   /#4A#53 (a fully escaped /JS key) (expected: 415)
  font-space-name.pdf  a font name with an escaped space (/Synthetic#20Sans), legitimate
                   (expected: stored)
  photo.png        a small synthetic RGB photo (expected: re-encoded by the sanitizer, stored)
  not-a-document.txt  plain text (expected: 415 before any scan)

The PDF structure matches the test suite's synthetic PDFs, so staging evidence and tests agree.
"""
import os
import struct
import sys
import zlib

MARKER = "KYBR-SCANNER-SELFTEST-5E1F7D3C"


def text(line):
    return "BT /F1 12 Tf 20 100 Td (" + line + ") Tj ET"


def build(content, catalog_extra="", sixth=None, trailer_extra="", font="Helvetica", version="1.4"):
    objects = [
        "<< /Type /Catalog /Pages 2 0 R" + catalog_extra + " >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R"
        " /Resources << /Font << /F1 5 0 R >> >> >>",
        "<< /Length " + str(len(content)) + " >>\nstream\n" + content + "\nendstream",
        "<< /Type /Font /Subtype /Type1 /BaseFont /" + font + " >>",
    ]
    if sixth is not None:
        objects.append(sixth)
    pdf = "%PDF-" + version + "\n"
    offsets = []
    for i, obj in enumerate(objects):
        offsets.append(len(pdf))
        pdf += str(i + 1) + " 0 obj\n" + obj + "\nendobj\n"
    xref = len(pdf)
    pdf += "xref\n0 " + str(len(objects) + 1) + "\n0000000000 65535 f \n"
    for off in offsets:
        pdf += "%010d 00000 n \n" % off
    pdf += ("trailer\n<< /Size " + str(len(objects) + 1) + " /Root 1 0 R" + trailer_extra
            + " >>\nstartxref\n" + str(xref) + "\n%%EOF\n")
    return pdf.encode("latin-1")


def encrypted():
    o, u, ident = "a" * 64, "b" * 64, "c" * 32
    return build(text("Documento sintetico"), "",
                 "<< /Filter /Standard /V 1 /R 2 /O <" + o + "> /U <" + u + "> /P -44 >>",
                 " /Encrypt 6 0 R /ID [<" + ident + "> <" + ident + ">]")


def object_stream(inner):
    """Object 6: a FlateDecode object stream holding object 7 = inner (PDF 1.5)."""
    header = "7 0 "
    data = zlib.compress((header + inner).encode("latin-1")).decode("latin-1")
    return ("<< /Type /ObjStm /N 1 /First " + str(len(header)) + " /Filter /FlateDecode /Length "
            + str(len(data)) + " >>\nstream\n" + data + "\nendstream")


def png(width=64, height=48):
    # Each row: filter byte 0, then RGB pixels forming a simple gradient.
    rows = b"".join(
        b"\x00" + bytes(v for x in range(width) for v in ((x * 4) % 256, (y * 5) % 256, 128))
        for y in range(height))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    files = {
        "plain.pdf": build(text("Documento sintetico")),
        "marker.pdf": build(text(MARKER)),
        "javascript.pdf": build(text("Documento sintetico"), " /OpenAction 6 0 R",
                                "<< /Type /Action /S /JavaScript /JS (app.alert\\(1\\)) >>"),
        "launch.pdf": build(text("Documento sintetico"), " /OpenAction 6 0 R",
                            "<< /Type /Action /S /Launch /F (sintetico.exe) >>"),
        "encrypted.pdf": encrypted(),
        "openaction.pdf": build(text("Documento sintetico"), " /OpenAction [3 0 R /Fit]"),
        "aa.pdf": build(text("Documento sintetico"), " /AA << /WC 6 0 R >>",
                        "<< /Type /Action /S /GoTo /D [3 0 R /Fit] >>"),
        "objstm-js.pdf": build(text("Documento sintetico"), "", object_stream("<< /JS (app.alert\\(1\\)) >>"),
                               version="1.5"),
        "escaped-javascript.pdf": build(text("Documento sintetico"), "",
                                        "<< /Type /Action /S /J#61vaScript /J#53 (app.alert\\(1\\)) >>"),
        "escaped-js.pdf": build(text("Documento sintetico"), "", "<< /#4A#53 (app.alert\\(1\\)) >>"),
        "font-space-name.pdf": build(text("Documento sintetico"), font="Synthetic#20Sans"),
        "photo.png": png(),
        "not-a-document.txt": b"synthetic text, not a document\n",
    }
    for name, data in files.items():
        with open(os.path.join(out, name), "wb") as f:
            f.write(data)
    print("wrote " + ", ".join(sorted(files)) + " to " + out)


if __name__ == "__main__":
    main()
