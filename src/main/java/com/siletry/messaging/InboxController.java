package com.siletry.messaging;

import com.siletry.messaging.InboxService.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/inbox")
public class InboxController {

    public record ReplyRequest(@NotBlank String text) {}

    private final InboxService inbox;

    public InboxController(InboxService inbox) {
        this.inbox = inbox;
    }

    /** tab: needs (default) | all */
    @GetMapping
    public InboxList list(@RequestParam(defaultValue = "needs") String tab, @RequestParam(defaultValue = "50") int limit) {
        return inbox.list(tab, Math.min(limit, 200));
    }

    @GetMapping("/{id}")
    public ChatThread thread(@PathVariable Long id, @RequestParam(defaultValue = "100") int limit) {
        return inbox.thread(id, limit);
    }

    /** Poll for new messages: /api/inbox/5/messages?after=120 */
    @GetMapping("/{id}/messages")
    public List<MessageView> after(@PathVariable Long id, @RequestParam(required = false) Long after) {
        return inbox.after(id, after);
    }

    @PostMapping("/{id}/take-over")
    public ChatThread takeOver(@PathVariable Long id) {
        return inbox.takeOver(id);
    }

    @PostMapping("/{id}/hand-back")
    public ChatThread handBack(@PathVariable Long id) {
        return inbox.handBack(id);
    }

    @PostMapping("/{id}/reply")
    public MessageView reply(@PathVariable Long id, @Valid @RequestBody ReplyRequest req) {
        return inbox.reply(id, req.text());
    }

    @PostMapping("/{id}/resolve")
    public ChatThread resolve(@PathVariable Long id) {
        return inbox.resolve(id);
    }
}
