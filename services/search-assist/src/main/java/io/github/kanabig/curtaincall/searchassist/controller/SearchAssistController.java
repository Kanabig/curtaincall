package io.github.kanabig.curtaincall.searchassist.controller;

import io.github.kanabig.curtaincall.searchassist.dto.AssistRequest;
import io.github.kanabig.curtaincall.searchassist.dto.AssistResponse;
import io.github.kanabig.curtaincall.searchassist.service.SearchAssistService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SRC-1~3: 자연어 검색어를 검색 조건으로 바꾼다. 검색 자체는 클라이언트가 event API로 한다. */
@RestController
@RequestMapping("/api/v1/search")
public class SearchAssistController {

    private final SearchAssistService service;

    public SearchAssistController(SearchAssistService service) {
        this.service = service;
    }

    @PostMapping("/assist")
    public AssistResponse assist(@Valid @RequestBody AssistRequest request) {
        return service.assist(request.query());
    }
}
