package com.example.question.model;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class AnswerValue {
    private String textValue;          // para simple-text e simple-textarea
    private Boolean booleanValue;      // para boolean
    private LocalDate dateValue;       // para date
    private LocalDateTime dateTimeValue; // para dateTime
    private BigDecimal amountValue;    // para amount
    private String currency;           // para amount (BRL, USD, etc)
    private String comboValue;         // para combo (código da resposta selecionada)
}
