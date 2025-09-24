package com.example.question.controller;

import com.example.question.model.Answer;
import com.example.question.model.Question;
import com.example.question.service.QuestionnaireService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.List;

@RestController
public class QuestionController {

    private static final Logger log = LoggerFactory.getLogger(QuestionController.class);

    @Autowired
    private QuestionnaireService questionnaireService;

    /**
     * Endpoint principal que recebe o progresso do questionário, salva o progresso no cache,
     * calcula o próximo passo e retorna a próxima pergunta (ou fim) — e o resumo apenas
     * quando o fluxo chega ao final.
     *
     * Suporta dois formatos de entrada no corpo do request: `answers` (recomendado) e
     * `comboQuestions` (compatibilidade legada). Ambos serão normalizados internamente.
     */
    @PostMapping("/question_next_step")
    public Object nextStep(@RequestBody Map<String, Object> request) {
        String sessionId = request.getOrDefault("sessionId", "defaultSession").toString();
        Map<String, Object> questionnaireMap = (Map<String, Object>) request.get("questionnaire");

        // 1) Normaliza entradas (answers ou comboQuestions) para formato padrão
        List<Map<String, Object>> incomingAnswers = normalizeIncomingAnswers(questionnaireMap);

        // 2) Se o request contém respostas novas (incoming), primeiro detectamos se alguma edição altera o fluxo
        if (incomingAnswers != null && !incomingAnswers.isEmpty()) {
            // Build quick maps trimmed
            java.util.Map<String, Object> incomingMap = new java.util.LinkedHashMap<>();
            for (Map<String, Object> a : incomingAnswers) {
                Object qc = a.get("questionCode");
                if (qc != null) incomingMap.put(String.valueOf(qc).trim(), a.get("value"));
            }

            // make lastIncoming / lastQ / lastVal available for later use
            Map<String, Object> lastIncoming = incomingAnswers.get(incomingAnswers.size() - 1);
            String lastQ = lastIncoming.get("questionCode") == null ? null : String.valueOf(lastIncoming.get("questionCode")).trim();
            Object lastVal = lastIncoming.get("value");

            // Recupera saved answers
            List<Map<String, Object>> savedAnswers = questionnaireService.getSavedAnswers(sessionId);
            java.util.Map<String, Object> savedMap = new java.util.LinkedHashMap<>();
            if (savedAnswers != null) {
                for (Map<String, Object> a : savedAnswers) {
                    Object qc = a.get("questionCode");
                    if (qc != null) savedMap.put(String.valueOf(qc).trim(), a.get("value"));
                }
            }

            // Detect earliest edited question where value changed
            String changedCode = null;
            Object oldVal = null;
            Object newVal = null;
            String prevChild = null;
            String newChild = null;

            for (Map<String, Object> inc : incomingAnswers) {
                Object qcObj = inc.get("questionCode");
                if (qcObj == null) continue;
                String qc = String.valueOf(qcObj).trim();
                Object incVal = inc.get("value");
                Object savedVal = savedMap.get(qc);
                // if savedVal exists and is different -> potential edit
                if (savedVal != null && !String.valueOf(savedVal).equals(String.valueOf(incVal))) {
                    // determine children for saved vs incoming
                    prevChild = questionnaireService.getNextQuestionCodeForAnswer(qc, savedVal);
                    newChild = questionnaireService.getNextQuestionCodeForAnswer(qc, incVal);
                    if ((prevChild == null && newChild == null) || (prevChild != null && prevChild.equals(newChild))) {
                        // changed value but did not change branch, continue searching
                        continue;
                    }
                    // this edit changes the flow
                    changedCode = qc;
                    oldVal = savedVal;
                    newVal = incVal;
                    break;
                }
            }

            if (changedCode != null) {
                // Flow changed: need to prune downstream answers from savedMap
                if (prevChild != null) {
                    java.util.Set<String> subtree = questionnaireService.collectSubtreeCodes(prevChild);
                    for (String rm : subtree) {
                        savedMap.remove(rm);
                    }
                }
                // Merge savedMap (pruned) with incoming (incoming overwrites)
                java.util.Map<String, Object> mergedByQuestion = new java.util.LinkedHashMap<>();
                // keep saved (pruned) first
                for (java.util.Map.Entry<String, Object> e : savedMap.entrySet()) mergedByQuestion.put(e.getKey(), e.getValue());
                // then incoming overwrites
                for (java.util.Map.Entry<String, Object> e : incomingMap.entrySet()) mergedByQuestion.put(e.getKey(), e.getValue());

                List<Map<String, Object>> mergedList = mapToList(mergedByQuestion);
                String questionnaireId = questionnaireMap == null ? questionnaireService.getQuestionnaire().getQuestionnaireId() : String.valueOf(questionnaireMap.getOrDefault("questionnaireId", questionnaireService.getQuestionnaire().getQuestionnaireId()));
                // save pruned+merged progress
                questionnaireService.saveAnswersList(sessionId, questionnaireId, mergedList);

                // Decide next based on newChild
                if (newChild == null) {
                    // final now => return summary
                    List<Question> branch = questionnaireService.getAnsweredBranch(mergedList);
                    List<Map<String, Object>> summary = questionnaireService.buildAnsweredSummary(mergedList, branch);
                    return Map.of("message", "Fim do questionário", "summary", summary);
                }
                Question next = questionnaireService.findQuestionByCode(newChild);
                if (next == null) {
                    List<Question> branch = questionnaireService.getAnsweredBranch(mergedList);
                    List<Map<String, Object>> summary = questionnaireService.buildAnsweredSummary(mergedList, branch);
                    return Map.of("message", "Fim do questionário", "summary", summary);
                }
                return Map.of("questionnaireId", questionnaireId, "questions", List.of(next));
            }

            // No flow change detected: fall through to normal behavior (merge saved+incoming, save, use last incoming to decide next)
            java.util.Map<String, Object> mergedByQuestion = mergeSavedAndIncoming(savedAnswers, incomingAnswers);
            List<Map<String, Object>> mergedList = mapToList(mergedByQuestion);
            String questionnaireId = questionnaireMap == null ? questionnaireService.getQuestionnaire().getQuestionnaireId() : String.valueOf(questionnaireMap.getOrDefault("questionnaireId", questionnaireService.getQuestionnaire().getQuestionnaireId()));
            questionnaireService.saveAnswersList(sessionId, questionnaireId, mergedList);

            // decide next based on last incoming
            String nextFromIncoming = questionnaireService.getNextQuestionCodeForAnswer(lastQ, lastVal);
            if (nextFromIncoming == null) {
                List<Question> branch = questionnaireService.getAnsweredBranch(mergedList);
                List<Map<String, Object>> summary = questionnaireService.buildAnsweredSummary(mergedList, branch);
                return Map.of("message", "Fim do questionário", "summary", summary);
            }
            Question next = questionnaireService.findQuestionByCode(nextFromIncoming);
            if (next == null) {
                List<Question> branch = questionnaireService.getAnsweredBranch(mergedList);
                List<Map<String, Object>> summary = questionnaireService.buildAnsweredSummary(mergedList, branch);
                return Map.of("message", "Fim do questionário", "summary", summary);
            }
            return Map.of("questionnaireId", questionnaireId, "questions", List.of(next));
        }

        // 3) Se não houver respostas novas no request, recupera e usa o progresso salvo
        List<Map<String, Object>> saved = questionnaireService.getSavedAnswers(sessionId);
        java.util.Map<String, Object> mergedByQuestion = mergeSavedAndIncoming(saved, null);
        List<Map<String, Object>> mergedList = mapToList(mergedByQuestion);
        String questionnaireId = questionnaireMap == null ? questionnaireService.getQuestionnaire().getQuestionnaireId() : String.valueOf(questionnaireMap.getOrDefault("questionnaireId", questionnaireService.getQuestionnaire().getQuestionnaireId()));
        // garante que o estado salvo exista
        questionnaireService.saveAnswersList(sessionId, questionnaireId, mergedList);

        // If there are no saved answers (mergedList empty), treat as start/reset and return first question
        if (mergedList == null || mergedList.isEmpty()) {
            Question first = questionnaireService.getQuestionnaire().getQuestions().get(0);
            return Map.of("questionnaireId", questionnaireId, "questions", List.of(first));
        }

        // Recalcula branch e summary a partir do progresso salvo
        List<Question> branch = questionnaireService.getAnsweredBranch(mergedList);
        List<Map<String, Object>> summary = questionnaireService.buildAnsweredSummary(mergedList, branch);

        // Determina próxima pergunta com base no ramo completo
        String nextQuestionCode = determineNextQuestionCode(branch, mergedByQuestion);
        if (nextQuestionCode == null) {
            return Map.of("message", "Fim do questionário", "summary", summary);
        }
        Question nextQuestion = questionnaireService.findQuestionByCode(nextQuestionCode);
        if (nextQuestion == null) {
            return Map.of("message", "Fim do questionário", "summary", summary);
        }

        return Map.of("questionnaireId", questionnaireId, "questions", List.of(nextQuestion));
    }

