package com.zonak.portal.admin;

import java.util.UUID;

public record Sociedad(
        UUID id,
        String razonSocial,
        String nit,
        String apiKey,
        String correoEmision,
        String correoRecepcion,
        String hostSmtp,
        Integer puertoSmtp,
        String usuarioSmtp,
        String hostImap,
        Integer puertoImap,
        String usuarioImap,
        String dianAmbiente,
        String dianRegimenFiscal,
        String dianSoftwareId,
        boolean dianSoftwarePinConfigured,
        Integer idEmpresa,
        String sapUsuario,
        boolean sapPasswordConfigured
) {
}
