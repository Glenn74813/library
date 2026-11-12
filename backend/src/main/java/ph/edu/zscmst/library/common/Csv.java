package ph.edu.zscmst.library.common;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Minimal RFC 4180 CSV writing/parsing (opens directly in Excel). */
public final class Csv {

    private Csv() {
    }

    public static ResponseEntity<byte[]> download(String filename, List<String> header, List<List<?>> rows) {
        StringBuilder sb = new StringBuilder("﻿"); // BOM so Excel detects UTF-8
        appendRow(sb, header);
        rows.forEach(r -> appendRow(sb, r));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void appendRow(StringBuilder sb, List<?> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            Object v = values.get(i);
            String s = v == null ? "" : v.toString();
            // Text starting like a formula would run in Excel (CSV injection): prefix an apostrophe to keep it text.
            if (v instanceof CharSequence && !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
            if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
                s = "\"" + s.replace("\"", "\"\"") + "\"";
            }
            sb.append(s);
        }
        sb.append("\r\n");
    }

    /** Parses CSV text into rows of fields, honouring quoted fields. */
    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        String s = text.startsWith("﻿") ? text.substring(1) : text;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < s.length() && s.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString().trim());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
                row.add(field.toString().trim());
                field.setLength(0);
                if (!(row.size() == 1 && row.getFirst().isEmpty())) rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (!field.isEmpty() || !row.isEmpty()) {
            row.add(field.toString().trim());
            rows.add(row);
        }
        return rows;
    }
}
