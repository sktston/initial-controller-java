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

    @PostMapping("/webhooks")
    public ResponseEntity webhooksTopicHandler(@RequestBody String body) {
        globalService.handleEvent(body);
        return ResponseEntity.ok().build();
    }

}
