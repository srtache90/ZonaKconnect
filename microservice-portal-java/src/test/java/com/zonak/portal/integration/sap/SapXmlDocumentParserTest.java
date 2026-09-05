package com.zonak.portal.integration.sap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SapXmlDocumentParserTest {
    private final SapXmlDocumentParser parser = new SapXmlDocumentParser();

    @Test
    void parsesSapProxySoapPayload() throws Exception {
        String soap = """
                <?xml version="1.0" encoding="UTF-8"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <n0:enviarDocumento xmlns:n0="http://wsenviardocumento.webservice.dispapeles.com/"
                                        xmlns:prx="urn:sap.com:proxy:KAP:/1SAI/TAS46FBCE707256B0BCB3FA:750">
                      <felCabezaDocumento>
                        <idEmpresa>1</idEmpresa>
                        <consecutivo>20226967</consecutivo>
                        <prefijo>EPR</prefijo>
                        <usuario>ULocalSap</usuario>
                        <contrasenia>SapMock2026!</contrasenia>
                        <fechafacturacion>2026-06-12T15:47:55Z</fechafacturacion>
                        <listaDetalle>
                          <cantidad>1.0</cantidad>
                          <codigoproducto>SKU-1</codigoproducto>
                          <descripcion>Producto SAP</descripcion>
                          <valorunitario>10000.0000</valorunitario>
                          <preciosinimpuestos>10000.0000</preciosinimpuestos>
                          <preciototal>11900.0000</preciototal>
                          <listaDescuentos>
                            <codigoDescuento>ZDES</codigoDescuento>
                            <descuento>0.0000</descuento>
                          </listaDescuentos>
                          <listaImpuestos>
                            <baseimponible>10000.0000</baseimponible>
                            <codigoImpuestoRetencion>01</codigoImpuestoRetencion>
                            <porcentaje>19.0</porcentaje>
                            <valorImpuestoRetencion>1900.0000</valorImpuestoRetencion>
                          </listaImpuestos>
                        </listaDetalle>
                        <pago>
                          <moneda>COP</moneda>
                          <totalbaseimponible>10000.0000</totalbaseimponible>
                          <totalfactura>11900.0000</totalfactura>
                        </pago>
                        <listaAdquirentes>
                          <tipoIdentificacion>31</tipoIdentificacion>
                          <numeroIdentificacion>900123456</numeroIdentificacion>
                          <nombreCompleto>Cliente SAP</nombreCompleto>
                          <email>cliente.sap@zonak.local</email>
                        </listaAdquirentes>
                      </felCabezaDocumento>
                    </n0:enviarDocumento>
                  </soap:Body>
                </soap:Envelope>
                """;

        SapEnviarDocumento documento = parser.parse(soap);
        SapEnviarDocumento.FelCabezaDocumento cabeza = documento.getFelCabezaDocumento();

        assertNotNull(cabeza);
        assertEquals("1", cabeza.getIdEmpresa());
        assertEquals("EPR", cabeza.getPrefijo());
        assertEquals("ULocalSap", cabeza.getUsuario());
        assertEquals("SapMock2026!", cabeza.getContrasenia());
        assertEquals("20226967", cabeza.getConsecutivo());
        assertEquals(1, cabeza.getListaDetalle().size());
        assertEquals("SKU-1", cabeza.getListaDetalle().get(0).getCodigoproducto());
        assertEquals(new BigDecimal("1900.0000"), cabeza.getListaDetalle().get(0).getListaImpuestos().get(0).getValorImpuestoRetencion());
        assertEquals("Cliente SAP", cabeza.getListaAdquirentes().getNombreCompleto());
    }

    @Test
    void soapResponseContainsDispapelesTags() {
        String xml = SapSoapResponse.success("Procesado Correctamente.", "CUFE-1", "inv-1", "EPR10");
        assertTrue(xml.contains("<codigo>0</codigo>"));
        assertTrue(xml.contains("<cufe>CUFE-1</cufe>"));
        assertTrue(xml.contains("<estadoProceso>PROCESADO</estadoProceso>"));
        assertTrue(xml.contains("<numeroDocumento>EPR10</numeroDocumento>"));
        assertTrue(xml.contains("enviarDocumentoResponse"));

        String errorXml = SapSoapResponse.error(SapSoapResponse.CODIGO_AUTH, "Credenciales SAP inválidas");
        assertTrue(errorXml.contains("<codigo>2</codigo>"));
        assertTrue(errorXml.contains("<estadoProceso>ERROR</estadoProceso>"));
    }
}
