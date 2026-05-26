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

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    @Value("${app.storage.local-upload-dir:uploads}")
    private String localUploadDir;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login",
                        "/api/auth/register",
                        "/api/ai/**",
                        "/api/browser/proxy",
                        "/api/browser/proxy/**",
                        "/api/browser/search",
                        "/api/browser/bilibili-player",
                        "/api/browser/bilibili-play-page",
                        "/api/browser/bilibili-stream",
                        "/api/browser/bilibili-video-stream",
                        "/api/browser/stream",
                        "/api/browser/empty",
                        "/api/browser/ws-proxy"
                );
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
        Path frontendDist = Paths.get("web/dist").toAbsolutePath().normalize();
        String distUri = frontendDist.toUri().toString();
        registry.addResourceHandler("/assets/**")
                .addResourceLocations(distUri + "assets/");
    }
}
