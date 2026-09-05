package com.zonak.portal.integration.sap;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import org.springframework.util.StringUtils;

@JsonIgnoreProperties(ignoreUnknown = true)
@JacksonXmlRootElement(localName = "consultarEstado")
public class SapConsultarEstado {
    @JsonAlias({"felConsultaFactura", "consulta", "arg0", "parameters"})
    @JacksonXmlProperty(localName = "felConsultaFactura")
    private FelConsultaFactura felConsultaFactura;

    private String idEmpresa;
    private String usuario;
    private String contrasenia;
    private String prefijo;
    private String consecutivo;
    @JsonAlias({"tipoDocumento", "tipodocumento"})
    private String tipoDocumento;

    public FelConsultaFactura getFelConsultaFactura() {
        return felConsultaFactura;
    }

    public void setFelConsultaFactura(FelConsultaFactura felConsultaFactura) {
        this.felConsultaFactura = felConsultaFactura;
    }

    public String getIdEmpresa() {
        return first(felConsultaFactura == null ? null : felConsultaFactura.idEmpresa, idEmpresa);
    }

    public void setIdEmpresa(String idEmpresa) {
        this.idEmpresa = idEmpresa;
    }

    public String getUsuario() {
        return first(felConsultaFactura == null ? null : felConsultaFactura.usuario, usuario);
    }

    public void setUsuario(String usuario) {
        this.usuario = usuario;
    }

    public String getContrasenia() {
        return first(felConsultaFactura == null ? null : felConsultaFactura.contrasenia, contrasenia);
    }

    public void setContrasenia(String contrasenia) {
        this.contrasenia = contrasenia;
    }

    public String getPrefijo() {
        return first(felConsultaFactura == null ? null : felConsultaFactura.prefijo, prefijo);
    }

    public void setPrefijo(String prefijo) {
        this.prefijo = prefijo;
    }

    public String getConsecutivo() {
        return first(felConsultaFactura == null ? null : felConsultaFactura.consecutivo, consecutivo);
    }

    public void setConsecutivo(String consecutivo) {
        this.consecutivo = consecutivo;
    }

    public String getTipoDocumento() {
        return first(felConsultaFactura == null ? null : felConsultaFactura.tipoDocumento, tipoDocumento);
    }

    public void setTipoDocumento(String tipoDocumento) {
        this.tipoDocumento = tipoDocumento;
    }

    private static String first(String nested, String root) {
        if (StringUtils.hasText(nested)) {
            return nested.trim();
        }
        return root == null ? "" : root.trim();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FelConsultaFactura {
        private String idEmpresa;
        private String usuario;
        private String contrasenia;
        private String prefijo;
        private String consecutivo;
        @JsonAlias({"tipoDocumento", "tipodocumento"})
        private String tipoDocumento;

        public String getIdEmpresa() {
            return idEmpresa;
        }

        public void setIdEmpresa(String idEmpresa) {
            this.idEmpresa = idEmpresa;
        }

        public String getUsuario() {
            return usuario;
        }

        public void setUsuario(String usuario) {
            this.usuario = usuario;
        }

        public String getContrasenia() {
            return contrasenia;
        }

        public void setContrasenia(String contrasenia) {
            this.contrasenia = contrasenia;
        }

        public String getPrefijo() {
            return prefijo;
        }

        public void setPrefijo(String prefijo) {
            this.prefijo = prefijo;
        }

        public String getConsecutivo() {
            return consecutivo;
        }

        public void setConsecutivo(String consecutivo) {
            this.consecutivo = consecutivo;
        }

        public String getTipoDocumento() {
            return tipoDocumento;
        }

        public void setTipoDocumento(String tipoDocumento) {
            this.tipoDocumento = tipoDocumento;
        }
    }
}
