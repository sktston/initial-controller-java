package com.sktelecom.initial.controller.holder;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.sktelecom.initial.controller.utils.HttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Timer;
import java.util.TimerTask;

import static com.sktelecom.initial.controller.utils.Common.*;

@RequiredArgsConstructor
@Service
@Slf4j
public class GlobalService {
    private final HttpClient client = new HttpClient();

    @Value("${agentApiUrl}")
    private String agentApiUrl; // agent service api url

    @Value("${accessToken}")
    private String accessToken; // controller access token

    @Value("${serviceType}")
    private String serviceType; // issuer or verifier

    @Value("${invitationUrl}")
    private String invitationUrl; // issuer controller invitation url to receive invitation-url

    @Value("${CredDefIdToReceive}")
    private String CredDefIdToReceive; // credential definition identifier to receive

    String orgName;
    String orgImageUrl;
    String publicDid;
    String phase = "";
    boolean webhookUrlIsValid = false;

    // for manual web view example
    static boolean enableManualWebView = Boolean.parseBoolean(System.getenv().getOrDefault("ENABLE_MANUAL_WEBVIEW", "false"));

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        provisionController();

        log.info("Controller is ready");
        log.info("Controller configurations");
        log.info("------------------------------");
        log.info("- organization name: " + orgName);
        log.info("- organization imageUrl: " + orgImageUrl);
        log.info("- public did: " + publicDid);
        log.info("- controller access token: " + accessToken);
        if (serviceType.equals("issuer")) {
            log.info("- issuer controller invitation url: " + invitationUrl);
            log.info("- credential definition id to receive from issuer: " + CredDefIdToReceive);
        }
        else {
            log.info("- verifier controller invitation url: " + invitationUrl);
        }
        log.info("------------------------------");
        startDemo();
    }

    public void handleEvent(String body) {
        if (!webhookUrlIsValid)
            webhookUrlIsValid = true;

        String topic = JsonPath.read(body, "$.topic");
        String state = null;
        try {
            state = JsonPath.read(body, "$.state");
        } catch (PathNotFoundException e) {}
        log.info("handleEvent >>> topic:" + topic + ", state:" + state + ", body:" + body);

        switch(topic) {
            case "issue_credential":
                if (state == null) {
                    log.warn("- Case (topic:" + topic + ", ProblemReport) -> Print Error Message");
                    String errorMsg = JsonPath.read(body, "$.error_msg");
                    log.warn("  - error_msg: " + errorMsg);
                }
                else if (state.equals("offer_received")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendCredentialRequest");
                    String credExId = JsonPath.read(body, "$.credential_exchange_id");
                    sendCredentialRequest(credExId);
                }
                break;
            case "present_proof":
                if (state == null) {
                    log.warn("- Case (topic:" + topic + ", ProblemReport) -> Print Error Message");
                    String errorMsg = JsonPath.read(body, "$.error_msg");
                    log.warn("  - error_msg: " + errorMsg);
                }
                else if (state.equals("request_received")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendPresentation");
                    String presExId = JsonPath.read(body, "$.presentation_exchange_id");
                    String presentationRequest = JsonPath.parse((LinkedHashMap)JsonPath.read(body, "$.presentation_request")).jsonString();
                    sendPresentation(presExId, presentationRequest);
                }
                else if (state.equals("presentation_acked")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> delayedExit");
                    delayedExit();
                }
                break;
            case "problem_report":
                log.warn("- Case (topic:" + topic + ") -> Print body");
                log.warn("  - body:" + body);
                break;
            case "connections":
            case "basicmessages":
            case "revocation_registry":
            case "issuer_cred_rev":
                break;
            default:
                log.warn("- Warning: Unexpected topic:" + topic);
        }
    }

    public void provisionController() {
        log.info("TEST - Create invitation-url");
        String invitationUrl = createInvitationUrl();
        if (invitationUrl == null) {
            log.info("- FAILED: Check if accessToken is valid - " + accessToken);
            System.exit(0);
        }
        String invitation = parseInvitationUrl(invitationUrl);
        publicDid = JsonPath.read(invitation, "$.did");
        orgName = JsonPath.read(invitation, "$.label");
        orgImageUrl = JsonPath.read(invitation, "$.imageUrl");
        log.info("- SUCCESS");

        log.info("TEST - Check if webhook url (in console) is valid");
        // create non-public invitation to receive webhook message
        client.requestPOST(agentApiUrl + "/connections/create-invitation", accessToken, "{}");
        try {
            Thread.sleep(1000); // wait to receive webhook message
        } catch (InterruptedException e) {}
        if (!webhookUrlIsValid) {
            log.info("- FAILED: webhook message is not received - Check if it is valid in console configuration");
            System.exit(0);
        }
        log.info("- SUCCESS");
    }

    public String createInvitationUrl() {
        String params = "?public=true";
        String response = client.requestPOST(agentApiUrl + "/connections/create-invitation" + params, accessToken, "{}");
        log.info("response: " + response);
        try {
            return JsonPath.read(response, "$.invitation_url");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void startDemo() {
        phase = "started";
        log.info("Receive invitation from issuer controller");
        receiveInvitationUrl(invitationUrl);
    }

    public void receiveInvitationUrl(String controllerInvitationUrl) {
        String invitationUrl = client.requestGET(controllerInvitationUrl, "");
        if (invitationUrl == null) {
            log.warn("Invalid invitation-url");
            return;
        }
        log.info("invitation-url: " + invitationUrl);
        String invitation = parseInvitationUrl(invitationUrl);
        log.info("invitation: " + invitation);
        String response = client.requestPOST(agentApiUrl + "/connections/receive-invitation", accessToken, invitation);
        log.info("response: " + response);
    }

    public void sendCredentialRequest(String credExId) {
        String response = client.requestPOST(agentApiUrl + "/issue-credential/records/" + credExId + "/send-request", accessToken, "{}");
        log.info("response: " + response);
    }

    public void sendPresentation(String presExId, String presentationRequest) {
        String response = client.requestGET(agentApiUrl + "/present-proof/records/" + presExId + "/credentials", accessToken);
        log.info("Matching Credentials in my wallet: " + response);

        ArrayList<LinkedHashMap<String, Object>> credentials = JsonPath.read(response, "$");
        int credRevId = 0;
        String credId = null;
        for (LinkedHashMap<String, Object> element : credentials) {
            if (JsonPath.read(element, "$.cred_info.cred_rev_id") != null){ // case of support revocation
                int curCredRevId = Integer.parseInt(JsonPath.read(element, "$.cred_info.cred_rev_id"));
                if (curCredRevId > credRevId) {
                    credRevId = curCredRevId;
                    credId = JsonPath.read(element, "$.cred_info.referent");
                }
            }
            else { // case of not support revocation
                credId = JsonPath.read(element, "$.cred_info.referent");
            }
        }
        log.info("Use latest credential in demo - credId: "+ credId);

        // Make body using presentationRequest
        LinkedHashMap<String, Object> reqAttrs = JsonPath.read(presentationRequest, "$.requested_attributes");
        for(String key : reqAttrs.keySet())
            reqAttrs.replace(key, JsonPath.parse("{ cred_id: '" + credId + "', revealed: true }").json());

        LinkedHashMap<String, Object> reqPreds = JsonPath.read(presentationRequest, "$.requested_predicates");
        for(String key : reqPreds.keySet())
            reqPreds.replace(key, JsonPath.parse("{ cred_id: '" + credId + "' }").json());

        LinkedHashMap<String, Object> selfAttrs = new LinkedHashMap<>();

        String body = JsonPath.parse("{}").put("$", "requested_attributes", reqAttrs)
                .put("$", "requested_predicates", reqPreds)
                .put("$", "self_attested_attributes", selfAttrs).jsonString();

        response = client.requestPOST(agentApiUrl + "/present-proof/records/" + presExId + "/send-presentation", accessToken, body);
        log.info("response: " + response);
    }

    public void delayedExit() {
        TimerTask task = new TimerTask() {
            public void run() {
                log.info("Holder demo completes - Exit");
                System.exit(0);
            }
        };
        Timer timer = new Timer("Timer");
        timer.schedule(task, 100L);
    }
}