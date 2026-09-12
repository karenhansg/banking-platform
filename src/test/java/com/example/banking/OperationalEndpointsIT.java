package com.example.banking;

import static com.example.banking.support.TestJwt.fullAccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.banking.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class OperationalEndpointsIT {

    @Autowired
    private MockMvc mvc;

    @Test
    void healthProbesArePublicForTheLoadBalancer() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }

    @Test
    void sensitiveActuatorEndpointsAreNotReachable() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env").with(fullAccess())).andExpect(status().isForbidden());
    }

    @Test
    void operationsThatAreNotExplicitlyAllowedAreDenied() throws Exception {
        mvc.perform(delete("/api/v1/accounts/{accountId}", UUID.randomUUID()).with(fullAccess()))
                .andExpect(status().isForbidden());
    }

    @Test
    void apiDocumentationIsServedOutsideAws() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Banking API"));
    }
}
