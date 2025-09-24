package com.example.question.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Question {
    private String questionId;
    private String code;
    private String description;
    private String categoryCode;
    private String categoryDescription;

    @JsonProperty("isMandatory")
    private boolean mandatory;

    @JsonProperty("isCreditBooked")
    private boolean creditBooked;

    @JsonProperty("isDocumentMandatory")
    private boolean documentMandatory;

    @JsonProperty("isCommentMandatory")
    private boolean commentMandatory;

    private String answerDataTypeDescription;
    private List<Answer> answers;
    private List<NextAction> nextActions;
    private List<String> guidance;
    private ChildQuestion childQuestion;  // Adicionando campo para próxima pergunta

    public ChildQuestion getChildQuestion() {
        return this.childQuestion;
    }
}
