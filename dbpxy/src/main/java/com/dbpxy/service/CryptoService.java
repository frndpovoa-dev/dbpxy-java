package com.dbpxy.service;

/*-
 * #%L
 * dbpxy
 * $Id:$
 * $HeadURL:$
 * %%
 * Copyright (C) 2025 Fernando Lemes Povoa
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class CryptoService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String AES_GCM_NO_PADDING = "AES/GCM/NoPadding";
    private static final int AES_KEY_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private static final SecretKey SECRET_KEY = CryptoService.newAESKey();

    private static final ThreadLocal<Cipher> CIPHER_HOLDER = ThreadLocal.withInitial(() ->{
        try {
            return Cipher.getInstance(AES_GCM_NO_PADDING);
        } catch (final NoSuchAlgorithmException
                | NoSuchPaddingException e) {
            throw new RuntimeException(e);
        }
    });

    @Value("${app.encryption.enabled}")
    private boolean useEncryption;

    public String encrypt(final String plainText) {
        if (!useEncryption) {
            return plainText;
        }
        try {
            final byte[] iv = new byte[GCM_IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);

            final Cipher cipher = CIPHER_HOLDER.get();
            final GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, SECRET_KEY, gcmParameterSpec);

            final byte[] encryptedText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            final byte[] output = new byte[iv.length + encryptedText.length];
            System.arraycopy(iv, 0, output, 0, iv.length);
            System.arraycopy(encryptedText, 0, output, iv.length, encryptedText.length);

            return Base64.getEncoder().encodeToString(output);
        } catch (final IllegalBlockSizeException
                       | InvalidAlgorithmParameterException
                       | BadPaddingException
                       | InvalidKeyException e) {
            throw new RuntimeException(e);
        }
    }

    public String decrypt(final String encryptedText) {
        if (!useEncryption) {
            return encryptedText;
        }
        try {
            final byte[] encryptedPayload = Base64.getDecoder().decode(encryptedText);

            final ByteBuffer byteBuffer = ByteBuffer.wrap(encryptedPayload);
            final byte[] iv = new byte[GCM_IV_LENGTH];
            byteBuffer.get(iv);
            final byte[] ciphertext = new byte[byteBuffer.remaining()];
            byteBuffer.get(ciphertext);

            final Cipher cipher = CIPHER_HOLDER.get();
            final GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, SECRET_KEY, gcmParameterSpec);

            final byte[] decryptedText = cipher.doFinal(ciphertext);
            return new String(decryptedText, StandardCharsets.UTF_8);
        } catch (final IllegalBlockSizeException
                       | InvalidAlgorithmParameterException
                       | BadPaddingException
                       | InvalidKeyException e) {
            throw new RuntimeException(e);
        }
    }

    private static SecretKey newAESKey() {
        try {
            final KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
            keyGenerator.init(AES_KEY_LENGTH);
            return keyGenerator.generateKey();
        } catch (final NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
