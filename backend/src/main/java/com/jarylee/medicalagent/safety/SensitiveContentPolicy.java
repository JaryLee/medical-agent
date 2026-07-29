package com.jarylee.medicalagent.safety;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class SensitiveContentPolicy {
    private static final List<Rule> RULES = List.of(
            new Rule("CHINESE_ID", Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)")),
            new Rule("MOBILE_PHONE", Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)")),
            new Rule("EMAIL_ADDRESS", Pattern.compile(
                    "(?i)(?<![\\w.+-])[\\w.+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![\\w.-])")),
            new Rule("PATIENT_NAME", Pattern.compile(
                    "(?:患者|病人)?姓名\\s*[:：]\\s*\\p{IsHan}{2,6}")),
            new Rule("DATE_OF_BIRTH", Pattern.compile(
                    "(?:出生日期|出生年月|生日)\\s*[:：]?\\s*(?:19|20)\\d{2}[-年/.]"
                            + "(?:0?[1-9]|1[0-2])(?:[-月/.](?:0?[1-9]|[12]\\d|3[01])日?)?")),
            new Rule("POSTAL_ADDRESS", Pattern.compile(
                    "(?:家庭住址|现住址|联系地址|居住地址)\\s*[:：]\\s*[^\\r\\n]{6,120}")),
            new Rule("INPATIENT_NUMBER", Pattern.compile("(住院号|病案号|门诊号)\\s*[:：]?\\s*[A-Za-z0-9-]{5,}")),
            new Rule("HEALTH_CARD_NUMBER", Pattern.compile(
                    "(?:医保卡号|健康卡号|就诊卡号)\\s*[:：]?\\s*[A-Za-z0-9-]{6,}")),
            new Rule("BANK_ACCOUNT", Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)"))
    );

    public Assessment assess(String content) {
        if (content == null || content.isBlank()) return new Assessment(Status.SAFE, List.of());
        List<String> hits = new ArrayList<>();
        RULES.stream().filter(rule -> rule.pattern().matcher(content).find())
                .map(Rule::code).forEach(hits::add);
        return hits.isEmpty()
                ? new Assessment(Status.SAFE, List.of())
                : new Assessment(Status.BLOCKED_FOR_EXTERNAL_MODEL, List.copyOf(hits));
    }

    public enum Status {
        SAFE, WARNING, BLOCKED_FOR_EXTERNAL_MODEL, REQUIRES_ADMIN_REVIEW
    }

    public record Assessment(Status status, List<String> matchedRules) {
        public boolean canSendToExternalModel() {
            return status == Status.SAFE || status == Status.WARNING;
        }
    }

    private record Rule(String code, Pattern pattern) {}
}
