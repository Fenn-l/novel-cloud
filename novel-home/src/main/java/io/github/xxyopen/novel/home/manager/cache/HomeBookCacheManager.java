package io.github.xxyopen.novel.home.manager.cache;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.xxyopen.novel.book.dto.resp.BookInfoRespDto;
import io.github.xxyopen.novel.common.constant.CacheConsts;
import io.github.xxyopen.novel.common.constant.DatabaseConsts;
import io.github.xxyopen.novel.home.dao.entity.HomeBook;
import io.github.xxyopen.novel.home.dao.mapper.HomeBookMapper;
import io.github.xxyopen.novel.home.dto.resp.HomeBookRespDto;
import io.github.xxyopen.novel.home.manager.feign.BookFeignManager;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 首页推荐小说 缓存管理类。
 *
 * <p>职责：负责首页推荐位的拼装与缓存。首页推荐表（home_book）为了和书籍数据解耦，
 * 只存 book_id、展示类型与排序，不冗余书名、封面、作者、简介——这些内容归 book 微服务
 * 所有。因此拿到推荐位后，要用 Feign 按 bookId 列表「批量」回查 book 服务，再在内存里
 * 按 id 把两份数据 join 成前端要的 HomeBookRespDto。
 *
 * <p>设计取舍：这是典型的「跨服务内存 join」。一次 Feign 批量查询拿回全部书籍详情，
 * 用 Map 做 O(1) 匹配，避免对每个推荐位都发一次远程调用（N+1）；代价是首页展示的
 * 书名/封面相对 book 库最多滞后一个缓存周期，对运营推荐这种弱实时场景完全可接受，
 * 换来的是首页接口不随推荐位数量线性放大远程调用次数。
 *
 * <p>缓存取舍：推荐位变动极少，用 Caffeine 本地缓存 24 小时挡住首页流量；但 Feign
 * 降级或 book 服务无数据时会返回空列表，若把空列表也缓存一整天，首页会「假空」直到
 * 过期为止。所以 @Cacheable 用 unless 把 null / 空结果挡在缓存之外，让下次请求能立即
 * 重新回源。需要强制刷新时（后台改了推荐位）走 {@link #evictCache()}。
 *
 * @author xiongxiaoyang
 * @date 2022/5/12
 */
@Component
@RequiredArgsConstructor
public class HomeBookCacheManager {

    private final HomeBookMapper homeBookMapper;

    private final BookFeignManager bookFeignManager;

    /**
     * 查询首页小说推荐列表并放入缓存（空结果不缓存）。
     *
     * <p>关键步骤：① 按 sort 升序读出推荐位；② 收集 bookId，一次 Feign 批量回查书籍
     * 详情；③ 详情转 Map，按推荐位原有顺序做内存 join，补出书名/封面/作者/简介；
     * ④ 取不到详情的推荐位直接丢弃。
     *
     * <p>边界与取舍：
     * <ul>
     *   <li>Feign 失败时 BookFeignManager 返回空列表，此时返回空列表，且不被缓存；</li>
     *   <li>推荐表里的 bookId 可能已被删除/下架，Feign 查不回来，bookInfoMap.get 会是
     *       null。这里在 map 中显式判空、交给 filter 丢弃该推荐位，既避免空指针，也避免
     *       首页出现一条「只有 id 没有内容」的坏数据；</li>
     *   <li>返回顺序以推荐表的 sort 为准，不依赖 Map 的遍历顺序。</li>
     * </ul>
     *
     * @return 推荐位列表；无数据或降级时返回空列表
     */
    @Cacheable(cacheManager = CacheConsts.CAFFEINE_CACHE_MANAGER,
        value = CacheConsts.HOME_BOOK_CACHE_NAME,
        unless = "#result == null or #result.isEmpty()")
    public List<HomeBookRespDto> listHomeBooks() {
        // 从首页小说推荐表中查询出需要推荐的小说（按运营配置的 sort 升序）
        QueryWrapper<HomeBook> queryWrapper = new QueryWrapper<>();
        queryWrapper.orderByAsc(DatabaseConsts.CommonColumnEnum.SORT.getName());
        List<HomeBook> homeBooks = homeBookMapper.selectList(queryWrapper);

        // 获取推荐小说ID列表
        if (!CollectionUtils.isEmpty(homeBooks)) {
            List<Long> bookIds = homeBooks.stream()
                .map(HomeBook::getBookId)
                .toList();

            // 一次批量回查 book 服务的书籍详情，替代逐个 id 远程调用（避免 N+1）
            List<BookInfoRespDto> bookInfos = bookFeignManager.listBookInfoByIds(bookIds);

            // 组装 HomeBookRespDto 列表数据并返回
            if (!CollectionUtils.isEmpty(bookInfos)) {
                Map<Long, BookInfoRespDto> bookInfoMap = bookInfos.stream()
                    .collect(Collectors.toMap(BookInfoRespDto::getId, Function.identity()));
                return homeBooks.stream().map(v -> {
                    BookInfoRespDto bookInfo = bookInfoMap.get(v.getBookId());
                    if (bookInfo == null) {
                        // 推荐位指向的书查不回来（已删除/降级漏数据），返回 null 交给下游 filter 丢弃；
                        // 不能直接 .get 之后取字段，否则 NPE
                        return null;
                    }
                    HomeBookRespDto bookRespDto = new HomeBookRespDto();
                    bookRespDto.setType(v.getType());
                    bookRespDto.setBookId(v.getBookId());
                    bookRespDto.setBookName(bookInfo.getBookName());
                    bookRespDto.setPicUrl(bookInfo.getPicUrl());
                    bookRespDto.setAuthorName(bookInfo.getAuthorName());
                    bookRespDto.setBookDesc(bookInfo.getBookDesc());
                    return bookRespDto;
                }).filter(Objects::nonNull).toList();

            }

        }

        return Collections.emptyList();
    }

    /**
     * 清空首页推荐缓存（后台增删改推荐位后调用）。
     *
     * <p>方法体为空即可：@CacheEvict 由 Spring AOP 在方法调用时完成清除，缓存名与
     * listHomeBooks 保持一致，下次访问自然重新加载最新数据。
     */
    @CacheEvict(cacheManager = CacheConsts.CAFFEINE_CACHE_MANAGER,
        value = CacheConsts.HOME_BOOK_CACHE_NAME)
    public void evictCache(){

    }

}
