package io.github.xxyopen.novel.user.manager.redis;

import io.github.xxyopen.novel.common.constant.CacheConsts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 图形验证码 管理类：校验并销毁登录 / 注册前的图片验证码。
 *
 * <p>【为什么验证码放 Redis 而不是 Session】服务是多实例部署，请求可能被负载到任意一台，
 * 存在本地 Session 里换台机器就验不到了；Redis 集中存储、所有实例共享，天然适配无状态服务。
 * 验证码本身由生成接口写入（带 TTL 过期），这里只负责「读出来比对」和「用完删除」。
 *
 * <p>【key 为什么用 sessionId 而不是 userId】验证码出现在登录 / 注册之前，
 * 此时系统还不知道用户是谁，只能靠前端带来的会话标识（sessionId）把「展示给某人的图」
 * 和「他随后提交答案」关联起来；用 userId 根本无从谈起。
 *
 * <p>【一次性，边界要讲】校验通过后业务层必须调用 removeImgVerifyCode 把验证码删掉，
 * 否则同一个验证码可被反复提交（重放攻击 / 暴力爆破）。比对用 {@code Objects.equals} 精确匹配，
 * 大小写是否敏感取决于生成接口写入时的规则，两端要保持一致。
 *
 * @author xiongxiaoyang
 * @date 2022/5/12
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VerifyCodeManager {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 校验图形验证码：按 sessionId 取出 Redis 里的正确答案与用户输入比对。
     * 验证码不存在 / 已过期时 get 返回 null，equals 结果自然是 false，按校验失败处理。
     */
    public boolean imgVerifyCodeOk(String sessionId, String verifyCode) {
        return Objects.equals(stringRedisTemplate.opsForValue()
            .get(CacheConsts.IMG_VERIFY_CODE_CACHE_KEY + sessionId), verifyCode);
    }

    /**
     * 从 Redis 删除验证码，保证一次性。校验通过（或明确失败需作废）后都应调用，
     * 避免同一验证码被重复尝试使用。
     */
    public void removeImgVerifyCode(String sessionId) {
        stringRedisTemplate.delete(CacheConsts.IMG_VERIFY_CODE_CACHE_KEY + sessionId);
    }

}
