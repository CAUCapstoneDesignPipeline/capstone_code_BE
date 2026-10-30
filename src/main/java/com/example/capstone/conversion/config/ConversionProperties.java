package com.example.capstone.conversion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("capstone.conversion")
public record ConversionProperties(boolean enabled,String storageRoot,String validatorJar,
        @DefaultValue("20") int validationSeconds,@DefaultValue("120") int callSeconds,
        @DefaultValue("false") boolean workerEnabled,long budgetMicrounits,String currency) {
    public boolean bounded() { return validationSeconds>=1 && validationSeconds<=30 && callSeconds>=1 && callSeconds<=120; }
}
