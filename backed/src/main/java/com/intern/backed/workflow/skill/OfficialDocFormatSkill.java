package com.intern.backed.workflow.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
/**
 * 自定义 Skill：公文格式校验（OfficialDocFormatSkill）。
 * 纯规则引擎、零模型调用：依据《党政机关公文处理工作条例》对生成的公文草稿做确定性校验，
 */
@Slf4j
@Component
public class OfficialDocFormatSkill {
    public record RuleItem(String rule, String name, String severity, boolean passed, String message) {}
    public record FormatReport(boolean passed, int score, List<RuleItem> items) {}
    private static final List<String> DOC_TYPES = List.of(
            "决议", "决定", "命令", "公报", "公告", "通告", "意见", "通知",
            "通报", "报告", "请示", "批复", "议案", "函", "纪要");
    private static final Pattern DATE_PAT = Pattern.compile(
            "(\\d{4}\\s*年\\s*\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日|[二三四五六七八九〇零一十]{2,4}年[一二三四五六七八九十]{1,3}月[一二三四五六七八九十]{1,4}日)");
    private static final Pattern TITLE_END_PUNCT = Pattern.compile("[。！？；，、,.!?;]$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public FormatReport check(String draft) {
        List<RuleItem> items = new ArrayList<>();
        String text = draft == null ? "" : draft.strip();
        // 公文正文常用全角空格（U+3000）缩进，先统一换成普通空格再 strip，避免影响标题/主送机关判定
        String[] lines = text.lines().map(s -> s.replace("\u3000", " ").strip())
                .filter(s -> !s.isBlank()).toArray(String[]::new);
        String title = lines.length > 0 ? lines[0] : "";

        // R1 文种合法：标题必须含 15 种法定文种之一
        boolean hasType = DOC_TYPES.stream().anyMatch(title::contains);
        items.add(new RuleItem("R1", "法定文种", "ERROR", hasType,
                hasType ? "标题包含法定文种" : "标题未包含 15 种法定公文文种之一（通知/报告/请示/函等）"));

        // R2 标题规范：非空、不超过 50 字、末尾不用标点
        boolean titleOk = !title.isBlank() && title.length() <= 50 && !TITLE_END_PUNCT.matcher(title).find();
        items.add(new RuleItem("R2", "标题规范", "ERROR", titleOk,
                titleOk ? "标题长度与标点符合规范" : "标题为空、超过 50 字或以标点结尾"));

        // R3 请示报告不得混用（条例：不得在报告等非请示性公文中夹带请示事项）
        boolean mixed = title.contains("请示") && title.contains("报告");
        items.add(new RuleItem("R3", "请示报告分开", "ERROR", !mixed,
                mixed ? "标题同时出现\u201c请示\u201d与\u201c报告\u201d，请示与报告不得混用" : "未发现请示报告混用"));

        // R4 主送机关：通知/请示/报告/函应有顶格主送机关（第二行以\u201c：\u201d结尾）
        boolean needAddressee = title.contains("通知") || title.contains("请示")
                || title.contains("报告") || title.contains("函");
        boolean hasAddressee = lines.length > 1 && lines[1].endsWith("：") && lines[1].length() <= 60;
        items.add(new RuleItem("R4", "主送机关", "WARN", !needAddressee || hasAddressee,
                !needAddressee ? "该文种不强制主送机关" :
                        hasAddressee ? "已标注主送机关" : "缺少主送机关（标题下一行应顶格写\u201c×××：\u201d）"));

        // R5 结构层次序数：出现\u201c（一）\u201d级时必须先有\u201c一、\u201d级
        boolean hasLevel2 = text.contains("（一）");
        boolean hasLevel1 = Pattern.compile("(?m)^[\\s\u3000]*一、").matcher(text).find();
        items.add(new RuleItem("R5", "层次序数", "WARN", !hasLevel2 || hasLevel1,
                !hasLevel2 || hasLevel1 ? "结构层次序数使用规范"
                        : "使用了\u201c（一）\u201d级序数但缺少上级\u201c一、\u201d级（规范顺序：一、→（一）→1.→（1））"));

        // R6 落款成文日期：文末应有规范日期
        boolean hasDate = DATE_PAT.matcher(text).find();
        items.add(new RuleItem("R6", "成文日期", "ERROR", hasDate,
                hasDate ? "已标注成文日期" : "缺少成文日期（如\u201c2026年11月25日\u201d，用阿拉伯数字标全年月日）"));

        // R7 请示结语：请示类公文应有\u201c妥否，请批示\u201d等期复用语
        boolean isQingshi = title.contains("请示");
        boolean hasClosing = text.contains("妥否") || text.contains("当否")
                || text.contains("请批示") || text.contains("请批复") || text.contains("请审核");
        items.add(new RuleItem("R7", "请示结语", "WARN", !isQingshi || hasClosing,
                !isQingshi ? "非请示文种，不检查结语" :
                        hasClosing ? "请示结语规范" : "请示缺少期复结语（如\u201c妥否，请批示\u201d）"));

        // R8 正文充实度：去掉标题后正文不少于 50 字
        int bodyLen = text.length() - title.length();
        items.add(new RuleItem("R8", "正文完整", "ERROR", bodyLen >= 50,
                bodyLen >= 50 ? "正文内容完整" : "正文过短（不足 50 字），疑似生成不完整"));

        long errors = items.stream().filter(i -> !i.passed() && "ERROR".equals(i.severity())).count();
        long warns = items.stream().filter(i -> !i.passed() && "WARN".equals(i.severity())).count();
        int score = (int) Math.max(0, 100 - errors * 20 - warns * 8);
        return new FormatReport(errors == 0, score, items);
    }

    public String checkAsJson(String draft) {
        try {
            return MAPPER.writeValueAsString(check(draft));
        } catch (Exception e) {
            log.warn("格式校验报告序列化失败: {}", e.getMessage());
            return "{\"passed\":false,\"score\":0,\"items\":[]}";
        }
    }

    /** 供工作流状态里存的轻量 Map 形式 */
    public Map<String, Object> checkAsMap(String draft) {
        FormatReport r = check(draft);
        return Map.of("passed", r.passed(), "score", r.score(),
                "items", r.items().stream().map(i -> Map.of(
                        "rule", i.rule(), "name", i.name(), "severity", i.severity(),
                        "passed", i.passed(), "message", i.message())).toList());
    }
}