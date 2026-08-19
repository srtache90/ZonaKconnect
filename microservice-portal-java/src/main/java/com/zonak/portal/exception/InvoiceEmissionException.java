package com.zonak.portal.exception;

import org.springframework.http.HttpStatusCode;

public class InvoiceEmissionException extends RuntimeException {
    private final HttpStatusCode statusCode;
    private final String responseBody;

    public InvoiceEmissionException(HttpStatusCode statusCode, String responseBody) {
        super(buildMessage(statusCode, responseBody));
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public HttpStatusCode statusCode() {
        return statusCode;
    }

    public String responseBody() {
        return responseBody;
    }

    private static String buildMessage(HttpStatusCode statusCode, String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "Error emitiendo factura: HTTP " + statusCode.value();
        }
        return "Error emitiendo factura: " + responseBody.strip();
    }
}
