package dev.aryan.nitagent.controller;

import dev.aryan.nitagent.agent.review.ReviewAgentService;
import dev.aryan.nitagent.agent.test.TestGenAgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ReviewController {

    private static final Logger log = LoggerFactory.getLogger(ReviewController.class);
    private static final long SSE_TIMEOUT_MILLIS = 120_000L;
    private static final int MAX_BODY_LENGTH = 1024;

    private final ReviewAgentService reviewAgentService;
    private final TestGenAgentService testGenAgentService;

    public ReviewController(ReviewAgentService reviewAgentService, TestGenAgentService testGenAgentService) {
        this.reviewAgentService = reviewAgentService;
        this.testGenAgentService = testGenAgentService;
    }

    @PostMapping("/review")
    public SseEmitter review(
            @RequestBody String repoPath,
            @RequestParam(defaultValue = "thinking") String modelMode
    ) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);

        new Thread(() -> {
            try {
                if (isTooLarge(repoPath)) {
                    emitter.send("Error: request body exceeds maximum length.\n");
                    emitter.complete();
                    return;
                }

                boolean fast = modelMode.equalsIgnoreCase("fast");
                reviewAgentService.review(repoPath, emitter, fast);
            } catch (Exception e) {
                log.error("Review request failed: {}", e.getMessage(), e);
                completeWithMessage(emitter, "Error: review failed: " + e.getMessage() + "\n");
            }
        }).start();

        return emitter;
    }

    @PostMapping("/generate-tests")
    public SseEmitter generateTests(
            @RequestBody String path,
            @RequestParam(defaultValue = "false") boolean isRepo,
            @RequestParam(defaultValue = "thinking") String modelMode,
            @RequestParam(defaultValue = "false") boolean parallel
    ) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);

        new Thread(() -> {
            try {
                if (isTooLarge(path)) {
                    emitter.send("Error: request body exceeds maximum length.\n");
                    emitter.complete();
                    return;
                }

                boolean fast = modelMode.equalsIgnoreCase("fast");
                testGenAgentService.generateTests(path, isRepo, emitter, fast, parallel);
                emitter.complete();
            } catch (Exception e) {
                log.error("Generate tests request failed: {}", e.getMessage(), e);
                completeWithMessage(emitter, "Error: test generation failed: " + e.getMessage() + "\n");
            }
        }).start();

        return emitter;
    }

    private boolean isTooLarge(String body) {
        return body != null && body.length() > MAX_BODY_LENGTH;
    }

    private void completeWithMessage(SseEmitter emitter, String message) {
        try {
            emitter.send(message);
            emitter.complete();
        } catch (Exception sendError) {
            emitter.completeWithError(sendError);
        }
    }
}