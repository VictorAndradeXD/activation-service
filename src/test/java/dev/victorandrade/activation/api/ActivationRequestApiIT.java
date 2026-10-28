package dev.victorandrade.activation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import dev.victorandrade.activation.domain.ActivationStatus;
import dev.victorandrade.activation.support.IntegrationTest;

class ActivationRequestApiIT extends IntegrationTest {

    @Test
    void createsAndFetchesARequest() throws Exception {
        var created = mockMvc.perform(createRequest("erp", "EXT-1", "CUST-1", "INTERNET-500"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/v1/requests/{id}", id).with(client("erp")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalId").value("EXT-1"))
                .andExpect(jsonPath("$.customerId").value("CUST-1"))
                .andExpect(jsonPath("$.productCode").value("INTERNET-500"));

        var stored = repository.findByExternalId("EXT-1").orElseThrow();
        assertThat(stored.getClientId()).isEqualTo("erp");
        assertThat(stored.getCorrelationId()).isNotBlank();
    }

    @Test
    void returns404ForUnknownId() throws Exception {
        mockMvc.perform(get("/api/v1/requests/{id}", "0b7f3c1e-5d2a-4c4e-9a51-2f8d7b1e6c90").with(client("erp")))
                .andExpect(status().isNotFound());
    }

    @Test
    void retryWithSameContentReturnsTheExistingRequest() throws Exception {
        var first = mockMvc.perform(createRequest("erp", "EXT-1", "CUST-1", "INTERNET-500"))
                .andExpect(status().isCreated()).andReturn();
        var second = mockMvc.perform(createRequest("erp", "EXT-1", "CUST-1", "INTERNET-500"))
                .andExpect(status().isOk()).andReturn();

        assertThat(idOf(second)).isEqualTo(idOf(first));
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void sameExternalIdWithDifferentContentIsAConflict() throws Exception {
        mockMvc.perform(createRequest("erp", "EXT-1", "CUST-1", "INTERNET-500")).andExpect(status().isCreated());

        mockMvc.perform(createRequest("erp", "EXT-1", "CUST-1", "INTERNET-1000"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/duplicate-request"));
        // Another consumer reusing the id gets a conflict too, without seeing the original request.
        mockMvc.perform(createRequest("crm", "EXT-1", "CUST-1", "INTERNET-500"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.id").doesNotExist());

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void concurrentIdenticalRequestsCreateExactlyOneRecord() throws Exception {
        int threads = 16;
        var start = new CountDownLatch(1);
        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            calls.add(() -> {
                start.await();
                return mockMvc.perform(createRequest("erp", "EXT-RACE", "CUST-1", "INTERNET-500")).andReturn();
            });
        }

        List<MvcResult> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(threads)) {
            var futures = calls.stream().map(executor::submit).toList();
            start.countDown();
            for (var future : futures) {
                results.add(future.get());
            }
        }

        var statuses = results.stream().map(r -> r.getResponse().getStatus()).toList();
        assertThat(statuses).containsOnly(201, 200);
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(results).extracting(this::idOf).containsOnly(idOf(results.getFirst()));
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void aClientCannotReadAnotherClientsRequest() throws Exception {
        var created = mockMvc.perform(createRequest("erp", "EXT-1", "CUST-1", "INTERNET-500")).andReturn();

        mockMvc.perform(get("/api/v1/requests/{id}", idOf(created)).with(client("crm")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/requests").with(client("crm")))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void listsOwnRequestsFilteredByStatusAndCustomer() throws Exception {
        mockMvc.perform(createRequest("erp", "EXT-1", "CUST-1", "INTERNET-500"));
        mockMvc.perform(createRequest("erp", "EXT-2", "CUST-2", "INTERNET-500"));
        mockMvc.perform(createRequest("crm", "EXT-3", "CUST-1", "INTERNET-500"));

        mockMvc.perform(get("/api/v1/requests").with(client("erp"))
                        .param("customerId", "CUST-1").param("status", ActivationStatus.PENDING.name()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].externalId").value("EXT-1"));
        mockMvc.perform(get("/api/v1/requests").with(client("erp")).param("size", "1"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    private String idOf(MvcResult result) {
        try {
            return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
