package com.askfirst.common.channel.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EntityRef(
    @JsonProperty("kind") String kind, // "url" | "text_link"
    @JsonProperty("offset") int offset, // UTF-16 code units
    @JsonProperty("length") int length,
    @JsonProperty("url") String url // Required when kind == "text_link"
    ) {
  public EntityRef {
    if ("text_link".equals(kind) && (url == null || url.isBlank())) {
      throw new IllegalArgumentException("text_link requires url");
    }
  }
}
