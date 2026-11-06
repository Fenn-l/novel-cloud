package io.github.xxyopen.novel.common.json.serializer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;

/**
 * 用户名序列化器：评论 / 书评列表展示时对昵称脱敏。
 *
 * <p>【负责的业务】用户发表的内容会公开展示，昵称属于个人信息，不能原样吐给前端；
 * 通过 Jackson 自定义序列化器在「响应序列化」这一步统一打码，
 * 业务代码仍然持有完整昵称（用于个人中心、@ 提醒等），互不影响，也不用每个 DTO 手动处理。
 * 通常配合字段上的 {@code @JsonSerialize(using = UsernameSerializer.class)} 使用。
 *
 * <p>【修了什么 bug，面试重点】原实现固定写死
 * {@code s.substring(0, 4) + "****" + s.substring(8)}，隐含假设昵称至少 8 个字符：
 * 遇到短昵称（如「小明」）或 null 会直接抛 StringIndexOutOfBoundsException / NPE，
 * 导致整个评论列表接口 500。昵称是用户可自由设置的，长度完全不可控，这是典型的边界缺失。
 * 现改为按长度分档、并对 null 做保护。
 *
 * <p>【脱敏规则与取舍】
 * - null → 输出 JSON null，不编造字符串；
 * - 空串 → 原样返回；
 * - 长度 ≤ 4：只保留首字符 + ****（短昵称本身信息量就少，多留几位等于没脱敏）；
 * - 5 ~ 8 位：保留前 4 + ****，尾部整段隐藏（此时再取尾部会越界，且剩余字符太少不值得留）；
 * - &gt; 8 位：前 4 + **** + 末 4，中间隐藏。尾部只保留末 4 位，
 *   避免长昵称仍泄露十几位字符；相比「保留第 9 位之后全部」更安全。
 * 打码长度是产品与安全之间的取舍：留太少用户认不出自己人，留太多等于没脱敏。
 *
 * @author xiongxiaoyang
 * @date 2022/5/20
 */
public class UsernameSerializer extends JsonSerializer<String> {

    @Override
    public void serialize(String s, JsonGenerator jsonGenerator,
        SerializerProvider serializerProvider) throws IOException {
        // 字段允许为 null（未设置昵称），直接输出 JSON null，避免 NPE
        if (s == null) {
            jsonGenerator.writeNull();
            return;
        }
        int len = s.length();
        if (len == 0) {
            jsonGenerator.writeString(s);
            return;
        }
        String masked;
        if (len <= 4) {
            // 短昵称：只留首字符
            masked = s.substring(0, 1) + "****";
        } else if (len <= 8) {
            // 中等长度：留前 4，尾部整段打码
            masked = s.substring(0, 4) + "****";
        } else {
            // 长昵称：前 4 + **** + 末 4，中间隐藏
            masked = s.substring(0, 4) + "****" + s.substring(len - 4);
        }
        jsonGenerator.writeString(masked);
    }

}
