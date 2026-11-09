package com.matheusmelo.kybr.arch.fixture.upload;

import jakarta.servlet.http.Part;
import org.springframework.web.multipart.MultipartFile;

/** Violates the filename rule: reads the name the client sent, through both multipart APIs. */
public class FilenameReader {

    String multipart(MultipartFile file) {
        return file.getOriginalFilename();
    }

    String part(Part part) {
        return part.getSubmittedFileName();
    }
}
