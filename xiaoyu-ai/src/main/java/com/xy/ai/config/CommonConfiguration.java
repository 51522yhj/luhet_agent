package com.xy.ai.config;

import com.xy.ai.constants.SystemConstants;
import com.xy.ai.tools.CourseTools;
import com.xy.ai.tools.RecordContractTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CommonConfiguration {

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder().build();
    }

    @Bean
    public ChatClient chatClient(ChatModel model, ChatMemory chatMemory) {
        return ChatClient.builder(model)
                .defaultSystem("你是一个热心、可爱的智能助手，你的名字叫小团团，请以小团团的身份和语气回答问题。")
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                )
                .build();
    }

    @Bean
    public ChatClient gameChatClient(ChatModel model, ChatMemory chatMemory) {
        return ChatClient.builder(model)
                .defaultSystem(SystemConstants.GAME_SYSTEM_PROMPT)
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                )
                .build();
    }

    @Bean
    public ChatClient serviceChatClient(ChatModel model, ChatMemory chatMemory, CourseTools courseTools) {
        return ChatClient.builder(model)
                .defaultSystem(SystemConstants.SERVICE_SYSTEM_PROMPT)
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                )
                .defaultTools(courseTools)
                .build();
    }

    @Bean
    public ChatClient recordContractChatClient(ChatModel model, ChatMemory chatMemory, RecordContractTools recordContractTools) {
        return ChatClient.builder(model)
                .defaultSystem("""
                        你是“录合同Agent”，只负责专家授课协议的录入。
                        你的目标是把用户的录合同请求，按照固定流程收集、校验、预览、确认、提交。

                        固定必填字段只有：登录人工号、医生姓名或编码、医生角色、Q计划活动名称或ID、授课地区、详细地址、开始日期、结束日期、授课时长、报酬金额。

                        工作规则：
                        1. 只处理录合同相关问题；如果用户问的是别的事情，礼貌告知当前入口只支持录合同流程。
                        2. 优先通过工具完成识别、查询、校验和提交，不要凭空编造医生、活动、地区、甲方或我方信息。
                        3. 首轮如果信息很少，调用 getRecordContractGuide 展示必填项或输入模板；同一会话后续不要重复展示完整引导。
                        4. 识别医生时调用 matchDoctor；如果返回多个候选，必须展示候选列表让用户选择，不能擅自决定。
                        5. 确认角色时调用 matchDoctorRole；医生角色只允许是：主持人、主席、专家咨询会顾问、讲者、评论嘉宾。用户输入不属于这些角色时，展示工具返回的角色候选让用户重新选择。
                        6. 确认Q计划活动时调用 matchPlan；如果返回多个候选，让用户继续选择。
                        7. 校验授课地区时调用 validateTeachArea；缺省市区时只追问缺失项。
                        8. 需要登录人、部门、甲方或我方信息时，调用 getCurrentUserContractContext；如果返回 companyOptions，必须展示可选我方公司列表让用户选择。
                        9. 信息基本齐全后调用 buildContractPreview，把缺失项、风险提示、候选项和合同预览整理后回复。
                        10. 用户明确确认提交后，先调用 checkRecordContractRisk。风险检查通过后，只能提示用户风险检查通过并请用户再次确认创建。
                        11. 只有风险检查通过且用户再次明确确认创建后，才能调用 submitRecordContract，此时不要再次调用 checkRecordContractRisk。
                        12. 调用 checkRecordContractRisk 和 submitRecordContract 时按工具参数传入业务字段，不要传入会话ID。
                        13. 创建成功后必须回复“合同创建成功”。合同编号只能使用 submitRecordContract 返回的 contractId；如果 contractId 为空，必须说明“创建接口未返回合同编号”。还要根据 relationMessage 提示Q计划是否已关联合同。
                        14. 用户输入的医生、角色、Q计划活动、地区或我方公司与工具查询结果不完全匹配时，友好提示“我查到了以下可选项，请选择一个”，并列出候选编号、名称和关键编码。
                        15. 开始时间和结束时间只需要精确到日期，不要追问时分秒；调用工具时优先传 yyyy-MM-dd 格式。
                        16. 如果任何工具返回“接口调用失败”“HTTP请求失败”“接口未返回内容”“接口URL未配置”“缺少我方签约供应商信息”等提示，必须立即停止后续流程，明确告诉用户哪个节点失败，不要继续调用后续工具。
                        17. 风险检查只能通过 checkRecordContractRisk 调用 risk-check 接口完成；如果 checkRecordContractRisk 返回“未调用风险检查接口”，必须把缺失项、候选项或接口失败原因告诉用户，不要假装已经做过风控。

                        回复要求：全程使用中文；缺什么只问什么；禁止追问合同类型、合同条款、支付方式、付款周期、发票信息、差旅信息、活动日期等泛合同字段。
                        """)
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                )
                .defaultTools(recordContractTools)
                .build();
    }
}
