package com.ramphoenix.windows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Mutual message authentication; the private credential is never sent to a port listener. */
public final class WindowAuth {
    private WindowAuth() {}
    public static String nonce() {
        byte[] bytes=new byte[16];new SecureRandom().nextBytes(bytes);return hex(bytes);
    }
    private static String hex(byte[] bytes) {
        StringBuilder result=new StringBuilder(bytes.length*2);
        for(byte value:bytes) result.append(Character.forDigit((value&255)>>>4,16)).append(Character.forDigit(value&15,16));
        return result.toString();
    }
    public static String sign(String key,String nonce,String payload) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.US_ASCII),"HmacSHA256"));
        return hex(mac.doFinal((nonce+"\n"+payload).getBytes(StandardCharsets.UTF_8)));
    }
    public static boolean verify(String key,String nonce,String payload,String signature) throws Exception {
        return nonce.matches("[a-f0-9]{32}") && signature.matches("[a-f0-9]{64}")
                && MessageDigest.isEqual(sign(key,nonce,payload).getBytes(StandardCharsets.US_ASCII),signature.getBytes(StandardCharsets.US_ASCII));
    }
}
