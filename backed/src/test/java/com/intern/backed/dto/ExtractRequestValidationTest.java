package com.intern.backed.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtractRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void overlongTextIsRejected() {
        Set<ConstraintViolation<ExtractRequest>> violations =
                validator.validate(new ExtractRequest("文".repeat(10_001)));
        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("text")));
    }

    @Test
    void blankTextIsRejected() {
        assertFalse(validator.validate(new ExtractRequest("   ")).isEmpty());
    }

    @Test
    void normalTextPasses() {
        assertTrue(validator.validate(new ExtractRequest("关于开展安全生产检查的通知")).isEmpty());
    }
}
