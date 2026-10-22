package klepaas.backend.diagnosis.eval;

import com.fasterxml.jackson.databind.JsonNode;
import klepaas.backend.ai.diagnosis.DiagnosisLabels;
import klepaas.backend.diagnosis.eval.DiagnosisEval.Case;

import java.util.ArrayList;
import java.util.List;

/**
 * 모델 원문을 서버 보정(`DiagnosisPrompt.parse`) 없이 판정한다 (#159, 정의는 스펙 0159).
 * 서버는 목록 밖 라벨·없는 근거를 버리고 보류로 바꾸므로, 그 전의 모델 출력 자체를 따로 본다.
 */
final class RawDiagnosis {

    private RawDiagnosis() {
    }

    /**
     * @param json             원문을 JSON으로 읽었는가
     * @param labelsInList     낸 범주·구체 원인이 목록 안인가 (null은 통과)
     * @param relationOk       범주와 구체 원인이 같은 범주인가 (둘 다 목록 안일 때만 판정)
     * @param pointers         인용한 근거 위치 수
     * @param invalidPointers  입력에 없는 근거 위치 수 (checkPointers가 false면 0)
     * @param abstainWithLabel 보류라고 하면서 라벨을 냄
     * @param asserted         보류하지 않고 구체 원인을 냄
     * @param confidentWrong   단정했는데 틀렸거나, 보류가 정답인 사례에서 단정
     * @param evidenceHit      단정한 지원 사례에서 원시 인용이 정답 근거 중 하나와 일치 (해당 없으면 null)
     */
    record Verdict(boolean json, boolean labelsInList, boolean relationOk, int pointers, int invalidPointers,
                   boolean abstainWithLabel, boolean asserted, boolean confidentWrong, Boolean evidenceHit) {

        boolean contractViolation() {
            return !json || !labelsInList || !relationOk || invalidPointers > 0 || abstainWithLabel;
        }
    }

    /** knowledgeCount: 그 호출에 넣은 지식 수(`/knowledge/i` 판정용). checkPointers=false면 근거 위치 존재를 판정하지 않는다(입력을 바꾼 변형) */
    static Verdict judge(Case c, String raw, int knowledgeCount, boolean checkPointers) {
        JsonNode out;
        try {
            out = DiagnosisEval.JSON.readTree(stripFence(raw));
        } catch (Exception e) {
            out = null;
        }
        if (out == null || !out.isObject()) return new Verdict(false, true, true, 0, 0, false, false, false, null);

        String category = text(out.get("category"));
        String rootCause = text(out.get("root_cause"));
        boolean labelsInList = (category == null || DiagnosisLabels.isCategory(category))
                && (rootCause == null || DiagnosisLabels.isRootCause(rootCause));
        boolean relationOk = category == null || rootCause == null || !labelsInList
                || DiagnosisLabels.ROOT_CAUSES.get(rootCause).startsWith(category + ":");

        List<String> pointers = new ArrayList<>();
        int invalid = 0;
        for (JsonNode p : out.path("cited")) {
            if (!p.isTextual()) {
                invalid++;
                continue;
            }
            String pointer = p.asText();
            pointers.add(pointer);
            if (checkPointers && !exists(c, pointer, knowledgeCount)) invalid++;
        }
        boolean abstain = out.path("abstain").asBoolean(false);
        boolean asserted = !abstain && rootCause != null;
        var e = c.expected();
        boolean wrong = asserted && (e.abstain() || !rootCause.equals(e.rootCause()));
        Boolean evidenceHit = asserted && !e.abstain() && !e.evidence().isEmpty()
                ? pointers.stream().anyMatch(e.evidence()::contains) : null;
        return new Verdict(true, labelsInList, relationOk, out.path("cited").size(),
                invalid, abstain && (category != null || rootCause != null), asserted, wrong, evidenceHit);
    }

    private static boolean exists(Case c, String pointer, int knowledgeCount) {
        if (pointer.startsWith("/knowledge/")) {
            String[] parts = pointer.split("/");
            try {
                return parts.length >= 3 && Integer.parseInt(parts[2]) < knowledgeCount;
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        return pointer.startsWith("/") && !c.input().at(pointer).isMissingNode();
    }

    /** 서버와 같은 코드 블록 제거 (`DiagnosisPrompt.stripFence`) */
    static String stripFence(String text) {
        String t = text == null ? "" : text.strip();
        if (t.startsWith("```")) {
            t = t.substring(t.indexOf('\n') + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.strip();
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() || !node.isTextual() || node.asText().isBlank() ? null : node.asText();
    }
}
