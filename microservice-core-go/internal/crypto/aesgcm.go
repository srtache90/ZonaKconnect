package crypto

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"strings"
)

const ivBytes = 12

// DecryptAESGCM decrypts portal-compatible payloads: Base64(IV || ciphertext+tag),
// key = SHA-256(JWT_SECRET).
func DecryptAESGCM(secret, encoded string) (string, error) {
	if strings.TrimSpace(encoded) == "" {
		return "", nil
	}
	payload, err := base64.StdEncoding.DecodeString(strings.TrimSpace(encoded))
	if err != nil {
		return "", fmt.Errorf("payload cifrado inválido: %w", err)
	}
	if len(payload) <= ivBytes {
		return "", fmt.Errorf("payload cifrado demasiado corto")
	}
	iv := payload[:ivBytes]
	ciphertext := payload[ivBytes:]
	sum := sha256.Sum256([]byte(secret))
	block, err := aes.NewCipher(sum[:])
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	plain, err := gcm.Open(nil, iv, ciphertext, nil)
	if err != nil {
		return "", fmt.Errorf("no fue posible descifrar dato sensible (JWT_SECRET?): %w", err)
	}
	return string(plain), nil
}
