package com.leandrossb.nummus.interfaces.csv;

import java.util.ArrayList;
import java.util.List;

/** RFC 4180 rendering for merchant exports: header row, CRLF endings,
 *  fields wrapped (and inner quotes doubled) when they carry the separator,
 *  a quote, or a line break. */
public final class Csv {

  private Csv() {}

  /** True when the Accept header names text/csv (browsers send bare or
   *  q-weighted; JSON remains the default for absent or wildcard). */
  public static boolean wantsCsv(String acceptHeader) {
    return acceptHeader != null && acceptHeader.contains("text/csv");
  }

  public static String render(List<String> header, List<List<String>> rows) {
    var out = new StringBuilder();
    appendRow(out, header);
    for (List<String> row : rows) {
      appendRow(out, row);
    }
    return out.toString();
  }

  private static void appendRow(StringBuilder out, List<String> row) {
    List<String> rendered = new ArrayList<>(row.size());
    for (String field : row) {
      rendered.add(field == null ? "" : quote(field));
    }
    out.append(String.join(",", rendered)).append("\r\n");
  }

  private static String quote(String field) {
    if (field.indexOf(',') >= 0 || field.indexOf('"') >= 0
        || field.indexOf('\r') >= 0 || field.indexOf('\n') >= 0) {
      return '"' + field.replace("\"", "\"\"") + '"';
    }
    return field;
  }
}
