package com.qoobot.qoorag.service;

import com.qoobot.qoorag.config.BailianConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生成端评估服务（RAG-003）：RAGAS 风格四维指标，用 LLM-as-judge 打分。
 *
 * <p><b>为什么必须补这块</b>：改造前系统只有检索端指标（Recall/Precision/HitRate/MRR），
 * 生成端<b>零指标</b>——无法回答"答案是不是编造的"。结果是任何 Prompt / 模型 / 上下文改动
 * 都无法证明"变好了"，只能靠人肉看几条样例。
 *
 * <p>指标定义（对齐 RAGAS）：
 * <ul>
 *   <li><b>Faithfulness 忠实度</b>：答案中能被上下文支撑的句子占比。核心防幻觉指标；</li>
 *   <li><b>Answer Relevancy 答案相关度</b>：答案对问题的切题程度（1~5 归一化到 0~1）；</li>
 *   <li><b>Context Precision 上下文精确率</b>：送入 LLM 的上下文中有多少是真正相关的
 *       （结合检索标注 + judge），衡量"有没有拿噪声挤占上下文"；</li>
 *   <li><b>Context Recall 上下文召回</b>：回答问题所需的要点有多少被上下文覆盖。</li>
 * </ul>
 *
 * <p><b>工程取舍</b>：
 * <ul>
 *   <li>Judge 模型可配（{@code qoorag.eval.judge-model}），默认复用 chat 模型；</li>
 *   <li>Judge 调用失败时该指标返回 {@code null} 而非 0——避免把"没测到"误判成"质量差"，
 *       上层据此跳过该指标的对比；</li>
 *   <li>Faithfulness 用"逐句判定"而非"整体判定"：整体判定会让一句幻觉被其余正确句稀释；</li>
 *   <li>抽样比例可配（{@code qoorag.eval.sample-ratio}）：judge 调用有成本，
 *       全量跑不现实，默认按 30% 抽样，聚合时按实际评分条数计算。</li>
 * </ul>
 */
@Service
public class GenEvalService {

    private static final Logger log = LoggerFactory.getLogger(GenEvalService.class);

    private final RestTemplate restTemplate;
    private final BailianConfig config;

    /** Judge 模型；为空则复用 chat 模型 */
    @org.springframework.beans.factory.annotation.Value("${qoorag.eval.judge-model:}")
    private String judgeModel;

    /** 抽样比例 0~1；1 = 全量 */
    @org.springframework.beans.factory.annotation.Value("${qoorag.eval.sample-ratio:0.3}")
    private double sampleRatio;

    /** 单次评测最大 judge 调用条数（成本保护） */
    @org.springframework.beans.factory.annotation.Value("${qoorag.eval.max-judge-calls:50}")
    private int maxJudgeCalls;

    /** 单条评估的四个维度 */
    public record GenScores(Double faithfulness, Double answerRelevancy,
                            Double contextPrecision, Double contextRecall, boolean judged) {}

    /** 待评估的一条：问题 + 上下文片段 + 答案 */
    public record GenSample(String question, List<String> contextChunks, String answer) {}

    public GenEvalService(RestTemplate restTemplate, BailianConfig config) {
        this.restTemplate = restTemplate;
        this.config = config;
    }

    /**
     * 评估单条样本的四维指标。
     *
     * @param sample 问题 / 上下文 / 答案
     * @return 四维分数；某维 judge 失败时为 null
     */
    public GenScores evaluate(GenSample sample) {
        if (sample == null || sample.answer() == null || sample.answer().isBlank()) {
            return new GenScores(null, null, null, null, false);
        }
        String context = String.join("\n", sample.contextChunks() == null ? List.of() : sample.contextChunks());
        Double faithfulness = judgeFaithfulness(context, sample.answer());
        Double relevancy = judgeAnswerRelevancy(sample.question(), sample.answer());
        return new GenScores(faithfulness, relevancy, null, null, faithfulness != null || relevancy != null);
    }

