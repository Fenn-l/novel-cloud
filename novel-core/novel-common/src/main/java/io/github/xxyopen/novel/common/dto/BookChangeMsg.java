package io.github.xxyopen.novel.common.dto;

import java.io.Serializable;

/**
 * 小说信息变更消息体
 * <p>
 * 生产端（novel-book-service）在章节发布/作品信息变更后投递；
 * 消费端（ES 同步、Redis 缓存失效、书架通知）据此处理，
 * 并通过 msgId 做幂等去重（"至少一次投递 + 消费端幂等"）。
 *
 * @author xiongxiaoyang (二次开发)
 */
public class BookChangeMsg implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 消息唯一 ID，用于消费端幂等去重
     */
    private String msgId;

    /**
     * 小说 ID
     */
    private Long bookId;

    /**
     * 变更类型：BOOK_INFO_CHANGE / CHAPTER_CREATED
     */
    private String type;

    /**
     * 消息产生时间戳（毫秒）
     */
    private Long timestamp;

    public BookChangeMsg() {
    }

    public BookChangeMsg(String msgId, Long bookId, String type, Long timestamp) {
        this.msgId = msgId;
        this.bookId = bookId;
        this.type = type;
        this.timestamp = timestamp;
    }

    public String getMsgId() {
        return msgId;
    }

    public void setMsgId(String msgId) {
        this.msgId = msgId;
    }

    public Long getBookId() {
        return bookId;
    }

    public void setBookId(Long bookId) {
        this.bookId = bookId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "BookChangeMsg{msgId='" + msgId + "', bookId=" + bookId + ", type='" + type
            + "', timestamp=" + timestamp + '}';
    }
}
