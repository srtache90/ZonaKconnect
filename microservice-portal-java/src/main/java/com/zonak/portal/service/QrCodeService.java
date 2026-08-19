package com.zonak.portal.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.zonak.portal.exception.InvoiceStorageException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class QrCodeService {
    private static final int DEFAULT_SIZE = 220;

    public String toDataUri(String content) {
        if (content == null || content.isBlank()) {
            throw new InvoiceStorageException("contenido requerido para generar QR DIAN");
        }

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Map<EncodeHintType, Object> hints = Map.of(
                    EncodeHintType.CHARACTER_SET, "UTF-8",
                    EncodeHintType.MARGIN, 1
            );
            BitMatrix matrix = new QRCodeWriter()
                    .encode(content, BarcodeFormat.QR_CODE, DEFAULT_SIZE, DEFAULT_SIZE, hints);
            MatrixToImageWriter.writeToStream(matrix, "PNG", output);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (WriterException | IOException ex) {
            throw new InvoiceStorageException("no fue posible generar QR DIAN", ex);
        }
    }
}
