package io.github.xxyopen.novel.book.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.xxyopen.novel.book.dao.entity.BookInfo;
import org.apache.ibatis.annotations.Param;

/**
 * 小说信息 Mapper。
 *
 * <p>说明：搜索能力已完全交给 novel-search 服务的 Elasticsearch（支持分词、权重、
 * 深分页），因此原先这里基于 {@code book_info} 的 {@code searchBooks()} SQL
 * 没有任何调用方，且带 {@code order by ${condition.sort}} 的拼接注入点，已整体删除。
 *
 * <p>现在只保留一个点击量原子自增方法：用 {@code visit_count = visit_count + 1}
 * 在数据库层完成并发累加，避免「读-改-写」造成计数丢失。
 *
 * @author xiongxiaoyang
 * @date 2022/05/11
 */
public interface BookInfoMapper extends BaseMapper<BookInfo> {

    /**
     * 增加小说点击量（DB 原子自增，幂等性由调用方保证，不回写缓存）
     *
     * @param bookId 小说ID
     */
    void addVisitCount(@Param("bookId") Long bookId);

}
