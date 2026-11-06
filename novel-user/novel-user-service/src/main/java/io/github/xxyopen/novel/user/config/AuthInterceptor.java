package io.github.xxyopen.novel.user.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xxyopen.novel.common.auth.JwtUtils;
import io.github.xxyopen.novel.common.auth.UserHolder;
import io.github.xxyopen.novel.common.constant.ErrorCodeEnum;
import io.github.xxyopen.novel.common.constant.SystemConfigConsts;
import io.github.xxyopen.novel.config.exception.BusinessException;
import io.github.xxyopen.novel.user.dto.UserInfoDto;
import io.github.xxyopen.novel.user.manager.cache.UserInfoCacheManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * 会员中心认证拦截器：强制登录校验，并把登录态写进 {@code UserHolder} 供业务层使用。
 *
 * <p>【认证链路】取请求头 JWT → 为空直接判「登录过期」→ 用 {@code JwtUtils} 验签解析出
 * userId（同时校验 systemKey 必须是前台系统）→ 查用户信息缓存确认账号确实存在 →
 * 把 userId 写入 ThreadLocal，后续 Service 就能直接 {@code UserHolder.getUserId()}。
 * 任何一步失败都抛 {@code BusinessException}，由 CommonExceptionHandler 统一转成 RestResp，
 * 所以本类不直接写响应体。
 *
 * <p>【与 TokenParseInterceptor 的区别】本类是「强制登录」：没有合法 token 一律拒绝；
 * TokenParseInterceptor 是「可选识别」：有 token 就解析、没有也放行，用于游客可访问的阅读接口。
 * 另外 WebConfig 里把 register / login 排除了本拦截器——否则用户还没 token 就无法登录，形成死循环。
 *
 * <p>【为什么必须 @Component】拦截器里要注入 {@code UserInfoCacheManager} 等 Spring Bean，
 * 不能自己 new，所以交给容器管理，再由 WebConfig 注册进 MVC 拦截器链。
 *
 * @author xiongxiaoyang
 * @date 2022/5/18
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;

    private final UserInfoCacheManager userInfoCacheManager;

    /**
     * 请求进入 Controller 前执行：完成上述认证并写入登录态。
     */
    @SuppressWarnings("NullableProblems")
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
        Object handler) throws Exception {
        // 获取登录 JWT
        String token = request.getHeader(SystemConfigConsts.HTTP_AUTH_HEADER_NAME);

        // 开始认证
        if (!StringUtils.hasText(token)) {
            // token 为空
            throw new BusinessException(ErrorCodeEnum.USER_LOGIN_EXPIRED);
        }
        Long userId = JwtUtils.parseToken(token, SystemConfigConsts.NOVEL_FRONT_KEY);
        if (Objects.isNull(userId)) {
            // 验签/过期/系统标识不匹配都会得到 null，统一按登录过期处理
            throw new BusinessException(ErrorCodeEnum.USER_LOGIN_EXPIRED);
        }
        UserInfoDto userInfo = userInfoCacheManager.getUser(userId);
        if (Objects.isNull(userInfo)) {
            // token 合法但账号已不存在（注销/删号）
            throw new BusinessException(ErrorCodeEnum.USER_ACCOUNT_NOT_EXIST);
        }
        // 设置 userId 到当前线程
        UserHolder.setUserId(userId);
        return HandlerInterceptor.super.preHandle(request, response, handler);
    }

    /**
     * handler 执行后调用，出现异常不调用；本拦截器无需后置处理，走默认实现。
     */
    @SuppressWarnings("NullableProblems")
    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
        ModelAndView modelAndView) throws Exception {
        HandlerInterceptor.super.postHandle(request, response, handler, modelAndView);
    }

    /**
     * 请求完全结束后调用（异常也照常执行）：清理 ThreadLocal。
     * 这一步是必须的，否则线程池复用线程会把上一个用户的登录态带给下一个请求，造成串号越权。
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
