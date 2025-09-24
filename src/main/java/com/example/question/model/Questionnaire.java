package com.example.question.model;

import lombok.Data;
import java.util.List;

@Data
public class Questionnaire {
    private String questionnaireId;
    private List<Question> questions;
}

