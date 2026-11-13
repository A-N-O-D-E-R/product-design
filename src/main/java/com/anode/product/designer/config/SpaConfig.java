package com.anode.product.designer.config;

import java.io.IOException;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/** Sert le build Vite ; toute route inconnue hors /api renvoie index.html (routing côté client). */
@Configuration
class SpaConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String path, Resource location) throws IOException {
                        Resource resource = location.createRelative(path);
                        if (resource.exists() && resource.isReadable()) {
                            return resource;
                        }
                        return path.startsWith("api/") ? null : new ClassPathResource("/static/index.html");
                    }
                });
    }
}
