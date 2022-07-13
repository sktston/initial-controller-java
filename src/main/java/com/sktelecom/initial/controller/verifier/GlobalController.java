package com.sktelecom.initial.controller.verifier;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@Slf4j
@RestController
public class GlobalController {

    @Autowired
    GlobalService globalService;

    @GetMapping(value = "/invitation-url")
    public String invitationUrlHandler() {
        return globalService.createInvitationUrl();
    }

    @PostMapping(value = "/webhooks")
    public ResponseEntity webhooksTopicHandler(@RequestBody String body) {
        globalService.handleEvent(body);
        return ResponseEntity.ok().build();
    }

}
