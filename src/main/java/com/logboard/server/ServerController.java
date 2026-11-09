package com.logboard.server;

import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ServerController {

    private final ServerService service;

    public ServerController(ServerService service) {
        this.service = service;
    }

    @GetMapping("/servers")
    public List<ServerDto> list() {
        return service.list();
    }

    @PostMapping("/servers")
    @ResponseStatus(HttpStatus.CREATED)
    public ServerDto create(@Valid @RequestBody ServerUpsert body) {
        return service.create(body);
    }

    @PutMapping("/servers/{id}")
    public ServerDto update(@PathVariable long id, @Valid @RequestBody ServerUpsert body) {
        return service.update(id, body);
    }

    @DeleteMapping("/servers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }

    /** SSH connect + SFTP stat of logPath. Always 200; failures are reported as {@code ok=false}. */
    @PostMapping("/servers/{id}/test")
    public ConnectionTestResult test(@PathVariable long id) {
        return service.test(id);
    }

    /** Forget the trusted SSH host key (e.g. after the server was reinstalled). The next /test or fetch pins it again. */
    @PostMapping("/servers/{id}/reset-host-key")
    public ServerDto resetHostKey(@PathVariable long id) {
        return service.resetHostKey(id);
    }

    @GetMapping("/log-patterns")
    public List<LogPatternDto> logPatterns() {
        return Arrays.stream(LogPatternPreset.values()).map(LogPatternDto::of).toList();
    }
}
