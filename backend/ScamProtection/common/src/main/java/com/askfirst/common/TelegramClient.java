package com.askfirst.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

public class TelegramClient {

  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final int MAX_ATTEMPTS = 2;

  private final String url;

  public TelegramClient(String botToken) {
    if (botToken == null || botToken.isBlank()) {
      throw new IllegalArgumentException("TELEGRAM_BOT_TOKEN chưa được cấu hình");
    }

    this.url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
  }

  public void sendMessage(long chatId, String text) {
    try {
      String json = MAPPER.writeValueAsString(Map.of("chat_id", chatId, "text", text));

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(Duration.ofSeconds(20))
              .header("Content-Type", "application/json; charset=UTF-8")
              .POST(HttpRequest.BodyPublishers.ofString(json))
              .build();

      for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
        try {
          HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

          if (response.statusCode() != 200) {
            throw new IllegalStateException(
                "Telegram trả về HTTP " + response.statusCode() + ": " + response.body());
          }

          return;

        } catch (HttpConnectTimeoutException e) {
          // Chỉ retry lỗi timeout khi chưa kết nối được.
          if (attempt == MAX_ATTEMPTS) {
            throw e;
          }

          Thread.sleep(300L * attempt);
        }
      }

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Gửi Telegram bị gián đoạn", e);
    } catch (IOException e) {
      throw new RuntimeException("Lỗi kết nối khi gửi tin nhắn Telegram", e);
    } catch (Exception e) {
      throw new RuntimeException("Không gửi được tin nhắn Telegram", e);
    }
  }
}
