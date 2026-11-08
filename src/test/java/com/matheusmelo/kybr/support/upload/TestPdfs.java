package com.matheusmelo.kybr.support.upload;

import com.matheusmelo.kybr.service.scan.StubScanner;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Small, well-formed synthetic PDFs (one page, uncompressed, a correct cross-reference table), each
 * carrying at most one feature the scanner must refuse: the scanner self-test marker, an
 * {@code /OpenAction} running {@code /JavaScript} or a {@code /Launch} action, or an
 * {@code /Encrypt} dictionary. None is a real document.
 */
public final class TestPdfs {

    private TestPdfs() {
    }

    public static byte[] plain() {
        return build(text("Documento sintetico"), "", null, "");
    }

    /** The self-test marker inside the page's (uncompressed) content stream. */
    public static byte[] withScannerMarker() {
        return build(text(StubScanner.TEST_MARKER), "", null, "");
    }

    /** Opens with a JavaScript action. */
    public static byte[] withJavaScript() {
        return build(text("Documento sintetico"), " /OpenAction 6 0 R",
                "<< /Type /Action /S /JavaScript /JS (app.alert\\(1\\)) >>", "");
    }

    /** Opens with a launch action. */
    public static byte[] withLaunch() {
        return build(text("Documento sintetico"), " /OpenAction 6 0 R",
                "<< /Type /Action /S /Launch /F (sintetico.exe) >>", "");
    }

    /** Declares standard (RC4) encryption in its trailer. */
    public static byte[] encrypted() {
        HexFormat hex = HexFormat.of();
        String o = hex.formatHex(new byte[32]).replace('0', 'a');
        String u = hex.formatHex(new byte[32]).replace('0', 'b');
        String id = hex.formatHex(new byte[16]).replace('0', 'c');
        return build(text("Documento sintetico"), "",
                "<< /Filter /Standard /V 1 /R 2 /O <" + o + "> /U <" + u + "> /P -44 >>",
                " /Encrypt 6 0 R /ID [<" + id + "> <" + id + ">]");
    }

    private static String text(String line) {
        return "BT /F1 12 Tf 20 100 Td (" + line + ") Tj ET";
    }

    private static byte[] build(String content, String catalogExtra, String sixth, String trailerExtra) {
        List<String> objects = new ArrayList<>();
        objects.add("<< /Type /Catalog /Pages 2 0 R" + catalogExtra + " >>");
        objects.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
        objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R"
                + " /Resources << /Font << /F1 5 0 R >> >> >>");
        objects.add("<< /Length " + content.length() + " >>\nstream\n" + content + "\nendstream");
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        if (sixth != null) {
            objects.add(sixth);
        }
        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(pdf.length());
            pdf.append(i + 1).append(" 0 obj\n").append(objects.get(i)).append("\nendobj\n");
        }
        int xref = pdf.length();
        pdf.append("xref\n0 ").append(objects.size() + 1).append("\n0000000000 65535 f \n");
        for (int offset : offsets) {
            pdf.append(String.format("%010d 00000 n \n", offset));
        }
        pdf.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R").append(trailerExtra)
                .append(" >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
}
