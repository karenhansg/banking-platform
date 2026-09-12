package com.example.banking.payment.api;

import static com.example.banking.config.Scopes.ACCOUNTS_WRITE;
import static com.example.banking.config.Scopes.PAYMENTS_READ;
import static com.example.banking.config.Scopes.PAYMENTS_WRITE;
import static com.example.banking.support.TestJwt.withScopes;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.banking.common.web.IdempotencyKey;
import com.example.banking.config.SecurityConfiguration;
import com.example.banking.payment.InvalidPaymentRequestException;
import com.example.banking.payment.InvalidPaymentStateException;
import com.example.banking.payment.PaymentExpiredException;
import com.example.banking.payment.PaymentFixtures;
import com.example.banking.payment.PaymentNotFoundException;
import com.example.banking.payment.PaymentService;
import com.example.banking.payment.PaymentStatus;
import com.example.banking.payment.SchedulePaymentCommand;
import com.example.banking.payment.ScheduledPayment;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PaymentController.class)
@Import(SecurityConfiguration.class)
class PaymentControllerTest {

    private static final Instant NOW = Instant.parse("2026-03-10T14:00:00Z");
    private static final UUID SOURCE_ID = UUID.fromString("5f0c6a7e-3b1d-4f2a-8c9e-1d2b3a4c5e6f");
    private static final UUID TARGET_ID = UUID.fromString("9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PaymentService paymentService;

    /** Tokens are simulated with jwt(); the resource server only needs a decoder to exist. */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void schedulesAPayment() throws Exception {
        ScheduledPayment payment = PaymentFixtures.pendingAcceptance(SOURCE_ID, TARGET_ID, "400.00", NOW, Duration.ofDays(3));
        when(paymentService.schedulePayment(any())).thenReturn(payment);

        mvc.perform(post("/api/v1/payments")
                        .with(withScopes(PAYMENTS_WRITE))
                        .header(IdempotencyKey.HEADER, "rent-2026-03")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccountId": "%s",
                                  "targetAccountId": "%s",
                                  "amount": 400.00,
                                  "executeAt": "2026-03-11T09:00:00Z",
                                  "description": "Rent"
                                }
                                """.formatted(SOURCE_ID, TARGET_ID)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/payments/" + payment.getId()))
                .andExpect(jsonPath("$.id").value(payment.getId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING_ACCEPTANCE"))
                .andExpect(jsonPath("$.expiresAt").value("2026-03-13T14:00:00Z"));

        verify(paymentService).schedulePayment(new SchedulePaymentCommand(SOURCE_ID, TARGET_ID,
                new BigDecimal("400.00"), Instant.parse("2026-03-11T09:00:00Z"), "Rent", "rent-2026-03"));
    }

    @Test
    void rejectsAnIncompleteRequest() throws Exception {
        mvc.perform(post("/api/v1/payments").with(withScopes(PAYMENTS_WRITE)).contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field",
                        containsInAnyOrder("sourceAccountId", "targetAccountId", "amount")));

        verifyNoInteractions(paymentService);
    }

    @Test
    void payingTheSameAccountIsABadRequest() throws Exception {
        when(paymentService.schedulePayment(any()))
                .thenThrow(new InvalidPaymentRequestException("Source and target accounts must be different"));

        mvc.perform(post("/api/v1/payments")
                        .with(withScopes(PAYMENTS_WRITE))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"sourceAccountId": "%s", "targetAccountId": "%s", "amount": 10.00}
                                """.formatted(SOURCE_ID, SOURCE_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_REQUEST"));
    }

    @Test
    void reportsAPaymentsStatus() throws Exception {
        ScheduledPayment payment =
                PaymentFixtures.scheduled(SOURCE_ID, TARGET_ID, "250.00", Instant.parse("2026-03-11T09:00:00Z"), NOW);
        when(paymentService.getPayment(payment.getId())).thenReturn(payment);

        mvc.perform(get("/api/v1/payments/{paymentId}", payment.getId()).with(withScopes(PAYMENTS_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.amount").value(250.00))
                .andExpect(jsonPath("$.executeAt").value("2026-03-11T09:00:00Z"));
    }

    @Test
    void unknownPaymentIsNotFound() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(paymentService.getPayment(paymentId)).thenThrow(new PaymentNotFoundException(paymentId));

        mvc.perform(get("/api/v1/payments/{paymentId}", paymentId).with(withScopes(PAYMENTS_READ)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    void listsAnAccountsPaymentsByStatus() throws Exception {
        ScheduledPayment payment =
                PaymentFixtures.pendingAcceptance(SOURCE_ID, TARGET_ID, "400.00", NOW, Duration.ofDays(3));
        when(paymentService.findPayments(eq(TARGET_ID), eq(PaymentStatus.PENDING_ACCEPTANCE), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(payment)));

        mvc.perform(get("/api/v1/payments")
                        .param("accountId", TARGET_ID.toString())
                        .param("status", "PENDING_ACCEPTANCE")
                        .with(withScopes(PAYMENTS_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sourceAccountId").value(SOURCE_ID.toString()));
    }

    @Test
    void rejectsAnUnknownStatusFilter() throws Exception {
        mvc.perform(get("/api/v1/payments")
                        .param("accountId", TARGET_ID.toString())
                        .param("status", "BOGUS")
                        .with(withScopes(PAYMENTS_READ)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void acceptsAPayment() throws Exception {
        ScheduledPayment payment = PaymentFixtures.completed(SOURCE_ID, TARGET_ID, "400.00", NOW, NOW.plusSeconds(60));
        when(paymentService.acceptPayment(payment.getId())).thenReturn(payment);

        mvc.perform(post("/api/v1/payments/{paymentId}/accept", payment.getId()).with(withScopes(PAYMENTS_WRITE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void acceptingAnExpiredPaymentIsAConflict() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(paymentService.acceptPayment(paymentId)).thenThrow(new PaymentExpiredException(paymentId, NOW));

        mvc.perform(post("/api/v1/payments/{paymentId}/accept", paymentId).with(withScopes(PAYMENTS_WRITE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_EXPIRED"))
                .andExpect(jsonPath("$.expiredAt").value("2026-03-10T14:00:00Z"));
    }

    @Test
    void cancellingAPaymentThatAlreadyHoldsFundsIsAConflict() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(paymentService.cancelPayment(paymentId))
                .thenThrow(new InvalidPaymentStateException(paymentId, PaymentStatus.PENDING_ACCEPTANCE, "cancel"));

        mvc.perform(post("/api/v1/payments/{paymentId}/cancel", paymentId).with(withScopes(PAYMENTS_WRITE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_STATE"))
                .andExpect(jsonPath("$.paymentStatus").value("PENDING_ACCEPTANCE"));
    }

    @Test
    void accountScopesDoNotGrantAccessToPayments() throws Exception {
        mvc.perform(post("/api/v1/payments")
                        .with(withScopes(ACCOUNTS_WRITE))
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(paymentService);
    }
}
