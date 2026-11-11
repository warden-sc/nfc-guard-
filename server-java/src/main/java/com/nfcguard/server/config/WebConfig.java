package com.nfcguard.server.config;

import com.nfcguard.server.auth.AdminKeyInterceptor;
import com.nfcguard.server.auth.DeviceAuthInterceptor;
import com.nfcguard.server.auth.DeviceAuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final DeviceAuthService authService;
    private final String adminKey;
    private final int maxRequestsPerHour;

    public WebConfig(DeviceAuthService authService,
                     @Value("${nfcguard.admin.api-key:}") String adminKey,
                     @Value("${nfcguard.rate-limit.per-device-per-hour:120}") int maxRequestsPerHour) {
        this.authService = authService;
        this.adminKey = adminKey;
        this.maxRequestsPerHour = maxRequestsPerHour;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new DeviceAuthInterceptor(authService, maxRequestsPerHour))
                .addPathPatterns("/api/v1/events/**")
                .excludePathPatterns("/api/v1/events/dataset/**");
        registry.addInterceptor(new AdminKeyInterceptor(adminKey))
                .addPathPatterns("/api/v1/events/dataset/**");
    }
}
