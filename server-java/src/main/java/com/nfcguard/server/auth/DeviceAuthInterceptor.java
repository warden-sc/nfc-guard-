package com.nfcguard.server.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 이벤트 API 인증 + 기기별 간단한 요청 제한.
 * 헤더: X-Device-Token, Authorization: Bearer {apiKey}
 * 인증된 기기 토큰은 request attribute(DEVICE_TOKEN_ATTR)로 컨트롤러에 전달 -
 * 요청 본문의 deviceToken은 믿지 않는다 (다른 기기 사칭 방지).
 */
public class DeviceAuthInterceptor implements HandlerInterceptor {

    public static final String DEVICE_TOKEN_ATTR = "nfcguard.deviceToken";

    private final DeviceAuthService authService;
    private final int maxRequestsPerHour;
    private final Map<String, long[]> windows = new ConcurrentHashMap<>(); // token -> [windowStartMs, count]

    public DeviceAuthInterceptor(DeviceAuthService authService, int maxRequestsPerHour) {
        this.authService = authService;
        this.maxRequestsPerHour = maxRequestsPerHour;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = request.getHeader("X-Device-Token");
        String auth = request.getHeader("Authorization");
        String apiKey = (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7) : null;

        var device = authService.authenticate(token, apiKey);
        if (device.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        if (!withinRateLimit(device.get())) {
            response.setStatus(429);
            return false;
        }
        request.setAttribute(DEVICE_TOKEN_ATTR, device.get());
        return true;
    }

    private boolean withinRateLimit(String deviceToken) {
        long now = System.currentTimeMillis();
        long[] w = windows.compute(deviceToken, (k, v) -> {
            if (v == null || now - v[0] > 3_600_000L) return new long[]{now, 1};
            v[1]++;
            return v;
        });
        return w[1] <= maxRequestsPerHour;
    }
}
