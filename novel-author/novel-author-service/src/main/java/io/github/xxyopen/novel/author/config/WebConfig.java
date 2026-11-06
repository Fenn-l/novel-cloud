package io.github.xxyopen.novel.author.config;

import io.github.xxyopen.novel.common.constant.ApiRouterConsts;
import io.github.xxyopen.novel.config.interceptor.TokenParseInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 作家后台服务的 MVC 配置：注册两条职责不同的拦截器。
 *
 * <p>【为什么不能加 @EnableWebMvc】标注后 Spring Boot 的 WebMvcAutoConfiguration 会整体失效，
 * 全局 Jackson 配置（如用户名脱敏序列化器）也会跟着失效，因此只实现 WebMvcConfigurer 做增量扩展。
 *
 * <p>【两条拦截器如何分工，面试常问】
 * - AuthInterceptor（order 2）：拦作家后台全部接口，放行 register / status。
 *   这两个接口在「还没成为作家」时也要能调：一个用来提交入驻申请，一个用来查询审核状态，
 *   此时强校验作家身份会把自己锁在门外；其余写操作必须已登录且已入驻。
 * - TokenParseInterceptor（order 3）：只挂在 register / status 上，做「可选识别」——
 *   没 token 也放行，有 token 就解析出 userId 供接口判断「当前用户」是谁。
 * 两条拦截器的路径互斥（前者排除了这两个、后者只管这两个），所以 order 数值在这里不影响结果，
 * 写 order 只是为了与其它模块保持一致的注册习惯。
 *
 * @author xiongxiaoyang
 * @date 2022/5/18
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    private final TokenParseInterceptor tokenParseInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {

        // 权限认证拦截
        registry.addInterceptor(authInterceptor)
            // 拦截作家后台相关请求接口
            .addPathPatterns(
                ApiRouterConsts.API_AUTHOR_URL_PREFIX + "/**")
            // 放行注册相关请求接口
            .excludePathPatterns(ApiRouterConsts.API_AUTHOR_URL_PREFIX + "/register",
                ApiRouterConsts.API_AUTHOR_URL_PREFIX + "/status")
            .order(2);

        // Token 解析：仅对放行路径做可选的登录态识别
        registry.addInterceptor(tokenParseInterceptor)
            .addPathPatterns(
                ApiRouterConsts.API_AUTHOR_URL_PREFIX + "/register", ApiRouterConsts.API_AUTHOR_URL_PREFIX + "/status")
            .order(3);

    }

}
