package io.github.xxyopen.novel.common.auth;

import lombok.experimental.UtilityClass;

/**
 * 登录用户上下文持有类：在当前请求线程内保存 userId / authorId。
 *
 * <p>【为什么用 ThreadLocal 而不是方法参数】登录信息是在拦截器（AuthInterceptor /
 * TokenParseInterceptor）里解析 JWT 得到的，但真正要用它的是很深的 Service / DAO 层。
 * 若靠方法参数一层层往下传，几乎每个业务方法签名都得加一个 userId，污染接口且难以维护。
 * ThreadLocal 提供一个「同一线程内随处可读」的隐式上下文，拦截器写入、业务层直接取，
 * 是 Web 应用里传递登录态的常见做法。
 *
 * <p>【边界与坑，面试一定要讲】
 * 1. 只在同一个线程内有效：一旦跨线程（{@code @Async}、线程池、MQ 消费者、新起线程），
 *    ThreadLocal 里的值不会自动带过去，会读到 null，必须显式传递；
 * 2. 必须在请求结束时 clear()：Tomcat / 线程池会复用线程，若 afterCompletion 不清理，
 *    下一个请求复用到同一线程时会读到上一个用户的 userId，造成串号（严重的越权风险），
 *    同时 ThreadLocal 强引用也会导致类似内存泄漏的问题；
 * 3. 两个 ThreadLocal 都是 static（{@code @UtilityClass} 全静态），
 *    「全局一份、按线程隔离」，不是每个用户一份对象。
 *
 * <p>【为什么作家端同时存 authorId 和 userId】作家本身也是平台用户：
 * authorId 用于稿件、收入等业务归属，userId 用于身份与账号体系，两者维度不同，都要保留。
 *
 * @author xiongxiaoyang
 * @date 2022/5/18
 */
@UtilityClass
public class UserHolder {

    /** 当前线程的用户ID */
    private static final ThreadLocal<Long> userIdTL = new ThreadLocal<>();

    /** 当前线程的作家ID（仅作家专区登录后有值） */
    private static final ThreadLocal<Long> authorIdTL = new ThreadLocal<>();

    public void setUserId(Long userId) {
        userIdTL.set(userId);
    }

    public Long getUserId() {
        return userIdTL.get();
    }

    public void setAuthorId(Long authorId) {
        authorIdTL.set(authorId);
    }

    public Long getAuthorId() {
        return authorIdTL.get();
    }

    /**
     * 清理当前线程的两个上下文。由各拦截器的 afterCompletion 调用——
     * 该方法即使请求抛异常也会执行，正好保证异常路径下也能清空，避免线程复用串号。
     */
    public void clear() {
        userIdTL.remove();
        authorIdTL.remove();
    }

}
