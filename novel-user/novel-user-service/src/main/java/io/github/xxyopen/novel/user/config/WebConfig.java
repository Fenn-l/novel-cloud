package io.github.xxyopen.novel.user.config;

import io.github.xxyopen.novel.common.constant.ApiRouterConsts;
import io.github.xxyopen.novel.config.interceptor.TokenParseInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 会员中心服务的 MVC 配置：把认证拦截器挂到需要登录的接口路径上。
 *
 * <p>【为什么不能加 @EnableWebMvc】一旦标注，Spring Boot 的 WebMvcAutoConfiguration 会整体失效，
 * 全局 Jackson 配置（如 UsernameSerializer 脱敏、日期格式）以及静态资源映射都会丢，
 * 只为实现 WebMvcConfigurer 注册拦截器就得不偿失，所以这里用「实现接口 + @Configuration」的方式增量扩展。
 *
 * <p>【拦截范围与放行】只拦会员中心前缀下的接口，并明确放行 register / login：
 * 这两个接口用户还没有 token，若不放行会被 AuthInterceptor 直接判「登录过期」，导致永远登不进来。
 * order(2) 控制拦截器优先级，为后续可能的日志 / 限流拦截器留位置。
 *
 * @author xiongxiaoyang
 * @date 2022/5/18
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {

        // 权限认证拦截
        registry.addInterceptor(authInterceptor)
            // 拦截会员中心相关请求接口
            .addPathPatterns(ApiRouterConsts.API_FRONT_USER_URL_PREFIX + "/**")
            // 放行登录注册相关请求接口
            .excludePathPatterns(ApiRouterConsts.API_FRONT_USER_URL_PREFIX + "/register",
                ApiRouterConsts.API_FRONT_USER_URL_PREFIX + "/login")
            .order(2);

    }

}
