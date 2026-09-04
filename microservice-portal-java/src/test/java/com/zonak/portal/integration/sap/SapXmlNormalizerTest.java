package com.zonak.portal.integration.sap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SapXmlNormalizerTest {

    @Test
    void unwrapsSoapEnvelopeAndStripsDispapelesPrefix() {
        String soap = """
                <?xml version="1.0" encoding="UTF-8"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <n0:enviarDocumento xmlns:n0="http://wsenviardocumento.webservice.dispapeles.com/"
                                        xmlns:prx="urn:sap.com:proxy:KAP:/1SAI/TAS46FBCE707256B0BCB3FA:750">
                      <felCabezaDocumento>
                        <idEmpresa>154</idEmpresa>
                        <prefijo>EPR</prefijo>
                      </felCabezaDocumento>
                    </n0:enviarDocumento>
                  </soap:Body>
                </soap:Envelope>
                """;

        String normalized = SapXmlNormalizer.unwrap(soap);

        assertTrue(normalized.contains("<enviarDocumento>"));
        assertTrue(normalized.contains("<idEmpresa>154</idEmpresa>"));
        assertTrue(normalized.contains("<prefijo>EPR</prefijo>"));
        assertTrue(!normalized.contains("n0:"));
        assertTrue(!normalized.contains("xmlns"));
    }

    @Test
    void keepsBareEnviarDocumento() {
        String xml = """
                <enviarDocumento>
                  <felCabezaDocumento>
                    <idEmpresa>1</idEmpresa>
                  </felCabezaDocumento>
                </enviarDocumento>
                """;

        assertEquals(xml.strip(), SapXmlNormalizer.unwrap(xml));
    }
}
