package io.github.kanabig.curtaincall.searchassist.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AssistRequest(@NotBlank @Size(max = 200) String query) {}
