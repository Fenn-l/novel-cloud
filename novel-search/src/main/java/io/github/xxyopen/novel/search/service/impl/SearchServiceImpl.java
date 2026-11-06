package io.github.xxyopen.novel.search.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScore;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.TermQuery;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.TotalHits;
import co.elastic.clients.json.JsonData;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import io.github.xxyopen.novel.book.dto.req.BookSearchReqDto;
import io.github.xxyopen.novel.book.dto.resp.BookEsRespDto;
import io.github.xxyopen.novel.book.dto.resp.BookInfoRespDto;
import io.github.xxyopen.novel.common.resp.PageRespDto;
import io.github.xxyopen.novel.common.resp.RestResp;
import io.github.xxyopen.novel.search.constant.EsConsts;
import io.github.xxyopen.novel.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Elasticsearch 检索服务：小说搜索页的唯一实现（MySQL 侧的同名 SQL 已删除）。
 *
 * <p><b>检索设计</b>：
 * <ul>
 *   <li>bool + multi_match：关键词同时匹配书名/作者/简介，权重分别 3 / 2 / 1，
 *       书名命中更重要；再叠加作品方向、分类、字数区间、更新时间等 term/range 过滤；</li>
 *   <li>function_score：在文本相关度之外，用 log1p(点击量)、log1p(字数) 做热度加权，
 *       最终得分 = 文本分 × (1 + 权重和)。取 log 是为了让百万级点击不至于碾压新书；</li>
 *   <li>高亮：书名/作者命中片段包 &lt;em&gt; 标签返回前端。</li>
 * </ul>
 *
 * <p><b>分页设计</b>：
 * 普通分页用 from + size，适合前几页；深分页改用 search_after 游标
 * （排序必须是稳定的 [_score desc, id asc]，否则游标会漏/重数据），
 * 代价是只能"加载更多"、不能随机跳页。两者共用同一套默认排序，便于平滑切换。
 *
 * <p><b>运行前提与边界</b>：
 * book 索引需先手工执行 doc/es/book.http 建好，代码不会自动建索引；
 * ES 不可用或索引缺失时由全局异常处理器返回系统错误，<b>没有降级到 DB 搜索</b>。
 * 数据来自 XXL-JOB 全量同步 + 发布章节后的 MQ 增量同步，因此是最终一致。
 *
 * @author xiongxiaoyang
 * @date 2022/5/23
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchServiceImpl implements SearchService {

    /** 单页上限，防止 pageSize 被传成超大值把 ES 打爆 */
    private static final int MAX_PAGE_SIZE = 50;

    private static final int DEFAULT_PAGE_SIZE = 10;

    private final ElasticsearchClient esClient;

    @SneakyThrows
    @Override
    public RestResp<PageRespDto<BookInfoRespDto>> searchBooks(BookSearchReqDto condition) {
        // 分页参数防御：ES 的 from 不接受负数，pageNum=0 会算出负的 from 直接报错。
        // PageReqDto 里 pageNum/pageSize 是原始 int（默认 1/10），这里再做一次范围收敛。
        int pageNum = Math.max(condition.getPageNum(), 1);
        int pageSize = condition.getPageSize() < 1
            ? DEFAULT_PAGE_SIZE : Math.min(condition.getPageSize(), MAX_PAGE_SIZE);

        boolean useSearchAfter = !StringUtils.isBlank(condition.getSearchAfter());

        SearchResponse<BookEsRespDto> response = esClient.search(s -> {

                SearchRequest.Builder searchBuilder = s.index(EsConsts.BookIndex.INDEX_NAME);
                searchBuilder.query(buildQuery(condition));

                if (useSearchAfter) {
                    // 游标模式：必须用与普通分页默认排序完全一致的稳定排序，
                    // 且忽略用户传入的 sort（否则游标是在另一套排序下生成的，会翻页错乱）
                    searchBuilder.sort(o -> o.score(sc -> sc.order(SortOrder.Desc)));
                    searchBuilder.sort(o -> o.field(f -> f
                        .field(EsConsts.BookIndex.FIELD_ID).order(SortOrder.Asc)));
                    searchBuilder.size(pageSize);
                    searchBuilder.searchAfter(buildSearchAfter(condition.getSearchAfter()));
                } else if (!StringUtils.isBlank(condition.getSort())) {
                    // 用户指定排序字段时走普通分页；字段名做驼峰转换，忽略排序方向
                    // （当前 DTO 只传字段名，方向固定 desc；这是明确的简化）
                    searchBuilder.sort(o -> o.field(f -> f
                        .field(StringUtils.underlineToCamel(condition.getSort().split(" ")[0]))
                        .order(SortOrder.Desc)));
                    searchBuilder.from((pageNum - 1) * pageSize).size(pageSize);
                } else {
                    // 默认稳定排序：相关度降序 + id 升序（id 作为 tie-breaker 保证唯一顺序）
                    searchBuilder.sort(o -> o.score(sc -> sc.order(SortOrder.Desc)));
                    searchBuilder.sort(o -> o.field(f -> f
                        .field(EsConsts.BookIndex.FIELD_ID).order(SortOrder.Asc)));
                    searchBuilder.from((pageNum - 1) * pageSize).size(pageSize);
                }

                searchBuilder.highlight(h -> h.fields(EsConsts.BookIndex.FIELD_BOOK_NAME,
                        t -> t.preTags("<em style='color:red'>").postTags("</em>"))
                    .fields(EsConsts.BookIndex.FIELD_AUTHOR_NAME,
                        t -> t.preTags("<em style='color:red'>").postTags("</em>")));

                return searchBuilder;
            },
            BookEsRespDto.class
        );

        TotalHits total = response.hits().total();
        long totalCount = Objects.isNull(total) ? 0L : total.value();

        List<BookInfoRespDto> list = new ArrayList<>();
        List<Hit<BookEsRespDto>> hits = response.hits().hits();
        for (Hit<BookEsRespDto> hit : hits) {
            BookEsRespDto book = hit.source();
            if (Objects.isNull(book)) {
                continue;
            }
            // 没有命中高亮字段时 highlight() 可能为 null 或 Map 里没有对应 key，都要判空
            Map<String, List<String>> highlight = hit.highlight();
            if (Objects.nonNull(highlight)
                && !CollectionUtils.isEmpty(highlight.get(EsConsts.BookIndex.FIELD_BOOK_NAME))) {
                book.setBookName(highlight.get(EsConsts.BookIndex.FIELD_BOOK_NAME).get(0));
            }
            if (Objects.nonNull(highlight)
                && !CollectionUtils.isEmpty(highlight.get(EsConsts.BookIndex.FIELD_AUTHOR_NAME))) {
                book.setAuthorName(highlight.get(EsConsts.BookIndex.FIELD_AUTHOR_NAME).get(0));
            }
            list.add(BookInfoRespDto.builder()
                .id(book.getId())
                .bookName(book.getBookName())
                .categoryId(book.getCategoryId())
                .categoryName(book.getCategoryName())
                .authorId(book.getAuthorId())
                .authorName(book.getAuthorName())
                .wordCount(book.getWordCount())
                .lastChapterName(book.getLastChapterName())
                .build());
        }

        // 把最后一条的排序值 [_score, id] 拼成下一页游标；前端"加载更多"时原样回传
        if (!hits.isEmpty() && !list.isEmpty()) {
            Hit<BookEsRespDto> lastHit = hits.get(hits.size() - 1);
            list.get(list.size() - 1).setSearchAfter(String.join(",", lastHit.sort()));
        }
        return RestResp.ok(PageRespDto.of(pageNum, pageSize, totalCount, list));
    }

    /**
     * 解析 "score,id" 游标。
     * 格式不合法时抛参数异常，而不是把脏值透传给 ES 后报一个看不懂的 500。
     */
    private List<String> buildSearchAfter(String cursor) {
        String[] parts = cursor.split(",");
        if (parts.length < 2) {
            throw new IllegalArgumentException("searchAfter 游标格式应为 'score,id'");
        }
        List<String> values = new ArrayList<>(2);
        values.add(parts[0].trim());
        values.add(parts[1].trim());
        return values;
    }

    /**
     * 构建 bool 过滤 + function_score 热度加权。
     *
     * <p>isVip / bookStatus 这两个筛选位现在真实参与检索（早期版本 DTO 有字段但这里没消费，
     * 属于"看起来能筛其实不能"）。对应字段在 doc/es/book.http 的 mapping 中已补齐，
     * 旧索引需要先重建才能生效。
     */
    private Query buildQuery(BookSearchReqDto condition) {

        BoolQuery boolQuery = BoolQuery.of(b -> {

            // 只查有正文的书：没有章节的空书不该出现在搜索结果里
            b.must(RangeQuery.of(m -> m
                .field(EsConsts.BookIndex.FIELD_WORD_COUNT)
                .gt(JsonData.of(0))
            )._toQuery());

            if (!StringUtils.isBlank(condition.getKeyword())) {
                b.must(q -> q.multiMatch(t -> t
                    .fields(EsConsts.BookIndex.FIELD_BOOK_NAME + "^3",
                        EsConsts.BookIndex.FIELD_AUTHOR_NAME + "^2",
                        EsConsts.BookIndex.FIELD_BOOK_DESC + "^1")
                    .query(condition.getKeyword())
                ));
            }

            if (Objects.nonNull(condition.getWorkDirection())) {
                b.must(TermQuery.of(m -> m
                    .field(EsConsts.BookIndex.FIELD_WORK_DIRECTION)
                    .value(condition.getWorkDirection())
                )._toQuery());
            }

            if (Objects.nonNull(condition.getCategoryId())) {
                b.must(TermQuery.of(m -> m
                    .field(EsConsts.BookIndex.FIELD_CATEGORY_ID)
                    .value(condition.getCategoryId())
                )._toQuery());
            }

            if (Objects.nonNull(condition.getIsVip())) {
                b.must(TermQuery.of(m -> m
                    .field(EsConsts.BookIndex.FIELD_IS_VIP)
                    .value(condition.getIsVip())
                )._toQuery());
            }

            if (Objects.nonNull(condition.getBookStatus())) {
                b.must(TermQuery.of(m -> m
                    .field(EsConsts.BookIndex.FIELD_BOOK_STATUS)
                    .value(condition.getBookStatus())
                )._toQuery());
            }

            if (Objects.nonNull(condition.getWordCountMin())) {
                b.must(RangeQuery.of(m -> m
                    .field(EsConsts.BookIndex.FIELD_WORD_COUNT)
                    .gte(JsonData.of(condition.getWordCountMin()))
                )._toQuery());
            }

            if (Objects.nonNull(condition.getWordCountMax())) {
                b.must(RangeQuery.of(m -> m
                    .field(EsConsts.BookIndex.FIELD_WORD_COUNT)
                    .lt(JsonData.of(condition.getWordCountMax()))
                )._toQuery());
            }

            if (Objects.nonNull(condition.getUpdateTimeMin())) {
                b.must(RangeQuery.of(m -> m
                    .field(EsConsts.BookIndex.FIELD_LAST_CHAPTER_UPDATE_TIME)
                    .gte(JsonData.of(condition.getUpdateTimeMin().getTime()))
                )._toQuery());
            }

            return b;
        });

        // function_score：最终得分 = 文本相关度 × (1 + 0.5*log1p(点击量) + 0.1*log1p(字数))
        // boostMode=Multiply 保证热度只是"放大"而不会取代文本相关性；
        // missing(0.0) 让缺字段的老文档也能正常参与排序。
        return FunctionScoreQuery.of(fs -> fs
            .query(boolQuery._toQuery())
            .functions(
                FunctionScore.of(f -> f.fieldValueFactor(fv -> fv
                    .field(EsConsts.BookIndex.FIELD_VISIT_COUNT)
                    .factor(0.5).modifier(FieldValueFactorModifier.Log1p).missing(0.0))),
                FunctionScore.of(f -> f.fieldValueFactor(fv -> fv
                    .field(EsConsts.BookIndex.FIELD_WORD_COUNT)
                    .factor(0.1).modifier(FieldValueFactorModifier.Log1p).missing(0.0)))
            )
            .scoreMode(FunctionScoreMode.Sum)
            .boostMode(FunctionBoostMode.Multiply)
        )._toQuery();
    }
}
