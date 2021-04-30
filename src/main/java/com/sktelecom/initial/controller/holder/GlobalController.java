package com.sktelecom.initial.controller.holder;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@Slf4j
@RestController
public class GlobalController {

    @Autowired
    GlobalService globalService;

    @PostMapping("/webhooks/topic/{topic}")
    public ResponseEntity webhooksTopicHandler(
            @PathVariable String topic,
            @RequestBody String body) {
        if (globalService.phase.equals("preparation"))
            globalService.handleEventOnPreparation(topic, body);
        else
            globalService.handleEvent(topic, body);
        return ResponseEntity.ok().build();
    }

}