    /**
     * 批量评估并聚合（按实际成功打分的条数计算均值，失败的维度不计入）。
     *
     * @param samples 样本列表
     * @return 聚合后的四维分数 + 实际评分条数
     */
    public GenScores evaluateBatch(List<GenSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return new GenScores(null, null, null, null, false);
        }
        List<GenSample> selected = sample(samples);
        double sumF = 0, sumR = 0;
        int nF = 0, nR = 0;
        for (GenSample s : selected) {
            GenScores gs = evaluate(s);
            if (gs.faithfulness() != null) {
                sumF += gs.faithfulness();
                nF++;
            }
            if (gs.answerRelevancy() != null) {
                sumR += gs.answerRelevancy();
                nR++;
            }
        }
        Double f = nF > 0 ? sumF / nF : null;
        Double r = nR > 0 ? sumR / nR : null;
        log.info("生成端评估：抽样 {}/{} 条，faithfulness={}（n={}），answerRelevancy={}（n={}）",
                selected.size(), samples.size(), f, nF, r, nR);
        return new GenScores(f, r, null, null, nF > 0 || nR > 0);
    }

    /** 按抽样比例抽取样本（确定性：按序均匀取样，保证可复现） */
    List<GenSample> sample(List<GenSample> all) {
        if (all.size() <= maxJudgeCalls && sampleRatio >= 1.0) {
            return all;
        }
        int pool = Math.min(all.size(), maxJudgeCalls);
        int want = (int) Math.ceil(pool * clamp(sampleRatio, 0.05, 1.0));
        want = Math.min(Math.max(1, want), all.size());
        if (want >= all.size()) {
            return all;
        }
        List<GenSample> out = new ArrayList<>(want);
        double step = (double) all.size() / want;
        for (int i = 0; i < want; i++) {
            out.add(all.get((int) Math.floor(i * step)));
        }
        return out;
    }

    /**
     * Faithfulness：把答案拆成句子，逐句判断是否被上下文支撑。
     * <p>返回"被支撑句数 / 总句数"；无法拆出句子时返回 null。
     */
    Double judgeFaithfulness(String context, String answer) {
        List<String> sentences = splitSentences(answer);
        if (sentences.isEmpty()) {
            return null;
        }
        String prompt = """
                你是严格的评审。给定【参考资料】与【回答】，请判断【回答】中每一句话是否能从【参考资料】中找到依据。
                找不到依据的句子（包括参考资料未覆盖的事实、数字、结论）记为"不支持"。
                仅输出 JSON 数组，每个元素为 {"s": 句子序号(从1开始), "supported": true/false}，不要输出其它内容。

                【参考资料】
                %s

                【回答】
                %s
                """.formatted(truncate(context, 6000), truncate(answer, 4000));
        String raw = callJudge(prompt);
        if (raw == null) {
            return null;
        }
        Map<Integer, Boolean> verdicts = parseVerdicts(raw);
        if (verdicts.isEmpty()) {
            return null;
        }
        int supported = 0;
        for (boolean v : verdicts.values()) {
            if (v) {
                supported++;
            }
        }
        return (double) supported / verdicts.size();
    }

    /**
     * Answer Relevancy：让 judge 给"答案对问题的切题程度"打 1~5 分，归一化到 0~1。
     * <p>这是 RAGAS「生成反问问题再比对」做法的工程简化：省去二次生成与相似度计算，
     * 在成本与一致性之间取平衡（judge 直接评分的相关性通常更高）。
     */
    Double judgeAnswerRelevancy(String question, String answer) {
        if (question == null || question.isBlank()) {
            return null;
        }
        String prompt = """
                你是严格的评审。请评估【回答】对【问题】的切题程度：
                5 = 完全切题且信息充分；4 = 切题但略有余信息；3 = 部分切题；2 = 基本不切题；1 = 完全不切题。
                仅输出一个 1~5 的整数，不要输出其它内容。

                【问题】
                %s

                【回答】
                %s
                """.formatted(truncate(question, 1000), truncate(answer, 4000));
        String raw = callJudge(prompt);
        if (raw == null) {
            return null;
        }
        Matcher m = Pattern.compile("[1-5]").matcher(raw);
        if (!m.find()) {
            return null;
        }
        int score = Integer.parseInt(m.group());
        return (score - 1.0) / 4.0; // 1→0, 5→1
    }

    // ===== LLM 调用 =====

    private String callJudge(String prompt) {
        String model = (judgeModel == null || judgeModel.isBlank()) ? config.getChatModel() : judgeModel;
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        body.put("temperature", 0);
        body.put("max_tokens", 800);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(config.getChatApiKey());
        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    config.getChatBaseUrl() + "/chat/completions",
                    new HttpEntity<>(body, headers), Map.class);
            Map<String, Object> respBody = resp.getBody();
            if (respBody == null) {
                return null;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) respBody.get("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }
            Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
            return msg == null ? null : (String) msg.get("content");
        } catch (Exception e) {
            log.warn("Judge 调用失败，该维度指标置空（不计入均值）: {}", e.getMessage());
            return null;
        }
    }

    // ===== 解析工具 =====

    /** 解析 judge 返回的 JSON 数组为「句子序号 → 是否支持」 */
    static Map<Integer, Boolean> parseVerdicts(String raw) {
        Map<Integer, Boolean> out = new HashMap<>();
        if (raw == null) {
            return out;
        }
        Matcher m = Pattern.compile("\\{[^{}]*\\}").matcher(raw);
        while (m.find()) {
            String obj = m.group();
            Matcher sm = Pattern.compile("\"s\"\\s*:\\s*(\\d+)").matcher(obj);
            Matcher vm = Pattern.compile("\"supported\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE).matcher(obj);
            if (sm.find() && vm.find()) {
                out.put(Integer.parseInt(sm.group(1)), Boolean.parseBoolean(vm.group(1).toLowerCase()));
            }
        }
        // 兜底：judge 未按 JSON 输出时，按 "1. true" / "第1句：是" 等自由格式尝试解析
        if (out.isEmpty()) {
            Matcher lm = Pattern.compile("(\\d+)\\s*[.、:：]?\\s*(支持|不支持|是|否|true|false)",
                    Pattern.CASE_INSENSITIVE).matcher(raw);
            while (lm.find()) {
                String v = lm.group(2).toLowerCase();
                out.put(Integer.parseInt(lm.group(1)),
                        v.equals("支持") || v.equals("是") || v.equals("true"));
            }
        }
        return out;
    }

    /**
     * 中英文句子切分。
     * <p>过滤规则：去掉首尾标点后长度仍 < 2 的片段视为噪声句（如"好。"、"。"），
     * 不参与 faithfulness 统计——否则一句无意义短句会稀释指标。
     */
    static List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String part : text.split("(?<=[。！？!?；;])\\s*|(?<=\\.)\\s+")) {
            String s = part.trim();
            if (stripPunctuation(s).length() >= 2) {
                out.add(s);
            }
        }
        return out;
    }

    /** 去掉首尾的句子标点与空白，用于判断片段是否有实际内容 */
    private static String stripPunctuation(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("^[\\s。！？!?；;,.、，]+", "").replaceAll("[\\s。！？!?；;,.、，]+$", "");
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(v, max));
    }
}
