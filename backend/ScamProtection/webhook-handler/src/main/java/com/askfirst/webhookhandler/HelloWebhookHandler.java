package com.askfirst.webhookhandler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.util.HashMap;
import java.util.Map;

/**
 * Lambda webhook hello-world handler for task BE-03. Serves as the initial tracer bullet with AWS
 * Lambda Function URL.
 */
public class HelloWebhookHandler
    implements RequestHandler<Map<String, Object>, Map<String, Object>> {

  @Override
  public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
    Map<String, Object> response = new HashMap<>();
    response.put("statusCode", 200);
    response.put("body", "{\"message\": \"Hello from AskFirst Webhook!\"}");

    Map<String, String> headers = new HashMap<>();
    headers.put("Content-Type", "application/json");
    response.put("headers", headers);
    return response;
  }
}
