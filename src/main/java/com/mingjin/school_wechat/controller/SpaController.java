package com.mingjin.school_wechat.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
public class SpaController {

    @Value("${app.frontend-dist:web/dist}")
    private String frontendDist;

    @GetMapping(value = {"/", "/index.html"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> index() {
        Path indexPath = Paths.get(frontendDist).toAbsolutePath().resolve("index.html");
        Resource resource = new FileSystemResource(indexPath);
        if (resource.exists()) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(resource);
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/favicon.png")
    public ResponseEntity<Resource> favicon() {
        Path faviconPath = Paths.get(frontendDist).toAbsolutePath().resolve("favicon.png");
        Resource resource = new FileSystemResource(faviconPath);
        if (resource.exists()) {
            return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(resource);
        }
        return ResponseEntity.notFound().build();
    }
}
