package com.zonak.portal.integration.pos;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.springframework.util.StringUtils;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PosTicketRequest(
        @JsonProperty("numero_factura") String numeroFactura,
        @JsonProperty("fecha_procesamiento") String fechaProcesamiento,
        @JsonProperty("numero_ticket") String numeroTicket,
        @JsonProperty("check_id") String checkId,
        @JsonProperty("harmony_id") String harmonyId,
        @JsonProperty("caja_wsid") String cajaWsid,
        @JsonProperty("fecha_hora") String fechaHora,
        @JsonProperty("condicion_venta") String condicionVenta,
        @JsonProperty("codigo_fiscal") String codigoFiscal,
        @JsonProperty("tipo_documento") String tipoDocumento,
        @JsonProperty("invoice_type_code") String invoiceTypeCode,
        @JsonProperty("credit_note_type_code") String creditNoteTypeCode,
        @JsonProperty("customization_id") String customizationId,
        @JsonProperty("guid_transaccion") String guidTransaccion,
        @JsonProperty("tipo_ambiente") String tipoAmbiente,
        String ambiente,
        @JsonProperty("numero_factura_completo") String numeroFacturaCompleto,
        @JsonAlias({"Resolucion", "resolucion"}) String resolucion,
        @JsonAlias({"Prefijo", "prefijo"}) String prefijo,
        @JsonAlias({"ResolucionIni", "resolucion_ini"}) String resolucionIni,
        @JsonAlias({"ResolucionFin", "resolucion_fin"}) String resolucionFin,
        @JsonAlias({"FechaResolucion", "fecha_resolucion"}) String fechaResolucion,
        String moneda,
        String subtotal,
        @JsonProperty("total_impuestos") String totalImpuestos,
        String propina,
        String total,
        String restaurante,
        String workstation,
        String empleado,
        Cliente cliente,
        List<Impuesto> impuestos,
        List<Item> items,
        List<Cargo> cargos,
        List<Pago> pagos,
        @JsonProperty("factura_referencia") FacturaReferencia facturaReferencia,
        @JsonProperty("conceptos_correccion") List<ConceptoCorreccion> conceptosCorreccion
) {
    public boolean isCreditNote() {
        String tipo = tipoDocumento == null ? "" : tipoDocumento.trim().toUpperCase();
        String fiscal = codigoFiscal == null ? "" : codigoFiscal.trim();
        String noteCode = creditNoteTypeCode == null ? "" : creditNoteTypeCode.trim();
        String invoiceCode = invoiceTypeCode == null ? "" : invoiceTypeCode.trim();
        if ("FV".equals(tipo) || "01".equals(fiscal) || "01".equals(invoiceCode)) {
            return false;
        }
        return "NC".equals(tipo)
                || "91".equals(fiscal)
                || "91".equals(noteCode)
                || facturaReferencia != null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cliente(
            @JsonProperty("tipo_identificacion")
            @JsonAlias({"tipo_id", "tipoCliente"}) String tipoIdentificacion,
            @JsonProperty("numero_identificacion")
            @JsonAlias({"identificacion"}) String numeroIdentificacion,
            String dv,
            @JsonProperty("tipo_persona") String tipoPersona,
            @JsonProperty("razon_social") String razonSocial,
            @JsonProperty("nombre_comercial") String nombreComercial,
            String nombre,
            String apellido,
            String email,
            List<String> emails,
            String telefono,
            String direccion
    ) {
        public String resolvedName() {
            if (StringUtils.hasText(razonSocial)) {
                return razonSocial.trim();
            }
            String full = ((nombre == null ? "" : nombre.trim()) + " " + (apellido == null ? "" : apellido.trim())).trim();
            return StringUtils.hasText(full) ? full : null;
        }

        public String resolvedEmail() {
            if (StringUtils.hasText(email)) {
                return email.trim();
            }
            if (emails == null || emails.isEmpty()) {
                return null;
            }
            return emails.stream().filter(StringUtils::hasText).findFirst().orElse(null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Direccion(
            String pais,
            @JsonProperty("codigo_departamento") String codigoDepartamento,
            String departamento,
            @JsonProperty("codigo_municipio") String codigoMunicipio,
            String municipio,
            @JsonProperty("codigo_postal") String codigoPostal,
            @JsonProperty("direccion_completa") String direccionCompleta
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Impuesto(
            @JsonAlias({"codigo_impuesto", "codigo"}) String codigo,
            @JsonAlias({"nombre_impuesto", "nombre"}) String nombre,
            @JsonProperty("numero_impuesto") String numero,
            @JsonAlias({"porcentaje_impuesto", "porcentaje"}) String porcentaje,
            @JsonAlias({"base_imponible", "base"}) String base,
            @JsonAlias({"valor_impuesto", "valor", "monto_impuesto"}) String valor
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
            String nombre,
            String codigo,
            String descripcion,
            @JsonProperty("unidad_medida") String unidadMedida,
            String cantidad,
            String precio,
            String descuento,
            String subtotal,
            List<Impuesto> impuestos
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cargo(
            String codigo,
            String nombre,
            String valor,
            @JsonProperty("es_propina") Boolean esPropina
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pago(
            @JsonProperty("forma_pago") String formaPago,
            @JsonProperty("medio_pago")
            @JsonAlias({"tenderName"}) String medioPago,
            @JsonProperty("fecha_pago") String fechaPago,
            @JsonAlias({"tenderAmount", "valor"}) String valor
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FacturaReferencia(
            @JsonAlias({"tipoDocumento", "tipo_documento"}) String tipoDocumento,
            @JsonAlias({"numeroDocumento", "numero_documento"}) String numeroDocumento,
            @JsonAlias({"fechaEmision", "fecha_emision"}) String fechaEmision,
            String cufe,
            @JsonAlias({"schemeName", "scheme_name"}) String schemeName
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConceptoCorreccion(
            @JsonAlias({"referenceID", "reference_id"}) String referenceId,
            String codigo,
            String descripcion
    ) {
    }

    public String referenciaNumero() {
        if (facturaReferencia != null && StringUtils.hasText(facturaReferencia.numeroDocumento())) {
            return facturaReferencia.numeroDocumento();
        }
        return firstText(numeroFacturaCompleto, numeroFactura);
    }

    public String resolvedPrefijo() {
        if (StringUtils.hasText(prefijo)) {
            return prefijo.trim();
        }
        return leadingLetters(firstText(numeroFacturaCompleto, numeroFactura));
    }

    public String resolvedAmbiente() {
        return normalizeAmbiente(firstText(tipoAmbiente, ambiente));
    }

    public Long resolvedNumero() {
        return trailingLong(firstText(numeroFacturaCompleto, numeroFactura));
    }

    public static String normalizeAmbiente(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        return switch (raw.trim().toLowerCase()) {
            case "1", "produccion", "producción", "prod" -> "Produccion";
            case "2", "habilitacion", "habilitación", "hab" -> "Habilitacion";
            default -> raw.trim();
        };
    }

    private static String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static String leadingLetters(String documentNumber) {
        if (!StringUtils.hasText(documentNumber)) {
            return null;
        }
        StringBuilder letters = new StringBuilder();
        for (char ch : documentNumber.trim().toCharArray()) {
            if (Character.isLetter(ch)) {
                letters.append(ch);
            } else if (letters.length() > 0) {
                break;
            }
        }
        return letters.length() == 0 ? null : letters.toString();
    }

    private static Long trailingLong(String documentNumber) {
        if (!StringUtils.hasText(documentNumber)) {
            return null;
        }
        StringBuilder digits = new StringBuilder();
        for (char ch : documentNumber.trim().toCharArray()) {
            if (Character.isDigit(ch)) {
                digits.append(ch);
            } else if (digits.length() > 0) {
                break;
            }
        }
        if (digits.length() == 0) {
            return null;
        }
        try {
            return Long.parseLong(digits.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public boolean hasExplicitPropinaCargo() {
        if (cargos == null) {
            return false;
        }
        return cargos.stream().anyMatch(c -> Boolean.TRUE.equals(c.esPropina())
                || "PROPINA".equalsIgnoreCase(c.codigo() == null ? "" : c.codigo()));
    }

    public static boolean hasText(String value) {
        return StringUtils.hasText(value);
    }
}
