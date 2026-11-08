package com.matheusmelo.kybr.support.upload;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/**
 * Test-only multipart route ({@code POST /test/upload/parts}, declared multipart-only with the
 * document upload's cap in {@code WebIntegrationTest}). Reading the files makes Spring parse the
 * lazily resolved request, so the container's part limits apply as they do for a real handler. It
 * answers the number of files and the {@code tipo} field. Never on the production classpath.
 */
@RestController
public class MultipartProbeController {

    public static final String PATH = "/test/upload/parts";

    @PostMapping(PATH)
    Map<String, Object> parts(MultipartHttpServletRequest request) {
        return Map.of("files", request.getMultiFileMap().values().stream().mapToInt(List::size).sum(),
                "tipo", String.valueOf(request.getParameter("tipo")));
    }
}
