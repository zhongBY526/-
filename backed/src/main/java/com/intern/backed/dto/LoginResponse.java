package com.intern.backed.dto;

import com.intern.backed.auth.UserView;

public record LoginResponse(
        String token,
        UserView user
) {}