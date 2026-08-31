package com.zonak.portal.admin;



import java.util.Arrays;

import java.util.List;

import java.util.Locale;

import java.util.Set;

import java.util.stream.Collectors;



public final class DianRegimenFiscal {

    /** Lista DIAN vigente para emisor (TipoResponsabilidad-2.1). */

    public static final String DEFAULT = "ZZ";



    public record Option(String code, String label) {}



    private static final Set<String> ALLOWED = Set.of(

            "O-13", "O-15", "O-23", "O-47", "ZZ"

    );



    private DianRegimenFiscal() {

    }



    public static List<Option> emisorOptions() {

        return List.of(

                new Option("ZZ", "ZZ — No aplica (sin responsabilidad especial FE)"),

                new Option("O-13", "O-13 — Gran contribuyente"),

                new Option("O-15", "O-15 — Autorretenedor"),

                new Option("O-23", "O-23 — Agente de retención IVA (RUT 09)"),

                new Option("O-47", "O-47 — Régimen simple de tributación")

        );

    }



    public static String normalize(String value) {

        if (value == null || value.isBlank()) {

            return DEFAULT;

        }

        String normalized = Arrays.stream(value.split(";"))

                .map(String::trim)

                .filter(token -> !token.isEmpty())

                .map(token -> token.toUpperCase(Locale.ROOT))

                .map(DianRegimenFiscal::remapLegacyCode)

                .filter(ALLOWED::contains)

                .distinct()

                .collect(Collectors.joining(";"));

        return normalized.isBlank() ? DEFAULT : normalized;

    }



    public static boolean isValid(String value) {

        if (value == null || value.isBlank()) {

            return false;

        }

        return Arrays.stream(value.split(";"))

                .map(String::trim)

                .filter(token -> !token.isEmpty())

                .map(token -> token.toUpperCase(Locale.ROOT))

                .allMatch(token -> ALLOWED.contains(remapLegacyCode(token)));

    }



    private static String remapLegacyCode(String token) {

        return switch (token) {

            case "O-48", "O-49", "O-99", "O-33", "R-99-PJ", "R-99-PN" -> DEFAULT;

            default -> token;

        };

    }

}

