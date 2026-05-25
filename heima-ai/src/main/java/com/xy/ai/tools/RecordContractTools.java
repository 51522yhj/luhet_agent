package com.xy.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xy.ai.config.RecordContractHttpProperties;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RecordContractTools {

    private static final Logger log = LoggerFactory.getLogger(RecordContractTools.class);

    private static final String SUCCESS_CODE = "100";
    private static final String DEFAULT_APP_CODE = "ContractManage";
    private static final String DEFAULT_PLAN_TYPE_CATEGORY = "QPlanType";
    private static final String DEFAULT_SIGNER_SUPPLIER_CONFIG_CODE = "supplierCodeReplace";
    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 30;
    private static final int DOCTOR_PAGE_SIZE = 200;
    private static final List<String> ALLOWED_ROLE_NAMES = List.of("主持人", "主席", "专家咨询会顾问", "讲者", "评论嘉宾");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final RecordContractHttpProperties properties;
    private final Map<String, ContractRiskCheckResult> passedRiskChecks = new ConcurrentHashMap<>();

    public RecordContractTools(RestClient.Builder restClientBuilder,
                               ObjectMapper objectMapper,
                               RecordContractHttpProperties properties) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Tool(description = "获取录合同流程说明")
    public RecordContractGuide getRecordContractGuide() {
        return new RecordContractGuide(
                List.of("登录人工号", "医生姓名或编码", "医生角色", "Q计划活动名称或ID", "授课地区", "详细地址", "开始日期", "结束日期", "授课时长", "报酬金额"),
                List.of("查登录人和签约信息", "识别医生", "识别角色", "识别Q计划活动", "校验地区", "生成预览", "确认后提交"),
                "登录人工号 2020665，医生 张三，角色 主持人，Q计划活动 XXX，地点 山西省太原市杏花岭区，开始日期 2026-03-05，结束日期 2026-03-05，授课时长 56.7，报酬 2000 元。"
        );
    }

    @Tool(description = "根据登录人工号查询录合同上下文")
    public CurrentUserContractContext getCurrentUserContractContext(
            @ToolParam(description = "登录人工号") String userCode) {
        if (!StringUtils.hasText(userCode)) {
            return emptyCurrentUserContext("缺少登录人工号，无法查询登录人和签约信息。");
        }

        Map<String, String> headers = userHeaders(userCode, null, null, null, null);
        ApiEnvelope baseInfoEnvelope = postJson(endpoint("base-info-batch"),
                Map.of("personCodeList", List.of(userCode)), headers);
        if (!baseInfoEnvelope.success()) {
            return emptyCurrentUserContext("登录人基本信息接口调用失败：" + withFallback(baseInfoEnvelope.message(), "接口未返回成功状态。"));
        }
        ApiEnvelope companyInfoEnvelope = getJson(endpoint("company-info-by-person"),
                Map.of("personCode", userCode, "appCode", DEFAULT_APP_CODE), headers);
        if (!companyInfoEnvelope.success()) {
            return emptyCurrentUserContext("我方公司/部门信息接口调用失败：" + withFallback(companyInfoEnvelope.message(), "接口未返回成功状态。"));
        }

        JsonNode baseInfo = firstItem(baseInfoEnvelope.responseData());
        List<CompanyOption> companyOptions = extractCompanyOptions(companyInfoEnvelope.responseData());
        JsonNode companyInfo = firstItem(companyInfoEnvelope.responseData());
        JsonNode departInfo = firstItem(companyInfo.path("departInfoDTOS"));

        String userId = text(baseInfo, "id");
        String personName = text(baseInfo, "personName");
        String phoneNumber = text(baseInfo, "tel");
        String companyCode = text(companyInfo, "companyCode");
        String companyName = text(companyInfo, "companyName");
        String accountCompanyCode = text(companyInfo, "accountCompanyCode");
        String accountCompanyName = text(companyInfo, "accountCompanyName");
        String departCode = text(departInfo, "departCode");
        String departName = text(departInfo, "departName");

        Map<String, String> enrichedHeaders = userHeaders(userCode, null, userId, departCode, null);
        String signerSupplierCode = resolveSignerSupplierCode(companyCode, accountCompanyCode, enrichedHeaders);
        if (signerSupplierCode != null && signerSupplierCode.startsWith("__ERROR__:")) {
            return emptyCurrentUserContext(signerSupplierCode.substring("__ERROR__:".length()));
        }
        SignerInfo signerInfo = querySignerInfo(signerSupplierCode, enrichedHeaders);

        return new CurrentUserContractContext(
                blankToNull(userCode),
                blankToNull(personName),
                blankToNull(phoneNumber),
                blankToNull(companyCode),
                blankToNull(companyName),
                blankToNull(accountCompanyCode),
                blankToNull(accountCompanyName),
                blankToNull(departCode),
                blankToNull(departName),
                blankToNull(departName),
                blankToNull(personName),
                blankToNull(signerSupplierCode),
                blankToNull(signerInfo.signerId()),
                blankToNull(signerInfo.signerName()),
                blankToNull(signerInfo.signerAddress()),
                blankToNull(signerInfo.bankName()),
                blankToNull(signerInfo.bankAccountName()),
                blankToNull(signerInfo.bankCode()),
                blankToNull(signerInfo.bankAccount()),
                companyOptions,
                mergeMessages(baseInfoEnvelope.message(), companyInfoEnvelope.message(), signerInfo.message())
        );
    }

    @Tool(description = "按医生姓名或编码匹配医生")
    public DoctorMatchResult matchDoctor(
            @ToolParam(description = "医生姓名", required = false) String doctorName,
            @ToolParam(description = "医生编码", required = false) String doctorCode,
            @ToolParam(description = "登录人工号", required = false) String userCode) {
        if (StringUtils.hasText(doctorCode)) {
            DoctorDetail detail = queryDoctorDetail(doctorCode, userCode);
            if (detail.exists()) {
                return new DoctorMatchResult(true, "已按医生编码精确匹配。", List.of(toDoctorSummary(detail)));
            }
            return new DoctorMatchResult(false, withFallback(detail.message(), "未查询到医生明细。"), List.of());
        }
        if (!StringUtils.hasText(doctorName)) {
            return new DoctorMatchResult(false, "请先提供医生姓名或医生编码。", List.of());
        }

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("auditState", "1");
        payload.put("pageNo", DEFAULT_PAGE_NO);
        payload.put("pageSize", DOCTOR_PAGE_SIZE);
        payload.put("supplierName", doctorName);
        ApiEnvelope envelope = postJson(endpoint("doctor-list"), payload, userHeaders(userCode, null, null, null, null));
        if (!envelope.success()) {
            return new DoctorMatchResult(false, "医生列表接口调用失败：" + withFallback(envelope.message(), "接口未返回成功状态。"), List.of());
        }

        List<DoctorSummary> candidates = new ArrayList<>();
        for (JsonNode item : iterable(listItems(envelope.responseData()))) {
            candidates.add(new DoctorSummary(
                    firstNonBlank(text(item, "supplierCode"), text(item, "doctorCode")),
                    firstNonBlank(text(item, "supplierName"), text(item, "doctorName"), text(item, "name")),
                    text(item, "supplierCode"),
                    text(item, "orgName"),
                    text(item, "expertLevelName"),
                    firstNonBlank(text(item, "mobile"), text(item, "phone")),
                    text(item, "id")
            ));
        }
        candidates = filterDoctorCandidatesByAllocation(userCode, candidates);
        if (candidates.isEmpty()) {
            return new DoctorMatchResult(false, withFallback(envelope.message(), "未查询到匹配的医生信息。"), List.of());
        }
        List<DoctorSummary> exact = candidates.stream().filter(item -> equalsNormalized(item.doctorName(), doctorName)).toList();
        if (exact.size() == 1) {
            return new DoctorMatchResult(true, "已匹配到医生。", exact);
        }
        if (exact.size() > 1) {
            return new DoctorMatchResult(false, "查到了多个同名医生，请让用户从候选医生中选择。", exact);
        }
        return new DoctorMatchResult(false, "我查到了相近的医生信息，但和用户输入未完全一致，请友好提示用户从候选医生中选择。", candidates);
    }

    @Tool(description = "根据医生编码和角色名称匹配角色")
    public RoleMatchResult matchDoctorRole(
            @ToolParam(description = "医生编码") String doctorCode,
            @ToolParam(description = "医生角色", required = false) String roleName,
            @ToolParam(description = "登录人工号", required = false) String userCode) {
        if (!StringUtils.hasText(doctorCode)) {
            return new RoleMatchResult(false, "缺少医生编码，请先完成医生识别。", List.of(), List.of());
        }

        DoctorDetail doctorDetail = queryDoctorDetail(doctorCode, userCode);
        if (!doctorDetail.exists()) {
            return new RoleMatchResult(false, withFallback(doctorDetail.message(), "未查询到医生明细。"), List.of(), List.of());
        }
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("expertLevelCode", firstNonBlank(doctorDetail.expertLevelCode(), "I"));
        payload.put("roleCode", null);
        ApiEnvelope envelope = postJson(endpoint("role-cost"), payload, userHeaders(userCode, null, null, null, null));
        if (!envelope.success()) {
            return new RoleMatchResult(false, "医生角色接口调用失败：" + withFallback(envelope.message(), "接口未返回成功状态。"), List.of(), listOfNonBlank(expertLevelWarning(doctorDetail)));
        }

        List<RoleSummary> candidates = new ArrayList<>();
        for (JsonNode item : iterable(listItems(envelope.responseData()))) {
            candidates.add(new RoleSummary(
                    firstNonBlank(text(item, "roleCode"), text(item, "meetingRoleCode"), text(item, "id")),
                    firstNonBlank(text(item, "roleName"), text(item, "meetingRoleName"), text(item, "name")),
                    integerValue(item, "amount", "cost", "maxAmount", "limitAmount"),
                    doctorDetail.expertLevelName()
            ));
        }
        List<RoleSummary> allowedCandidates = allowedRoleCandidates(candidates);
        if (candidates.isEmpty()) {
            return new RoleMatchResult(false, withFallback(envelope.message(), "角色费用接口未返回可用角色。"), List.of(), listOfNonBlank(expertLevelWarning(doctorDetail)));
        }
        if (StringUtils.hasText(roleName) && !isAllowedRoleName(roleName)) {
            return new RoleMatchResult(false,
                    "用户输入的医生角色不在允许范围内，请让用户从查询到的角色列表中重新选择。",
                    allowedCandidates.isEmpty() ? candidates : allowedCandidates,
                    listOfNonBlank(expertLevelWarning(doctorDetail)));
        }

        List<RoleSummary> rolePool = allowedCandidates.isEmpty() ? candidates : allowedCandidates;
        List<RoleSummary> matched = exactRoles(rolePool, roleName);
        if (!StringUtils.hasText(roleName)) {
            return new RoleMatchResult(false, "请让用户从以下角色中选择一个。", rolePool, listOfNonBlank(expertLevelWarning(doctorDetail)));
        }
        if (matched.size() == 1) {
            return new RoleMatchResult(true, "已匹配到医生角色。", matched, listOfNonBlank(expertLevelWarning(doctorDetail)));
        }
        if (matched.isEmpty()) {
            return new RoleMatchResult(false, "用户输入的角色和查询到的角色未匹配，请友好提示用户从可选角色中重新选择。", rolePool, listOfNonBlank(expertLevelWarning(doctorDetail)));
        }
        return new RoleMatchResult(false, "角色匹配到多个候选，请让用户进一步确认。", matched, listOfNonBlank(expertLevelWarning(doctorDetail)));
    }

    @Tool(description = "按Q计划活动名称或活动ID匹配活动")
    public PlanMatchResult matchPlan(
            @ToolParam(description = "Q计划活动名称", required = false) String planName,
            @ToolParam(description = "活动ID", required = false) String planId,
            @ToolParam(description = "登录人工号", required = false) String userCode) {
        if (!StringUtils.hasText(planName) && !StringUtils.hasText(planId)) {
            return new PlanMatchResult(false, "请先提供Q计划活动名称或活动ID。", List.of());
        }

        ApiEnvelope typeEnvelope = getJson(endpoint("q-plan-type-config"),
                Map.of("categoryCode", DEFAULT_PLAN_TYPE_CATEGORY), userHeaders(userCode, null, null, null, null));
        if (!typeEnvelope.success()) {
            return new PlanMatchResult(false, "Q计划类型接口调用失败：" + withFallback(typeEnvelope.message(), "接口未返回成功状态。"), List.of());
        }
        List<String> planTypes = extractPlanTypes(typeEnvelope.responseData());

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("approveStatus", "2");
        payload.put("pageNo", DEFAULT_PAGE_NO);
        payload.put("pageSize", DEFAULT_PAGE_SIZE);
        payload.put("personCode", firstNonBlank(userCode, ""));
        payload.put("planTypes", planTypes);
        payload.put("processTitle", planCodeQueryValue(planId));
        payload.put("subject", firstNonBlank(planName, ""));
        ApiEnvelope envelope = postJson(endpoint("q-plan-list"), payload, userHeaders(userCode, null, null, null, null));
        if (!envelope.success()) {
            return new PlanMatchResult(false, "Q计划活动列表接口调用失败：" + withFallback(envelope.message(), "接口未返回成功状态。"), List.of());
        }

        List<PlanSummary> candidates = new ArrayList<>();
        for (JsonNode item : iterable(listItems(envelope.responseData()))) {
            candidates.add(new PlanSummary(
                    text(item, "planId"),
                    firstNonBlank(text(item, "subject"), text(item, "planName")),
                    firstNonBlank(text(item, "planType"), text(item, "meetingActivityType"), text(item, "meetingActivityTypeDesc")),
                    text(item, "startDateTime", "startDate"),
                    text(item, "endDateTime", "endDate"),
                    text(item, "processTitle"),
                    text(item, "userName"),
                    text(item, "productTypeCode")
            ));
        }
        if (candidates.isEmpty()) {
            return new PlanMatchResult(false, withFallback(envelope.message(), "未查询到匹配的Q计划活动。"), List.of());
        }
        List<PlanSummary> exactMatched = exactPlans(candidates, planName, planId);
        if (exactMatched.size() == 1) return new PlanMatchResult(true, "已匹配到Q计划活动。", exactMatched);
        if (exactMatched.size() > 1) return new PlanMatchResult(false, "匹配到多个Q计划活动候选，请让用户进一步确认。", exactMatched);
        List<PlanSummary> matched = matchPlans(candidates, planName, planId);
        if (matched.isEmpty())
            return new PlanMatchResult(false, "查到了活动，但用户输入和查询结果未完全匹配，请友好提示用户从可选活动中选择。", candidates);
        return new PlanMatchResult(false, "我查到了相近的Q计划活动，但和用户输入未完全一致，请让用户从候选活动中选择。", matched);
    }

    @Tool(description = "校验授课地区")
    public AreaValidationResult validateTeachArea(
            @ToolParam(description = "省份", required = false) String provinceName,
            @ToolParam(description = "城市", required = false) String cityName,
            @ToolParam(description = "区县", required = false) String districtName,
            @ToolParam(description = "详细地址", required = false) String detailAddress) {
        if (!StringUtils.hasText(provinceName) && !StringUtils.hasText(cityName) && !StringUtils.hasText(districtName)) {
            return new AreaValidationResult(false, "请至少补充省、市、区中的一项。", null, null, null, null, null, null, blankToNull(detailAddress), List.of());
        }
        ApiEnvelope envelope = getJson(endpoint("area-list"), Map.of(), jsonHeaders());
        if (!envelope.success()) {
            return new AreaValidationResult(false, "地区列表接口调用失败：" + withFallback(envelope.message(), "接口未返回成功状态。"), null, null, null, null, null, null, blankToNull(detailAddress), List.of());
        }
        List<AreaInfo> allAreas = flattenAreas(envelope.responseData());
        List<AreaInfo> matched = allAreas.stream()
                .filter(item -> matchesAreaPart(item.provinceName(), provinceName))
                .filter(item -> matchesAreaPart(item.cityName(), cityName))
                .filter(item -> matchesAreaPart(item.districtName(), districtName))
                .toList();
        if (matched.size() == 1) {
            AreaInfo area = matched.get(0);
            return new AreaValidationResult(true, "地区校验通过。", area.provinceCode(), area.provinceName(), area.cityCode(), area.cityName(), area.districtCode(), area.districtName(), blankToNull(detailAddress), List.of());
        }
        List<String> suggestions = matched.isEmpty() ? allAreas.stream().map(AreaInfo::displayName).distinct().limit(20).toList() : matched.stream().map(AreaInfo::displayName).distinct().toList();
        return new AreaValidationResult(false,
                matched.isEmpty() ? withFallback(envelope.message(), "未找到匹配地区，请重新确认。") : "地区匹配到多个候选，请让用户进一步确认。",
                null, null, null, null, null, null, blankToNull(detailAddress), suggestions);
    }

    @Tool(description = "汇总录合同所需信息并生成预览")
    public ContractPreview buildContractPreview(
            @ToolParam(description = "登录人工号") String userCode,
            @ToolParam(description = "医生姓名", required = false) String doctorName,
            @ToolParam(description = "医生编码", required = false) String doctorCode,
            @ToolParam(description = "医生角色", required = false) String roleName,
            @ToolParam(description = "Q计划活动名称", required = false) String planName,
            @ToolParam(description = "活动ID", required = false) String planId,
            @ToolParam(description = "省份", required = false) String provinceName,
            @ToolParam(description = "城市", required = false) String cityName,
            @ToolParam(description = "区县", required = false) String districtName,
            @ToolParam(description = "详细地址", required = false) String detailAddress,
            @ToolParam(description = "开始日期，精确到日，格式 yyyy-MM-dd", required = false) String startTime,
            @ToolParam(description = "结束日期，精确到日，格式 yyyy-MM-dd", required = false) String endTime,
            @ToolParam(description = "授课时长，必填，字符串类型，用户随意输入，没有单位，不是计算来的", required = false) String teachDuration,
            @ToolParam(description = "报酬金额", required = false) Integer amount) {
        List<String> missingFields = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        if (!StringUtils.hasText(userCode)) missingFields.add("登录人工号");
        if (!StringUtils.hasText(doctorName) && !StringUtils.hasText(doctorCode))
            missingFields.add("医生姓名或医生编码");
        if (!StringUtils.hasText(roleName)) missingFields.add("医生角色");
        if (!StringUtils.hasText(planName) && !StringUtils.hasText(planId)) missingFields.add("Q计划活动名称或活动ID");
        if (!StringUtils.hasText(provinceName) && !StringUtils.hasText(cityName) && !StringUtils.hasText(districtName))
            missingFields.add("授课地区");
        if (!StringUtils.hasText(detailAddress)) missingFields.add("详细地址");
        if (!StringUtils.hasText(startTime)) missingFields.add("开始日期");
        if (!StringUtils.hasText(endTime)) missingFields.add("结束日期");
        if (!StringUtils.hasText(teachDuration)) missingFields.add("授课时长");
        if (amount == null) missingFields.add("报酬金额");

        CurrentUserContractContext userContext = getCurrentUserContractContext(userCode);
        if (isInterfaceFailureMessage(userContext.message())) {
            warnings.add(userContext.message());
        }
        if (StringUtils.hasText(userCode) && !StringUtils.hasText(userContext.userName())) {
            warnings.add("登录人信息查询失败，已停止后续提交流程。");
        }
        if (StringUtils.hasText(userCode) && !StringUtils.hasText(userContext.signerSupplierCode())) {
            warnings.add("缺少我方签约供应商信息，已停止后续提交流程。");
        }
        if (userContext.companyOptions() != null && userContext.companyOptions().size() > 1) {
            suggestions.add("我方公司候选：" + joinCompanyOptions(userContext.companyOptions()));
        }
        DoctorSummary doctor = null;
        if (!shouldStopFlow(warnings)) {
            DoctorMatchResult doctorMatch = matchDoctor(doctorName, doctorCode, userCode);
            doctor = doctorMatch.exactMatch() && !doctorMatch.candidates().isEmpty() ? doctorMatch.candidates().get(0) : null;
            if (!doctorMatch.exactMatch()) {
                if (!doctorMatch.candidates().isEmpty())
                    suggestions.add("医生候选：" + joinDoctorCandidates(doctorMatch.candidates()));
                else if (StringUtils.hasText(doctorMatch.message())) warnings.add(doctorMatch.message());
            }
        }

        RoleSummary role = null;
        if (!shouldStopFlow(warnings)) {
            RoleMatchResult roleMatch = doctor != null ? matchDoctorRole(doctor.doctorCode(), roleName, userCode)
                    : new RoleMatchResult(false, "医生未确认前无法匹配角色。", List.of(), List.of());
            role = roleMatch.exactMatch() && !roleMatch.candidates().isEmpty() ? roleMatch.candidates().get(0) : null;
            if (!roleMatch.exactMatch()) {
                if (!roleMatch.candidates().isEmpty())
                    suggestions.add("角色候选：" + joinRoleCandidates(roleMatch.candidates()));
                else if (StringUtils.hasText(roleMatch.message())) warnings.add(roleMatch.message());
            }
            warnings.addAll(roleMatch.warnings());
        }

        PlanSummary plan = null;
        if (!shouldStopFlow(warnings)) {
            PlanMatchResult planMatch = matchPlan(planName, planId, userCode);
            plan = planMatch.exactMatch() && !planMatch.candidates().isEmpty() ? planMatch.candidates().get(0) : null;
            if (!planMatch.exactMatch()) {
                if (!planMatch.candidates().isEmpty())
                    suggestions.add("活动候选：" + joinPlanCandidates(planMatch.candidates()));
                else if (StringUtils.hasText(planMatch.message())) warnings.add(planMatch.message());
            }
        }

        AreaValidationResult areaValidation = new AreaValidationResult(false, null, null, null, null, null, null, null, null, List.of());
        if (!shouldStopFlow(warnings)) {
            areaValidation = validateTeachArea(provinceName, cityName, districtName, detailAddress);
            if (!areaValidation.valid()) {
                if (!areaValidation.suggestions().isEmpty())
                    suggestions.add("地区候选：" + String.join("，", areaValidation.suggestions()));
                else if (StringUtils.hasText(areaValidation.message())) warnings.add(areaValidation.message());
            }
        }

        LocalDateTime parsedStart = parseDateTime(startTime);
        LocalDateTime parsedEnd = parseDateTime(endTime);
        if (StringUtils.hasText(startTime) && parsedStart == null)
            warnings.add("开始日期格式无法识别，请使用 yyyy-MM-dd，例如 2026-03-05。");
        if (StringUtils.hasText(endTime) && parsedEnd == null)
            warnings.add("结束日期格式无法识别，请使用 yyyy-MM-dd，例如 2026-03-05。");
        if (parsedStart != null && parsedEnd != null && parsedEnd.toLocalDate().isBefore(parsedStart.toLocalDate()))
            warnings.add("结束日期不能早于开始日期。");
        if (amount != null && amount <= 0) warnings.add("报酬金额必须大于 0。");
        if (amount != null && role != null && role.maxAmount() != null && amount > role.maxAmount())
            warnings.add("当前角色建议金额上限为 " + role.maxAmount() + " 元，请人工确认金额是否可以提交。");
        if (plan != null && parsedStart != null && doctor != null)
            warnings.addAll(optionalPlanWarnings(plan.planId(), doctor.doctorCode(), parsedStart));

        ContractDraft draft = new ContractDraft(
                blankToNull(userCode), blankToNull(userContext.userName()), blankToNull(userContext.quoteDepartName()), blankToNull(userContext.quoteUserName()),
                blankToNull(userContext.companyCode()), blankToNull(userContext.companyName()), blankToNull(userContext.accountCompanyCode()), blankToNull(userContext.accountCompanyName()),
                blankToNull(userContext.departCode()), blankToNull(userContext.departName()), blankToNull(userContext.signerSupplierCode()), blankToNull(userContext.signerId()),
                blankToNull(userContext.signerName()), blankToNull(userContext.signerAddress()), doctor == null ? blankToNull(doctorCode) : blankToNull(doctor.doctorCode()),
                doctor == null ? blankToNull(doctorName) : blankToNull(doctor.doctorName()), doctor == null ? null : blankToNull(doctor.supplierCode()),
                doctor == null ? null : blankToNull(doctor.supplierOrgName()), role == null ? null : blankToNull(role.roleId()), role == null ? blankToNull(roleName) : blankToNull(role.roleName()),
                plan == null ? blankToNull(planId) : blankToNull(plan.planId()), plan == null ? blankToNull(planName) : blankToNull(plan.planName()), plan == null ? null : blankToNull(plan.planType()),
                plan == null ? null : blankToNull(plan.planCode()), plan == null ? null : blankToNull(plan.ownerName()), plan == null ? null : blankToNull(plan.productTypeCode()),
                areaValidation.valid() ? blankToNull(areaValidation.provinceCode()) : null,
                areaValidation.valid() ? blankToNull(areaValidation.provinceName()) : blankToNull(provinceName), areaValidation.valid() ? blankToNull(areaValidation.cityCode()) : null,
                areaValidation.valid() ? blankToNull(areaValidation.cityName()) : blankToNull(cityName), areaValidation.valid() ? blankToNull(areaValidation.districtCode()) : null,
                areaValidation.valid() ? blankToNull(areaValidation.districtName()) : blankToNull(districtName), blankToNull(detailAddress),
                formatDate(parsedStart), formatDate(parsedEnd), blankToNull(teachDuration), amount, buildContractName(parsedStart), buildProcessTitle(plan, doctor, parsedStart)
        );

        boolean readyToSubmit = missingFields.isEmpty() && doctor != null && role != null && plan != null && areaValidation.valid()
                && parsedStart != null && parsedEnd != null && !parsedEnd.toLocalDate().isBefore(parsedStart.toLocalDate()) && amount != null && amount > 0
                && warnings.stream().noneMatch(this::isBlockingWarning);

        return new ContractPreview(
                readyToSubmit,
                readyToSubmit ? "录合同信息已齐全，可以展示预览并等待用户确认。" : "当前信息还不完整或存在风险，请根据缺失项、警告和候选项继续补充。",
                uniqueList(missingFields), uniqueList(warnings), uniqueList(suggestions), draft
        );
    }

    @Tool(description = "在信息齐全且用户确认后进行录合同风险检查；风险检查通过后必须再次等待用户确认，不能直接创建合同")
    public ContractRiskCheckResult checkRecordContractRisk(
            @ToolParam(description = "登录人工号") String userCode,
            @ToolParam(description = "医生姓名", required = false) String doctorName,
            @ToolParam(description = "医生编码", required = false) String doctorCode,
            @ToolParam(description = "医生角色") String roleName,
            @ToolParam(description = "Q计划活动名称", required = false) String planName,
            @ToolParam(description = "活动ID", required = false) String planId,
            @ToolParam(description = "省份", required = false) String provinceName,
            @ToolParam(description = "城市", required = false) String cityName,
            @ToolParam(description = "区县", required = false) String districtName,
            @ToolParam(description = "详细地址") String detailAddress,
            @ToolParam(description = "开始日期，精确到日，格式 yyyy-MM-dd") String startTime,
            @ToolParam(description = "结束日期，精确到日，格式 yyyy-MM-dd") String endTime,
            @ToolParam(description = "授课时长，必填，字符串类型，用户随意输入，没有单位") String teachDuration,
            @ToolParam(description = "报酬金额") Integer amount) {
        ContractPreview preview = buildContractPreview(userCode, doctorName, doctorCode, roleName, planName, planId,
                provinceName, cityName, districtName, detailAddress, startTime, endTime, teachDuration, amount);
        if (!preview.readyToSubmit()) {
            return new ContractRiskCheckResult(false, false, "未调用风险检查接口：当前信息还不能进行风险检查，请先处理缺失项、候选项或接口失败提示。",
                    preview.missingFields(), preview.warnings(), preview.draft(), null);
        }

        ContractDraft draft = preview.draft();
        Map<String, String> headers = userHeaders(draft.userCode(), null, null, draft.departCode(), null);
        List<String> warnings = new ArrayList<>(preview.warnings());

        Map<String, Object> riskPayload = buildRiskCheckPayload(draft);
        log.info("[record-contract-tool] start risk check riskKey={} payload={}", draftRiskKey(draft), safeJson(riskPayload));
        ApiEnvelope riskEnvelope = postJson(endpoint("risk-check"), riskPayload, headers);
        JsonNode riskInfo = riskInfoNode(riskEnvelope);
        if (!riskEnvelope.success()) {
            warnings.add("风险检查未通过：" + withFallback(riskEnvelope.message(), "接口未返回通过状态。"));
        }

        boolean passed = riskEnvelope.success() && warnings.stream().noneMatch(this::isBlockingWarning);
        ContractRiskCheckResult result = new ContractRiskCheckResult(
                passed,
                passed,
                passed ? "风险检查通过。请向用户展示风险检查结果，并等待用户再次明确确认后再创建合同。" : "风险检查未通过，请根据提示处理。",
                List.of(),
                uniqueList(warnings),
                draft,
                riskInfo
        );
        if (passed) {
            passedRiskChecks.put(draftRiskKey(draft), result);
        }
        return result;
    }

    @Tool(description = "仅在风险检查通过且用户再次明确确认后创建合同；如果没有当前合同草稿的风险检查通过记录，不允许创建")
    public ContractSubmissionResult submitRecordContract(
            @ToolParam(description = "登录人工号") String userCode,
            @ToolParam(description = "医生姓名", required = false) String doctorName,
            @ToolParam(description = "医生编码", required = false) String doctorCode,
            @ToolParam(description = "医生角色") String roleName,
            @ToolParam(description = "Q计划活动名称", required = false) String planName,
            @ToolParam(description = "活动ID", required = false) String planId,
            @ToolParam(description = "省份", required = false) String provinceName,
            @ToolParam(description = "城市", required = false) String cityName,
            @ToolParam(description = "区县", required = false) String districtName,
            @ToolParam(description = "详细地址") String detailAddress,
            @ToolParam(description = "开始日期，精确到日，格式 yyyy-MM-dd") String startTime,
            @ToolParam(description = "结束日期，精确到日，格式 yyyy-MM-dd") String endTime,
            @ToolParam(description = "授课时长，必填，字符串类型，用户随意输入，没有单位，不是计算来的") String teachDuration,
            @ToolParam(description = "风险检查提醒后的用户原因，存在风险提示时必填，传入创建合同请求的limitReason字段", required = false) String limitReason,
            @ToolParam(description = "报酬金额") Integer amount) {
        ContractPreview preview = buildContractPreview(userCode, doctorName, doctorCode, roleName, planName, planId,
                provinceName, cityName, districtName, detailAddress, startTime, endTime, teachDuration, amount);
        if (!preview.readyToSubmit()) {
            return new ContractSubmissionResult(false, "当前信息还不能提交，请先处理缺失项和风险提示。", null,
                    preview.missingFields(), preview.warnings(), preview.draft(), null, null);
        }

        ContractDraft draft = preview.draft();
        String riskKey = draftRiskKey(draft);
        ContractRiskCheckResult riskCheck = passedRiskChecks.get(riskKey);
        if (riskCheck == null || !riskCheck.readyToCreate()) {
            return new ContractSubmissionResult(false, "创建合同前必须先完成风险检查，并在风险检查通过后再次取得用户确认。", null,
                    preview.missingFields(), preview.warnings(), preview.draft(), null, null);
        }
        if (hasRiskInfo(riskCheck.riskInfo()) && !StringUtils.hasText(limitReason)) {
            return new ContractSubmissionResult(false, "风险检查存在提醒，请先让用户输入原因，再创建合同。", null,
                    List.of("风险原因"), preview.warnings(), preview.draft(), null, null);
        }

        Map<String, String> headers = createContractHeaders(draft);
        List<String> warnings = new ArrayList<>(riskCheck.warnings());

        ApiEnvelope createEnvelope = postJson(endpoint("create-contract"), buildCreateContractPayload(draft, riskCheck.riskInfo(), limitReason), headers);
        String contractId = extractContractId(createEnvelope);
        String relationMessage = null;
        if (createEnvelope.success()) {
            ApiEnvelope relationEnvelope = queryPlanContractRelation(draft.planId(), headers);
            relationMessage = buildRelationMessage(relationEnvelope);
            passedRiskChecks.remove(riskKey);
        }
        return new ContractSubmissionResult(createEnvelope.success(), buildCreateMessage(createEnvelope, contractId),
                blankToNull(contractId), List.of(), uniqueList(warnings), draft, blankToNull(properties.getOpenUrl()), blankToNull(relationMessage));
    }

    private Map<String, Object> buildRiskCheckPayload(ContractDraft draft) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("existExperts", List.of(buildRiskCheckExpert(draft)));
        payload.put("planId", draft.planId());
        payload.put("teachTime", draft.startTime());
        return payload;
    }

    private Map<String, Object> buildRiskCheckExpert(ContractDraft draft) {
        DoctorDetail doctorDetail = queryDoctorDetail(draft.doctorCode(), draft.userCode());
        LinkedHashMap<String, Object> expert = new LinkedHashMap<>();
        expert.put("amount", draft.amount());
        expert.put("amountCNY", draft.amount());
        expert.put("currencyCode", "CNY");
        expert.put("currencyName", "人民币");
        expert.put("expertLevel", firstNonBlankOrEmpty(doctorDetail.expertLevelCode()));
        expert.put("expertLevelName", firstNonBlankOrEmpty(doctorDetail.expertLevelName()));
        expert.put("meetingRole", firstNonBlank(draft.roleId(), ""));
        expert.put("meetingRoleName", firstNonBlank(draft.roleName(), ""));
        expert.put("signerCode", firstNonBlank(doctorDetail.doctorCode(), draft.doctorCode(), doctorDetail.supplierCode(), draft.supplierCode(), ""));
        expert.put("signerName", firstNonBlank(doctorDetail.doctorName(), draft.doctorName(), ""));
        expert.put("supplierOrgName", firstNonBlank(doctorDetail.orgName(), draft.supplierOrgName(), ""));
        expert.put("agentCardNo", firstNonBlank(doctorDetail.cardNo(), ""));
        return expert;
    }

    private Map<String, Object> buildCreateContractPayload(ContractDraft draft, Object riskInfo, String limitReason) {
        CurrentUserContractContext userContext = getCurrentUserContractContext(draft.userCode());
        DoctorDetail doctorDetail = queryDoctorDetail(draft.doctorCode(), draft.userCode());
        BigDecimal amount = draft.amount() == null ? null : BigDecimal.valueOf(draft.amount());

        Map<String, Object> signer = new LinkedHashMap<>();
        signer.put("agentCardNo", null);
        signer.put("agentCode", draft.userCode());
        signer.put("agentName", draft.userName());
        signer.put("agentPhone", userContext.phoneNumber());
        signer.put("bankName", userContext.signerBankName());
        signer.put("bankAccountName", userContext.signerBankAccountName());
        signer.put("bankCode", userContext.signerBankCode());
        signer.put("bankAccount", userContext.signerBankAccount());
        signer.put("creditCode", null);
        signer.put("deptCode", null);
        signer.put("deptName", null);
        signer.put("legalRepresent", null);
        signer.put("payChannelType", "1");
        signer.put("payPart", "1");
        signer.put("signCategory", "1");
        signer.put("signMode", "0");
        signer.put("signName", "甲方");
        signer.put("signOrder", "1");
        signer.put("signRemark", null);
        signer.put("signStatus", "0");
        signer.put("signType", "1");
        signer.put("signerAddress", draft.signerAddress());
        signer.put("signerCode", draft.signerSupplierCode());
        signer.put("signerId", draft.signerId());
        signer.put("signerName", draft.signerName());
        signer.put("signerTypeName", "甲方");
        signer.put("supplyType", "1");

        Map<String, Object> supplier = new LinkedHashMap<>();
        supplier.put("supplierCode", firstNonBlank(doctorDetail.supplierCode(), draft.supplierCode(), draft.doctorCode()));
        supplier.put("supplierName", firstNonBlank(doctorDetail.doctorName(), draft.doctorName()));
        supplier.put("amount", amount);
        supplier.put("amountCNY", amount);
        supplier.put("bankName", doctorDetail.bankName());
        supplier.put("currency", "CNY");
        supplier.put("currencyName", "人民币");
        supplier.put("expertLevelCode", firstNonBlankOrEmpty(doctorDetail.expertLevelCode()));
        supplier.put("expertLevelName", firstNonBlankOrEmpty(doctorDetail.expertLevelName()));
        supplier.put("id", doctorDetail.id());
        supplier.put("jobAdmin", doctorDetail.jobAdmin());
        supplier.put("jobAdminName", doctorDetail.jobAdminName());
        supplier.put("meetingRoleCode", draft.roleId());
        supplier.put("meetingRoleName", draft.roleName());
        supplier.put("mobile", doctorDetail.mobile());
        supplier.put("signStatus", "0");
        supplier.put("supplierBankNum", firstNonBlank(doctorDetail.bankAccount(), doctorDetail.bankNum()));
        supplier.put("supplierIdCard", doctorDetail.cardNo());
        supplier.put("supplierOrgName", firstNonBlank(doctorDetail.orgName(), draft.supplierOrgName()));
        supplier.put("supplierPhone", doctorDetail.mobile());
        supplier.put("bankAccount", arrayOrEmpty(doctorDetail.bankAccountList()));
        supplier.put("phoneList", arrayOrEmpty(doctorDetail.phoneList()));
        supplier.put("expertSignInfoDTO", buildExpertSignInfo(draft, doctorDetail));
        supplier.put("signType", 2);
        supplier.put("signCategory", 3);
        supplier.put("payChannelType", 1);
        supplier.put("signOrder", 2);
        supplier.put("supplyType", 2);
        supplier.put("payPart", 2);
        supplier.put("signMode", 0);

        Map<String, Object> basicInfo = new LinkedHashMap<>();
        basicInfo.put("accountCompanyCode", draft.accountCompanyCode());
        basicInfo.put("amount", amount);
        basicInfo.put("amountCNY", amount);
        basicInfo.put("companyCode", draft.companyCode());
        basicInfo.put("companyName", draft.companyName());
        basicInfo.put("contractName", draft.contractName());
        basicInfo.put("contractCategoryType", "SE");
        basicInfo.put("createUserCode", draft.userCode());
        basicInfo.put("createUserName", draft.userName());
        basicInfo.put("currency", "人民币");
        basicInfo.put("currencyCode", "CNY");
        basicInfo.put("departCode", draft.departCode());
        basicInfo.put("departName", draft.departName());
        basicInfo.put("deptCode", draft.departCode());
        basicInfo.put("deptName", draft.departName());
        basicInfo.put("predictCurrencyCode", "CNY");
        basicInfo.put("predictCurrencyName", "人民币");
        basicInfo.put("isGroup", 1);

        Map<String, Object> planInfo = new LinkedHashMap<>();
        planInfo.put("relationType", 1);
        planInfo.put("planCategory", 1);
        planInfo.put("planType", draft.planType());
        planInfo.put("planId", draft.planId());
        planInfo.put("planCode", draft.planProcessTitle());
        planInfo.put("planName", draft.planName());
        planInfo.put("planSubject", draft.planName());
        planInfo.put("createPersonName", draft.planCreatePersonName());
        planInfo.put("productTypeCode", draft.productTypeCode());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("appSource", "agent");
        payload.put("basicInfo", basicInfo);
        payload.put("cityName", draft.cityName());
        payload.put("countryName", draft.districtName());
        payload.put("departCode", draft.departCode());
        payload.put("departName", draft.departName());
        payload.put("limitReason", blankToNull(limitReason));
        payload.put("planInfo", planInfo);
        payload.put("provinceName", draft.provinceName());
        payload.put("reqSource", properties.getDefaultHeaders().getOrDefault("reqsource", "FEISHU"));
        payload.put("riskInfo", riskInfo == null ? buildRiskCheckPayload(draft) : riskInfo);
        payload.put("signer", signer);
        payload.put("suppliers", List.of(supplier));
        payload.put("teachAddress", draft.detailAddress());
        payload.put("teachCity", writeJson(buildTeachCity(draft.provinceCode(), draft.cityCode(), draft.districtCode())));
        payload.put("teachTime", writeJson(buildTeachTime(draft.startTime(), draft.endTime())));
        payload.put("userCode", draft.userCode());
        payload.put("userName", safeHeaderValue(firstNonBlankOrEmpty(draft.userName())));
        payload.put("teachDuration", draft.teachDuration());
        payload.put("version", "1");
        return payload;
    }

    private Map<String, Object> buildExpertSignInfo(ContractDraft draft, DoctorDetail doctorDetail) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("amount", draft.amount());
        info.put("amountCNY", draft.amount());
        info.put("currencyCode", "CNY");
        info.put("currencyName", "人民币");
        info.put("expertLevel", firstNonBlankOrEmpty(doctorDetail.expertLevelCode()));
        info.put("expertLevelName", firstNonBlankOrEmpty(doctorDetail.expertLevelName()));
        info.put("meetingRole", firstNonBlankOrEmpty(draft.roleId()));
        info.put("meetingRoleName", firstNonBlankOrEmpty(draft.roleName()));
        info.put("signerCode", firstNonBlankOrEmpty(doctorDetail.doctorCode(), draft.doctorCode(), doctorDetail.supplierCode(), draft.supplierCode()));
        info.put("signerName", firstNonBlankOrEmpty(doctorDetail.doctorName(), draft.doctorName()));
        info.put("supplierOrgName", firstNonBlankOrEmpty(doctorDetail.orgName(), draft.supplierOrgName()));
        return info;
    }

    private CurrentUserContractContext emptyCurrentUserContext(String message) {
        return new CurrentUserContractContext(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, List.of(), message);
    }

    private Map<String, String> userHeaders(String userCode, String userName, String userId, String departCode, String departName) {
        Map<String, String> headers = new LinkedHashMap<>(properties.getDefaultHeaders());
        headers.putIfAbsent("Content-Type", "application/json");
        if (StringUtils.hasText(userCode)) {
            headers.putIfAbsent("personcode", userCode);
            headers.putIfAbsent("usercode", userCode);
        }
        if (StringUtils.hasText(userId)) headers.putIfAbsent("userid", userId);
        if (StringUtils.hasText(departCode)) headers.putIfAbsent("departcode", departCode);
        headers.putIfAbsent("appcode", DEFAULT_APP_CODE);
        return headers;
    }

    private Map<String, String> jsonHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        return headers;
    }

    private Map<String, String> createContractHeaders(ContractDraft draft) {
        Map<String, String> headers = userHeaders(draft.userCode(), draft.userName(), null, draft.departCode(), draft.departName());
        if (StringUtils.hasText(draft.userCode())) headers.put("userCode", draft.userCode());
        if (StringUtils.hasText(draft.userName())) headers.put("userName", draft.userName());
        if (StringUtils.hasText(draft.departCode())) headers.put("departCode", draft.departCode());
        if (StringUtils.hasText(draft.departName())) headers.put("departName", draft.departName());
        return headers;
    }

    private String endpoint(String key) {
        return blankToNull(properties.getEndpoints().get(key));
    }

    private void applyHeaders(HttpHeaders target, Map<String, String> headers) {
        headers.forEach((name, value) -> {
            if (StringUtils.hasText(name) && value != null) {
                target.set(name, safeHeaderValue(value));
            }
        });
    }

    private String safeHeaderValue(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("\r", "").replace("\n", "");
        if (normalized.chars().allMatch(ch -> ch >= 0x20 && ch <= 0x7E)) {
            return normalized;
        }
        return URLEncoder.encode(normalized, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private ApiEnvelope postJson(String url, Object requestBody, Map<String, String> headers) {
        if (!StringUtils.hasText(url)) {
            log.warn("[record-contract-http] skip POST because url is blank body={}", safeJson(requestBody));
            return new ApiEnvelope(false, null, "接口URL未配置。", objectMapper.createObjectNode(), objectMapper.createObjectNode());
        }
        try {
            log.info("[record-contract-http] request method=POST url={} body={}", url, safeJson(requestBody));
            String responseText = restClient.post().uri(url).headers(h -> applyHeaders(h, headers)).body(requestBody).retrieve().body(String.class);
            if (!StringUtils.hasText(responseText)) {
                log.warn("[record-contract-http] response method=POST url={} empty body", url);
                return new ApiEnvelope(false, null, "接口未返回内容。", objectMapper.createObjectNode(), objectMapper.createObjectNode());
            }
            log.info("[record-contract-http] response method=POST url={} body={}", url, responseText);
            return parseApiEnvelope(responseText);
        } catch (JsonProcessingException e) {
            log.warn("[record-contract-http] response parse failed method=POST url={} error={}", url, e.getOriginalMessage());
            return new ApiEnvelope(false, null, "接口返回不是合法JSON: " + e.getOriginalMessage(), objectMapper.createObjectNode(), objectMapper.createObjectNode());
        } catch (Exception e) {
            log.warn("[record-contract-http] request failed method=POST url={} error={}", url, e.getMessage(), e);
            return new ApiEnvelope(false, null, "HTTP请求失败: " + e.getMessage(), objectMapper.createObjectNode(), objectMapper.createObjectNode());
        }
    }

    private ApiEnvelope getJson(String url, Map<String, Object> queryParams, Map<String, String> headers) {
        if (!StringUtils.hasText(url)) {
            log.warn("[record-contract-http] skip GET because url is blank query={}", safeJson(queryParams));
            return new ApiEnvelope(false, null, "接口URL未配置。", objectMapper.createObjectNode(), objectMapper.createObjectNode());
        }
        try {
            UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(url);
            queryParams.forEach(uriBuilder::queryParam);
            URI uri = uriBuilder.build(true).toUri();
            log.info("[record-contract-http] request method=GET url={}", uri);
            String responseText = restClient.get()
                    .uri(uri)
                    .headers(h -> applyHeaders(h, headers))
                    .retrieve()
                    .body(String.class);
            if (isAreaListUrl(url)) {
                log.info("[record-contract-http] response method=GET url={} body=<omitted area-list>", uri);
            } else {
                log.info("[record-contract-http] response method=GET url={} body={}", uri, responseText);
            }
            return parseApiEnvelope(responseText);
        } catch (JsonProcessingException e) {
            log.warn("[record-contract-http] response parse failed method=GET url={} error={}", url, e.getOriginalMessage());
            return new ApiEnvelope(false, null, "接口返回不是合法JSON: " + e.getOriginalMessage(), objectMapper.createObjectNode(), objectMapper.createObjectNode());
        } catch (Exception e) {
            log.warn("[record-contract-http] request failed method=GET url={} query={} error={}", url, safeJson(queryParams), e.getMessage(), e);
            return new ApiEnvelope(false, null, "HTTP请求失败: " + e.getMessage(), objectMapper.createObjectNode(), objectMapper.createObjectNode());
        }
    }

    private boolean isAreaListUrl(String url) {
        String areaListUrl = endpoint("area-list");
        return StringUtils.hasText(areaListUrl) && areaListUrl.equals(url);
    }

    private ApiEnvelope parseApiEnvelope(String responseText) throws JsonProcessingException {
        if (!StringUtils.hasText(responseText)) {
            return new ApiEnvelope(false, null, "接口未返回内容。", objectMapper.createObjectNode(), objectMapper.createObjectNode());
        }
        JsonNode raw = objectMapper.readTree(responseText);
        String code = firstNonBlank(text(raw, "code"), text(raw, "responseCode"), text(raw, "status"), text(raw, "retCode"), text(raw, "msgCode"));
        String message = firstNonBlank(text(raw, "message"), text(raw, "msg"), text(raw, "responseMsg"), text(raw, "errorMsg"));
        JsonNode responseData = raw.path("responseData");
        if (responseData.isMissingNode() || responseData.isNull()) responseData = raw.path("data");
        if (responseData.isMissingNode() || responseData.isNull()) responseData = raw.path("result");
        if (responseData.isMissingNode() || responseData.isNull()) responseData = objectMapper.createObjectNode();
        boolean success = raw.path("success").asBoolean(false) || "true".equalsIgnoreCase(text(raw, "success")) || SUCCESS_CODE.equals(code) || "200".equals(code) || "0".equals(code);
        return new ApiEnvelope(success, blankToNull(code), blankToNull(message), responseData, raw);
    }

    private String resolveSignerSupplierCode(String companyCode, String accountCompanyCode, Map<String, String> headers) {
        ApiEnvelope envelope = postJson(endpoint("config-center"), Map.of("configCode", DEFAULT_SIGNER_SUPPLIER_CONFIG_CODE, "pageNo", 1, "pageSize", 998), headers);
        if (!envelope.success()) {
            log.warn("[record-contract-tool] signer supplier config query failed message={}", envelope.message());
            return "__ERROR__:配置中心接口调用失败：" + withFallback(envelope.message(), "接口未返回成功状态。");
        }
        for (JsonNode item : iterable(listItems(envelope.responseData()))) {
            String matchedCompanyCode = text(item, "companyCode", "companycode");
            if (equalsNormalized(matchedCompanyCode, companyCode) || equalsNormalized(matchedCompanyCode, accountCompanyCode)) {
                return firstNonBlank(text(item, "supplierCode"), text(item, "suppliercode"));
            }
        }
        return null;
    }

    private SignerInfo querySignerInfo(String signerSupplierCode, Map<String, String> headers) {
        if (!StringUtils.hasText(signerSupplierCode)) {
            return new SignerInfo(null, null, null, null, null, null, null, "未匹配到我方签约供应商编码。");
        }
        ApiEnvelope envelope = postJson(endpoint("signer-bank-info"), Map.of("supplierCode", signerSupplierCode), headers);
        if (!envelope.success()) {
            return new SignerInfo(null, null, null, null, null, null, null, "签约方银行信息接口调用失败：" + withFallback(envelope.message(), "接口未返回成功状态。"));
        }
        JsonNode body = firstItem(envelope.responseData());
        JsonNode account = firstItem(body.path("accountList"));
        return new SignerInfo(
                firstNonBlank(text(body, "signId"), text(body, "supplierId"), text(body, "id")),
                firstNonBlank(text(body, "signName"), text(body, "supplierName"), text(body, "name")),
                firstNonBlank(text(body, "signAddress"), text(body, "addressInfo"), text(body, "address")),
                firstNonBlank(text(account, "depositBankName"), text(account, "bankName"), text(body, "bankName")),
                firstNonBlank(text(account, "accountName"), text(account, "bankAccountName"), text(body, "bankAccountName")),
                firstNonBlank(text(account, "depositBankId"), text(account, "bankCode"), text(body, "bankCode")),
                firstNonBlank(text(account, "accountNumber"), text(account, "bankNum"), text(account, "bankAccount"), text(body, "bankNum")),
                envelope.message());
    }

    private List<CompanyOption> extractCompanyOptions(JsonNode responseData) {
        List<CompanyOption> options = new ArrayList<>();
        int index = 1;
        for (JsonNode company : iterable(listItems(responseData))) {
            JsonNode departInfo = firstItem(company.path("departInfoDTOS"));
            String companyCode = text(company, "companyCode");
            String companyName = text(company, "companyName");
            String accountCompanyCode = text(company, "accountCompanyCode");
            String accountCompanyName = text(company, "accountCompanyName");
            String departCode = text(departInfo, "departCode");
            String departName = text(departInfo, "departName");
            if (StringUtils.hasText(companyCode) || StringUtils.hasText(companyName)
                    || StringUtils.hasText(accountCompanyCode) || StringUtils.hasText(accountCompanyName)) {
                options.add(new CompanyOption(index++, blankToNull(companyCode), blankToNull(companyName),
                        blankToNull(accountCompanyCode), blankToNull(accountCompanyName),
                        blankToNull(departCode), blankToNull(departName)));
            }
        }
        return options;
    }

    private DoctorDetail queryDoctorDetail(String doctorCode, String userCode) {
        if (!StringUtils.hasText(doctorCode)) return DoctorDetail.notFound("缺少医生编码。");
        ApiEnvelope envelope = postJson(endpoint("doctor-detail"), Map.of("supplierCode", doctorCode), userHeaders(userCode, null, null, null, null));
        JsonNode body = firstItem(envelope.responseData());
        if ((body == null || body.isMissingNode() || body.isNull() || body.isEmpty()) && StringUtils.hasText(endpoint("doctor-display"))) {
            envelope = postJson(endpoint("doctor-display"), Map.of("supplierCode", doctorCode), userHeaders(userCode, null, null, null, null));
            body = firstItem(envelope.responseData());
        }
        if (body == null || body.isMissingNode() || body.isNull() || body.isEmpty())
            return DoctorDetail.notFound(withFallback(envelope.message(), "未查询到医生明细。"));
        JsonNode phone = firstItem(body.path("phoneList"));
        JsonNode account = firstItem(body.path("accountList"));
        if (account == null || account.isMissingNode() || account.isNull() || account.isEmpty()) {
            account = firstItem(body.path("bankAccount"));
        }
        return new DoctorDetail(true,
                firstNonBlank(text(body, "id"), text(body, "supplierId")),
                firstNonBlank(text(body, "supplierCode"), doctorCode),
                firstNonBlank(text(body, "supplierName"), text(body, "personName"), text(body, "name")),
                firstNonBlank(text(body, "supplierCode"), doctorCode),
                firstNonBlank(text(body, "orgName"), text(body, "hospitalName")),
                firstNonBlank(text(body, "expertLevelCode"), text(body, "expertLevel")),
                text(body, "expertLevelName"),
                firstNonBlank(text(body, "mobile"), text(phone, "mobile"), text(phone, "phone"), text(phone, "phoneNum")),
                text(body, "cardNo"),
                firstNonBlank(text(account, "accountNumber"), text(account, "bankNum"), text(account, "bankAccount"), text(body, "bankNum")),
                firstNonBlank(text(account, "accountName"), text(account, "bankAccountName"), text(body, "bankAccountName")),
                firstNonBlank(text(account, "depositBankName"), text(account, "bankName"), text(body, "bankName")),
                firstNonBlank(text(account, "accountNumber"), text(account, "bankNum"), text(body, "bankNum")),
                firstNonBlank(text(body, "jobAdmin"), "P900"),
                firstNonBlank(text(body, "jobAdminName"), "其他"),
                body.path("bankAccount").isMissingNode() || body.path("bankAccount").isNull() ? body.path("accountList") : body.path("bankAccount"),
                body.path("phoneList"),
                envelope.message());
    }

    private DoctorSummary toDoctorSummary(DoctorDetail detail) {
        return new DoctorSummary(detail.doctorCode(), detail.doctorName(), detail.supplierCode(), detail.orgName(), detail.expertLevelName(), detail.mobile(), null);
    }

    private List<DoctorSummary> filterDoctorCandidatesByAllocation(String userCode, List<DoctorSummary> candidates) {
        if (!StringUtils.hasText(userCode) || candidates.size() <= 1 || !StringUtils.hasText(endpoint("ncrm-query"))) {
            return candidates;
        }
        String userId = queryUserId(userCode);
        List<String> doctorIds = candidates.stream()
                .map(DoctorSummary::id)
                .filter(StringUtils::hasText)
                .toList();
        if (!StringUtils.hasText(userId) || doctorIds.isEmpty()) {
            return candidates;
        }
        ApiEnvelope envelope = postJson(endpoint("ncrm-query"),
                Map.of("userId", userId, "doctorIds", doctorIds),
                userHeaders(userCode, null, userId, null, null));
        JsonNode responseData = envelope.responseData();
        List<String> preferredIds = stringList(responseData.path("withAllocation"));
        if (preferredIds.isEmpty()) {
            preferredIds = stringList(responseData.path("resultList"));
        }
        if (preferredIds.isEmpty()) {
            return candidates;
        }
        Map<String, DoctorSummary> byId = new LinkedHashMap<>();
        for (DoctorSummary candidate : candidates) {
            if (StringUtils.hasText(candidate.id())) {
                byId.put(candidate.id(), candidate);
            }
        }
        List<DoctorSummary> filtered = new ArrayList<>();
        for (String id : preferredIds) {
            DoctorSummary candidate = byId.get(id);
            if (candidate != null) {
                filtered.add(candidate);
            }
        }
        return filtered.isEmpty() ? candidates : filtered;
    }

    private String queryUserId(String userCode) {
        if (!StringUtils.hasText(userCode)) {
            return null;
        }
        ApiEnvelope envelope = postJson(endpoint("base-info-batch"),
                Map.of("personCodeList", List.of(userCode)),
                userHeaders(userCode, null, null, null, null));
        return text(firstItem(envelope.responseData()), "id");
    }

    private List<RoleSummary> matchRoles(List<RoleSummary> candidates, String roleName) {
        if (!StringUtils.hasText(roleName)) return candidates;
        List<RoleSummary> exact = candidates.stream().filter(i -> equalsNormalized(i.roleName(), roleName) || equalsNormalized(i.roleId(), roleName)).toList();
        return exact.isEmpty() ? candidates.stream().filter(i -> containsEither(i.roleName(), roleName) || containsEither(i.roleId(), roleName)).toList() : exact;
    }

    private List<RoleSummary> exactRoles(List<RoleSummary> candidates, String roleName) {
        if (!StringUtils.hasText(roleName)) return List.of();
        return candidates.stream()
                .filter(i -> equalsNormalized(i.roleName(), roleName) || equalsNormalized(i.roleId(), roleName))
                .toList();
    }

    private List<RoleSummary> allowedRoleCandidates(List<RoleSummary> candidates) {
        return candidates.stream()
                .filter(role -> isAllowedRoleName(role.roleName()))
                .toList();
    }

    private boolean isAllowedRoleName(String roleName) {
        if (!StringUtils.hasText(roleName)) return false;
        return ALLOWED_ROLE_NAMES.stream().anyMatch(allowed -> equalsNormalized(allowed, roleName));
    }

    private List<PlanSummary> matchPlans(List<PlanSummary> candidates, String planName, String planId) {
        if (StringUtils.hasText(planId)) {
            List<PlanSummary> byId = candidates.stream().filter(i -> equalsNormalized(i.planId(), planId) || equalsNormalized(i.planCode(), planId)).toList();
            if (!byId.isEmpty()) return byId;
        }
        if (StringUtils.hasText(planName)) {
            List<PlanSummary> byName = candidates.stream().filter(i -> equalsNormalized(i.planName(), planName)).toList();
            return byName.isEmpty() ? candidates.stream().filter(i -> containsEither(i.planName(), planName) || containsEither(i.planCode(), planName)).toList() : byName;
        }
        return candidates;
    }

    private List<PlanSummary> exactPlans(List<PlanSummary> candidates, String planName, String planId) {
        if (StringUtils.hasText(planId)) {
            List<PlanSummary> byId = candidates.stream()
                    .filter(i -> equalsNormalized(i.planId(), planId) || equalsNormalized(i.planCode(), planId))
                    .toList();
            if (!byId.isEmpty()) return byId;
        }
        if (StringUtils.hasText(planName)) {
            return candidates.stream()
                    .filter(i -> equalsNormalized(i.planName(), planName))
                    .toList();
        }
        return List.of();
    }

    private String planCodeQueryValue(String planIdOrCode) {
        if (!StringUtils.hasText(planIdOrCode) || isLikelyPlanId(planIdOrCode)) {
            return "";
        }
        return planIdOrCode.trim();
    }

    private boolean isLikelyPlanId(String value) {
        return StringUtils.hasText(value) && normalize(value).matches("[0-9a-f]{32}");
    }

    private List<String> extractPlanTypes(JsonNode responseData) {
        List<String> result = new ArrayList<>();
        JsonNode root = responseData != null && responseData.has("itemList") ? responseData.path("itemList") : responseData;
        for (JsonNode item : iterable(root)) {
            String key = firstNonBlank(text(item, "itemKey"), text(item, "value"), text(item, "code"));
            if (StringUtils.hasText(key)) result.add(key);
        }
        return uniqueList(result);
    }

    private List<AreaInfo> flattenAreas(JsonNode responseData) {
        List<AreaInfo> result = new ArrayList<>();
        JsonNode root = responseData != null && responseData.has("list") ? responseData.path("list") : responseData;
        for (JsonNode province : iterable(root)) collectAreas(province, 0, null, null, null, null, result);
        return result;
    }

    private void collectAreas(JsonNode node, int depth, String provinceCode, String provinceName, String cityCode, String cityName, List<AreaInfo> result) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        String code = firstNonBlank(text(node, "areaCode"), text(node, "code"), text(node, "id"), text(node, "value"));
        String name = firstNonBlank(text(node, "areaName"), text(node, "name"), text(node, "label"), text(node, "title"));
        JsonNode children = node.path("list");
        if (children.isMissingNode() || children.isNull() || children.isEmpty()) children = node.path("children");
        if (children.isMissingNode() || children.isNull() || children.isEmpty()) children = node.path("childList");
        if (children.isMissingNode() || children.isNull() || children.isEmpty()) children = node.path("areaList");
        if (children.isMissingNode() || children.isNull()) children = objectMapper.createArrayNode();
        if (depth == 0) {
            for (JsonNode city : iterable(children)) collectAreas(city, 1, code, name, null, null, result);
            return;
        }
        if (depth == 1) {
            if (children.isEmpty()) {
                result.add(new AreaInfo(provinceCode, provinceName, code, name, null, null));
                return;
            }
            for (JsonNode district : iterable(children))
                collectAreas(district, 2, provinceCode, provinceName, code, name, result);
            return;
        }
        result.add(new AreaInfo(provinceCode, provinceName, cityCode, cityName, code, name));
    }

    private boolean matchesAreaPart(String actual, String expected) {
        return !StringUtils.hasText(expected) || equalsNormalized(actual, expected) || containsEither(actual, expected);
    }

    private LocalDateTime parseDateTime(String value) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = value.trim();
        for (DateTimeFormatter f : List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))) {
            try {
                return LocalDateTime.parse(normalized, f).toLocalDate().atStartOfDay();
            } catch (DateTimeParseException ignore) {
            }
        }
        for (DateTimeFormatter f : List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd"),
                DateTimeFormatter.ofPattern("yyyy年M月d日"))) {
            try {
                return LocalDate.parse(normalized, f).atStartOfDay();
            } catch (DateTimeParseException ignore) {
            }
        }
        return null;
    }

    private String formatDate(LocalDateTime value) {
        return value == null ? null : value.toLocalDate().toString();
    }

    private String buildContractName(LocalDateTime startTime) {
        return startTime == null ? "授课服务协议" : "授课服务协议" + startTime.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private String buildProcessTitle(PlanSummary plan, DoctorSummary doctor, LocalDateTime startTime) {
        List<String> parts = new ArrayList<>();
        if (plan != null && StringUtils.hasText(plan.planName())) parts.add(plan.planName());
        if (doctor != null && StringUtils.hasText(doctor.doctorName())) parts.add(doctor.doctorName());
        if (startTime != null) parts.add(startTime.toLocalDate().toString());
        return parts.isEmpty() ? "录合同流程" : String.join(" - ", parts);
    }

    private List<String> optionalPlanWarnings(String planId, String doctorCode, LocalDateTime teachTime) {
        return List.of();
    }

    private boolean isBlockingWarning(String warning) {
        return StringUtils.hasText(warning) && (warning.contains("失败") || warning.contains("未通过") || warning.contains("必须") || warning.contains("缺少"));
    }

    private boolean shouldStopFlow(List<String> warnings) {
        return warnings.stream().anyMatch(warning -> isInterfaceFailureMessage(warning)
                || warning.contains("已停止后续提交流程")
                || warning.contains("缺少我方签约供应商信息"));
    }

    private boolean isInterfaceFailureMessage(String message) {
        return StringUtils.hasText(message)
                && (message.contains("接口调用失败")
                || message.contains("HTTP请求失败")
                || message.contains("接口URL未配置")
                || message.contains("接口返回不是合法JSON")
                || message.contains("接口未返回内容"));
    }

    private String joinDoctorCandidates(List<DoctorSummary> candidates) {
        List<String> parts = new ArrayList<>();
        int index = 1;
        for (DoctorSummary i : candidates) {
            parts.add(index++ + ". " + i.doctorName()
                    + "，编码：" + firstNonBlank(i.doctorCode(), i.supplierCode(), "未返回")
                    + (StringUtils.hasText(i.supplierOrgName()) ? "，机构：" + i.supplierOrgName() : ""));
        }
        return String.join("，", parts);
    }

    private String joinRoleCandidates(List<RoleSummary> candidates) {
        List<String> parts = new ArrayList<>();
        int index = 1;
        for (RoleSummary i : candidates) {
            parts.add(index++ + ". " + firstNonBlank(i.roleName(), i.roleId(), "未返回")
                    + (StringUtils.hasText(i.roleId()) ? "，编码：" + i.roleId() : "")
                    + (i.maxAmount() == null ? "" : "，金额上限：" + i.maxAmount() + "元"));
        }
        return String.join("；", parts);
    }

    private String joinPlanCandidates(List<PlanSummary> candidates) {
        List<String> parts = new ArrayList<>();
        int index = 1;
        for (PlanSummary i : candidates) {
            parts.add(index++ + ". " + firstNonBlank(i.planName(), i.planCode(), "未返回")
                    + "，活动ID：" + firstNonBlank(i.planId(), "未返回")
                    + "，计划编码：" + firstNonBlank(i.planCode(), "未返回")
                    + (StringUtils.hasText(i.planType()) ? "，类型：" + i.planType() : "")
                    + (StringUtils.hasText(i.startTime()) || StringUtils.hasText(i.endTime())
                    ? "，时间：" + firstNonBlank(i.startTime(), "未返回") + " 至 " + firstNonBlank(i.endTime(), "未返回")
                    : ""));
        }
        return String.join("，", parts);
    }

    private String joinCompanyOptions(List<CompanyOption> options) {
        List<String> parts = new ArrayList<>();
        for (CompanyOption option : options) {
            parts.add(option.index() + ". "
                    + firstNonBlank(option.companyName(), option.accountCompanyName(), "未返回")
                    + "，公司编码：" + firstNonBlank(option.companyCode(), "未返回")
                    + (StringUtils.hasText(option.accountCompanyName()) ? "，核算公司：" + option.accountCompanyName() : "")
                    + (StringUtils.hasText(option.accountCompanyCode()) ? "，核算公司编码：" + option.accountCompanyCode() : "")
                    + (StringUtils.hasText(option.departName()) ? "，部门：" + option.departName() : ""));
        }
        return String.join("；", parts);
    }

    private String expertLevelWarning(DoctorDetail detail) {
        String level = firstNonBlank(detail.expertLevelName(), detail.expertLevelCode());
        return StringUtils.hasText(level) ? "当前医生专家级别：" + level : null;
    }

    private List<String> buildTeachTime(String startTime, String endTime) {
        List<String> values = new ArrayList<>();
        LocalDateTime start = parseDateTime(startTime);
        LocalDateTime end = parseDateTime(endTime);
        if (start != null) values.add(start.toLocalDate().toString());
        if (end != null) values.add(end.toLocalDate().toString());
        return values;
    }

    private Object arrayOrEmpty(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? objectMapper.createArrayNode() : node;
    }

    private List<String> buildTeachCity(String provinceCode, String cityCode, String districtCode) {
        List<String> values = new ArrayList<>();
        if (StringUtils.hasText(provinceCode)) values.add(provinceCode);
        if (StringUtils.hasText(cityCode)) values.add(cityCode);
        if (StringUtils.hasText(districtCode)) values.add(districtCode);
        return values;
    }

    private JsonNode riskInfoNode(ApiEnvelope envelope) {
        JsonNode responseData = envelope.responseData();
        if (responseData != null && !responseData.isMissingNode() && !responseData.isNull() && !responseData.isEmpty()) {
            return responseData;
        }
        return envelope.raw();
    }

    private boolean hasRiskInfo(JsonNode riskInfo) {
        if (riskInfo == null || riskInfo.isMissingNode() || riskInfo.isNull() || riskInfo.isEmpty()) {
            return false;
        }
        if (riskInfo.isObject()) {
            return riskInfo.fieldNames().hasNext();
        }
        if (riskInfo.isArray()) {
            return riskInfo.size() > 0;
        }
        return !riskInfo.isBoolean();
    }

    private ApiEnvelope queryPlanContractRelation(String planId, Map<String, String> headers) {
        if (!StringUtils.hasText(planId)) {
            return new ApiEnvelope(false, null, "缺少活动ID，无法查询Q计划是否关联合同。", objectMapper.createObjectNode(), objectMapper.createObjectNode());
        }
        return getJson(endpoint("plan-contract-relation"), Map.of("planId", planId), headers);
    }

    private String buildRelationMessage(ApiEnvelope envelope) {
        if (!envelope.success()) {
            return "Q计划关联合同查询未成功：" + withFallback(envelope.message(), "接口未返回通过状态。");
        }
        JsonNode responseData = envelope.responseData();
        if (responseData != null
                && ((responseData.isBoolean() && !responseData.booleanValue())
                || (responseData.isTextual() && "false".equalsIgnoreCase(responseData.asText())))) {
            return "系统检测您还未创建e活动，您可前往创建e活动";
        }
        return null;
    }

    private String draftRiskKey(ContractDraft draft) {
        return String.join("|",
                normalize(firstNonBlank(draft.userCode(), "")),
                normalize(firstNonBlank(draft.doctorCode(), "")),
                normalize(firstNonBlank(draft.roleName(), "")),
                normalize(firstNonBlank(draft.planId(), "")),
                normalize(firstNonBlank(draft.startTime(), "")),
                normalize(firstNonBlank(draft.endTime(), "")),
                normalize(firstNonBlank(draft.teachDuration(), "")),
                String.valueOf(draft.amount())
        );
    }

    private String buildCreateMessage(ApiEnvelope createEnvelope, String contractId) {
        if (!createEnvelope.success()) {
            return withFallback(createEnvelope.message(), "录合同提交失败。");
        }
        if (StringUtils.hasText(contractId)) {
            return withFallback(createEnvelope.message(), "录合同请求已提交。");
        }
        return withFallback(createEnvelope.message(), "录合同请求已提交，但创建接口未返回合同编号。");
    }

    private String extractContractId(ApiEnvelope envelope) {
        String directResponseData = directNodeText(envelope.responseData());
        JsonNode firstResponseData = firstItem(envelope.responseData());
        JsonNode firstRaw = firstItem(envelope.raw());
        return firstNonBlank(
                directResponseData,
                text(envelope.responseData(), "contractCode"),
                text(envelope.responseData(), "contractNo"),
                text(envelope.responseData(), "contractId"),
                text(envelope.responseData(), "id"),
                text(firstResponseData, "contractCode"),
                text(firstResponseData, "contractNo"),
                text(firstResponseData, "contractId"),
                text(firstResponseData, "id"),
                text(envelope.raw(), "contractCode"),
                text(envelope.raw(), "contractNo"),
                text(envelope.raw(), "contractId"),
                text(envelope.raw(), "id"),
                text(firstRaw, "contractCode"),
                text(firstRaw, "contractNo"),
                text(firstRaw, "contractId"),
                text(firstRaw, "id")
        );
    }

    private String directNodeText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || node.isContainerNode()) {
            return null;
        }
        String value = node.asText();
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private String mergeMessages(String... messages) {
        List<String> values = new ArrayList<>();
        for (String message : messages) if (StringUtils.hasText(message)) values.add(message);
        return values.isEmpty() ? null : String.join("；", uniqueList(values));
    }

    private JsonNode firstItem(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return objectMapper.createObjectNode();
        if (node.isArray()) return node.isEmpty() ? objectMapper.createObjectNode() : node.get(0);
        if (node.has("list")) return firstItem(node.path("list"));
        if (node.has("rows")) return firstItem(node.path("rows"));
        if (node.has("records")) return firstItem(node.path("records"));
        return node;
    }

    private JsonNode listItems(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return objectMapper.createArrayNode();
        if (node.isArray()) return node;
        if (node.has("list")) return listItems(node.path("list"));
        if (node.has("rows")) return listItems(node.path("rows"));
        if (node.has("records")) return listItems(node.path("records"));
        return node.isObject() ? objectMapper.createArrayNode().add(node) : objectMapper.createArrayNode();
    }

    private Iterable<JsonNode> iterable(JsonNode node) {
        List<JsonNode> result = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) return result;
        if (node.isArray()) {
            node.forEach(result::add);
            return result;
        }
        result.add(node);
        return result;
    }

    private List<String> stringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        for (JsonNode item : iterable(node)) {
            String value = directNodeText(item);
            if (!StringUtils.hasText(value)) {
                value = firstNonBlank(text(item, "id"), text(item, "doctorId"), text(item, "supplierId"));
            }
            if (StringUtils.hasText(value)) {
                result.add(value);
            }
        }
        return uniqueList(result);
    }

    private String text(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText();
                if (StringUtils.hasText(text)) return text.trim();
            }
        }
        return null;
    }

    private Integer integerValue(JsonNode node, String... fields) {
        String value = text(node, fields);
        if (!StringUtils.hasText(value)) return null;
        try {
            return new BigDecimal(value).intValue();
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String withFallback(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (StringUtils.hasText(value)) return value.trim();
        return null;
    }

    private String firstNonBlankOrEmpty(String... values) {
        String value = firstNonBlank(values);
        return value == null ? "" : value;
    }

    private List<String> listOfNonBlank(String... values) {
        List<String> result = new ArrayList<>();
        for (String value : values) if (StringUtils.hasText(value)) result.add(value);
        return result;
    }

    private boolean equalsNormalized(String left, String right) {
        return StringUtils.hasText(left) && StringUtils.hasText(right) && normalize(left).equals(normalize(right));
    }

    private boolean containsEither(String left, String right) {
        if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) return false;
        String nl = normalize(left);
        String nr = normalize(right);
        return nl.contains(nr) || nr.contains(nl);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s\\-_/（）()]+", "");
    }

    private List<String> uniqueList(List<String> values) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) if (StringUtils.hasText(value)) unique.add(value);
        return new ArrayList<>(unique);
    }

    private record ApiEnvelope(boolean success, String code, String message, JsonNode responseData, JsonNode raw) {
    }

    private record SignerInfo(String signerId, String signerName, String signerAddress, String bankName,
                              String bankAccountName, String bankCode, String bankAccount, String message) {
    }

    private record DoctorDetail(boolean exists, String id, String doctorCode, String doctorName, String supplierCode,
                                String orgName, String expertLevelCode, String expertLevelName, String mobile,
                                String cardNo, String bankAccount, String bankAccountName, String bankName,
                                String bankNum, String jobAdmin, String jobAdminName, JsonNode bankAccountList,
                                JsonNode phoneList, String message) {
        static DoctorDetail notFound(String message) {
            return new DoctorDetail(false, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, message);
        }
    }

    private record AreaInfo(String provinceCode, String provinceName, String cityCode, String cityName,
                            String districtCode, String districtName) {
        String displayName() {
            List<String> parts = new ArrayList<>();
            if (StringUtils.hasText(provinceName)) parts.add(provinceName);
            if (StringUtils.hasText(cityName)) parts.add(cityName);
            if (StringUtils.hasText(districtName)) parts.add(districtName);
            return String.join("/", parts);
        }
    }

    public record RecordContractGuide(List<String> requiredFields, List<String> steps, String exampleInput) {
    }

    public record CurrentUserContractContext(String userCode, String userName, String phoneNumber, String companyCode,
                                             String companyName, String accountCompanyCode, String accountCompanyName,
                                             String departCode, String departName, String quoteDepartName,
                                             String quoteUserName, String signerSupplierCode, String signerId,
                                             String signerName, String signerAddress, String signerBankName,
                                             String signerBankAccountName, String signerBankCode, String signerBankAccount,
                                             List<CompanyOption> companyOptions, String message) {
    }

    public record CompanyOption(Integer index, String companyCode, String companyName, String accountCompanyCode,
                                String accountCompanyName, String departCode, String departName) {
    }

    public record DoctorSummary(String doctorCode, String doctorName, String supplierCode, String supplierOrgName,
                                String expertLevelName, String mobile, String id) {
    }

    public record DoctorMatchResult(boolean exactMatch, String message, List<DoctorSummary> candidates) {
    }

    public record RoleSummary(String roleId, String roleName, Integer maxAmount, String expertLevelName) {
    }

    public record RoleMatchResult(boolean exactMatch, String message, List<RoleSummary> candidates,
                                  List<String> warnings) {
    }

    public record PlanSummary(String planId, String planName, String planType, String startTime, String endTime,
                              String planCode, String ownerName, String productTypeCode) {
    }

    public record PlanMatchResult(boolean exactMatch, String message, List<PlanSummary> candidates) {
    }

    public record AreaValidationResult(boolean valid, String message, String provinceCode, String provinceName,
                                       String cityCode, String cityName, String districtCode, String districtName,
                                       String detailAddress, List<String> suggestions) {
    }

    public record ContractDraft(String userCode, String userName, String quoteDepartName, String quoteUserName,
                                String companyCode, String companyName, String accountCompanyCode,
                                String accountCompanyName, String departCode, String departName,
                                String signerSupplierCode, String signerId, String signerName, String signerAddress,
                                String doctorCode, String doctorName, String supplierCode, String supplierOrgName,
                                String roleId, String roleName, String planId, String planName, String planType,
                                String planProcessTitle, String planCreatePersonName, String productTypeCode,
                                String provinceCode, String provinceName, String cityCode,
                                String cityName, String districtCode, String districtName, String detailAddress,
                                String startTime, String endTime, String teachDuration, Integer amount, String contractName,
                                String processTitle) {
    }

    public record ContractPreview(boolean readyToSubmit, String message, List<String> missingFields,
                                  List<String> warnings, List<String> suggestions, ContractDraft draft) {
    }

    public record ContractRiskCheckResult(boolean riskPassed, boolean readyToCreate, String message,
                                          List<String> missingFields, List<String> warnings, ContractDraft draft,
                                          JsonNode riskInfo) {
    }

    public record ContractSubmissionResult(boolean success, String message, String contractId,
                                           List<String> missingFields, List<String> warnings, ContractDraft draft,
                                           String openUrl, String relationMessage) {
    }
}
