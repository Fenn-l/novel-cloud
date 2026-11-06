package io.github.xxyopen.novel.config.wrapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.HashMap;
import java.util.Map;

/**
 * XSS 请求包装器：用装饰器模式覆写取参方法，对参数值做 HTML 字符转义。
 *
 * <p>【为什么这么设计】Servlet 规范里请求参数是不可变的，Filter 无法直接改写；
 * 继承 {@code HttpServletRequestWrapper} 并覆写 {@code getParameterValues}，
 * 再把包装对象传下去，后续 {@code request.getParameterXxx()} 拿到的就是清洗后的值，
 * 对业务代码完全透明。
 *
 * <p>【转义规则与取舍】只把 {@code <} / {@code >} 替换成 {@code &lt;} / {@code &gt;}：
 * 尖括号是构造 {@code <script>} 等 HTML 标签的必要条件，挡住它们能阻断最常见的反射型注入，
 * 同时不会误伤正常文本。这是「够用且副作用小」的轻量方案，不是完整的 HTML 净化。
 *
 * <p>【边界，面试主动讲】
 * 1. 只覆写了 getParameterValues，只覆盖 query / form 参数；JSON 请求体、Header、Cookie 不受影响；
 * 2. getParameter() 等其它取值方法未覆写，是否生效取决于容器内部是否委托到 getParameterValues；
 * 3. 参数不存在时返回空数组而非 null，避免调用方遍历时 NPE（与父类返回 null 的行为不同，是有意的防御）；
 * 4. replaceAll 接收正则，这里 {@code <} {@code >} 不是正则元字符，直接替换是安全的。
 *
 * @author xiongxiaoyang
 * @date 2022/5/17
 */
public class XssHttpServletRequestWrapper extends HttpServletRequestWrapper {

    /** 转义规则表：静态不可变，所有实例共享 */
    private static final Map<String, String> REPLACE_RULE = new HashMap<>();

    static {
        REPLACE_RULE.put("<", "&lt;");
        REPLACE_RULE.put(">", "&gt;");
    }

    public XssHttpServletRequestWrapper(HttpServletRequest request) {
        super(request);
    }

    /**
     * 逐个取出原始参数值，按规则表做替换后返回新数组；不修改原数组，避免污染容器持有的请求对象。
     */
    @Override
    public String[] getParameterValues(String name) {
        String[] values = super.getParameterValues(name);
        if (values != null) {
            int length = values.length;
            String[] escapeValues = new String[length];
            for (int i = 0; i < length; i++) {
                escapeValues[i] = values[i];
                int index = i;
                REPLACE_RULE.forEach(
                    (k, v) -> escapeValues[index] = escapeValues[index].replaceAll(k, v));
            }
            return escapeValues;
        }
        return new String[0];
    }
}
