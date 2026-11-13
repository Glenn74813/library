package ph.edu.zscmst.library.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Excel (.xlsx) version of an export: title and subtitle on top, a bold header row with filters and
 * frozen panes, numbers as numbers. Text is always stored as text, so nothing in a cell can run as a formula.
 */
public final class Xlsx {

    private static final int MAX_WIDTH = 60;

    private Xlsx() {
    }

    public static ResponseEntity<byte[]> download(String filename, String title, String subtitle,
                                                  List<String> header, List<List<?>> rows) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(sheetName(title));
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle boldStyle = wb.createCellStyle();
            boldStyle.setFont(bold);
            Font big = wb.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 14);
            CellStyle titleStyle = wb.createCellStyle();
            titleStyle.setFont(big);
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));

            int r = 0;
            Cell t = sheet.createRow(r++).createCell(0);
            t.setCellValue(title);
            t.setCellStyle(titleStyle);
            if (subtitle != null && !subtitle.isBlank()) sheet.createRow(r++).createCell(0).setCellValue(subtitle);
            r++;
            int headerRow = r;
            int[] widths = new int[header.size()];
            Row h = sheet.createRow(r++);
            for (int c = 0; c < header.size(); c++) {
                Cell cell = h.createCell(c);
                cell.setCellValue(header.get(c));
                cell.setCellStyle(boldStyle);
                widths[c] = header.get(c).length();
            }
            for (List<?> values : rows) {
                Row row = sheet.createRow(r++);
                for (int c = 0; c < values.size() && c < header.size(); c++) {
                    Object v = values.get(c);
                    if (v == null) continue;
                    Cell cell = row.createCell(c);
                    if (v instanceof BigDecimal d) {
                        cell.setCellValue(d.doubleValue());
                        cell.setCellStyle(money);
                    } else if (v instanceof Number n) {
                        cell.setCellValue(n.doubleValue());
                    } else if (v instanceof Boolean b) {
                        cell.setCellValue(b ? "Yes" : "No");
                    } else {
                        cell.setCellValue(v.toString());
                    }
                    widths[c] = Math.max(widths[c], Math.min(MAX_WIDTH, v.toString().length()));
                }
            }
            for (int c = 0; c < widths.length; c++) sheet.setColumnWidth(c, (widths[c] + 3) * 256);
            sheet.createFreezePane(0, headerRow + 1);
            if (!header.isEmpty()) sheet.setAutoFilter(new CellRangeAddress(headerRow, Math.max(headerRow, r - 1), 0, header.size() - 1));
            wb.write(out);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the Excel file", e);
        }
    }

    /** Excel sheet names: at most 31 characters, none of : \ / ? * [ ]. */
    private static String sheetName(String title) {
        String s = title == null || title.isBlank() ? "Report" : title.replaceAll("[:\\\\/?*\\[\\]]", " ").trim();
        return s.length() > 31 ? s.substring(0, 31) : s;
    }
}
