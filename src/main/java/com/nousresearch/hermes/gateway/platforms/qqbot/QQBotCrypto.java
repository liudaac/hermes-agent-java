package com.nousresearch.hermes.gateway.platforms.qqbot;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM utilities for QQBot credential decryption.
 * Mirrors Python gateway/platforms/qqbot/crypto.py
 */
public class QQBotCrypto {
    
    private static final int AES_KEY_SIZE = 32; // 256 bits
    private static final int GCM_IV_SIZE = 12;  // 96 bits
    private static final int GCM_TAG_SIZE = 16; // 128 bits
    private static final SecureRandom secureRandom = new SecureRandom();
    
    /**
     * Generate a 256-bit random AES key and return it as base64.
     * 
     * @return Base64 encoded AES key
     */
    public static String generateBindKey() {
        byte[] key = new byte[AES_KEY_SIZE];
        secureRandom.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
    
    /**
     * Decrypt a base64-encoded AES-256-GCM ciphertext.
     * 
     * Ciphertext layout (after base64-decoding):
     * IV (12 bytes) || ciphertext (N bytes) || AuthTag (16 bytes)
     * 
     * @param encryptedBase64 The encrypted secret
     * @param keyBase64 The base64 AES key
     * @return The decrypted secret as UTF-8 string
     * @throws Exception if decryption fails
     */
    public static String decryptSecret(String encryptedBase64, String keyBase64) throws Exception {
        byte[] key = Base64.getDecoder().decode(keyBase64);
        byte[] raw = Base64.getDecoder().decode(encryptedBase64);
        
        // Extract IV, ciphertext, and tag
        byte[] iv = new byte[GCM_IV_SIZE];
        System.arraycopy(raw, 0, iv, 0, GCM_IV_SIZE);
        
        byte[] ciphertextWithTag = new byte[raw.length - GCM_IV_SIZE];
        System.arraycopy(raw, GCM_IV_SIZE, ciphertextWithTag, 0, ciphertextWithTag.length);
        
        // Decrypt
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_SIZE * 8, iv);
        
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);
        
        byte[] plaintext = cipher.doFinal(ciphertextWithTag);
        return new String(plaintext, "UTF-8");
    }
    
    /**
     * Encrypt a secret using AES-256-GCM.
     * 
     * @param plaintext The secret to encrypt
     * @param keyBase64 The base64 AES key
     * @return Base64 encoded ciphertext (IV + ciphertext + tag)
     * @throws Exception if encryption fails
     */
    public static String encryptSecret(String plaintext, String keyBase64) throws Exception {
        byte[] key = Base64.getDecoder().decode(keyBase64);
        
        // Generate random IV
        byte[] iv = new byte[GCM_IV_SIZE];
        secureRandom.nextBytes(iv);
        
        // Encrypt
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_SIZE * 8, iv);
        
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
        
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes("UTF-8"));
        
        // Combine IV + ciphertext
        byte[] result = new byte[GCM_IV_SIZE + ciphertext.length];
        System.arraycopy(iv, 0, result, 0, GCM_IV_SIZE);
        System.arraycopy(ciphertext, 0, result, GCM_IV_SIZE, ciphertext.length);
        
        return Base64.getEncoder().encodeToString(result);
    }
    
    /**
     * Generate a random nonce for API requests.
     * 
     * @return Base64 encoded nonce
     */
    public static String generateNonce() {
        byte[] nonce = new byte[16];
        secureRandom.nextBytes(nonce);
        return Base64.getEncoder().encodeToString(nonce);
    }
}
