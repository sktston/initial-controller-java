package com.sktelecom.initial.controller.issuer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import static com.sktelecom.initial.controller.utils.Common.*;

import org.springframework.beans.factory.annotation.Value;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.*;
import javax.servlet.http.*;
import java.util.*;
import java.io.*;

@RequiredArgsConstructor
@Slf4j
@RestController
public class GlobalController {

    @Value("${x-api-key}")
    private String xApiKey; // controller access token

    @Value("${credDefId}")
    private String credDefId; // credential definition identifier

    @Autowired
    GlobalService globalService;

    @GetMapping(value = "/oob-invitation")
    public String oobInvitationHandler() {
        String invitationUrl = globalService.createOobInvitationUrl();
        return parseOobInvitationUrl(invitationUrl);
    }

    @GetMapping(value = "/oob-invitation-url")
    public String oobInvitationUrlHandler() {
        return globalService.createOobInvitationUrl();
    }

    @GetMapping(value = "/invitation")
    public String invitationHandler() {
        String invitationUrl = globalService.createInvitationUrl();
        return parseInvitationUrl(invitationUrl);
    }

    @GetMapping(value = "/invitation-url")
    public String invitationUrl(HttpServletRequest request)  {
        String httpAddr = request.getRemoteAddr(); // Webhook Inbound IP Address
                log.info("##### Inbound IP Address :   " + httpAddr);
        return globalService.createInvitationUrl();
    }

    @GetMapping(value = "/invitation-issue-qr", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] getIssueInvitationUrlQRCode() {
        String invitationUrl = globalService.createInvitationUrl();
        String deeplinkUrl = "initial://reqService?process=I&ynCloud=Y" + "&svcPublicDID=DrLbXFSao4Vo8gMfjxPxU1" + "&nonce=123456789-987654321" + "&credDefId="+ credDefId + "&invitation=" + invitationUrl;
        log.info("##### deeplink url :   " + deeplinkUrl);
        return generateQRCode(deeplinkUrl, 300, 300);
    }

    @GetMapping(value = "/invitation-verify-qr", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] getVerifyInvitationUrlQRCode() {
        String invitationUrl = globalService.createInvitationUrl();
        String deeplinkUrl = "initial://reqService?process=V&ynCloud=Y" + "&svcPublicDID=DrLbXFSao4Vo8gMfjxPxU1" + "&nonce=123456789-987654321" + "&credDefId="+ credDefId + "&invitation=" + invitationUrl;
        log.info("##### deeplink url :   " + deeplinkUrl);
        return generateQRCode(deeplinkUrl, 300, 300);
    }

    @PostMapping(value = "/webhooks")
    public ResponseEntity webhooksTopicHandler(@RequestBody String body, HttpServletRequest request) {
        //Http header x-api-key 정보 확인
        String httpAddr = request.getRemoteAddr(); // Webhook Inbound IP Address
        String apiKey = request.getHeader("x-api-key");
	//String host = request.getHeeader("host");
	
	Enumeration eHeader = request.getHeaderNames();
		while (eHeader.hasMoreElements()) {
		String request_Name = (String)eHeader.nextElement();
		String request_Value = request.getHeader(request_Name);
		System.out.println("request_Name : " + request_Name + " | request_Value : " + request_Value);	       
	}

	log.info("JJ Request body : " + body);
        // API Key Check
        //if(apiKey != null && apiKey.isEmpty()) {
        //    if (!apiKey.equals(xApiKey)) {
        //        log.info("##### Inbound IP Address :   " + httpAddr);
        //        return ResponseEntity.badRequest().build();
        //    }
        //}
        globalService.handleEvent(body);
	//log.info("JJ Request body : " + body);
        return ResponseEntity.ok().build();
    }

    @PostMapping(value = "/web-view/submit")
    public ResponseEntity webViewHandler(@RequestBody String body) {
        globalService.handleWebView(body);
        return ResponseEntity.ok().build();
    }

}
