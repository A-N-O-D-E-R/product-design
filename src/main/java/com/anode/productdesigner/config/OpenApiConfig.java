package com.anode.productdesigner.config;

import java.util.List;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Contrat OpenAPI de l'API (`/api/openapi.json`) : serveur relatif, donc le même document partout. */
@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("productDesigner API").version("v1")
                        .description("Projets, Map, Sessions, Spec, Roadmap, exports Jira et Confluence, import d'idées, clés d'API."))
                .servers(List.of(new Server().url("/")));
    }
}
