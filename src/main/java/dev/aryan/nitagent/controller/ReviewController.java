package dev.aryan.nitagent.controller;

import dev.aryan.nitagent.agent.ReviewAgentService;
import dev.aryan.nitagent.agent.TestGenAgentService;
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
    public SseEmitter review(@RequestBody String repoPath) {
        SseEmitter emitter = new SseEmitter(0L);
        new Thread(() -> {
            try {
                reviewAgentService.review(repoPath, emitter);
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }).start();
        return emitter;
    }

    @PostMapping("/generate-tests")
    public SseEmitter generateTests(@RequestBody String path, @RequestParam(defaultValue = "false") boolean isRepo) {
        SseEmitter emitter = new SseEmitter(0L);
        new Thread(() -> {
            try {
                testGenAgentService.generateTests(path, isRepo, emitter);
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }).start();
        return emitter;
    }
}