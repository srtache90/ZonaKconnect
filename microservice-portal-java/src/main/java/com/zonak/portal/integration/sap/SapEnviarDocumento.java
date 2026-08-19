package com.zonak.portal.integration.sap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JacksonXmlRootElement(localName = "enviarDocumento")
public class SapEnviarDocumento {
    @JacksonXmlProperty(localName = "felCabezaDocumento")
    private FelCabezaDocumento felCabezaDocumento;

    public FelCabezaDocumento getFelCabezaDocumento() {
        return felCabezaDocumento;
    }

    public void setFelCabezaDocumento(FelCabezaDocumento felCabezaDocumento) {
        this.felCabezaDocumento = felCabezaDocumento;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FelCabezaDocumento {
        private String idEmpresa;
        private String consecutivo;
        private String prefijo;
        private OffsetDateTime fechafacturacion;

        @JacksonXmlElementWrapper(useWrapping = false)
        @JacksonXmlProperty(localName = "listaDetalle")
        private List<Detalle> listaDetalle = new ArrayList<>();

        @JacksonXmlProperty(localName = "pago")
        private Pago pago;

        @JacksonXmlElementWrapper(useWrapping = false)
        @JacksonXmlProperty(localName = "listaImpuestos")
        private List<Impuesto> listaImpuestos = new ArrayList<>();

        @JacksonXmlProperty(localName = "listaAdquirentes")
        private Adquirente listaAdquirentes;

        public String getIdEmpresa() {
            return idEmpresa;
        }

        public void setIdEmpresa(String idEmpresa) {
            this.idEmpresa = idEmpresa;
        }

        public String getConsecutivo() {
            return consecutivo;
        }

        public void setConsecutivo(String consecutivo) {
            this.consecutivo = consecutivo;
        }

        public String getPrefijo() {
            return prefijo;
        }

        public void setPrefijo(String prefijo) {
            this.prefijo = prefijo;
        }

        public OffsetDateTime getFechafacturacion() {
            return fechafacturacion;
        }

        public void setFechafacturacion(OffsetDateTime fechafacturacion) {
            this.fechafacturacion = fechafacturacion;
        }

        public List<Detalle> getListaDetalle() {
            return listaDetalle;
        }

        public void setListaDetalle(List<Detalle> listaDetalle) {
            this.listaDetalle = listaDetalle;
        }

        public Pago getPago() {
            return pago;
        }

        public void setPago(Pago pago) {
            this.pago = pago;
        }

        public List<Impuesto> getListaImpuestos() {
            return listaImpuestos;
        }

        public void setListaImpuestos(List<Impuesto> listaImpuestos) {
            this.listaImpuestos = listaImpuestos;
        }

        public Adquirente getListaAdquirentes() {
            return listaAdquirentes;
        }

        public void setListaAdquirentes(Adquirente listaAdquirentes) {
            this.listaAdquirentes = listaAdquirentes;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Detalle {
        private BigDecimal cantidad;
        private String codigoproducto;
        private String descripcion;
        private String nombreProducto;
        private BigDecimal preciosinimpuestos;
        private BigDecimal preciototal;
        private BigDecimal valorunitario;
        private String unidadmedida;

        @JacksonXmlElementWrapper(useWrapping = false)
        @JacksonXmlProperty(localName = "listaImpuestos")
        private List<Impuesto> listaImpuestos = new ArrayList<>();

        public BigDecimal getCantidad() {
            return cantidad;
        }

        public void setCantidad(BigDecimal cantidad) {
            this.cantidad = cantidad;
        }

        public String getCodigoproducto() {
            return codigoproducto;
        }

        public void setCodigoproducto(String codigoproducto) {
            this.codigoproducto = codigoproducto;
        }

        public String getDescripcion() {
            return descripcion;
        }

        public void setDescripcion(String descripcion) {
            this.descripcion = descripcion;
        }

        public String getNombreProducto() {
            return nombreProducto;
        }

        @JsonProperty("nombreProducto")
        public void setNombreProducto(String nombreProducto) {
            this.nombreProducto = nombreProducto;
        }

        public BigDecimal getPreciosinimpuestos() {
            return preciosinimpuestos;
        }

        public void setPreciosinimpuestos(BigDecimal preciosinimpuestos) {
            this.preciosinimpuestos = preciosinimpuestos;
        }

        public BigDecimal getPreciototal() {
            return preciototal;
        }

        public void setPreciototal(BigDecimal preciototal) {
            this.preciototal = preciototal;
        }

        public BigDecimal getValorunitario() {
            return valorunitario;
        }

        public void setValorunitario(BigDecimal valorunitario) {
            this.valorunitario = valorunitario;
        }

        public String getUnidadmedida() {
            return unidadmedida;
        }

        public void setUnidadmedida(String unidadmedida) {
            this.unidadmedida = unidadmedida;
        }

        public List<Impuesto> getListaImpuestos() {
            return listaImpuestos;
        }

        public void setListaImpuestos(List<Impuesto> listaImpuestos) {
            this.listaImpuestos = listaImpuestos;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Impuesto {
        private BigDecimal baseimponible;
        private String codigoImpuestoRetencion;
        private BigDecimal porcentaje;
        private BigDecimal valorImpuestoRetencion;

        public BigDecimal getBaseimponible() {
            return baseimponible;
        }

        public void setBaseimponible(BigDecimal baseimponible) {
            this.baseimponible = baseimponible;
        }

        public String getCodigoImpuestoRetencion() {
            return codigoImpuestoRetencion;
        }

        public void setCodigoImpuestoRetencion(String codigoImpuestoRetencion) {
            this.codigoImpuestoRetencion = codigoImpuestoRetencion;
        }

        public BigDecimal getPorcentaje() {
            return porcentaje;
        }

        public void setPorcentaje(BigDecimal porcentaje) {
            this.porcentaje = porcentaje;
        }

        public BigDecimal getValorImpuestoRetencion() {
            return valorImpuestoRetencion;
        }

        public void setValorImpuestoRetencion(BigDecimal valorImpuestoRetencion) {
            this.valorImpuestoRetencion = valorImpuestoRetencion;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Pago {
        private OffsetDateTime fechaTasaCambio;
        private OffsetDateTime fechavencimiento;
        private String moneda;
        private Integer periododepagoa;
        private String tipocompra;
        private BigDecimal totalbaseconimpuestos;
        private BigDecimal totalbaseimponible;
        private BigDecimal totalfactura;
        private BigDecimal totalimportebruto;

        public OffsetDateTime getFechaTasaCambio() {
            return fechaTasaCambio;
        }

        public void setFechaTasaCambio(OffsetDateTime fechaTasaCambio) {
            this.fechaTasaCambio = fechaTasaCambio;
        }

        public OffsetDateTime getFechavencimiento() {
            return fechavencimiento;
        }

        public void setFechavencimiento(OffsetDateTime fechavencimiento) {
            this.fechavencimiento = fechavencimiento;
        }

        public String getMoneda() {
            return moneda;
        }

        public void setMoneda(String moneda) {
            this.moneda = moneda;
        }

        public Integer getPeriododepagoa() {
            return periododepagoa;
        }

        public void setPeriododepagoa(Integer periododepagoa) {
            this.periododepagoa = periododepagoa;
        }

        public String getTipocompra() {
            return tipocompra;
        }

        public void setTipocompra(String tipocompra) {
            this.tipocompra = tipocompra;
        }

        public BigDecimal getTotalbaseconimpuestos() {
            return totalbaseconimpuestos;
        }

        public void setTotalbaseconimpuestos(BigDecimal totalbaseconimpuestos) {
            this.totalbaseconimpuestos = totalbaseconimpuestos;
        }

        public BigDecimal getTotalbaseimponible() {
            return totalbaseimponible;
        }

        public void setTotalbaseimponible(BigDecimal totalbaseimponible) {
            this.totalbaseimponible = totalbaseimponible;
        }

        public BigDecimal getTotalfactura() {
            return totalfactura;
        }

        public void setTotalfactura(BigDecimal totalfactura) {
            this.totalfactura = totalfactura;
        }

        public BigDecimal getTotalimportebruto() {
            return totalimportebruto;
        }

        public void setTotalimportebruto(BigDecimal totalimportebruto) {
            this.totalimportebruto = totalimportebruto;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Adquirente {
        private String tipoIdentificacion;
        private String numeroIdentificacion;
        private String nombreCompleto;
        private String email;
        private String direccion;

        public String getTipoIdentificacion() {
            return tipoIdentificacion;
        }

        public void setTipoIdentificacion(String tipoIdentificacion) {
            this.tipoIdentificacion = tipoIdentificacion;
        }

        public String getNumeroIdentificacion() {
            return numeroIdentificacion;
        }

        public void setNumeroIdentificacion(String numeroIdentificacion) {
            this.numeroIdentificacion = numeroIdentificacion;
        }

        public String getNombreCompleto() {
            return nombreCompleto;
        }

        public void setNombreCompleto(String nombreCompleto) {
            this.nombreCompleto = nombreCompleto;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getDireccion() {
            return direccion;
        }

        public void setDireccion(String direccion) {
            this.direccion = direccion;
        }
    }
}
