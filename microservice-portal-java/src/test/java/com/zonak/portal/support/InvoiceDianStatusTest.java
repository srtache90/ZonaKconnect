package com.zonak.portal.support;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InvoiceDianStatusTest {

    @Test
    void shouldAcceptApprovedAndAuthorizedDianStates() {
        assertTrue(InvoiceDianStatus.isValidated("ENVIADO", "abc"));
        assertTrue(InvoiceDianStatus.isValidated("Documento Validado Exitosamente", "abc"));
        assertTrue(InvoiceDianStatus.isValidated("APROBADO", "abc"));
        assertTrue(InvoiceDianStatus.isValidated("AUTORIZADO", "abc"));
        assertTrue(InvoiceDianStatus.isValidated("ACEPTADO", ""));
        assertTrue(InvoiceDianStatus.isValidated("AUTORIZADO", null));
    }

    @Test
    void shouldRejectRejectedDianStates() {
        assertFalse(InvoiceDianStatus.isValidated("RECHAZADO", "abc"));
        assertFalse(InvoiceDianStatus.isValidated("ERROR_DIAN_NET", "abc"));
    }
}
