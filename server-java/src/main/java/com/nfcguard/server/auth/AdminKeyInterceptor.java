package com.nfcguard.server.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 학습 데이터셋 export는 원본 센서 시계열 전체가 나가므로 관리자 키(X-Admin-Key)로만 허용 */
public class AdminKeyInterceptor implements HandlerInterceptor {

    private final String adminKey;

    public AdminKeyInterceptor(String adminKey) {
        this.adminKey = adminKey;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String given = request.getHeader("X-Admin-Key");
        boolean ok = adminKey != null && !adminKey.isBlank() && given != null
                && MessageDigest.isEqual(adminKey.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
        if (!ok) response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        return ok;
    }
}