    /**
     * Normaliza o bloco `questionnaire` do request para uma lista padronizada de respostas
     * no formato: List<{ questionCode: String, value: Object }>. Aceita `answers` ou `comboQuestions`.
     */
    private List<Map<String, Object>> normalizeIncomingAnswers(Map<String, Object> questionnaireMap) {
        return questionnaireService.normalizeAnswersFromQuestionnaireMap(questionnaireMap);
    }

    /**
     * Mescla respostas salvas com as entrantes. A ordem é preservada usando um LinkedHashMap:
     * - Primeiro são aplicadas as respostas salvas (na ordem que estavam)
     * - Depois as entrantes sobrescrevem/appendam na ordem recebida
     * Retorna um mapa questionCode -> value (preservando ordem de inserção)
     */
    private java.util.Map<String, Object> mergeSavedAndIncoming(List<Map<String, Object>> saved, List<Map<String, Object>> incoming) {
        java.util.Map<String, Object> merged = new java.util.LinkedHashMap<>();
        if (saved != null) {
            for (Map<String, Object> a : saved) {
                Object qc = a.get("questionCode");
                if (qc != null) merged.put(String.valueOf(qc).trim(), a.get("value"));
            }
        }
        if (incoming != null) {
            for (Map<String, Object> a : incoming) {
                Object qc = a.get("questionCode");
                if (qc != null) merged.put(String.valueOf(qc).trim(), a.get("value"));
            }
        }
        log.debug("merged answers map={}", merged);
        return merged;
    }

