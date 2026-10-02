package com.leandrossb.nummus.interfaces.csv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** RFC 4180 rendering: header, quoting, CRLF — and the Accept probe. */
class CsvTest {

  @Test
  void rendersHeaderAndRowsWithCrlf() {
    var csv = Csv.render(List.of("publicId", "amount"),
        List.of(List.of("a-1", "10.0000"), List.of("a-2", "20.0000")));
    assertEquals("publicId,amount\r\na-1,10.0000\r\na-2,20.0000\r\n", csv);
  }

  @Test
  void quotesFieldsContainingSeparatorQuoteOrNewline() {
    var csv = Csv.render(List.of("memo"),
        List.of(List.of("transfer a, \"urgent\"\nline"), List.of("plain")));
    assertEquals("memo\r\n\"transfer a, \"\"urgent\"\"\nline\"\r\nplain\r\n", csv);
  }

  @Test
  void acceptProbeMatchesTextCsvOnly() {
    assertTrue(Csv.wantsCsv("text/csv"));
    assertTrue(Csv.wantsCsv("text/csv;q=0.9,application/json"));
    assertFalse(Csv.wantsCsv("application/json"));
    assertFalse(Csv.wantsCsv(null));
    assertFalse(Csv.wantsCsv("*/*"));
  }
}
