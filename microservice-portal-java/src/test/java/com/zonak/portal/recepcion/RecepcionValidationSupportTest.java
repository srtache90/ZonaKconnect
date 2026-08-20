package com.zonak.portal.recepcion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecepcionValidationSupportTest {

    @Test
    void cufeSha384Valido() {
        String cufe = "a".repeat(96);
        assertTrue(RecepcionCufeValidator.isStructurallyValid(cufe));
        assertTrue(RecepcionCufeValidator.validateCufe(cufe).isEmpty());
    }

    @Test
    void cufeInvalido() {
        List<String> issues = RecepcionCufeValidator.validateCufe("corto");
        assertFalse(issues.isEmpty());
    }

    @Test
    void nitConYSinDv() {
        assertTrue(RecepcionCufeValidator.sameNit("900123456-1", "9001234561"));
        assertTrue(RecepcionCufeValidator.sameNit("900123456", "9001234567"));
        assertFalse(RecepcionCufeValidator.sameNit("900123456", "800123456"));
    }

    @Test
    void tresDiasHabilesDesdeViernes() {
        LocalDate viernes = LocalDate.of(2026, 8, 14);
        LocalDate limite = RecepcionBusinessDays.addBusinessDays(viernes, 3);
        assertEquals(LocalDate.of(2026, 8, 19), limite);
    }
}
