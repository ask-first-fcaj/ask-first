package com.askfirst.common.channel.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MediaRef(
    @JsonProperty("file_id") String fileId,
    @JsonProperty("file_unique_id") String fileUniqueId,
    @JsonProperty("mime") String mime,
    @JsonProperty("size") Long size, // bytes
    @JsonProperty("duration") Integer duration, // seconds, voice/audio only
    @JsonProperty("file_name") String fileName // document only
    ) {
  public MediaRef {
    if (fileId == null || fileId.isBlank()) {
      throw new IllegalArgumentException("file_id is required");
    }
    if (fileUniqueId == null || fileUniqueId.isBlank()) {
      throw new IllegalArgumentException("file_unique_id is required");
    }
  }
}
