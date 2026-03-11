package com.annotation.platform.service.auth;

import com.annotation.platform.dto.request.auth.LoginRequest;
import com.annotation.platform.dto.request.auth.RegisterRequest;
import com.annotation.platform.dto.response.auth.LoginResponse;

public interface AuthService {

    LoginResponse login(LoginRequest request);

    LoginResponse register(RegisterRequest request);

    void logout(String token);

    String refreshToken(String token);
}