    /**
     * Converte um mapa questionCode->value em uma lista padronizada [{questionCode, value}, ...]
     * preservando a ordem de iteração do mapa.
     */
    private List<Map<String, Object>> mapToList(java.util.Map<String, Object> mergedByQuestion) {
        java.util.List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, Object> e : mergedByQuestion.entrySet()) {
            java.util.Map<String, Object> m = new java.util.HashMap<>();
            m.put("questionCode", e.getKey());
            m.put("value", e.getValue());
            list.add(m);
        }
        return list;
    }

    /**
     * Determina o código da próxima pergunta (child) a partir do último elemento do branch.
     * Retorna null quando não existe próximo (fim do fluxo).
     */
    private String determineNextQuestionCode(List<Question> branch, java.util.Map<String, Object> mergedByQuestion) {
        if (branch == null || branch.isEmpty()) return null;
        Question lastAnswered = branch.get(branch.size() - 1);
        Object lastValue = mergedByQuestion.get(lastAnswered.getCode());
        if ("combo".equals(lastAnswered.getAnswerDataTypeDescription()) || "boolean".equals(lastAnswered.getAnswerDataTypeDescription())) {
            if (lastValue != null) {
                String v = String.valueOf(lastValue);
                Answer sel = lastAnswered.getAnswers().stream().filter(a -> a.getCode().equals(v)).findFirst().orElse(null);
                if (sel != null && sel.getChildQuestion() != null) return sel.getChildQuestion().getCode();
            }
            return null;
        } else {
            if (lastAnswered.getChildQuestion() != null) return lastAnswered.getChildQuestion().getCode();
            return null;
        }
    }
}
