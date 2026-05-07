package dev.aryan.nitagent.controller;

import dev.aryan.nitagent.agent.review.ReviewAgentService;
import dev.aryan.nitagent.agent.test.TestGenAgentService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ReviewController {

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
        SseEmitter emitter = new SseEmitter(0L);
        new Thread(() -> {
            try {
                boolean fast = modelMode.equalsIgnoreCase("fast");
                reviewAgentService.review(repoPath, emitter, fast);
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }).start();
        return emitter;
    }

    @PostMapping("/generate-tests")
    public SseEmitter generateTests(
            @RequestBody String path,
            @RequestParam(defaultValue = "false") boolean isRepo,
            @RequestParam(defaultValue = "thinking") String modelMode
    ) {
        SseEmitter emitter = new SseEmitter(0L);
        new Thread(() -> {
            try {
                boolean fast = modelMode.equalsIgnoreCase("fast");
                testGenAgentService.generateTests(path, isRepo, emitter, fast);
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }).start();
        return emitter;
    }
}