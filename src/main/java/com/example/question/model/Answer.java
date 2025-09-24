package com.example.question.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Answer {
    private String code;
    private String description;

    @JsonProperty("isCreditBooked")
    private boolean creditBooked;

    private List<NextAction> nextActions;
    private ChildQuestion childQuestion;
}
