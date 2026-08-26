package com.zonak.portal.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zonak.portal.dto.CreateCreditNoteRequestDTO;
import com.zonak.portal.dto.CreateInvoiceRequestDTO;
import com.zonak.portal.integration.pos.PosTicketRequest;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IngestInvoiceMapperPosTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IngestInvoiceMapper mapper = new IngestInvoiceMapper();

    @Test
    void fromPosMapsHarmonyTicketToInvoice() throws Exception {
        String json = """
                {
                  "numero_factura": 1,
                  "numero_ticket": "34770",
                  "check_id": "HPL000019389",
                  "caja_wsid": "17401",
                  "codigo_fiscal": "48",
                  "Resolucion": "18764089669852",
                  "propina": "13611.00",
                  "total": "160611.00",
                  "items": [
                    {"nombre": "ClubColombia", "cantidad": "4", "precio": "13800.00", "subtotal": "55200.00"},
                    {"nombre": "AgManan600", "cantidad": "1", "precio": "8800.00", "subtotal": "8800.00"}
                  ]
                }
                """;
        PosTicketRequest ticket = objectMapper.readValue(json, PosTicketRequest.class);
        CreateInvoiceRequestDTO dto = mapper.fromPos(ticket);

        assertEquals("CONSUMIDOR FINAL", dto.customer().businessName());
        assertEquals("222222222222", dto.customer().identificationNumber());
        assertEquals(3, dto.items().size());
        assertEquals("ClubColombia", dto.items().get(0).description());
        assertEquals(new BigDecimal("4"), dto.items().get(0).quantity());
        assertEquals("Propina", dto.items().get(2).description());
        assertEquals(new BigDecimal("13611.00"), dto.items().get(2).unitPrice());

        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) dto.totals();
        assertEquals("POS", totals.get("origen"));
        assertEquals("34770", totals.get("numero_ticket"));
        assertEquals("17401", totals.get("caja_wsid"));
        assertEquals(new BigDecimal("160611.00"), totals.get("total"));
        assertTrue(totals.get("resolucion").toString().contains("18764089669852"));
        assertFalse(dto.items().isEmpty());
    }

    @Test
    void fromPosMapsFactura01WithoutDuplicatingTipWhenCargosPresent() throws Exception {
        String json = """
                {
                  "tipo_documento": "FV",
                  "invoice_type_code": "01",
                  "codigo_fiscal": "01",
                  "Resolucion": "18764089669852",
                  "Prefijo": "EPR",
                  "propina": "5000.00",
                  "total": "55000.00",
                  "cliente": {
                    "tipo_identificacion": "31",
                    "numero_identificacion": "900123456",
                    "razon_social": "Cliente POS SAS"
                  },
                  "cargos": [{"codigo": "PROPINA", "nombre": "Propina", "valor": "5000.00", "es_propina": true}],
                  "items": [
                    {"codigo": "SKU-A1", "descripcion": "Plato", "cantidad": "1", "precio": "50000.00", "descuento": "0"}
                  ]
                }
                """;
        PosTicketRequest ticket = objectMapper.readValue(json, PosTicketRequest.class);
        CreateInvoiceRequestDTO dto = mapper.fromPos(ticket);

        assertFalse(ticket.isCreditNote());
        assertEquals("Cliente POS SAS", dto.customer().businessName());
        assertEquals(1, dto.items().size());
        assertEquals("SKU-A1", dto.items().get(0).code());
        assertEquals("Plato", dto.items().get(0).description());
    }

    @Test
    void fromPosCreditNoteMapsReferenceAndConcept() throws Exception {
        String json = """
                {
                  "tipo_documento": "NC",
                  "credit_note_type_code": "91",
                  "customization_id": "20",
                  "codigo_fiscal": "91",
                  "Prefijo": "NC",
                  "Resolucion": "18764089669852",
                  "cliente": {
                    "tipo_identificacion": "31",
                    "numero_identificacion": "900123456",
                    "razon_social": "Cliente POS SAS"
                  },
                  "items": [
                    {"codigo": "SKU-A1", "descripcion": "Plato", "cantidad": "1", "precio": "50000.00"}
                  ],
                  "factura_referencia": {
                    "tipoDocumento": "FV",
                    "numeroDocumento": "EPR1",
                    "fechaEmision": "2026-08-23",
                    "cufe": "abc123cufe",
                    "schemeName": "CUFE-SHA384"
                  },
                  "conceptos_correccion": [
                    {"codigo": "2", "descripcion": "Anulación de factura electrónica"}
                  ]
                }
                """;
        PosTicketRequest ticket = objectMapper.readValue(json, PosTicketRequest.class);
        assertTrue(ticket.isCreditNote());
        CreateCreditNoteRequestDTO dto = mapper.fromPosCreditNote(ticket);
        assertEquals("91", dto.creditNoteTypeCode());
        assertEquals("20", dto.customizationId());
        assertEquals("EPR1", dto.facturaReferencia().numeroDocumento());
        assertEquals("abc123cufe", dto.facturaReferencia().cufe());
        assertTrue(dto.facturaReferencia().fechaEmision().startsWith("2026-08-23T"));
        assertEquals("2", dto.conceptosCorreccion().get(0).codigo());
        assertEquals("SKU-A1", dto.items().get(0).code());
    }
}
