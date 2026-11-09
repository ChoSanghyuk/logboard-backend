package com.logboard.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.ThrowingController.class)
@Import(GlobalExceptionHandlerTest.ThrowingController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void apiExceptionKeepsStatusCodeAndDetails() throws Exception {
        mvc.perform(get("/test/api-exception"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_EXCEPTION_FOUND"))
                .andExpect(jsonPath("$.message").value("No exception in window"))
                .andExpect(jsonPath("$.details.windowMin").value(5));
    }

    @Test
    void validationFailureListsFields() throws Exception {
        mvc.perform(post("/test/validated").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields.name").exists());
    }

    @Test
    void malformedJsonIsBadRequest() throws Exception {
        mvc.perform(post("/test/validated").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.details").isMap());
    }

    @Test
    void unsupportedMethodIs405() throws Exception {
        mvc.perform(post("/test/api-exception"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unexpectedExceptionIs500WithoutLeakingMessage() throws Exception {
        mvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected server error"));
    }

    record Body(@NotBlank String name) {}

    @RestController
    static class ThrowingController {

        @GetMapping("/test/api-exception")
        void apiException() {
            throw new ApiException(HttpStatus.NOT_FOUND, "NO_EXCEPTION_FOUND", "No exception in window",
                    Map.of("windowMin", 5));
        }

        @PostMapping("/test/validated")
        void validated(@Valid @RequestBody Body body) {}

        @GetMapping("/test/boom")
        void boom() {
            throw new IllegalStateException("secret internal detail");
        }
    }
}
