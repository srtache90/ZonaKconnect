package com.zonak.portal.integration.sap;

import com.zonak.portal.support.InvoiceDianStatus;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

final class SapConsultarEstadoResponse {
    static final String NAMESPACE = "http://wsconsultaestadofactura.webservice.dispapeles.com/";
    static final int ESTADO_ERROR = 0;
    static final int ESTADO_EXITOSO = 1;
    static final int ESTADO_PROCESANDO = 4;

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
            .withZone(ZoneOffset.UTC);

    private SapConsultarEstadoResponse() {
    }

    static String found(SapConsultarEstado consulta, SapConsultaDocumento documento) {
        int estadoProceso = estadoProceso(documento.estadoDian(), documento.cufe());
        int codigoDian = InvoiceDianStatus.isRejected(documento.estadoDian())
                ? 99
                : (InvoiceDianStatus.isValidated(documento.estadoDian(), documento.cufe()) ? 0 : 4);
        int codigoDispapeles = estadoProceso == ESTADO_EXITOSO ? 14 : (estadoProceso == ESTADO_PROCESANDO ? 8 : 19);
        String mensaje = blankToDefault(documento.mensajeDian(), descripcionEstado(estadoProceso));
        String fecha = formatTimestamp(documento.createdAt());
        return envelope(
                blankToDefault(consulta.getTipoDocumento(), "1"),
                blankToDefault(documento.prefijo(), consulta.getPrefijo()),
                String.valueOf(documento.numero()),
                fecha,
                nullToEmpty(documento.cufe()),
                documento.invoiceId().toString(),
                codigoDispapeles,
                mensaje,
                codigoDian,
                mensaje,
                estadoProceso,
                mensaje
        );
    }

    static String notFound(SapConsultarEstado consulta, String mensaje) {
        String safe = blankToDefault(mensaje, "Documento no encontrado");
        String now = formatTimestamp(Instant.now());
        return envelope(
                blankToDefault(consulta == null ? null : consulta.getTipoDocumento(), "1"),
                consulta == null ? "" : blankToDefault(consulta.getPrefijo(), ""),
                consulta == null ? "0" : blankToDefault(consulta.getConsecutivo(), "0"),
                now,
                "",
                "",
                0,
                safe,
                0,
                safe,
                ESTADO_ERROR,
                safe
        );
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
            String tipoDocumento,
            String prefijo,
            String consecutivo,
            String fecha,
            String cufe,
            String idErp,
            int codigoDispapeles,
            String descripcionDispapeles,
            int codigoDian,
            String descripcionDian,
            int estadoProceso,
            String mensajeProceso
    ) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <n0:consultarEstadoResponse xmlns:n0="%s">
                      <return>
                        <tipoDocumento>%s</tipoDocumento>
                        <prefijo>%s</prefijo>
                        <consecutivo>%s</consecutivo>
                        <fechaFactura>%s</fechaFactura>
                        <cufe>%s</cufe>
                        <idErp>%s</idErp>
                        <idLote></idLote>
                        <codigoUltimoEstadoDispapeles>%s</codigoUltimoEstadoDispapeles>
                        <descripcionUltimoEstadoDispapeles>%s</descripcionUltimoEstadoDispapeles>
                        <fechaRespuestaUltimoEstadoDispapeles>%s</fechaRespuestaUltimoEstadoDispapeles>
                        <codigoUltimoEstadoDian>%s</codigoUltimoEstadoDian>
                        <descripcionUltimoEstadoDian>%s</descripcionUltimoEstadoDian>
                        <fechaRespuestaUltimoEstadoDian>%s</fechaRespuestaUltimoEstadoDian>
                        <codigoUltimoEstadoEmail>0</codigoUltimoEstadoEmail>
                        <descripcionUltimoEstadoEmail></descripcionUltimoEstadoEmail>
                        <fechaRespuestaUltimoEstadoEmail>%s</fechaRespuestaUltimoEstadoEmail>
                        <codigoUltimoEstadoAdquirente>0</codigoUltimoEstadoAdquirente>
                        <descripcionUltimoEstadoAdquirente></descripcionUltimoEstadoAdquirente>
                        <fechaRespuestaUltimoEstadoAdquirente>%s</fechaRespuestaUltimoEstadoAdquirente>
                        <fechaRespuesta>%s</fechaRespuesta>
                        <firmaDelDocumento></firmaDelDocumento>
                        <selloDeValidacion></selloDeValidacion>
                        <estadoProceso>%s</estadoProceso>
                        <listaMensajesProceso>
                          <codigo>0</codigo>
                          <mensaje>%s</mensaje>
                        </listaMensajesProceso>
                      </return>
                    </n0:consultarEstadoResponse>
                  </soap:Body>
                </soap:Envelope>
                """.formatted(
                NAMESPACE,
                xmlEscape(tipoDocumento),
                xmlEscape(nullToEmpty(prefijo)),
                xmlEscape(consecutivo),
                xmlEscape(fecha),
                xmlEscape(nullToEmpty(cufe)),
                xmlEscape(nullToEmpty(idErp)),
                codigoDispapeles,
                xmlEscape(descripcionDispapeles),
                xmlEscape(fecha),
                codigoDian,
                xmlEscape(descripcionDian),
                xmlEscape(fecha),
                xmlEscape(fecha),
                xmlEscape(fecha),
                xmlEscape(fecha),
                estadoProceso,
                xmlEscape(mensajeProceso)
        );
    }

    static int estadoProceso(String estadoDian, String cufe) {
        if (InvoiceDianStatus.isValidated(estadoDian, cufe)) {
            return ESTADO_EXITOSO;
        }
        if (InvoiceDianStatus.isRejected(estadoDian)) {
            return ESTADO_ERROR;
        }
        return ESTADO_PROCESANDO;
    }

    private static String descripcionEstado(int estadoProceso) {
        return switch (estadoProceso) {
            case ESTADO_EXITOSO -> "Procesado Correctamente.";
            case ESTADO_PROCESANDO -> "El documento está siendo procesado";
            default -> "Error procesando documento";
        };
    }

    private static String formatTimestamp(Instant instant) {
        Instant value = instant == null ? Instant.now() : instant;
        return TIMESTAMP.format(value);
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
