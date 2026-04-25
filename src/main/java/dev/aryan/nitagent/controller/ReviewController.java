package dev.aryan.nitagent.controller;

import dev.aryan.nitagent.agent.ReviewAgentService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/review")
public class ReviewController {
    private final ReviewAgentService reviewAgentService;

    public ReviewController(ReviewAgentService reviewAgentService) {this.reviewAgentService = reviewAgentService;}

    @PostMapping
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
}
