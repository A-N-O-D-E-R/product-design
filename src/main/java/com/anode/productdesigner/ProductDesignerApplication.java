package com.anode.productdesigner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ProductDesignerApplication {

    public static void main(String[] args) throws IOException {
        // SQLite ne crée pas le dossier parent de la base.
        Files.createDirectories(Path.of(System.getProperty("user.home"), ".productdesigner"));
        SpringApplication.run(ProductDesignerApplication.class, args);
    }
}
