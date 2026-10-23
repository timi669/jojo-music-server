package cn.edu.seig.vibemusic.config;

import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * 跨域（CORS）配置。
 *
 * <p><b>为什么需要改</b>：原实现是
 * {@code allowedOriginPatterns("*")} + {@code allowCredentials(true)}，
 * 这是最宽松的组合 —— 任意网站都能携带凭据（Authorization / Cookie）
 * 调用本服务的接口，并在浏览器中读取响应。配合本项目"token 存 localStorage、
 * 请求带 Authorization 头"的用法，等于把接口完全敞开给任何第三方页面。
 *
 * <p><b>现在的做法</b>：改为**显式白名单**，允许的来源通过
 * {@code app.cors.allowed-origins} 配置（见 application.yml），
 * 默认只放行本地开发端口。跨域来源必须精确匹配，不再接受任意域名。
 *
 * <p>注意：这是"浏览器同源策略"层面的限制，属于纵深防御的一环，
 * 并不能替代接口自身的鉴权 —— 真正的访问控制仍由登录拦截器负责。
 */
@Configuration
public class CorsConfig {

    /**
     * 允许跨域访问的来源列表。
     *
     * <p>使用 {@code allowedOrigins}（精确匹配）而非 {@code allowedOriginPatterns}
     * （通配匹配）。后者若写成 {@code "*"} 会与 {@code allowCredentials(true)}
     * 组合成最危险的配置。
     */
    @Value("${app.cors.allowed-origins:http://localhost:8090,http://localhost:8089}")
    private String allowedOrigins;

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(@NotNull CorsRegistry registry) {
                String[] origins = Arrays.stream(allowedOrigins.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toArray(String[]::new);

                registry.addMapping("/**")
                        .allowedOrigins(origins)
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .exposedHeaders("Authorization")
                        .allowCredentials(true)
                        // 预检结果缓存 1 小时，减少 OPTIONS 往返
                        .maxAge(3600);
            }
        };
    }
}