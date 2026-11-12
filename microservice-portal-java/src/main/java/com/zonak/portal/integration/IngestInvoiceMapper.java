package com.zonak.portal.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.zonak.portal.dto.CreateCreditNoteRequestDTO;
import com.zonak.portal.dto.CreateInvoiceRequestDTO;
import com.zonak.portal.integration.pos.PosTicketRequest;
import com.zonak.portal.integration.sap.SapEnviarDocumento;
import com.zonak.portal.integration.simphony.SimphonyTicketRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class IngestInvoiceMapper {

    public CreateInvoiceRequestDTO fromSap(SapEnviarDocumento documento) {
        return fromSap(documento, "");
    }

    public CreateInvoiceRequestDTO fromSap(SapEnviarDocumento documento, String xmlBase) {
        SapEnviarDocumento.FelCabezaDocumento cabeza = require(documento.getFelCabezaDocumento(), "felCabezaDocumento requerido");
        List<CreateInvoiceRequestDTO.ItemDTO> items = safeList(cabeza.getListaDetalle()).stream()
                .map(this::mapSapItem)
                .toList();

        if (items.isEmpty()) {
            throw new IllegalArgumentException("listaDetalle requiere al menos un item");
        }

        SapEnviarDocumento.Adquirente adquirente = cabeza.getListaAdquirentes();
        SapEnviarDocumento.Pago pago = cabeza.getPago();

        return new CreateInvoiceRequestDTO(
                "Habilitacion",
                new CreateInvoiceRequestDTO.CustomerDTO(
                        valueOrDefault(adquirente != null ? adquirente.getTipoIdentificacion() : null, "31"),
                        adquirente != null ? adquirente.getNumeroIdentificacion() : null,
                        adquirente != null ? adquirente.getNombreCompleto() : null,
                        adquirente != null ? adquirente.getEmail() : null
                ),
                items,
                "",
                Map.of(
                        "origen", "SAP",
                        "idEmpresa", valueOrDefault(cabeza.getIdEmpresa(), ""),
                        "prefijo", valueOrDefault(cabeza.getPrefijo(), ""),
                        "consecutivo", valueOrDefault(cabeza.getConsecutivo(), ""),
                        "subtotal", pago != null && pago.getTotalbaseimponible() != null ? pago.getTotalbaseimponible() : BigDecimal.ZERO,
                        "impuestos", sumSapTaxes(cabeza.getListaImpuestos()),
                        "total", pago != null && pago.getTotalfactura() != null ? pago.getTotalfactura() : sumItems(items)
                )
        );
    }

    public CreateInvoiceRequestDTO fromSimphony(SimphonyTicketRequest ticket) {
        List<CreateInvoiceRequestDTO.ItemDTO> items = safeList(ticket.items()).stream()
                .map(this::mapSimphonyItem)
                .toList();

        if (items.isEmpty()) {
            throw new IllegalArgumentException("items requiere al menos un item");
        }

        SimphonyTicketRequest.Customer customer = ticket.customer();
        SimphonyTicketRequest.Totals totals = ticket.totals();

        return new CreateInvoiceRequestDTO(
                valueOrDefault(ticket.ambiente(), "Habilitacion"),
                new CreateInvoiceRequestDTO.CustomerDTO(
                        valueOrDefault(customer != null ? customer.tipoIdentificacion() : null, "31"),
                        customer != null ? customer.numeroIdentificacion() : null,
                        customer != null ? customer.razonSocial() : null,
                        customer != null ? customer.email() : null
                ),
                items,
                "",
                Map.of(
                        "origen", "SIMPHONY",
                        "ticketId", valueOrDefault(ticket.ticketId(), ""),
                        "subtotal", totals != null && totals.subtotal() != null ? totals.subtotal() : sumItems(items),
                        "impuestos", totals != null && totals.impuestos() != null ? totals.impuestos() : BigDecimal.ZERO,
                        "total", totals != null && totals.total() != null ? totals.total() : sumItems(items)
                )
        );
    }

    public CreateInvoiceRequestDTO fromPos(PosTicketRequest ticket) {
        if (ticket == null) {
            throw new IllegalArgumentException("JSON POS vacío");
        }
        List<PosTicketRequest.Item> rawItems = safeList(ticket.items());
        if (rawItems.isEmpty()) {
            throw new IllegalArgumentException("items requiere al menos un item POS");
        }

        List<CreateInvoiceRequestDTO.ItemDTO> items = mapPosItems(rawItems, ticket);

        BigDecimal tip = parseDecimal(ticket.propina());
        Map<String, Object> totals = posTotals(ticket, items, tip);

        return new CreateInvoiceRequestDTO(
                ticket.resolvedAmbiente(),
                mapPosCustomer(ticket.cliente()),
                items,
                "",
                totals
        );
    }

    public CreateCreditNoteRequestDTO fromPosCreditNote(PosTicketRequest ticket) {
        if (ticket == null) {
            throw new IllegalArgumentException("JSON POS vacío");
        }
        PosTicketRequest.FacturaReferencia ref = ticket.facturaReferencia();
        if (ref == null
                || !StringUtils.hasText(ref.numeroDocumento())
                || !StringUtils.hasText(ref.cufe())) {
            throw new IllegalArgumentException(
                    "Nota crédito POS requiere factura_referencia con numeroDocumento y cufe."
            );
        }
        List<PosTicketRequest.Item> rawItems = safeList(ticket.items());
        if (rawItems.isEmpty()) {
            throw new IllegalArgumentException("items requiere al menos un item POS");
        }

        List<CreateInvoiceRequestDTO.ItemDTO> items = mapPosItems(rawItems, ticket);
        List<CreateCreditNoteRequestDTO.ConceptoCorreccionDTO> conceptos = safeList(ticket.conceptosCorreccion())
                .stream()
                .map(c -> new CreateCreditNoteRequestDTO.ConceptoCorreccionDTO(
                        valueOrDefault(c.referenceId(), "1"),
                        correctionCode(c.codigo(), "2"),
                        valueOrDefault(c.descripcion(), descripcionConceptoNotaCredito(correctionCode(c.codigo(), "2")))
                ))
                .toList();
        if (conceptos.isEmpty()) {
            String codigo = correctionCode(ticket.creditNoteTypeCode(), "2");
            conceptos = List.of(new CreateCreditNoteRequestDTO.ConceptoCorreccionDTO(
                    "1",
                    codigo,
                    valueOrDefault(ticket.creditNoteReason(), descripcionConceptoNotaCredito(codigo))
            ));
        }

        String fechaReferencia = firstText(
                ref.fechaEmision(),
                ticket.creditNoteInvoiceDate()
        );
        return new CreateCreditNoteRequestDTO(
                ticket.resolvedAmbiente(),
                valueOrDefault(ticket.customizationId(), "20"),
                "91",
                mapPosCustomer(ticket.cliente()),
                new CreateCreditNoteRequestDTO.FacturaReferenciaDTO(
                        valueOrDefault(ref.tipoDocumento(), "FV"),
                        ref.numeroDocumento().trim(),
                        normalizeFechaEmision(fechaReferencia),
                        ref.cufe().trim(),
                        valueOrDefault(ref.schemeName(), "CUFE-SHA384")
                ),
                conceptos,
                items,
                posTotals(ticket, items, parseDecimal(ticket.propina()))
        );
    }

    public CreateInvoiceRequestDTO withSimphonyJson(CreateInvoiceRequestDTO dto, JsonNode source) {
        if (dto == null || source == null || source.isNull()) {
            return dto;
        }
        return new CreateInvoiceRequestDTO(
                dto.ambiente(),
                dto.customer(),
                dto.items(),
                dto.xmlBase(),
                totalsWithSimphonyJson(dto.totals(), source)
        );
    }

    public CreateCreditNoteRequestDTO withSimphonyJson(CreateCreditNoteRequestDTO dto, JsonNode source) {
        if (dto == null || source == null || source.isNull()) {
            return dto;
        }
        return new CreateCreditNoteRequestDTO(
                dto.ambiente(),
                dto.customizationId(),
                dto.creditNoteTypeCode(),
                dto.cliente(),
                dto.facturaReferencia(),
                dto.conceptosCorreccion(),
                dto.items(),
                totalsWithSimphonyJson(dto.totals(), source)
        );
    }

    private Map<String, Object> totalsWithSimphonyJson(Object totals, JsonNode source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (totals instanceof Map<?, ?> map) {
            map.forEach((key, value) -> copy.put(String.valueOf(key), value));
        }
        copy.put("simphony_json", source);
        return copy;
    }

    private CreateInvoiceRequestDTO.ItemDTO mapSapItem(SapEnviarDocumento.Detalle detalle) {
        List<CreateInvoiceRequestDTO.TaxDTO> taxes = safeList(detalle.getListaImpuestos()).stream()
                .map(this::mapSapTax)
                .toList();
        return new CreateInvoiceRequestDTO.ItemDTO(
                valueOrDefault(detalle.getCodigoproducto(), "SAP-SIN-CODIGO"),
                valueOrDefault(detalle.getDescripcion(), valueOrDefault(detalle.getNombreProducto(), "Item SAP")),
                valueOrDefault(detalle.getCantidad(), BigDecimal.ONE),
                valueOrDefault(detalle.getValorunitario(), BigDecimal.ZERO),
                sumSapDiscounts(detalle.getListaDescuentos()),
                taxes.isEmpty() ? null : taxes
        );
    }

    private CreateInvoiceRequestDTO.TaxDTO mapSapTax(SapEnviarDocumento.Impuesto impuesto) {
        String code = valueOrDefault(impuesto.getCodigoImpuestoRetencion(), "01");
        return new CreateInvoiceRequestDTO.TaxDTO(
                code,
                taxName(code),
                valueOrDefault(impuesto.getPorcentaje(), BigDecimal.ZERO),
                valueOrDefault(impuesto.getBaseimponible(), BigDecimal.ZERO),
                valueOrDefault(impuesto.getValorImpuestoRetencion(), BigDecimal.ZERO)
        );
    }

    private String taxName(String code) {
        return switch (code) {
            case "01" -> "IVA";
            case "04" -> "INC";
            case "ZA" -> "IVA 0%";
            default -> "IMPUESTO-" + code;
        };
    }

    private BigDecimal sumSapDiscounts(List<SapEnviarDocumento.Descuento> descuentos) {
        return safeList(descuentos).stream()
                .map(SapEnviarDocumento.Descuento::getDescuento)
                .map(value -> valueOrDefault(value, BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private CreateInvoiceRequestDTO.ItemDTO mapSimphonyItem(SimphonyTicketRequest.Item item) {
        return new CreateInvoiceRequestDTO.ItemDTO(
                valueOrDefault(item.codigo(), "SIMPHONY-SIN-CODIGO"),
                valueOrDefault(item.descripcion(), "Item Simphony"),
                valueOrDefault(item.cantidad(), BigDecimal.ONE),
                valueOrDefault(item.precioUnitario(), BigDecimal.ZERO),
                valueOrDefault(item.descuento(), BigDecimal.ZERO),
                null
        );
    }

    private List<CreateInvoiceRequestDTO.ItemDTO> mapPosItems(
            List<PosTicketRequest.Item> rawItems,
            PosTicketRequest ticket
    ) {
        List<CreateInvoiceRequestDTO.ItemDTO> items = new ArrayList<>();
        int index = 1;
        for (PosTicketRequest.Item item : rawItems) {
            items.add(mapPosItem(item, index++));
        }
        return items;
    }

    private CreateInvoiceRequestDTO.CustomerDTO mapPosCustomer(PosTicketRequest.Cliente cliente) {
        return new CreateInvoiceRequestDTO.CustomerDTO(
                mapIdentificationType(cliente != null ? cliente.tipoIdentificacion() : null),
                valueOrDefault(cliente != null ? cliente.numeroIdentificacion() : null, "222222222222"),
                valueOrDefault(cliente != null ? cliente.resolvedName() : null, "CONSUMIDOR FINAL"),
                cliente != null ? cliente.resolvedEmail() : null
        );
    }

    private static String mapIdentificationType(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "13";
        }
        String value = raw.trim().toUpperCase();
        return switch (value) {
            case "CC", "C.C", "C.C.", "13" -> "13";
            case "NIT", "31" -> "31";
            case "CE", "C.E", "22" -> "22";
            case "PA", "PASAPORTE", "41" -> "41";
            case "TI", "12" -> "12";
            case "RC", "11" -> "11";
            case "NUIP", "91" -> "91";
            default -> value.matches("\\d+") ? value : "13";
        };
    }

    private Map<String, Object> posTotals(
            PosTicketRequest ticket,
            List<CreateInvoiceRequestDTO.ItemDTO> items,
            BigDecimal tip
    ) {
        BigDecimal itemsTotal = sumItems(items);
        BigDecimal base = parseDecimal(ticket.base());
        if (base.compareTo(BigDecimal.ZERO) <= 0) {
            base = itemsTotal;
        }
        BigDecimal declaredSubtotal = parseDecimal(ticket.subtotal());
        if (declaredSubtotal.compareTo(BigDecimal.ZERO) <= 0) {
            declaredSubtotal = base;
        }
        BigDecimal declaredTax = parseDecimal(firstText(ticket.inc(), ticket.totalImpuestos()));
        BigDecimal declaredTotal = parseDecimal(ticket.total());
        if (declaredTotal.compareTo(BigDecimal.ZERO) <= 0) {
            declaredTotal = declaredSubtotal.add(declaredTax).add(tip.max(BigDecimal.ZERO));
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("origen", "POS");
        totals.put("tipo_documento", valueOrDefault(ticket.tipoDocumento(), "FV"));
        totals.put("invoice_type_code", valueOrDefault(ticket.invoiceTypeCode(), ticket.codigoFiscal()));
        if (StringUtils.hasText(ticket.creditNoteTypeCode())) {
            totals.put("credit_note_type_code", ticket.creditNoteTypeCode().trim());
        }
        totals.put("numero_ticket", valueOrDefault(ticket.numeroTicket(), ""));
        totals.put("check_id", valueOrDefault(ticket.checkId(), ""));
        totals.put("harmony_id", valueOrDefault(ticket.harmonyId(), ""));
        totals.put("caja_wsid", valueOrDefault(ticket.cajaWsid(), ""));
        totals.put("guid_transaccion", valueOrDefault(ticket.guidTransaccion(), ""));
        totals.put("codigo_fiscal", valueOrDefault(ticket.codigoFiscal(), "48"));
        totals.put("condicion_venta", valueOrDefault(ticket.condicionVenta(), "01"));
        totals.put("resolucion", valueOrDefault(ticket.resolucion(), ""));
        totals.put("prefijo", valueOrDefault(ticket.resolvedPrefijo(), ""));
        totals.put("numero_factura", valueOrDefault(ticket.referenciaNumero(), ""));
        if (ticket.resolvedNumero() != null) {
            totals.put("consecutivo", ticket.resolvedNumero());
            totals.put("numero", ticket.resolvedNumero());
        }
        totals.put("tipo_ambiente", valueOrDefault(ticket.tipoAmbiente(), ""));
        totals.put("ambiente", valueOrDefault(ticket.resolvedAmbiente(), ""));
        totals.put("restaurante", valueOrDefault(ticket.restaurante(), ""));
        totals.put("workstation", valueOrDefault(ticket.workstation(), ""));
        totals.put("empleado", valueOrDefault(ticket.empleado(), ""));
        totals.put("fecha_hora", valueOrDefault(ticket.fechaHora(), ""));
        totals.put("propina", tip);
        totals.put("base", base);
        if (declaredTax.compareTo(BigDecimal.ZERO) > 0) {
            totals.put("inc", declaredTax);
        }
        if (StringUtils.hasText(ticket.cufe())) {
            totals.put("cufe_estacion", ticket.cufe().trim());
        }
        if (StringUtils.hasText(ticket.qr())) {
            totals.put("qr_estacion", ticket.qr().trim());
        }
        totals.put("cargos", ticket.cargos() == null ? List.of() : ticket.cargos());
        totals.put("pagos", ticket.pagos() == null ? List.of() : ticket.pagos());
        totals.put("impuestos", ticket.impuestos() == null ? List.of() : ticket.impuestos());
        totals.put("subtotal", declaredSubtotal);
        totals.put("total", declaredTotal);
        return totals;
    }

    private CreateInvoiceRequestDTO.ItemDTO mapPosItem(PosTicketRequest.Item item, int index) {
        String name = firstText(item != null ? item.descripcion() : null, item != null ? item.nombre() : null, "Item POS");
        String code = valueOrDefault(item != null ? item.codigo() : null, "POS-" + index);
        BigDecimal qty = parseDecimal(item != null ? item.cantidad() : null);
        if (qty.compareTo(BigDecimal.ZERO) <= 0) {
            qty = BigDecimal.ONE;
        }
        BigDecimal unit = parseDecimal(item != null ? item.precio() : null);
        if (unit.compareTo(BigDecimal.ZERO) == 0) {
            BigDecimal subtotal = parseDecimal(item != null ? item.subtotal() : null);
            unit = subtotal.divide(qty, 2, java.math.RoundingMode.HALF_UP);
        }
        BigDecimal discount = parseDecimal(item != null ? item.descuento() : null);
        return new CreateInvoiceRequestDTO.ItemDTO(code, name, qty, unit, discount, mapPosTaxes(item));
    }

    private List<CreateInvoiceRequestDTO.TaxDTO> mapPosTaxes(PosTicketRequest.Item item) {
        if (item == null) {
            return null;
        }
        List<CreateInvoiceRequestDTO.TaxDTO> taxes = safeList(item.impuestos()).stream()
                .map(this::mapPosTax)
                .filter(tax -> tax.amount().compareTo(BigDecimal.ZERO) > 0
                        || tax.percentage().compareTo(BigDecimal.ZERO) > 0)
                .toList();
        return taxes.isEmpty() ? null : taxes;
    }

    private CreateInvoiceRequestDTO.TaxDTO mapPosTax(PosTicketRequest.Impuesto impuesto) {
        String code = valueOrDefault(impuesto != null ? impuesto.codigo() : null, "01");
        return new CreateInvoiceRequestDTO.TaxDTO(
                code,
                valueOrDefault(impuesto != null ? impuesto.nombre() : null, taxName(code)),
                parseDecimal(impuesto != null ? impuesto.porcentaje() : null),
                parseDecimal(impuesto != null ? impuesto.base() : null),
                parseDecimal(impuesto != null ? impuesto.valor() : null)
        );
    }

    private String normalizeFechaEmision(String raw) {
        if (!StringUtils.hasText(raw)) {
            return java.time.OffsetDateTime.now(java.time.ZoneOffset.ofHours(-5)).toString();
        }
        String value = raw.trim();
        if (value.length() == 10 && value.charAt(4) == '-' && value.charAt(7) == '-') {
            return value + "T00:00:00-05:00";
        }
        if (value.length() == 10 && value.charAt(2) == '/' && value.charAt(5) == '/') {
            java.time.LocalDate date = java.time.LocalDate.parse(
                    value,
                    java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu")
            );
            return date + "T00:00:00-05:00";
        }
        return value;
    }

    private static String correctionCode(String raw, String fallback) {
        if (raw != null && raw.trim().matches("[1-6]")) {
            return raw.trim();
        }
        return fallback;
    }

    private static String descripcionConceptoNotaCredito(String codigo) {
        return switch (codigo) {
            case "2" -> "Anulación de factura electrónica";
            case "3" -> "Rebaja o descuento parcial o total";
            case "4" -> "Ajuste de precio";
            case "5" -> "Otros";
            default -> "Devolución parcial de los bienes y/o no aceptación parcial del servicio";
        };
    }

    private String firstText(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private BigDecimal parseDecimal(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.trim().replace(",", ""));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Valor numérico inválido: " + value);
        }
    }

    private BigDecimal sumSapTaxes(List<SapEnviarDocumento.Impuesto> impuestos) {
        return safeList(impuestos).stream()
                .map(SapEnviarDocumento.Impuesto::getValorImpuestoRetencion)
                .map(value -> valueOrDefault(value, BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumItems(List<CreateInvoiceRequestDTO.ItemDTO> items) {
        return items.stream()
                .map(item -> item.quantity().multiply(item.unitPrice()).subtract(item.discount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private <T> T require(T value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private BigDecimal valueOrDefault(BigDecimal value, BigDecimal defaultValue) {
        return value == null ? defaultValue : value;
    }
}
