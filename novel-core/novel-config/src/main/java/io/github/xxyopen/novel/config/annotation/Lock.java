package io.github.xxyopen.novel.config.annotation;

import io.github.xxyopen.novel.common.constant.ErrorCodeEnum;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * 分布式锁注解：标注在「先查后写」的并发敏感方法上，由 {@code LockAspect} 切面负责加锁。
 *
 * <p>【业务场景】发评论、发布章节、用户注册这类操作，都要先查询校验（是否已评论 /
 * 是否已存在 / 是否重名）再写入。单靠数据库唯一索引只能事后报错，
 * 用注解式分布式锁把「同一业务键」的并发请求串行化，可以在入口处快速失败，
 * 既减少无效写库，也挡住用户连点造成的重复提交。
 *
 * <p>【各属性含义】
 * - prefix：锁 key 的业务前缀，区分不同方法，避免不同接口互相误锁；
 * - isWait / waitTime：是否等待抢锁及最长等待秒数，默认不等待、立即失败（防刷、防雪崩）；
 * - failCode：抢锁失败时抛出 {@code BusinessException} 携带的业务错误码。
 *
 * <p>【为什么默认值必须是 USER_REQ_MANY 而不是 OK】抢锁失败意味着「同一时刻已有相同请求在处理」，
 * 本质是一次被拒绝的重复提交，必须返回明确的失败码让前端提示「操作太频繁，请稍后再试」。
 * 早期这里误设为 {@link ErrorCodeEnum#OK}，会导致响应码是 00000（成功）却没有任何业务数据，
 * 前端按成功处理、用户以为操作已完成，是一个隐蔽且危险的缺陷：
 * 成功码只能由真正执行完业务逻辑的路径返回。
 *
 * @author xiongxiaoyang
 * @date 2022/6/20
 */
@Documented
@Retention(RUNTIME)
@Target(METHOD)
public @interface Lock {

    String prefix();

    boolean isWait() default false;

    long waitTime() default 3L;

    ErrorCodeEnum failCode() default ErrorCodeEnum.USER_REQ_MANY;

}
