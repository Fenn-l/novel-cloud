package io.github.xxyopen.novel.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * XSS 过滤的配置属性，绑定 application 配置里 {@code novel.xss.*} 前缀。
 *
 * <p>【两个字段的分工】
 * - enabled：防护总开关。XssFilter 上的 {@code @ConditionalOnProperty} 直接读它决定过滤器是否装配，
 *   本地调试或确认无风险时可以一键关掉；
 * - excludes：放行路径（正则前缀）列表，给富文本 / 允许 HTML 的接口留口子，
 *   避免一刀切转义把正常内容改坏。
 *
 * <p>【为什么用 record】配置对象天然应该不可变：用 Java record 声明后字段 final、无 setter，
 * Spring Boot 3 的 ConfigurationProperties 原生支持构造器绑定 record，
 * 既少写模板代码，也杜绝了运行期被意外改配置。由 XssFilter 上的
 * {@code @EnableConfigurationProperties} 显式启用，因此本类不需要额外加 {@code @Component}。
 *
 * @author xiongxiaoyang
 * @date 2022/5/17
 */
@ConfigurationProperties(prefix = "novel.xss")
public record XssProperties(Boolean enabled, List<String> excludes) {

}
