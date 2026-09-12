package com.example.banking.account.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OpenAccountRequest(@NotBlank @Size(max = 100) String ownerName) {
}
