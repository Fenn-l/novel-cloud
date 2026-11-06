package io.github.xxyopen.novel.config.aspect;

import io.github.xxyopen.novel.config.annotation.Key;
import io.github.xxyopen.novel.config.annotation.Lock;
import io.github.xxyopen.novel.config.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁切面：拦截标注 {@link Lock} 的方法，用 Redisson 保证多实例部署下的互斥。
 *
 * <p>【锁 key 怎么拼】格式固定为 {@code Lock::prefix::<SpEL 求值结果>}：
 * 前缀 {@code Lock} 做命名空间隔离，{@code prefix} 区分不同业务方法，
 * 再逐个把标注了 {@link Key} 的方法参数用 SpEL 求值后拼进去（如 userId、bookId），
 * 从而把锁粒度收敛到「某用户对某本书」这种业务维度，而不是一刀切锁住整个接口。
 * {@code TemplateParserContext} 让 expr 可以写成 {@code #{xxx}} 模板，以参数对象为求值根对象；
 * expr 为空时退化成直接取 {@code arg.toString()}。
 * 注意：当前实现里没标 {@code @Key} 的参数只会追加一个分隔符占位、不贡献 key 值，
 * 所以加不加注解要按业务维度想清楚，否则锁键可能退化、误伤并发。
 *
 * <p>【为什么默认 tryLock 快速失败】{@code isWait=false} 时调用无参 {@code tryLock()}，
 * 拿不到锁立即抛 {@code BusinessException(failCode)}，不排队等待。
 * 这类接口（发评论、发布）本就是低频写操作，用户连点/网络重试才是并发主因，
 * 快速失败能直接告诉用户「别重复提交」，比让请求堆积等待更省心、也避免线程被拖住引发雪崩。
 * 只有确实需要串行的场景才把 {@code isWait} 打开并给一个有限的 {@code waitTime}。
 *
 * <p>【为什么只用在「先查后写」场景】分布式锁靠 Redis 串行化，每次都有网络往返，
 * 套在纯查询或高并发读路径上纯属浪费吞吐。它解决的是「检查与写入之间存在竞态窗口」：
 * 先查"是否已评论/是否已存在"，再插入，两个动作之间别的请求可能插进来。
 * 对于天然幂等的追加操作，用数据库唯一索引 + 兜底异常处理更轻；
 * 锁是入口的快速失败优化，数据库唯一索引仍是最后一道防线（两者配合，见 CommonExceptionHandler）。
 *
 * <p>【unlock 为什么要判 isHeldByCurrentThread】Redisson 默认看门狗会自动续期，
 * 但若业务执行时间超过锁租期、或显式设了 leaseTime，锁会被 Redis 自动释放并被别的线程抢到。
 * 此时当前线程再 unlock，轻则抛 {@code IllegalMonitorStateException}，重则释放掉别人的锁。
 * 先用 {@code isHeldByCurrentThread()} 确认「锁还归我」再释放，是防御性写法，必须放在 finally 里保证执行。
 *
 * @author xiongxiaoyang
 * @date 2022/6/20
 */
@Aspect
// 必须比事务切面更外层：发布章节同时标了 @Lock 与 @Transactional。
// 若事务在外层，unlock 会发生在事务提交之前，另一个请求可能拿到锁却读到未提交的旧章节号。
// HIGHEST_PRECEDENCE 保证「先加锁 → 再开事务 → 提交 → 最后释放锁」。
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
@RequiredArgsConstructor
public class LockAspect {

    private final RedissonClient redissonClient;

    private static final String KEY_PREFIX = "Lock";

    private static final String KEY_SEPARATOR = "::";

    @Around(value = "@annotation(io.github.xxyopen.novel.config.annotation.Lock)")
    @SneakyThrows
    public Object doAround(ProceedingJoinPoint joinPoint) {
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        Method targetMethod = methodSignature.getMethod();
        Lock lock = targetMethod.getAnnotation(Lock.class);
        String lockKey = KEY_PREFIX + buildLockKey(lock.prefix(), targetMethod,
            joinPoint.getArgs());
        RLock rLock = redissonClient.getLock(lockKey);
        if (lock.isWait() ? rLock.tryLock(lock.waitTime(), TimeUnit.SECONDS) : rLock.tryLock()) {
            try {
                return joinPoint.proceed();
            } finally {
                // 只释放仍由当前线程持有的锁：锁超时被自动释放后，这里再 unlock 会抛异常或误放别人的锁
                if (rLock.isHeldByCurrentThread()) {
                    rLock.unlock();
                }
            }
        }
        throw new BusinessException(lock.failCode());
    }

    /**
     * 拼装锁 key：{@code ::prefix} + 每个 {@code @Key} 参数的 {@code ::SpEL结果}。
     * 未标注 {@code @Key} 的参数仅追加分隔符占位，不参与 key 计算。
     */
    private String buildLockKey(String prefix, Method method, Object[] args) {
        StringBuilder builder = new StringBuilder();
        if (StringUtils.hasText(prefix)) {
            builder.append(KEY_SEPARATOR).append(prefix);
        }
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            builder.append(KEY_SEPARATOR);
            if (parameters[i].isAnnotationPresent(Key.class)) {
                Key key = parameters[i].getAnnotation(Key.class);
                builder.append(parseKeyExpr(key.expr(), args[i]));
            }
        }
        return builder.toString();
    }

    /**
     * 以方法参数为根对象求值 SpEL；expr 为空时直接取参数字符串，避免无谓的表达式解析。
     */
    private String parseKeyExpr(String expr, Object arg) {
        if (!StringUtils.hasText(expr)) {
            return arg.toString();
        }
        ExpressionParser parser = new SpelExpressionParser();
        Expression expression = parser.parseExpression(expr, new TemplateParserContext());
        return expression.getValue(arg, String.class);
    }

}
