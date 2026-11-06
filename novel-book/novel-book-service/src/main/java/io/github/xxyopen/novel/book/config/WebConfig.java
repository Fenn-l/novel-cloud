package io.github.xxyopen.novel.book.config;

import io.github.xxyopen.novel.common.constant.ApiRouterConsts;
import io.github.xxyopen.novel.config.interceptor.TokenParseInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring Web Mvc 相关配置不要加 @EnableWebMvc 注解，否则会导致 jackson 的全局配置失效。因为 @EnableWebMvc 注解会导致 WebMvcAutoConfiguration 自动配置失效
 *
 * @author xiongxiaoyang
 * @date 2022/5/18
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final TokenParseInterceptor tokenParseInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {

        // Token 解析拦截器：只挂在阅读正文接口上，把 JWT 里的 userId 放进 UserHolder。
        // 注意：这里做的是「识别当前登录用户」，不是鉴权 —— 当前版本没有付费章节购买校验，
        // isVip 只是展示标记。早期注释声称"判断是否有权阅读该章节"与实现不符，已更正。
        // 后续若接章节购买 / 阅读历史，可在此基础上扩展成真正的鉴权拦截器。
        registry.addInterceptor(tokenParseInterceptor)
            .addPathPatterns(ApiRouterConsts.API_FRONT_BOOK_URL_PREFIX + "/content/*");


    }

}
