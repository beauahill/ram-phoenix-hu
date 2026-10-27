package com.ramphoenix.launcher;

import com.ramphoenix.windows.WindowAuth;
import org.junit.Test;
import static org.junit.Assert.*;

public class WindowAuthTest {
    private static final String KEY="1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";
    @Test public void bothDirectionsRequireThePrivateCredential() throws Exception {
        String nonce=WindowAuth.nonce(), body="{\"visible\":true}";
        String signature=WindowAuth.sign(KEY,nonce,body);
        assertTrue(WindowAuth.verify(KEY,nonce,body,signature));
        assertFalse(WindowAuth.verify(KEY.replace('a','b'),nonce,body,signature));
        assertFalse(signature.contains(KEY));
    }
    @Test public void changedMessageOrNonceCannotReuseASignature() throws Exception {
        String nonce=WindowAuth.nonce(), signature=WindowAuth.sign(KEY,nonce,"original");
        assertFalse(WindowAuth.verify(KEY,nonce,"modified",signature));
        assertFalse(WindowAuth.verify(KEY,WindowAuth.nonce(),"original",signature));
    }
    @Test public void rejectsMalformedAuthenticationFields() throws Exception {
        assertFalse(WindowAuth.verify(KEY,"short","payload","bad"));
        String nonce=WindowAuth.nonce();
        assertEquals(32,nonce.length());
        assertFalse(WindowAuth.verify(KEY,nonce,"payload",new String(new char[64])));
    }
}
