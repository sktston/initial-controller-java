package com.sktelecom.initial.controller.issuer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

import static com.sktelecom.initial.controller.utils.Common.*;

@RequiredArgsConstructor
@Slf4j
@RestController
public class GlobalController {

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
        String deeplinkUrl = "initial://reqService?process=I&ynCloud=Y&invitation=" + invitationUrl;
        return generateQRCode(deeplinkUrl, 300, 300);
    }

    @PostMapping(value = "/webhooks")
    public ResponseEntity webhooksTopicHandler(@RequestBody String body, HttpServletRequest request) {
        String httpAddr = request.getRemoteAddr();
        String auth = request.getAuthType();
        //String query = request.getQueryString();
        //String url = request.getRequestURI();


        log.info("http header:   " + httpAddr + "   auth :" + auth);
        globalService.handleEvent(body);
        return ResponseEntity.ok().build();
    }

    @PostMapping(value = "/web-view/submit")
    public ResponseEntity webViewHandler(@RequestBody String body) {
        globalService.handleWebView(body);
        return ResponseEntity.ok().build();
    }

}
