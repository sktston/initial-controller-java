package com.sktelecom.initial.controller.verifier;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static com.sktelecom.initial.controller.utils.Common.generateQRCode;
import static com.sktelecom.initial.controller.utils.Common.parseInvitationUrl;

@RequiredArgsConstructor
@Slf4j
@RestController
public class GlobalController {

    @Value("${credDefId}")
    private String credDefId; // credential definition identifier

    @Autowired
    GlobalService globalService;

    @GetMapping(value = "/invitation")
    public String invitationHandler() {
        String invitationUrl = globalService.createInvitationUrl();
        return parseInvitationUrl(invitationUrl);
    }

    @GetMapping(value = "/invitation-url")
    public String invitationUrlHandler() {
        return globalService.createInvitationUrl();
    }

    @GetMapping(value = "/invitation-qr", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] getInvitationUrlQRCode() {
        String invitationUrl = globalService.createInvitationUrl();
        String deeplinkUrl = "initial://reqService?process=I&ynCloud=Y" + "&svcPublicDID=DrLbXFSao4Vo8gMfjxPxU1" + "&credDefId="+ credDefId + "&invitation=" + invitationUrl;
        log.info("##### deeplink url :   " + deeplinkUrl);
        return generateQRCode(deeplinkUrl, 300, 300);
    }

    @PostMapping(value = "/webhooks")
    public ResponseEntity webhooksTopicHandler(@RequestBody String body) {
        globalService.handleEvent(body);
        return ResponseEntity.ok().build();
    }

}
