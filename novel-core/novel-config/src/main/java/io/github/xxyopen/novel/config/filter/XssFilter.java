package io.github.xxyopen.novel.config.filter;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import io.github.xxyopen.novel.config.XssProperties;
import io.github.xxyopen.novel.config.wrapper.XssHttpServletRequestWrapper;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * XSS 防护过滤器：把普通 {@code HttpServletRequest} 包装成 {@code XssHttpServletRequestWrapper}，
 * 让后续代码取到的请求参数都经过 HTML 字符转义。
 *
 * <p>【在链路中的位置】作为 Servlet Filter 早于 Spring MVC 拦截器 / Controller 执行，
 * 属于「入口统一清洗」，业务代码无需各自处理，避免遗漏。
 * 用装饰器包装请求对象而不是改原始 request，是因为 Servlet 规范不允许直接改参数，
 * wrapper 覆写 getParameterValues 是标准做法。
 *
 * <p>【开关与白名单】{@code @ConditionalOnProperty(novel.xss.enabled=true)} 让防护可配置启停；
 * excludes 配置一组放行路径（按正则前缀匹配），专门给「参数里本就允许出现 HTML」的接口用——
 * 例如作家发布章节正文是富文本，若把 {@code <} {@code >} 全转义会破坏正常排版，
 * 这类接口必须排除，改由输出编码 / 白名单标签净化来处理。
 *
 * <p>【边界，面试主动讲】当前 wrapper 只覆写了 getParameterValues，
 * 即只覆盖 query 参数与表单参数；JSON 请求体（@RequestBody）、Header、Cookie 不走这里。
 * 因此它不是完整的 XSS 方案：存储型 XSS 还应配合「输出时按上下文编码」和 CSP，
 * 富文本则需专门的 HTML 白名单净化（如 Jsoup），不能只靠转义两个尖括号。
 *
 * @author xiongxiaoyang
 * @date 2022/5/17
 */
@Component
@ConditionalOnProperty(value = "novel.xss.enabled", havingValue = "true")
@WebFilter(urlPatterns = "/*", filterName = "xssFilter")
@EnableConfigurationProperties(value = {XssProperties.class})
@RequiredArgsConstructor
public class XssFilter implements Filter {

    private final XssProperties xssProperties;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        Filter.super.init(filterConfig);
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
        FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) servletRequest;
        if (handleExcludeUrl(req)) {
            // 命中白名单：放行原始请求，不做任何转义
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }
        XssHttpServletRequestWrapper xssRequest = new XssHttpServletRequestWrapper(
            (HttpServletRequest) servletRequest);
        // 关键：把包装后的请求交给后续链路，Controller 取参数时才会走到转义逻辑
        filterChain.doFilter(xssRequest, servletResponse);
    }

    /**
     * 判断当前请求路径是否命中 excludes 白名单。
     * 每个 pattern 按「^ + pattern」编译成前缀正则，用 find() 做前缀匹配，
     * 因此配置形如 /author/book 即可覆盖其下所有子路径。
     */
    private boolean handleExcludeUrl(HttpServletRequest request) {
        if (CollectionUtils.isEmpty(xssProperties.excludes())) {
            return false;
        }
        String url = request.getServletPath();
        for (String pattern : xssProperties.excludes()) {
            Pattern p = Pattern.compile("^" + pattern);
            Matcher m = p.matcher(url);
            if (m.find()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void destroy() {
        Filter.super.destroy();
    }
}
