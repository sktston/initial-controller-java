package com.sktelecom.initial.controller.faber;

import com.sktelecom.initial.controller.utils.Common;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hyperledger.aries.webhook.EventHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.inject.Inject;
import java.io.IOException;

@RequiredArgsConstructor
@Slf4j
@RestController
public class GlobalController {

    @Autowired
    GlobalService globalService;

    @Inject
    private EventHandler handler;

    @GetMapping(value = "/invitation-url")
    public String invitationUrlHandler() throws IOException {
        return globalService.createInvitationUrl();
    }

    @GetMapping(value = "/invitation-qr", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] getInvitationUrlQRCode() throws IOException {
        String invitationUrl = globalService.createInvitationUrl();
        return globalService.generateQRCode(invitationUrl, 300, 300);
    }

    // just for debugging
    @GetMapping(value = "/invitation")
    public String invitationHandler() throws IOException {
        String invitationUrl = globalService.createInvitationUrl();
        return Common.parseInvitationUrl(invitationUrl);
    }

    @PostMapping(value = "/webhooks/topic/{topic}")
    public ResponseEntity webhooksTopicHandler(
            @PathVariable String topic,
            @RequestBody String body) throws IOException {
        handler.handleEvent(topic, body);
        return ResponseEntity.ok().build();
    }
}
