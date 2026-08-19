package com.zonak.portal.exception;

public class InvoiceStorageException extends RuntimeException {
    public InvoiceStorageException(String message) {
        super(message);
    }

    public InvoiceStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
