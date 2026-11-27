package br.com.ia369.atendente.adapter.in.rest;

import br.com.ia369.atendente.adapter.in.rest.dto.CallPackageResponse;
import br.com.ia369.atendente.adapter.in.rest.dto.StartCallRequest;
import br.com.ia369.atendente.adapter.in.rest.dto.StartCallResponse;
import br.com.ia369.atendente.application.StartCallUseCase;
import br.com.ia369.atendente.domain.model.CallRecord;
import br.com.ia369.atendente.domain.model.PageResult;
import br.com.ia369.atendente.domain.model.TranscriptLine;
import br.com.ia369.atendente.domain.port.CallRepository;
import br.com.ia369.atendente.domain.port.RecordingStorage;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/calls")
public class CallController {

    private final StartCallUseCase startCallUseCase;
    private final CallRepository callRepository;
    private final RecordingStorage recordingStorage;

    public CallController(
            StartCallUseCase startCallUseCase,
            CallRepository callRepository,
            RecordingStorage recordingStorage
    ) {
        this.startCallUseCase = startCallUseCase;
        this.callRepository = callRepository;
        this.recordingStorage = recordingStorage;
    }

    @PostMapping
    public ResponseEntity<StartCallResponse> startCall(@Valid @RequestBody StartCallRequest request) {
        CallRecord created = startCallUseCase.execute(new StartCallUseCase.StartCallCommand(
                request.to(),
                request.from(),
                request.externalRef(),
                request.assistantId(),
                request.variables(),
                request.callbackUrl()
        ));
        return ResponseEntity.accepted()
                .body(new StartCallResponse(created.id(), created.callSid(), created.status().code()));
    }

    @GetMapping("/{id}")
    public CallPackageResponse getCall(@PathVariable UUID id) {
        CallRecord record = callRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chamada não encontrada: " + id));
        return CallPackageResponse.fromDomain(record, "");
    }

    @GetMapping("/{id}/transcript")
    public ResponseEntity<?> getTranscript(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "json") String format
    ) {
        CallRecord record = callRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chamada não encontrada: " + id));

        List<TranscriptLine> lines = record.transcript() != null ? record.transcript() : List.of();
        if ("txt".equalsIgnoreCase(format)) {
            String txt = lines.stream()
                    .map(l -> String.format("[%s] %s: %s",
                            l.spokenAt() != null ? l.spokenAt().toString() : "-",
                            l.role(),
                            l.text()))
                    .collect(Collectors.joining("\n"));
            return ResponseEntity.ok()
                    .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                    .body(txt);
        }

        List<CallPackageResponse.TranscriptLineDto> dtos = lines.stream()
                .map(l -> new CallPackageResponse.TranscriptLineDto(l.seq(), l.role(), l.text(), l.spokenAt()))
                .toList();
        return ResponseEntity.ok(dtos);
    }

    @GetMapping(value = "/{id}/recording.mp3", produces = "audio/mpeg")
    public ResponseEntity<InputStreamResource> getRecording(@PathVariable UUID id) {
        CallRecord record = callRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chamada não encontrada: " + id));

        if (record.recording() == null || record.recording().storageKey() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Gravação não disponível para a chamada: " + id);
        }

        InputStream stream = recordingStorage.openStream(record.recording().storageKey());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + id + ".mp3\"")
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .body(new InputStreamResource(stream));
    }

    @GetMapping
    public PageResult<CallPackageResponse> listCalls(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String externalRef,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageResult<CallRecord> result = callRepository.search(status, from, to, externalRef, page, size);
        List<CallPackageResponse> items = result.content().stream()
                .map(c -> CallPackageResponse.fromDomain(c, ""))
                .toList();
        return new PageResult<>(items, result.page(), result.size(), result.totalElements(), result.totalPages());
    }
}

