package com.oilquiz.app.infra;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;

@RunWith(AndroidJUnit4.class)
public class SecurityTest {

    private Security security;

    @Before
    public void setUp() {
        security = new Security();
    }

    @Test
    public void testSanitize_APIKey() {
        String input = "My API key is sk-abc123xyz for testing";
        String result = Security.sanitize(input);
        
        assertFalse("API key should be sanitized", 
                result.contains("sk-abc123xyz"));
        assertTrue("Should contain placeholder", 
                result.contains("***"));
    }

    @Test
    public void testSanitize_Password() {
        String input = "Password: mySecretPass123";
        String result = Security.sanitize(input);
        
        assertFalse("Password should be sanitized",
                result.contains("mySecretPass123"));
        assertTrue("Should contain placeholder",
                result.contains("***"));
    }

    @Test
    public void testSanitize_PhoneNumber() {
        String input = "Contact: 13812345678";
        String result = Security.sanitize(input);
        
        assertFalse("Phone number should be sanitized",
                result.contains("13812345678"));
        assertTrue("Should contain placeholder",
                result.contains("***"));
    }

    @Test
    public void testSanitize_Email() {
        String input = "Email: user@example.com";
        String result = Security.sanitize(input);
        
        assertFalse("Email should be sanitized",
                result.contains("user@example.com"));
        assertTrue("Should contain placeholder",
                result.contains("***"));
    }

    @Test
    public void testSanitize_MultipleSensitiveData() {
        String input = "API: sk-test123, Pass: secret, Phone: 13900001111";
        String result = Security.sanitize(input);
        
        assertFalse(result.contains("sk-test123"));
        assertFalse(result.contains("secret"));
        assertFalse(result.contains("13900001111"));
    }

    @Test
    public void testSanitize_NullInput() {
        assertNull(Security.sanitize(null));
    }

    @Test
    public void testSanitize_EmptyInput() {
        assertEquals("", Security.sanitize(""));
    }

    @Test
    public void testSanitize_NoSensitiveData() {
        String input = "This is a normal message without sensitive data";
        String result = Security.sanitize(input);
        
        assertEquals("Non-sensitive input should remain unchanged", 
                input, result);
    }
}
