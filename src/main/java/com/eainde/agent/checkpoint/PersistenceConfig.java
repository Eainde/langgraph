/*
package com.eainde.agent.checkpoint;

import org.bsc.langgraph4j.checkpoint.CreateOption;
import org.bsc.langgraph4j.checkpoint.OracleSaver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class PersistenceConfig {

    @Bean
    public OracleSaver oracleSaver(DataSource dataSource) {
        return OracleSaver.builder()
                .dataSource(dataSource)
                // You MUST set this to a value that prevents 'initTables' from executing SQL.
                // Look at the CreateOption enum. It likely has 'NONE' or similar.
                // If you don't set this, it defaults to CREATE_IF_NOT_EXISTS which will error.
                .createOption(CreateOption.CREATE_NONE)
                .build();
    }
}
*/
