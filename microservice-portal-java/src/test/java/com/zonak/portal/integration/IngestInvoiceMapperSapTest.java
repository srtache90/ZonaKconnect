package com.zonak.portal.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.zonak.portal.dto.CreateInvoiceRequestDTO;
import com.zonak.portal.integration.sap.SapEnviarDocumento;
import com.zonak.portal.integration.sap.SapXmlDocumentParser;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IngestInvoiceMapperSapTest {
    private final SapXmlDocumentParser parser = new SapXmlDocumentParser();
    private final IngestInvoiceMapper mapper = new IngestInvoiceMapper();

    @Test
    void fromSapMapsLineDiscountAndIva() {
        String xml = """
                <enviarDocumento>
                  <felCabezaDocumento>
                    <idEmpresa>1</idEmpresa>
                    <prefijo>EPR</prefijo>
                    <consecutivo>10</consecutivo>
                    <listaDetalle>
                      <cantidad>2</cantidad>
                      <codigoproducto>SKU-1</codigoproducto>
                      <descripcion>Producto</descripcion>
                      <valorunitario>10000</valorunitario>
                      <listaDescuentos>
                        <descuento>500</descuento>
                      </listaDescuentos>
                      <listaImpuestos>
                        <codigoImpuestoRetencion>01</codigoImpuestoRetencion>
                        <porcentaje>19</porcentaje>
                        <baseimponible>19500</baseimponible>
                        <valorImpuestoRetencion>3705</valorImpuestoRetencion>
                      </listaImpuestos>
                    </listaDetalle>
                    <pago>
                      <totalbaseimponible>19500</totalbaseimponible>
                      <totalfactura>23205</totalfactura>
                    </pago>
                    <listaAdquirentes>
                      <tipoIdentificacion>31</tipoIdentificacion>
                      <numeroIdentificacion>900123456</numeroIdentificacion>
                      <nombreCompleto>Cliente SAP</nombreCompleto>
                    </listaAdquirentes>
                  </felCabezaDocumento>
                </enviarDocumento>
                """;

        SapEnviarDocumento documento = parser.parse(xml);
        CreateInvoiceRequestDTO dto = mapper.fromSap(documento);

        assertEquals("Cliente SAP", dto.customer().businessName());
        assertEquals("SKU-1", dto.items().get(0).code());
        assertEquals(new BigDecimal("500"), dto.items().get(0).discount());
        assertEquals("01", dto.items().get(0).taxes().get(0).code());
        assertEquals("IVA", dto.items().get(0).taxes().get(0).name());

        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) dto.totals();
        assertEquals("SAP", totals.get("origen"));
        assertEquals("EPR", totals.get("prefijo"));
        assertEquals(new BigDecimal("23205"), totals.get("total"));
    }
}
