package com.intern.backed.auth;

import com.intern.backed.common.ApiResponse;
import com.intern.backed.common.UnauthorizedException;
import com.intern.backed.dto.LoginRequest;
import com.intern.backed.dto.LoginResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserRepository userRepository;
    private final TokenStore tokenStore;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthController(UserRepository userRepository, TokenStore tokenStore) {
        this.userRepository = userRepository;
        this.tokenStore = tokenStore;
    }
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        User user = userRepository.findByUsername(request.username())
                .filter(u -> passwordEncoder.matches(request.password(), u.passwordHash()))
                .orElseThrow(() -> new UnauthorizedException("用户名或密码错误"));
        String token = tokenStore.issue(user);
        return new ApiResponse<>(200, "ok", new LoginResponse(token, user.toView()), null);
    }
    @GetMapping("/me")
    public ApiResponse<UserView> me() {
        return new ApiResponse<>(200, "ok", UserContext.currentUser().toView(), null);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        tokenStore.remove(AuthInterceptor.extractToken(request));
        return new ApiResponse<>(200, "ok", null, null);
    }
}