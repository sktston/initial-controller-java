package com.sktelecom.initial.controller.issuer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import static com.sktelecom.initial.controller.utils.Common.*;

import javax.servlet.http.HttpServletRequest;

import java.util.Enumeration;

@RequiredArgsConstructor
@Slf4j
@RestController
public class GlobalController {
    @Value("${x-api-key}")
    private String xApiKey; // controller access token

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
        return generateQRCode(invitationUrl, 300, 300);
    }

    @PostMapping(value = "/webhooks")
    public ResponseEntity webhooksTopicHandler(@RequestBody String body, HttpServletRequest request) {
        //http header x-api-key 정보 확인
        String httpAddr = request.getRemoteAddr();
        String apiKey = request.getHeader("x-api-key");

        // api key check
        if(!apiKey.equals(xApiKey)){
            log.info("http header:   " + httpAddr + "   xapikey :" + apiKey + ", Unauthorized API-KEY");
            return ResponseEntity.badRequest().build();
        }
        globalService.handleEvent(body);
        return ResponseEntity.ok().build();
    }

    @PostMapping(value = "/web-view/submit")
    public ResponseEntity webViewHandler(@RequestBody String body) {
        globalService.handleWebView(body);
        return ResponseEntity.ok().build();
    }

}
