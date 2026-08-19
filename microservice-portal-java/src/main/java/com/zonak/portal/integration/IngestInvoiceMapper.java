package com.zonak.portal.integration;

import com.zonak.portal.dto.CreateInvoiceRequestDTO;
import com.zonak.portal.integration.sap.SapEnviarDocumento;
import com.zonak.portal.integration.simphony.SimphonyTicketRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

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

    private CreateInvoiceRequestDTO.ItemDTO mapSapItem(SapEnviarDocumento.Detalle detalle) {
        return new CreateInvoiceRequestDTO.ItemDTO(
                valueOrDefault(detalle.getCodigoproducto(), "SAP-SIN-CODIGO"),
                valueOrDefault(detalle.getDescripcion(), valueOrDefault(detalle.getNombreProducto(), "Item SAP")),
                valueOrDefault(detalle.getCantidad(), BigDecimal.ONE),
                valueOrDefault(detalle.getValorunitario(), BigDecimal.ZERO),
                BigDecimal.ZERO
        );
    }

    private CreateInvoiceRequestDTO.ItemDTO mapSimphonyItem(SimphonyTicketRequest.Item item) {
        return new CreateInvoiceRequestDTO.ItemDTO(
                valueOrDefault(item.codigo(), "SIMPHONY-SIN-CODIGO"),
                valueOrDefault(item.descripcion(), "Item Simphony"),
                valueOrDefault(item.cantidad(), BigDecimal.ONE),
                valueOrDefault(item.precioUnitario(), BigDecimal.ZERO),
                valueOrDefault(item.descuento(), BigDecimal.ZERO)
        );
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
