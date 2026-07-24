package org.quwuting.quwutingservice.config;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.security.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 软鉴权：所有路径都经过拦截器提取用户身份，但不拦截任何请求
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**");
    }
}
