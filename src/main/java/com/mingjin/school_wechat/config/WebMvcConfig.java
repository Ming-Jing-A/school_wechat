package com.mingjin.school_wechat.config;

import com.mingjin.school_wechat.common.auth.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    @Value("${app.storage.local-upload-dir:uploads}")
    private String localUploadDir;

    @Value("${app.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
    private String allowedOrigins;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login", "/api/auth/register");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 开发环境允许所有来源访问
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("*")
                .allowedHeaders("*")
                .exposedHeaders("Content-Disposition", "Content-Type", "Content-Length")
                .allowCredentials(true);
        registry.addMapping("/uploads/**")
                .allowedOriginPatterns("*")
                .allowedMethods("*")
                .allowedHeaders("*");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path uploadPath = Paths.get(localUploadDir).toAbsolutePath().normalize();
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(uploadPath.toUri().toString());
    }
}
