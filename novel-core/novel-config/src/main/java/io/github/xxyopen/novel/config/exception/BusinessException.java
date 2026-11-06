package io.github.xxyopen.novel.config.exception;

import io.github.xxyopen.novel.common.constant.ErrorCodeEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 自定义业务异常：携带一个 {@link ErrorCodeEnum}，表示「可预期、能告诉用户哪里错了」的失败。
 *
 * <p>【在链路里的角色】业务层遇到「用户名已存在 / 已评论 / 未登录 / 抢锁失败」时直接抛出，
 * 由 CommonExceptionHandler 捕获并把错误码原样转成 RestResp，Controller 因此不用写 try-catch。
 * 它是「系统异常」和「正常返回」之外的第三条通道，让错误码语义集中在 ErrorCodeEnum 一处维护。
 *
 * <p>【构造器里那个四参 super，面试常问】
 * {@code super(msg, null, false, false)} 最后两个 false 分别是关闭 suppression 和
 * 关闭 fillInStackTrace()（不生成堆栈追踪）。业务异常是高频、可预期的控制流，
 * 每次都采集完整栈帧既浪费 CPU 又把日志刷得全是重复堆栈；关掉后性能更好。
 * 代价是排查时没有调用栈，只能靠错误码 + 消息定位——对「已知业务分支」这个代价完全可接受。
 * 注意 Java 要求构造器间调用必须在第一行，所以这条 super 必须紧挨着方法开头。
 *
 * <p>【Lombok 说明】{@code @Data} 会为普通字段生成 getter/setter，
 * 但 errorCodeEnum 是 final，只生成 getter，保证异常一旦创建错误码就不可变。
 *
 * @author xiongxiaoyang
 * @date 2022/5/11
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class BusinessException extends RuntimeException {

    private final ErrorCodeEnum errorCodeEnum;

    public BusinessException(ErrorCodeEnum errorCodeEnum) {
        // 不调用父类 Throwable 的 fillInStackTrace() 生成栈追踪信息，提高应用性能
        // 构造器之间的调用必须在第一行
        super(errorCodeEnum.getMessage(), null, false, false);
        this.errorCodeEnum = errorCodeEnum;
    }

}
