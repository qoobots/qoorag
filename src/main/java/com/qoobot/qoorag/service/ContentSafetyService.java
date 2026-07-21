package com.qoobot.qoorag.service;

/**
 * 内容安全服务（#18）：对入库文档与用户提问做违规内容检测。
 * 实现方在不可用时应当 fail-open 返回 SAFE（不阻断业务）。
 */
public interface ContentSafetyService {

    /** 检测结论 */
    enum Verdict { SAFE, BLOCKED }

    /** 检测一段文本 */
    Result checkText(String text);

    /** 检测结果封装 */
    class Result {
        public final Verdict verdict;
        public final String reason;

        public Result(Verdict verdict, String reason) {
            this.verdict = verdict;
            this.reason = reason;
        }

        public static Result safe() {
            return new Result(Verdict.SAFE, null);
        }

        public static Result blocked(String reason) {
            return new Result(Verdict.BLOCKED, reason);
        }
    }
}
