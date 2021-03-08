package com.sktelecom.initial.controller.faber;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MyCredentialDefinition {
    private String name;
    private String date;
    private String degree;
    private String age;
}
