package com.zonak.portal.integration.sap;

final class SapSoapResponse {
    static final String NAMESPACE = "http://wsenviardocumento.webservice.dispapeles.com/";
    static final String CODIGO_OK = "0";
    static final String CODIGO_NEGOCIO = "1";
    static final String CODIGO_AUTH = "2";
    static final String CODIGO_DIAN = "3";

    private SapSoapResponse() {
    }

    static String success(String mensaje, String cufe, String invoiceId, String numeroDocumento) {
        return envelope(
                CODIGO_OK,
                blankToDefault(mensaje, "Procesado Correctamente."),
                cufe,
                invoiceId,
                numeroDocumento
        );
    }

    static String error(String codigo, String mensaje) {
        return envelope(codigo, blankToDefault(mensaje, "Error procesando documento SAP"), "", "", "");
    }

    static String fault(String mensaje) {
        String safe = xmlEscape(blankToDefault(mensaje, "Error SOAP"));
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <soap:Fault>
                      <faultcode>soap:Client</faultcode>
                      <faultstring>%s</faultstring>
                    </soap:Fault>
                  </soap:Body>
                </soap:Envelope>
                """.formatted(safe);
    }

    private static String envelope(
            String codigo,
            String mensaje,
            String cufe,
            String invoiceId,
            String numeroDocumento
    ) {
        String estadoProceso = CODIGO_OK.equals(codigo) ? "PROCESADO" : "ERROR";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <n0:enviarDocumentoResponse xmlns:n0="%s">
                      <return>
                        <codigo>%s</codigo>
                        <mensaje>%s</mensaje>
                        <cufe>%s</cufe>
                        <estadoProceso>%s</estadoProceso>
                        <idDocumento>%s</idDocumento>
                        <numeroDocumento>%s</numeroDocumento>
                      </return>
                    </n0:enviarDocumentoResponse>
                  </soap:Body>
                </soap:Envelope>
                """.formatted(
                NAMESPACE,
                xmlEscape(codigo),
                xmlEscape(mensaje),
                xmlEscape(nullToEmpty(cufe)),
                xmlEscape(estadoProceso),
                xmlEscape(nullToEmpty(invoiceId)),
                xmlEscape(nullToEmpty(numeroDocumento))
        );
    }

    private static String xmlEscape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
