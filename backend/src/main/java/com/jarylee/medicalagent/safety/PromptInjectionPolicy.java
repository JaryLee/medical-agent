package com.jarylee.medicalagent.safety;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class PromptInjectionPolicy {
    private static final List<Rule> RULES = List.of(
            new Rule("INSTRUCTION_OVERRIDE_ZH", Pattern.compile(
                    "(?iu)(?:忽略|无视|绕过|覆盖|不要遵守).{0,24}"
                            + "(?:之前|以上|系统|开发者|原有).{0,12}(?:指令|提示|规则|要求)")),
            new Rule("INSTRUCTION_OVERRIDE_EN", Pattern.compile(
                    "(?iu)\\b(?:ignore|disregard|bypass|override)\\b.{0,40}"
                            + "\\b(?:previous|prior|system|developer)\\b.{0,24}"
                            + "\\b(?:instructions?|prompts?|rules?)\\b")),
            new Rule("PROMPT_EXFILTRATION", Pattern.compile(
                    "(?iu)(?:显示|泄露|输出|复述|打印|reveal|show|print|repeat).{0,30}"
                            + "(?:系统提示|隐藏提示|开发者消息|system prompt|hidden prompt|developer message)")),
            new Rule("ROLE_OVERRIDE", Pattern.compile(
                    "(?iu)(?:你现在是|从现在起你是|进入开发者模式|越狱模式|"
                            + "you are now|act as|developer mode|jailbreak)")),
            new Rule("SECRET_EXFILTRATION", Pattern.compile(
                    "(?iu)(?:读取|获取|泄露|输出|reveal|show|print|read).{0,30}"
                            + "(?:api[_ -]?key|token|令牌|密钥|密码|环境变量|environment variables?)")),
            new Rule("TOOL_OR_COMMAND_INJECTION", Pattern.compile(
                    "(?iu)(?:调用|执行|运行|call|execute|run).{0,20}"
                            + "(?:工具|命令|shell|powershell|cmd|terminal|tool)"))
    );

    public Assessment assess(String content) {
        if (content == null || content.isBlank()) {
            return new Assessment(false, List.of());
        }
        List<String> hits = new ArrayList<>();
        RULES.stream()
                .filter(rule -> rule.pattern().matcher(content).find())
                .map(Rule::code)
                .forEach(hits::add);
        return new Assessment(!hits.isEmpty(), List.copyOf(hits));
    }

    public record Assessment(boolean blocked, List<String> matchedRules) {}

    private record Rule(String code, Pattern pattern) {}
}
