package io.github.kanabig.curtaincall.searchassist.service;

import io.github.kanabig.curtaincall.searchassist.client.ClaudeConditionExtractor;
import io.github.kanabig.curtaincall.searchassist.client.LlmConditionExtractor;
import io.github.kanabig.curtaincall.searchassist.client.OpenAiCompatibleConditionExtractor;
import io.github.kanabig.curtaincall.searchassist.config.SearchAssistProperties;
import io.github.kanabig.curtaincall.searchassist.config.SearchAssistProperties.ProviderType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** {@code search-assist.llm.provider}로 고른 제공자의 추출기. 키가 없으면 enabled가 false라 서비스가 Mock을 쓴다. */
@Configuration
class LlmExtractorConfig {

    @Bean
    LlmConditionExtractor llmConditionExtractor(SearchAssistProperties properties, ObjectMapper objectMapper) {
        ProviderType type = properties.llm().provider();
        return type == ProviderType.CLAUDE
                ? new ClaudeConditionExtractor(properties, objectMapper)
                : new OpenAiCompatibleConditionExtractor(properties, type, objectMapper);
    }
}
