package com.example.question.service;

import com.example.question.model.Questionnaire;
import com.example.question.model.Answer;
import com.example.question.model.Question;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class QuestionnaireService {
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private Questionnaire questionnaire;

    public QuestionnaireService() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            File file = new File("questionnaire.json");
            this.questionnaire = mapper.readValue(new FileInputStream(file), Questionnaire.class);
        } catch (IOException e) {
            throw new RuntimeException("Erro ao carregar questionnaire.json da raiz do projeto", e);
        }
    }

    /**
     * Retorna o questionário carregado em memória.
     */
    public Questionnaire getQuestionnaire() {
        return questionnaire;
    }

    /**
     * Normaliza uma estrutura de questionário enviada no payload para a lista padronizada de respostas.
     * Aceita tanto o formato novo (`answers`) quanto o formato antigo (`comboQuestions`) e retorna uma
     * lista no formato padrão: List<{ questionCode: String, value: Object }>.
     */
    public List<Map<String, Object>> normalizeAnswersFromQuestionnaireMap(Map<String, Object> questionnaireMap) {
        if (questionnaireMap == null) return null;
        Object rawAnswers = questionnaireMap.get("answers");
        java.util.List<Map<String, Object>> answersList = new java.util.ArrayList<>();

        if (rawAnswers instanceof java.util.List) {
            for (Object item : (java.util.List<?>) rawAnswers) {
                if (item instanceof java.util.Map) {
                    // Normalize questionCode key if present
                    java.util.Map<String, Object> m = new java.util.HashMap<>();
                    m.putAll((java.util.Map<String, Object>) item);
                    if (m.containsKey("questionCode") && m.get("questionCode") != null) {
                        m.put("questionCode", String.valueOf(m.get("questionCode")).trim());
                    }
                    answersList.add(m);
                }
            }
            return answersList;
        }

        // tenta o formato antigo comboQuestions
        Object rawCombo = questionnaireMap.get("comboQuestions");
        if (rawCombo instanceof java.util.List) {
            for (Object item : (java.util.List<?>) rawCombo) {
                if (item instanceof java.util.Map) {
                    java.util.Map<?, ?> m = (java.util.Map<?, ?>) item;
                    java.util.Map<String, Object> map = new java.util.HashMap<>();
                    map.put("questionCode", String.valueOf(m.get("key")).trim());
                    map.put("value", m.get("value"));
                    answersList.add(map);
                }
            }
        }
        return answersList;
    }

    /**
     * Salva o progresso bruto (payload) no Redis. Mantido por compatibilidade.
     */
    @SuppressWarnings("unchecked")
    public void saveProgress(String sessionId, Object progress) {
        if (!(progress instanceof Map)) return;
        Map<String, Object> payload = (Map<String, Object>) progress;
        Map<String, Object> questionnaireMap = (Map<String, Object>) payload.get("questionnaire");
        if (questionnaireMap == null) return;

        java.util.List<Map<String, Object>> answersList = normalizeAnswersFromQuestionnaireMap(questionnaireMap);

        if (answersList == null || answersList.isEmpty()) {
            clearProgress(sessionId);
        } else {
            redisTemplate.opsForValue().set(sessionId, progress);
        }
    }

    /**
     * Limpa o progresso salvo no cache para a sessão.
     */
    public void clearProgress(String sessionId) {
        redisTemplate.delete(sessionId);
    }

    /**
     * Recupera o payload salvo no Redis para a sessão, se existir.
     */
    public Optional<Object> getProgress(String sessionId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(sessionId));
    }

    /**
     * Busca uma pergunta pelo seu código (code) no questionário carregado.
     */
    public Question findQuestionByCode(String code) {
        for (Question question : questionnaire.getQuestions()) {
            if (question.getCode().equals(code)) {
                return question;
            }
        }
        return null;
    }

    // --- Métodos auxiliares para navegar e reconstruir o ramo respondido ---

    /**
     * Verifica recursivamente se a subtree iniciada em `code` contém alguma pergunta que esteja em `answeredCodes`.
     * Utilizado para decidir se devemos incluir um nó ancestral no resumo mesmo quando ele não tem resposta direta.
     */
    private boolean subtreeContainsAnswered(String code, java.util.Set<String> answeredCodes) {
        if (code == null) return false;
        if (answeredCodes.contains(code)) return true;
        Question q = findQuestionByCode(code);
        if (q == null) return false;
        if ("combo".equals(q.getAnswerDataTypeDescription()) || "boolean".equals(q.getAnswerDataTypeDescription())) {
            if (q.getAnswers() == null) return false;
            for (Answer a : q.getAnswers()) {
                if (a.getChildQuestion() != null) {
                    String childCode = a.getChildQuestion().getCode();
                    if (subtreeContainsAnswered(childCode, answeredCodes)) return true;
                }
            }
            return false;
        } else {
            if (q.getChildQuestion() == null) return false;
            return subtreeContainsAnswered(q.getChildQuestion().getCode(), answeredCodes);
        }
    }

    /**
     * Encontra o código do child (direto) que leva a uma pergunta respondida, caso exista.
     * Retorna null caso não exista.
     */
    private String findChildLeadingToAnswered(Question q, java.util.Set<String> answeredCodes) {
        if (q == null) return null;
        if ("combo".equals(q.getAnswerDataTypeDescription()) || "boolean".equals(q.getAnswerDataTypeDescription())) {
            if (q.getAnswers() == null) return null;
            for (Answer a : q.getAnswers()) {
                if (a.getChildQuestion() != null) {
                    String childCode = a.getChildQuestion().getCode();
                    if (subtreeContainsAnswered(childCode, answeredCodes)) return childCode;
                }
            }
            return null;
        } else {
            if (q.getChildQuestion() == null) return null;
            String childCode = q.getChildQuestion().getCode();
            if (subtreeContainsAnswered(childCode, answeredCodes)) return childCode;
            return null;
        }
    }

    /**
     * Reconstrói o ramo de perguntas válidas (respondidas ou necessárias para chegar até respostas)
     * começando pelo root (primeira pergunta do questionário).
     * Retorna uma lista de perguntas que compõem o ramo ativo.
     */
    public List<Question> getAnsweredBranch(List<Map<String, Object>> answers) {
        List<Question> branch = new java.util.ArrayList<>();
        if (answers == null || answers.isEmpty()) {
            return branch;
        }

        java.util.Map<String, String> answerByQuestion = new java.util.HashMap<>();
        java.util.Set<String> answeredCodes = new java.util.HashSet<>();
        for (Map<String, Object> a : answers) {
            Object qc = a.get("questionCode");
            if (qc != null) {
                Object val = a.get("value");
                String key = String.valueOf(qc).trim();
                answerByQuestion.put(key, val == null ? null : String.valueOf(val));
                answeredCodes.add(key);
            }
        }

        if (questionnaire.getQuestions() == null || questionnaire.getQuestions().isEmpty()) return branch;
        String currentCode = questionnaire.getQuestions().get(0).getCode();

        int safety = 0;
        while (currentCode != null && safety++ < 100) {
            Question question = findQuestionByCode(currentCode);
            if (question == null) break;

            if (!answeredCodes.contains(currentCode) && !subtreeContainsAnswered(currentCode, answeredCodes)) break;

            branch.add(question);

            if (answeredCodes.contains(currentCode)) {
                String value = answerByQuestion.get(currentCode);
                String nextCode = null;
                if ("combo".equals(question.getAnswerDataTypeDescription()) || "boolean".equals(question.getAnswerDataTypeDescription())) {
                    if (value != null) {
                        Answer selectedAnswer = question.getAnswers().stream()
                                .filter(a -> a.getCode().equals(value))
                                .findFirst()
                                .orElse(null);
                        if (selectedAnswer != null && selectedAnswer.getChildQuestion() != null) {
                            nextCode = selectedAnswer.getChildQuestion().getCode();
                        }
                    }
                } else {
                    if (question.getChildQuestion() != null) {
                        nextCode = question.getChildQuestion().getCode();
                    }
                }
                currentCode = nextCode;
            } else {
                String nextCode = findChildLeadingToAnswered(question, answeredCodes);
                currentCode = nextCode;
            }
        }

        return branch;
    }

    /**
     * Monta o resumo (summary) como uma lista de entradas { question, answer } para o ramo informado.
     * A entrada 'answer' pode ser null quando não houver resposta direta para a pergunta.
     */
    public List<Map<String, Object>> buildAnsweredSummary(List<Map<String, Object>> answers, List<Question> branch) {
        List<Map<String, Object>> summary = new java.util.ArrayList<>();
        if (branch == null || branch.isEmpty()) return summary;

        java.util.Map<String, Object> answerByQuestion = new java.util.HashMap<>();
        if (answers != null) {
            for (Map<String, Object> a : answers) {
                Object qc = a.get("questionCode");
                if (qc != null) answerByQuestion.put(String.valueOf(qc).trim(), a.get("value"));
            }
        }

        for (Question q : branch) {
            java.util.Map<String, Object> entry = new java.util.HashMap<>();
            entry.put("question", q);
            entry.put("answer", answerByQuestion.getOrDefault(q.getCode() == null ? null : q.getCode().trim(), null));
            summary.add(entry);
        }
        return summary;
    }

    /**
     * Valida se a resposta informada é compatível com o tipo de dado da pergunta.
     * Suporta: simple-text, simple-textarea, boolean, date, dateTime, amount, combo.
     */
    @SuppressWarnings("unchecked")
    public boolean isValidAnswer(Question question, Map<String, Object> answer) {
        String type = question.getAnswerDataTypeDescription();
        Object value = answer.get("value");

        if (value == null && question.isMandatory()) {
            return false;
        }

        switch (type) {
            case "simple-text":
            case "simple-textarea":
                return value instanceof String;

            case "boolean":
                if (!(value instanceof String)) return false;
                String boolStr = (String) value;
                return question.getAnswers().stream()
                        .anyMatch(a -> a.getCode().equals(boolStr));

            case "date":
                if (!(value instanceof String)) return false;
                try {
                    LocalDate.parse((String) value);
                    return true;
                } catch (Exception e) {
                    return false;
                }

            case "dateTime":
                if (!(value instanceof String)) return false;
                try {
                    LocalDateTime.parse((String) value);
                    return true;
                } catch (Exception e) {
                    return false;
                }

            case "amount":
                if (!(value instanceof Map)) return false;
                Map<String, Object> amountMap = (Map<String, Object>) value;
                try {
                    new BigDecimal(amountMap.get("amount").toString());
                    return amountMap.containsKey("currency");
                } catch (Exception e) {
                    return false;
                }

            case "combo":
                if (!(value instanceof String)) return false;
                String comboValue = (String) value;
                return question.getAnswers().stream()
                        .anyMatch(a -> a.getCode().equals(comboValue));

            default:
                return false;
        }
    }

    /**
     * Recupera a lista de respostas atualmente salvas no Redis para a sessão (padronizado como List<Map<String,Object>>).
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getSavedAnswers(String sessionId) {
        Object saved = redisTemplate.opsForValue().get(sessionId);
        if (!(saved instanceof Map)) return null;
        Map<?, ?> m = (Map<?, ?>) saved;
        Object q = m.get("questionnaire");
        if (!(q instanceof Map)) return null;
        Map<?, ?> qm = (Map<?, ?>) q;
        Object answers = qm.get("answers");
        if (!(answers instanceof List)) return null;
        return (List<Map<String, Object>>) answers;
    }

    /**
     * Salva a lista padronizada de respostas no Redis (substitui o valor existente).
     */
    public void saveAnswersList(String sessionId, String questionnaireId, List<Map<String, Object>> answersList) {
        java.util.Map<String, Object> toSave = new java.util.HashMap<>();
        java.util.Map<String, Object> inner = new java.util.HashMap<>();
        inner.put("questionnaireId", questionnaireId);
        inner.put("answers", answersList);
        toSave.put("questionnaire", inner);
        redisTemplate.opsForValue().set(sessionId, toSave);
    }

    /**
     * Retorna o código da próxima pergunta (child) quando se tem apenas uma resposta (questionCode + value).
     * Retorna null quando a resposta leva ao fim do fluxo (sem childQuestion).
     */
    public String getNextQuestionCodeForAnswer(String questionCode, Object valueObj) {
        if (questionCode == null) return null;
        Question question = findQuestionByCode(questionCode);
        if (question == null) return null;
        String value = valueObj == null ? null : String.valueOf(valueObj);
        if ("combo".equals(question.getAnswerDataTypeDescription()) || "boolean".equals(question.getAnswerDataTypeDescription())) {
            if (value == null) return null;
            Answer selected = question.getAnswers().stream().filter(a -> a.getCode().equals(value)).findFirst().orElse(null);
            if (selected == null) return null;
            return selected.getChildQuestion() == null ? null : selected.getChildQuestion().getCode();
        } else {
            return question.getChildQuestion() == null ? null : question.getChildQuestion().getCode();
        }
    }

    /**
     * Retorna o conjunto de códigos de todas as perguntas na subtree a partir de `startCode` (inclui o startCode se existir).
     * Útil para identificar quais respostas devem ser descartadas quando um nó do fluxo muda.
     */
    public java.util.Set<String> collectSubtreeCodes(String startCode) {
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        if (startCode == null) return result;
        collectSubtreeDfs(startCode, result);
        return result;
    }

    private void collectSubtreeDfs(String code, java.util.Set<String> acc) {
        if (code == null) return;
        if (acc.contains(code)) return; // evita ciclos
        Question q = findQuestionByCode(code);
        if (q == null) return;
        acc.add(code);
        if ("combo".equals(q.getAnswerDataTypeDescription()) || "boolean".equals(q.getAnswerDataTypeDescription())) {
            if (q.getAnswers() == null) return;
            for (Answer a : q.getAnswers()) {
                if (a.getChildQuestion() != null) {
                    String child = a.getChildQuestion().getCode();
                    collectSubtreeDfs(child, acc);
                }
            }
        } else {
            if (q.getChildQuestion() == null) return;
            collectSubtreeDfs(q.getChildQuestion().getCode(), acc);
        }
    }
}
