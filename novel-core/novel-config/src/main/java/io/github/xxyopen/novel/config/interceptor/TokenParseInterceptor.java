package io.github.xxyopen.novel.config.interceptor;

import io.github.xxyopen.novel.common.auth.JwtUtils;
import io.github.xxyopen.novel.common.auth.UserHolder;
import io.github.xxyopen.novel.common.constant.SystemConfigConsts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Token 解析拦截器：只做「可选识别」，不强制登录。
 *
 * <p>【用在哪、解决什么】挂在游客也能访问的接口上（如书籍正文阅读、作家入驻状态查询）：
 * 登录用户访问时希望能识别他是谁（记录阅读历史、判断入驻进度），游客没 token 也必须能正常看。
 * 因此它不像 AuthInterceptor 那样抛异常，而是「有 token 就解析、没有就放行」，
 * 把 userId 放进 {@code UserHolder}（ThreadLocal）供同一请求线程内的业务代码按需读取。
 *
 * <p>【边界】{@code JwtUtils.parseToken} 在 token 过期 / 被篡改 / 系统标识不匹配时返回 null，
 * 这里会把 null 写进 ThreadLocal 且不报错；业务层取用时必须判空，不能假定一定有登录用户。
 * 若某个接口其实必须登录，就应该用 AuthInterceptor，而不是靠这里判空兜底。
 *
 * <p>【为什么 afterCompletion 必须 clear】Web 容器线程是复用的，
 * 不清理 ThreadLocal 会让下一个请求读到上一个用户的 userId，造成串号越权；
 * 该方法在异常路径下也会执行，正好覆盖所有出口。
 *
 * @author xiongxiaoyang
 * @date 2022/5/27
 */
@Component
@RequiredArgsConstructor
public class TokenParseInterceptor implements HandlerInterceptor {

    @SuppressWarnings("NullableProblems")
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
        Object handler) throws Exception {
        // 获取登录 JWT：有才解析，没有直接放行
        String token = request.getHeader(SystemConfigConsts.HTTP_AUTH_HEADER_NAME);
        if (StringUtils.hasText(token)) {
            // 解析 token 并保存；解析失败得到 null，由业务层自行判空
            UserHolder.setUserId(JwtUtils.parseToken(token, SystemConfigConsts.NOVEL_FRONT_KEY));
        }
        return HandlerInterceptor.super.preHandle(request, response, handler);
    }

    /**
     * DispatcherServlet 完全处理完请求后调用，出现异常照常调用：清理线程上下文，防止串号。
     */
    @SuppressWarnings("NullableProblems")
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex)
        throws Exception {
        // 清理当前线程保存的用户数据
        UserHolder.clear();
        HandlerInterceptor.super.afterCompletion(request, response, handler, ex);
    }

}
