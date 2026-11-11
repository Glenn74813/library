package ph.edu.zscmst.library.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class CsvTest {

    @Test
    void parsesQuotedFieldsAndBom() {
        String text = "﻿cardNumber,lastName\r\n2024-1,\"Dela Cruz, Jr.\"\n2024-2,\"He said \"\"hi\"\"\"\n\n";
        assertThat(Csv.parse(text)).containsExactly(
                List.of("cardNumber", "lastName"),
                List.of("2024-1", "Dela Cruz, Jr."),
                List.of("2024-2", "He said \"hi\""));
    }

    @Test
    void parsesLastLineWithoutNewline() {
        assertThat(Csv.parse("a,b\n1,")).containsExactly(List.of("a", "b"), List.of("1", ""));
    }
}
