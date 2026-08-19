package com.zonak.portal.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SensitiveDataCryptoService {
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKeySpec key;

    public SensitiveDataCryptoService(@Value("${security.jwt.secret}") String secret) {
        this.key = new SecretKeySpec(sha256(secret), "AES");
    }

    public String encrypt(String value) {
        return encrypt(value.getBytes(StandardCharsets.UTF_8));
    }

    public String encrypt(byte[] value) {
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(value);

            ByteBuffer payload = ByteBuffer.allocate(iv.length + encrypted.length);
            payload.put(iv);
            payload.put(encrypted);
            return Base64.getEncoder().encodeToString(payload.array());
        } catch (Exception ex) {
            throw new IllegalStateException("No fue posible cifrar dato sensible", ex);
        }
    }

    public String decryptToString(String encoded) {
        byte[] decrypted = decrypt(encoded);
        if (decrypted == null) {
            return null;
        }
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    public byte[] decrypt(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            byte[] payload = Base64.getDecoder().decode(encoded.trim());
            if (payload.length <= IV_BYTES) {
                throw new IllegalStateException("Payload cifrado inválido");
            }
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(encrypted);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "No fue posible descifrar un dato sensible. Verifique que JWT_SECRET no haya cambiado.",
                    ex
            );
        }
    }

    private byte[] sha256(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("No fue posible derivar llave de cifrado", ex);
        }
    }
}
