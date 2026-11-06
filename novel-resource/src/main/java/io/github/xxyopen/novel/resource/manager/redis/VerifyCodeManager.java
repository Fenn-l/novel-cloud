package io.github.xxyopen.novel.resource.manager.redis;

import io.github.xxyopen.novel.common.constant.CacheConsts;
import io.github.xxyopen.novel.resource.util.ImgVerifyCodeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

/**
 * 验证码 管理类（生产侧 / 写入方）。
 *
 * <p>职责：只做一件事——生成验证码图片，并把「答案」以 sessionId 为键写进 Redis。
 * 它是验证码跨服务约定的写入方：图片返回给前端，答案留在 Redis 等 user 服务来读。
 *
 * <p>跨服务约定：Key 固定为 CacheConsts.IMG_VERIFY_CODE_CACHE_KEY + sessionId，TTL
 * 固定 5 分钟。user 服务的同名 VerifyCodeManager 用完全相同的 Key 读取比对、用完即删。
 * 两个服务之间没有 RPC 调用，靠「共享 Redis + 共享常量 + 同一个 sessionId」隐式协作，
 * 因此 Key 拼法与 TTL 必须由写入方单点定义，任一侧单独改动都会导致校验永远失败。
 *
 * <p>设计取舍：验证码不落库、不进 HTTP session。Redis 自带 TTL，5 分钟自动过期，既
 * 限制暴力重试窗口，又省掉定时清理任务；sessionId 由调用方（ResourceServiceImpl）生成，
 * 本类不关心它属于谁，只按 Key 存答案。
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
     * 生成图形验证码并把答案写入 Redis。
     *
     * <p>关键步骤：① 生成 4 位纯数字随机码；② 画成带干扰线的图片、转 Base64 返回前端；
     * ③ 以 IMG_VERIFY_CODE_CACHE_KEY + sessionId 为 Key 把答案存入 Redis，5 分钟过期。
     *
     * <p>边界：只存答案、不存图片；答案明文存放但时效极短、且 user 服务校验后立即删除，
     * 可重放窗口很小。返回 Base64 图片而非 URL，前端可直接用 img 标签渲染。
     *
     * @param sessionId 本次验证码的会话标识，由调用方生成并回传前端
     * @return Base64 编码的验证码图片
     * @throws IOException 验证码图片生成失败
     */
    public String genImgVerifyCode(String sessionId) throws IOException {
        String verifyCode = ImgVerifyCodeUtils.getRandomVerifyCode(4);
        String img = ImgVerifyCodeUtils.genVerifyCodeImg(verifyCode);
        stringRedisTemplate.opsForValue().set(CacheConsts.IMG_VERIFY_CODE_CACHE_KEY + sessionId,
            verifyCode, Duration.ofMinutes(5));
        return img;
    }

}
