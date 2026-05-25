package com.xy.ai.controller;

import com.xy.ai.repository.ChatHistoryRepository;
import com.xy.ai.tools.RecordContractTools;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RequiredArgsConstructor
@RestController
@RequestMapping("/ai")
public class RecordContractAgentController {

    private final ChatClient recordContractChatClient;

    private final RecordContractTools recordContractTools;

    private final ChatHistoryRepository chatHistoryRepository;

    @RequestMapping(value = "/record-contract-agent", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> recordContract(
            @RequestParam("prompt") String prompt,
            @RequestParam("chatId") String chatId,
            @RequestParam(value = "userCode", required = false) String userCode) {
        String resolvedUserCode = StringUtils.hasText(userCode) ? userCode : "8106727";
        chatHistoryRepository.save("record-contract-agent", chatId);

        if (isGreeting(prompt)) {
            return Flux.just("你好");
        }

        if (!StringUtils.hasText(prompt)) {
            RecordContractTools.RecordContractGuide guide = recordContractTools.getRecordContractGuide();
            return Flux.just("""
                    当前入口只支持录合同流程。请按下面信息补充：
                    必填项：%s
                    流程：%s
                    示例：%s
                    """.formatted(
                    String.join("、", guide.requiredFields()),
                    String.join(" -> ", guide.steps()),
                    guide.exampleInput()
            ));
        }

        String agentPrompt = """
                当前登录人工号：%s
                用户本轮输入：%s

                请围绕录合同流程处理本次请求，并结合历史上下文继续推进，不要重新开始。
                如果用户本轮是在选择Q计划活动、确认医生角色、补充金额/时间/地点，请把它与历史消息里的医生、金额、时间、地点合并后调用 buildContractPreview。
                开始时间和结束时间只需要精确到日期，不需要用户提供时分秒；请优先使用 yyyy-MM-dd 格式传给工具。

                只能追问以下固定必填字段中的缺失项：
                登录人工号、医生姓名或编码、医生角色、Q计划活动名称或ID、授课地区、详细地址、开始日期、结束日期、授课时长、报酬金额（授课时长是单独的字符串字段，只能用户输入获取，不要自己计算，不要追加单位）。

                禁止追问：合同类型、合同条款、支付方式、付款周期、活动日期、差旅信息、发票信息、合同模板。
                专家授课协议的合同类型已经固定，合同条款由后端模板处理。
                入口引导模板只允许在每个会话第一次问候时展示，后续对话绝对不要再次展示完整引导模板。
                医生角色只允许是：主持人、主席、专家咨询会顾问、讲者、评论嘉宾；用户角色不在范围内时，调用 matchDoctorRole 并展示查询到的角色列表，不要自行填值。
                查询我方信息时，如果 getCurrentUserContractContext 返回 companyOptions，必须展示可选我方公司列表给用户选择。
                当用户输入的医生、角色、Q计划活动、地区或我方公司与工具查询结果不完全匹配时，要友好提示“我查到了以下可选项，请选择一个”，并列出候选编号、名称和关键编码。
                用户确认提交后，先调用 checkRecordContractRisk。风险检查通过后，只能提示用户风险检查通过并请用户再次确认创建。
                只有风险检查通过且用户再次明确确认创建后，才能调用 submitRecordContract，此时不要再次调用 checkRecordContractRisk。调用 checkRecordContractRisk 和 submitRecordContract 时按工具参数传入业务字段，不要传入当前会话ID。
                如果工具返回接口调用失败、HTTP请求失败、接口未返回内容、接口URL未配置或缺少我方签约供应商信息，必须立即停止流程，明确提示失败节点，不要继续调用后续工具。
                风险检查必须通过 checkRecordContractRisk 调用 risk-check 接口；如果返回“未调用风险检查接口”，必须向用户说明未调用原因。
                创建成功后，请明确回复“合同创建成功”。合同编号只能使用 submitRecordContract 返回的 contractId；如果 contractId 为空，必须说明“创建接口未返回合同编号”，禁止自行生成合同编号。还要根据工具返回的 relationMessage 提示Q计划是否已关联合同。
                """.formatted(resolvedUserCode, prompt);

        return recordContractChatClient.prompt()
                .user(agentPrompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, chatId))
                .stream()
                .content();
    }

    private boolean isGreeting(String prompt) {
        if (!StringUtils.hasText(prompt)) return false;
        String normalizedPrompt = prompt.trim();
        return  "你好".equals(normalizedPrompt)
                || "您好".equals(normalizedPrompt)
                || "hi".equalsIgnoreCase(normalizedPrompt)
                || "hello".equalsIgnoreCase(normalizedPrompt);
    }
}
