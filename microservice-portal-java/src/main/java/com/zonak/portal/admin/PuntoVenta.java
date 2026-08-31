package com.zonak.portal.admin;

import java.time.LocalDate;
import java.util.UUID;

public record PuntoVenta(
        UUID id,
        UUID sociedadId,
        String sociedadRazonSocial,
        String codigo,
        String nombre,
        String direccion,
        String prefijo,
        String resolucionDian,
        String claveTecnica,
        Long rangoDesde,
        Long rangoHasta,
        Long numeroActual,
        String prefijoNc,
        Long numeroActualNc,
        String prefijoNd,
        Long numeroActualNd,
        LocalDate vigenciaDesde,
        LocalDate vigenciaHasta,
        boolean activo
) {
}
