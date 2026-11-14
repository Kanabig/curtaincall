package io.github.kanabig.curtaincall.notification.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UserContact(long id, String email, String name) {}
