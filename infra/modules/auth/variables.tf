variable "name" {
  description = "User pool name and domain prefix, e.g. banking-dev."
  type        = string
}

variable "deletion_protection" {
  description = "Prevent the user pool from being deleted."
  type        = bool
}

variable "scopes" {
  description = "OAuth2 scopes of the API. Must match com.example.banking.config.Scopes."
  type        = map(string)
  default = {
    "accounts.read"  = "Read account balances and transactions"
    "accounts.write" = "Open accounts, deposit and withdraw"
    "payments.read"  = "Read scheduled payments"
    "payments.write" = "Schedule, accept and cancel payments"
  }
}

variable "clients" {
  description = "Machine-to-machine clients, by name, with the scopes each may request."
  type        = map(list(string))
  default = {
    "channel"   = ["accounts.read", "accounts.write", "payments.read", "payments.write"]
    "reporting" = ["accounts.read", "payments.read"]
  }

  validation {
    condition = alltrue([
      for scopes in values(var.clients) : alltrue([
        for scope in scopes : contains(["accounts.read", "accounts.write", "payments.read", "payments.write"], scope)
      ])
    ])
    error_message = "Clients may only be granted accounts.read, accounts.write, payments.read or payments.write."
  }
}

variable "access_token_validity_minutes" {
  description = "Lifetime of issued access tokens."
  type        = number
  default     = 60
}
