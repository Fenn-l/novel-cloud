package io.github.xxyopen.novel.config;

import io.github.xxyopen.novel.common.constant.SystemConfigConsts;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * OpenAPI（Swagger）接口文档配置。
 *
 * <p>【为什么只在 dev 生效】{@code @Profile("dev")} 让文档及其 UI 仅在开发环境装配；
 * 生产环境暴露接口清单等于把攻击面摊开给外部，属于常见的安全基线要求。
 *
 * <p>【SecurityScheme 的作用】声明认证方式为「放在请求头里的 API Key」，
 * 字段名复用 {@code SystemConfigConsts.HTTP_AUTH_HEADER_NAME}，与 AuthInterceptor 读取的请求头
 * 严格一致。这样在 Swagger UI 里填入登录后拿到的 JWT，就能直接调试需要登录的接口，
 * 而不用手工拼 Header。配置写死在这里，是为了避免文档与拦截器各写一份请求头名称导致漂移。
 *
 * @author xiongxiaoyang
 * @date 2022/9/1
 */
@Configuration
@Profile("dev")
@OpenAPIDefinition(info = @Info(title = "novel 项目接口文档", version = "v3.2.0", license = @License(name = "Apache 2.0", url = "https://www.apache.org/licenses/LICENSE-2.0")))
@SecurityScheme(type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.HEADER, name = SystemConfigConsts.HTTP_AUTH_HEADER_NAME, description = "登录 token")
public class OpenApiConfig {

}
