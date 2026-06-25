package com.fongmi.android.tv.bean;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AiConfigTest {

    @Test
    public void objectFrom_usesSafeDefaultsAndRequiresExplicitEnable() {
        AiConfig config = AiConfig.objectFrom("");

        assertEquals(AiConfig.PROTOCOL_OPENAI_RESPONSES, config.getProtocol());
        assertEquals(AiConfig.DEFAULT_ENDPOINT, config.getEndpoint());
        assertEquals(AiConfig.DEFAULT_MODEL, config.getModel());
        assertEquals("", config.getCustomUserAgent());
        assertFalse(config.isReady());
    }

    @Test
    public void isReady_requiresEnabledEndpointKeyAndModel() {
        AiConfig config = AiConfig.objectFrom("{\"enabled\":true,\"endpoint\":\"https://api.openai.com/v1/responses\",\"apiKey\":\"sk-test\",\"model\":\"gpt-4.1-mini\"}");

        assertTrue(config.isReady());
    }

    @Test
    public void objectFrom_supportsProtocolSpecificEndpointDefaultAndUserAgentAlias() {
        AiConfig config = AiConfig.objectFrom("{\"enabled\":true,\"apiFormat\":\"openai_chat\",\"endpoint\":\"\",\"apiKey\":\"sk-test\",\"model\":\"gpt-test\",\"userAgent\":\" claude-cli/2.1.161 \"}");

        assertEquals(AiConfig.PROTOCOL_OPENAI_CHAT, config.getProtocol());
        assertEquals(AiConfig.DEFAULT_OPENAI_CHAT_ENDPOINT, config.getEndpoint());
        assertEquals("claude-cli/2.1.161", config.getCustomUserAgent());
        assertTrue(config.isReady());
    }

    @Test
    public void objectFrom_unknownProtocolFallsBackToResponses() {
        AiConfig config = AiConfig.objectFrom("{\"protocol\":\"unknown\",\"endpoint\":\"\",\"apiKey\":\"sk-test\"}");

        assertEquals(AiConfig.PROTOCOL_OPENAI_RESPONSES, config.getProtocol());
        assertEquals(AiConfig.DEFAULT_ENDPOINT, config.getEndpoint());
    }
}
