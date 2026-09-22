package com.example.metadata_service.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;

@Component
public class PresignedUrlSigner {

    @Value("${presigned.secret-key}")
    private String secretKey;

    public String sign(String chunkId, long expiresAt) {
        try {
            String payload = chunkId + ":" + expiresAt;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean isValid(String chunkId, long expiresAt, String signature) {
        if (System.currentTimeMillis() > expiresAt) {
            return false; // expired
        }
        String expectedSignature = sign(chunkId, expiresAt);
        return expectedSignature.equals(signature);
    }
}