package com.saipramod.resolveai.api;

import com.saipramod.resolveai.cases.CaseRecord;
import com.saipramod.resolveai.cases.CaseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api")
public class CaseController {

    public record NewCase(String customerId, @NotBlank @Size(max = 4000) String message) { }

    public record Review(@NotBlank String reviewer, Double amount, String note) { }

    private final CaseService cases;

    public CaseController(CaseService cases) {
        this.cases = cases;
    }

    @PostMapping("/cases")
    public CaseRecord submit(@Valid @RequestBody NewCase req) {
        return cases.submit(req.customerId(), req.message());
    }

    @GetMapping("/cases")
    public List<CaseRecord> list(@RequestParam(required = false) CaseRecord.Status status) {
        return cases.list(status);
    }

    @GetMapping("/cases/{id}")
    public CaseRecord get(@PathVariable String id) {
        return cases.get(id);
    }

    @PostMapping("/cases/{id}/approve")
    public CaseRecord approve(@PathVariable String id, @Valid @RequestBody Review r) {
        return cases.approve(id, r.reviewer(), r.amount(), r.note());
    }

    @PostMapping("/cases/{id}/reject")
    public CaseRecord reject(@PathVariable String id, @Valid @RequestBody Review r) {
        return cases.reject(id, r.reviewer(), r.note());
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return cases.metrics();
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
