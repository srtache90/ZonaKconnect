package com.zonak.portal.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetTime;

public record DianFiscalContext(
        DocumentKind documentKind,
        String documentTitle,
        String templateName,
        String uniqueCodeLabel,
        String uniqueCodeSchemeName,
        String uniqueCode,
        LocalDate issueDate,
        OffsetTime issueTime,
        BigDecimal valIva,
        BigDecimal valOtroIm,
        String qrUrl,
        String qrContent
) {
    public enum DocumentKind {
        INVOICE,
        CREDIT_NOTE,
        DEBIT_NOTE
    }

    public boolean isCreditNote() {
        return documentKind == DocumentKind.CREDIT_NOTE;
    }

    public boolean isDebitNote() {
        return documentKind == DocumentKind.DEBIT_NOTE;
    }
}
