package com.askfirst.webhookhandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HelloWebhookHandlerTest {

  @Test
  void testHandleRequestReturns200AndMessage() {
    HelloWebhookHandler handler = new HelloWebhookHandler();
    Map<String, Object> input = new HashMap<>();

    Map<String, Object> response = handler.handleRequest(input, null);

    assertNotNull(response);
    assertEquals(200, response.get("statusCode"));
    assertEquals("{\"message\": \"Hello from AskFirst Webhook!\"}", response.get("body"));
  }
}
