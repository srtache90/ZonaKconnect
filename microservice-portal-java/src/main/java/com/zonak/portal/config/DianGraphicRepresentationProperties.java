package com.zonak.portal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dian.graphic-representation")
public record DianGraphicRepresentationProperties(
        String softwareManufacturerName,
        String softwareManufacturerNit,
        String softwareName,
        String technologyProviderName
) {
    public DianGraphicRepresentationProperties {
        if (softwareManufacturerName == null || softwareManufacturerName.isBlank()) {
            softwareManufacturerName = "Zona K S.A.S.";
        }
        if (softwareManufacturerNit == null || softwareManufacturerNit.isBlank()) {
            softwareManufacturerNit = "901000000";
        }
        if (softwareName == null || softwareName.isBlank()) {
            softwareName = "Zona K Facturación Electrónica";
        }
        if (technologyProviderName == null || technologyProviderName.isBlank()) {
            technologyProviderName = softwareManufacturerName;
        }
    }
}
