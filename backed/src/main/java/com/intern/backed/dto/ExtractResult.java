package com.intern.backed.dto;

import java.util.List;

public record ExtractResult(
        String docType,
        String title,
        String addressee,
        String signature,
        String date,
        List<String> warnings
) {}