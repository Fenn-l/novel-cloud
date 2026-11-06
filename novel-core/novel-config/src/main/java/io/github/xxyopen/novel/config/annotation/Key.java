package io.github.xxyopen.novel.config.annotation;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * 分布式锁的 Key 注解：标记「哪个方法参数参与锁 key 的计算」。
 *
 * <p>【怎么用】配合 {@code @Lock} 使用。LockAspect 拼锁 key 时只扫描方法参数上的 {@code @Key}，
 * 把对应参数按 expr 求值后拼进 key（如 userId、bookId），从而把互斥粒度收敛到业务维度，
 * 而不是把整个接口一把锁锁死。没标 {@code @Key} 的参数不贡献 key 值。
 *
 * <p>【expr 的含义】一段 SpEL，经 {@code TemplateParserContext} 解析，可写成 {@code #{xxx}}，
 * 以该参数对象为求值根；留空时 LockAspect 退化为直接取 {@code arg.toString()}。
 * 运行时保留（RUNTIME）是必须的，切面靠反射读注解。
 *
 * <p>【边界】{@code @Target} 虽声明了 METHOD / FIELD / PARAMETER 三种位置，
 * 但目前只有 LockAspect 检查 PARAMETER 上的 {@code @Key} 会生效，标在别处不会报错但也没有效果。
 *
 * @author xiongxiaoyang
 * @date 2022/6/20
 */
@Documented
@Retention(RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
public @interface Key {

    String expr() default "";

}
