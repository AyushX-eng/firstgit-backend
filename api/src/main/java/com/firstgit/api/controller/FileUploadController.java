package com.firstgit.api.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.firstgit.api.service.ZipProcessingService;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*")
public class FileUploadController {

    private final ZipProcessingService zipProcessingService;

    // Manual constructor - no Lombok needed
    public FileUploadController(ZipProcessingService zipProcessingService) {
        this.zipProcessingService = zipProcessingService;
    }

    @PostMapping(value = "/deploy", consumes = "multipart/form-data")
    public ResponseEntity<Map<String, String>> deployProject(
            @RequestParam("file") MultipartFile file,
            @RequestParam("repoName") String repoName,
            @RequestParam("isPrivate") boolean isPrivate,
            @RequestHeader("Authorization") String authorizationHeader) {

        Map<String, String> response = new HashMap<>();

        if (file.isEmpty()) {
            response.put("error", "The uploaded zip file cannot be empty.");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }

        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            response.put("error", "Missing or malformed Authorization token.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        }

        String accessToken = authorizationHeader.substring(7);

        try {
            String repoUrl = zipProcessingService.deployZipToGithub(file, repoName, isPrivate, accessToken);
            response.put("status", "success");
            response.put("repositoryUrl", repoUrl);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            e.printStackTrace(pw);
            response.put("error", "Deployment failed: " + e.toString());
            response.put("trace", sw.toString());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
}