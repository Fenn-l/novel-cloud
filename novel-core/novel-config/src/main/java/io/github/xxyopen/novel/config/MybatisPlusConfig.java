package io.github.xxyopen.novel.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置：注册分页插件。
 *
 * <p>【为什么必须有它】MyBatis-Plus 的 {@code selectPage} 本身不会自动加 LIMIT，
 * 全靠 {@code PaginationInnerInterceptor} 拦截 SQL：自动改写成分页语句，并额外发一条 count
 * 查询算出总记录数，才能填出 PageRespDto 里的 total / pages。不注册这个插件，
 * 「分页」会退化成一次性把整张表查回内存，数据量大时直接拖垮服务。
 *
 * <p>【边界】{@code DbType.MYSQL} 指定数据库方言，决定分页 SQL 用 LIMIT 还是 ROWNUM / OFFSET；
 * 换数据库或做多数据源时必须同步调整。插件是全局生效的，
 * 若个别查询不想被 count，需要在 Mapper 层单独处理，这里不做细粒度开关。
 *
 * @author xiongxiaoyang
 * @date 2022/5/16
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 分页插件：以 MySQL 方言注册到 MyBatis-Plus 拦截器链。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

}
