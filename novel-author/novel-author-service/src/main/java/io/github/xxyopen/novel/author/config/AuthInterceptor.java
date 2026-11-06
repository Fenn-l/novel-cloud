package io.github.xxyopen.novel.author.config;

import io.github.xxyopen.novel.author.dto.AuthorInfoDto;
import io.github.xxyopen.novel.author.manager.cache.AuthorInfoCacheManager;
import io.github.xxyopen.novel.common.auth.JwtUtils;
import io.github.xxyopen.novel.common.auth.UserHolder;
import io.github.xxyopen.novel.common.constant.ErrorCodeEnum;
import io.github.xxyopen.novel.common.constant.SystemConfigConsts;
import io.github.xxyopen.novel.config.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import java.util.Objects;

/**
 * 作家后台认证拦截器：在「已登录」基础上再校验「是否已入驻成为作家」。
 *
 * <p>【与会员中心 AuthInterceptor 的差别】前者只确认账号存在即可；作家专区多一道权限校验：
 * 用 JWT 解出的 userId 去查作家信息缓存，查不到说明还没入驻，抛 {@code USER_UN_AUTH} 拒绝访问。
 * 换句话说，这里同时承担了认证（authentication）和粗粒度授权（authorization）两件事——
 * 更规范的做法是把权限判断拆到注解 / 鉴权框架里，学习项目里合在一起更简单。
 *
 * <p>【为什么同时写 authorId 和 userId】作家也是平台用户：authorId 用于稿件、收入归属，
 * userId 用于账号身份，后续业务两个都要用，所以都放进 {@code UserHolder}。
 *
 * <p>【边界】校验失败统一抛 {@code BusinessException} 交给 CommonExceptionHandler 转响应；
 * 请求结束必须在 afterCompletion 里 clear()，防止线程复用导致用户 / 作家身份串号。
 *
 * @author xiongxiaoyang
 * @date 2022/5/18
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthorInfoCacheManager authorInfoCacheManager;

    /**
     * 进入 Controller 前：验 JWT → 取 userId → 校验作家身份 → 写入登录上下文。
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
            // token 解析失败
            throw new BusinessException(ErrorCodeEnum.USER_LOGIN_EXPIRED);
        }
        // 作家权限认证
        AuthorInfoDto authorInfo = authorInfoCacheManager.getAuthor(userId);
        if (Objects.isNull(authorInfo)) {
            // 作家账号不存在，无权访问作家专区
            throw new BusinessException(ErrorCodeEnum.USER_UN_AUTH);
        }

        // 设置作家ID到当前线程
        UserHolder.setAuthorId(authorInfo.getId());
        // 设置 userId 到当前线程
        UserHolder.setUserId(userId);
        return HandlerInterceptor.super.preHandle(request, response, handler);
    }

    /**
     * handler 执行后调用，出现异常不调用；本拦截器无需后置处理。
     */
    @SuppressWarnings("NullableProblems")
    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
        ModelAndView modelAndView) throws Exception {
        HandlerInterceptor.super.postHandle(request, response, handler, modelAndView);
    }

    /**
     * 请求结束后（异常也执行）清理 ThreadLocal，避免线程复用造成的身份串号。
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
