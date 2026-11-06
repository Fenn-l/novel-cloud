package io.github.xxyopen.novel.book.manager.cache;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.xxyopen.novel.book.dao.entity.BookCategory;
import io.github.xxyopen.novel.book.dao.mapper.BookCategoryMapper;
import io.github.xxyopen.novel.book.dto.resp.BookCategoryRespDto;
import io.github.xxyopen.novel.common.constant.CacheConsts;
import io.github.xxyopen.novel.common.constant.DatabaseConsts;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 小说分类 缓存管理类：按作品方向（workDirection，男频 / 女频）缓存分类列表。
 *
 * <p>【为什么 ttl = 0 永不失效】分类是几乎不变的字典数据，查一次就能一直用，
 * 所以 CacheConsts 里 BOOK_CATEGORY_LIST_CACHE 配成 ttl=0、本地 Caffeine、maxSize=2
 * （正好两个方向各一条）。省去了过期重建的开销。
 *
 * <p>【代价，要主动讲】ttl=0 在两级缓存里都表示「永不失效」，且分类没有任何 @CacheEvict
 * 失效入口。一旦后台新增 / 改名分类，已启动的实例不会感知，只能重启服务或手动清 Redis / 本地缓存。
 * 这是典型的「用一致性换取零维护成本」取舍，只适用于真正不变的字典数据；
 * 友情链接缓存就是因为同样踩了这个坑，后来被改成 24h 兜底过期。
 *
 * @author xiongxiaoyang
 * @date 2022/5/12
 */
@Component
@RequiredArgsConstructor
public class BookCategoryCacheManager {

    private final BookCategoryMapper bookCategoryMapper;

    /**
     * 按作品方向查询分类列表并缓存，缓存 key 即 workDirection。
     * 只返回 id / name 两个字段，前端用于渲染分类筛选下拉框，不暴露排序、状态等内部列。
     */
    @Cacheable(cacheManager = CacheConsts.CAFFEINE_CACHE_MANAGER,
        value = CacheConsts.BOOK_CATEGORY_LIST_CACHE_NAME)
    public List<BookCategoryRespDto> listCategory(Integer workDirection) {
        QueryWrapper<BookCategory> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(DatabaseConsts.BookCategoryTable.COLUMN_WORK_DIRECTION, workDirection);
        return bookCategoryMapper.selectList(queryWrapper).stream().map(v ->
            BookCategoryRespDto.builder()
                .id(v.getId())
                .name(v.getName())
                .build()).toList();
    }

}
