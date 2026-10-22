package id.ppob2.app.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AdminReadConfig {

    @Bean
    JsonRedactor jsonRedactor(ObjectMapper mapper) {
        return new JsonRedactor(mapper);
    }
}
