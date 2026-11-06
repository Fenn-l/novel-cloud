package io.github.xxyopen.novel.config.exception;

import io.github.xxyopen.novel.common.constant.ErrorCodeEnum;
import io.github.xxyopen.novel.common.resp.RestResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器：把散落在各层的异常统一翻译成 {@link RestResp} 响应结构，
 * 让 Controller 不必写 try-catch，前端也总能拿到固定格式（code / message / data）。
 *
 * <p>【匹配优先级】Spring 按异常类型精确匹配，越具体的处理器越先命中：
 * BindException、BusinessException、DuplicateKeyException 各有一条专属通道，
 * 剩下未预期的异常才落到最下面的 Exception 兜底。因此兜底必须返回系统级错误码，
 * 并且打完整堆栈日志，绝不能把异常信息回显给前端。
 *
 * <p>【为什么要单独接 DuplicateKeyException】注册用户名、评论（用户×书唯一）、
 * 发布小说名等都靠数据库唯一索引做最后一道防重。高并发下即便上层用 {@code @Lock}
 * 串行化了一层，仍可能撞唯一键；若放任不管，会被兜底处理器吞成笼统的 SYSTEM_ERROR，
 * 前端只提示「系统错误」，用户分不清是重复提交还是服务挂了。这里显式翻译，
 * 至少给出一个「用户侧可理解、可重试」的失败信号。
 *
 * @author xiongxiaoyang
 * @date 2022/5/11
 */
@Slf4j
@RestControllerAdvice
public class CommonExceptionHandler {

    /**
     * 参数绑定 / 校验失败：直接告诉前端参数有问题，属于用户侧错误（A 类码）。
     */
    @ExceptionHandler(BindException.class)
    public RestResp<Void> handlerBindException(BindException e) {
        log.error(e.getMessage(), e);
        return RestResp.fail(ErrorCodeEnum.USER_REQUEST_PARAM_ERROR);
    }

    /**
     * 业务异常：错误码由抛出方 {@code BusinessException} 指定并原样透传，
     * 这样各业务模块可以自定义「用户名已存在 / 已评论 / 无权访问」等语义，
     * 通用处理器不必理解具体业务。
     */
    @ExceptionHandler(BusinessException.class)
    public RestResp<Void> handlerBusinessException(BusinessException e) {
        log.error(e.getMessage(), e);
        return RestResp.fail(e.getErrorCodeEnum());
    }

    /**
     * 唯一键冲突：注册、评论、发布等并发写入撞上数据库唯一约束。
     *
     * <p>【取舍】这里统一降级为 {@code USER_REQUEST_PARAM_ERROR}。
     * DuplicateKeyException 只携带「哪条约束冲突」的数据库原始信息，不同库、不同约束的
     * 文案不统一，靠解析异常字符串来细分非常脆弱；而且冲突不一定来自用户名——
     * 评论撞键时若返回「用户名已存在」就是错误提示。因此学习项目里取一个中性的
     * 「请求参数/重复提交」错误码，既不再让前端只看到 SYSTEM_ERROR，也不误导用户。
     * 生产环境更合理的做法：在各 DAO 层按约束名分别映射为 USER_NAME_EXIST、
     * USER_COMMENTED、AUTHOR_BOOK_NAME_EXIST，再由这里统一输出。
     * 只打 warn 不打 error 堆栈：这是可预期的并发冲突，不是系统故障。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public RestResp<Void> handlerDuplicateKeyException(DuplicateKeyException e) {
        log.warn("唯一键冲突:{}", e.getMessage());
        return RestResp.fail(ErrorCodeEnum.USER_REQUEST_PARAM_ERROR);
    }

    /**
     * 兜底：未预期的运行时异常，记完整堆栈并返回系统错误，异常细节绝不回显前端。
     */
    @ExceptionHandler(Exception.class)
    public RestResp<Void> handlerException(Exception e) {
        log.error(e.getMessage(), e);
        return RestResp.error();
    }

}
